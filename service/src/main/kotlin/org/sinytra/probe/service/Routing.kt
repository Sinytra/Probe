package org.sinytra.probe.service

import io.ktor.http.*
import io.ktor.serialization.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.sinytra.probe.base.*
import org.sinytra.probe.base.db.ProjectPlatform
import org.sinytra.probe.core.model.TestResult
import org.sinytra.probe.core.platform.GlobalPlatformService
import org.sinytra.probe.core.platform.ModrinthPlatform.Companion.LOADER_FABRIC
import org.sinytra.probe.core.service.AsyncTransformationRunner
import org.sinytra.probe.core.service.PersistenceService
import org.sinytra.probe.core.service.SetupService
import org.sinytra.probe.core.service.TransformationService
import org.slf4j.LoggerFactory
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.deleteIfExists
import kotlin.time.measureTimedValue

data class EnvironmentConfig(
    val gameVersion: String,
    val neoForgeVersion: String
)

private val LOGGER = LoggerFactory.getLogger("Routing")

fun Application.configureRouting(
    setup: SetupService, platforms: GlobalPlatformService,
    transformation: TransformationService, persistence: PersistenceService,
    env: EnvironmentConfig
) {
    val maxThreadCount = System.getenv("org.sinytra.probe.max_threads")?.toIntOrNull() ?: 10
    val asyncTransform = AsyncTransformationRunner(transformation, persistence, maxThreadCount)
    val liveResourceLock = ReentrantReadWriteLock()

    routing {
        get("/") {
            call.respondText("Service operational")
        }

        post("/api/v1/probe") {
            try {
                val body = call.receive<TestRequestBody>()
                if (body.platform != ProjectPlatform.MODRINTH) {
                    throw NotImplementedError("Unsupported platform ${body.platform}")
                }

                val project = platforms.getProject(body.platform, body.id)
                    ?: return@post call.respond(HttpStatusCode.NotFound)
                val testProject = TestProjectDTO(
                    project.id,
                    project.slug,
                    project.name,
                    project.summary,
                    project.iconUrl,
                    project.url,
                    project.platform
                )

                val ifNeoForge = platforms.isNeoForgeAvailable(project, env.gameVersion)
                if (ifNeoForge) {
                    return@post call.respond(
                        SkippedResponseBody(
                            env.gameVersion,
                            testProject,
                            ResultType.NATIVE
                        )
                    )
                }

                val resolved = platforms.resolveProject(project, env.gameVersion)
                    ?: return@post call.respond(
                        UnavailableResponseBody(
                            LOADER_FABRIC,
                            env.gameVersion,
                            testProject,
                            ResultType.UNAVAILABLE
                        )
                    )

                liveResourceLock.read {
                    val transformer = setup.getTransformLib()
                    val testEnvironment = persistence.getOrCreateTestEnvironment(transformer.version, env.gameVersion, env.neoForgeVersion)
                    val result: TestResult = asyncTransform.transform(project, resolved, testEnvironment)

                    val version = platforms.getVersion(project, result.versionId)
                    val envDto = TestEnvironmentDTO(testEnvironment.connectorVersion, testEnvironment.gameVersion, testEnvironment.neoForgeVersion)

                    val response = TestResponseBody(
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
            } catch (ex: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest)
            } catch (ex: JsonConvertException) {
                call.respond(HttpStatusCode.BadRequest)
            }
        }

        authenticate("api-key") {
            post("/api/v1/internal/import") {
                val body = call.receive<TestReport>()

                if (body.environment.transformerVersion == null) {
                    call.respond(HttpStatusCode.BadRequest, "environment.transformerVersion must not be null")
                    return@post
                }

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

                call.respond(HttpStatusCode.OK)
            }

            post("/api/v1/internal/update") {
                liveResourceLock.write {
                    LOGGER.info("Updating transformer library...")
                    val current = setup.getTransformLib()
                    val updated = setup.updateTransformLib()
                    if (updated != null) {
                        current.path.deleteIfExists()

                        LOGGER.info("Successfully updated transformer from {} to {}", current.version, updated.version)
                    } else {
                        LOGGER.info("Transformer library is already up-to-date")
                    }
                }

                call.respond(HttpStatusCode.OK)
            }
        }
    }
}
