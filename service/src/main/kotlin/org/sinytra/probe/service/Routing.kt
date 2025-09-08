package org.sinytra.probe.service

import io.ktor.http.*
import io.ktor.serialization.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
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
import java.lang.Exception
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.deleteIfExists

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

                val ifNeoForge = platforms.isNeoForgeAvailable(project, env.gameVersion)
                if (ifNeoForge) {
                    return@post call.respond(
                        SkippedResponseBody(
                            project.slug,
                            project.iconUrl ?: "",
                            project.url,
                            env.gameVersion,
                            ResultType.NATIVE
                        )
                    )
                }

                val resolved = platforms.resolveProject(project, env.gameVersion)
                    ?: return@post call.respond(
                        UnavailableResponseBody(
                            project.slug,
                            LOADER_FABRIC,
                            env.gameVersion,
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
                        project.iconUrl ?: "",
                        project.url,
                        version?.versionNumber ?: "unknown",
                        result.passing,
                        envDto,
                        result.createdAt,
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
            post("/api/v1/internal/update") {
                try {
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
                } catch (ex: Exception) {
                    LOGGER.error("Failed to update transformer library", ex)
                    call.respond(HttpStatusCode.InternalServerError)
                }
            }
        }
    }
}
