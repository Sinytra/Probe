package org.sinytra.probe.service.impl

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.sinytra.probe.base.*
import org.sinytra.probe.base.db.ProjectPlatform
import org.sinytra.probe.core.model.TestResult
import org.sinytra.probe.core.platform.GlobalPlatformService
import org.sinytra.probe.core.platform.ModrinthPlatform.Companion.LOADER_FABRIC
import org.sinytra.probe.core.platform.ModrinthPlatform.Companion.LOADER_NEOFORGE
import org.sinytra.probe.core.service.AsyncTransformationRunner
import org.sinytra.probe.core.service.PersistenceService
import org.sinytra.probe.core.service.SetupService
import org.slf4j.LoggerFactory
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.time.measureTimedValue

private val LOGGER = LoggerFactory.getLogger("RoutingImpl")

class RoutingImpl(
    private val setup: SetupService,
    private val platforms: GlobalPlatformService,
    private val persistence: PersistenceService,
    private val asyncTransform: AsyncTransformationRunner
) {
    val liveResourceLock = ReentrantReadWriteLock()

    suspend fun testMod(body: TestRequestBody, call: RoutingCall) {
        if (body.platform != ProjectPlatform.MODRINTH) {
            return call.respond(HttpStatusCode.BadRequest, "Unsupported platform ${body.platform}")
        }

        if (!setup.hasGameVersion(body.gameVersion)) {
            return call.respond(HttpStatusCode.BadRequest, "Game version ${body.gameVersion} is not supported")
        }

        val project = platforms.getProject(body.platform, body.slug)
            ?: return call.respond(HttpStatusCode.NotFound)
        val testProject = TestProjectDTO(
            project.id,
            project.slug,
            project.name,
            project.summary,
            project.iconUrl,
            project.url,
            project.platform
        )

        val ifNeoForge = platforms.isNeoForgeAvailable(project, body.gameVersion)
        if (ifNeoForge) {
            return call.respond(
                TestResponseBody.Native(
                    LOADER_NEOFORGE,
                    body.gameVersion,
                    testProject,
                    ResultType.NATIVE
                )
            )
        }

        val resolved = platforms.resolveProject(project, body.gameVersion)
            ?: return call.respond(
                TestResponseBody.Unavailable(
                    LOADER_FABRIC,
                    body.gameVersion,
                    testProject,
                    ResultType.UNAVAILABLE
                )
            )

        liveResourceLock.read {
            val neoForgeVersion = setup.getNeoForgeVersion(body.gameVersion)
            val transformer = setup.getTransformLib(body.gameVersion)
            val testEnvironment = persistence.getOrCreateTestEnvironment(transformer.version, body.gameVersion, neoForgeVersion)
            val result: TestResult = asyncTransform.transform(project, resolved, testEnvironment)

            val version = platforms.getVersion(project, result.versionId)
            val envDto = TestEnvironmentDTO(testEnvironment.connectorVersion, testEnvironment.gameVersion, testEnvironment.neoForgeVersion)

            val response = TestResponseBody.Tested(
                result.modid,
                version?.versionNumber,
                version?.versionId,
                result.passing,

                envDto,
                result.createdAt,

                testProject,
                ResultType.TESTED
            )

            call.respond(response)
        }
    }

    suspend fun importTestReport(body: TestReport) = coroutineScope {
        LOGGER.info("Importing ${body.results.size} test results")

        val testEnvironment = persistence.getOrCreateTestEnvironment(
            body.environment.transformerVersion!!,
            body.environment.gameVersion,
            body.environment.neoforgeVersion
        )
        val (results, duration) = measureTimedValue {
            body.results
                .filter { it.result != null }
                .map { res ->
                    async(Dispatchers.IO) {
                        try {
                            val project = platforms.getProject(ProjectPlatform.MODRINTH, res.project.slug)
                                ?: return@async null

                            return@async persistence.saveResult(
                                project,
                                res.result!!.output.primaryModid,
                                res.project.versionId,
                                res.result!!.output.success,
                                testEnvironment
                            )
                        } catch (ex: Exception) {
                            LOGGER.error("Error importing result", ex)
                            return@async null
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
        LOGGER.info("Imported ${results.size} test results in ${duration.inWholeMilliseconds} ms")
    }

    suspend fun updateLibraries() {
        liveResourceLock.write {
            setup.updateLibraries()
        }
    }
}