package org.sinytra.probe.service

import io.ktor.http.*
import io.ktor.serialization.*
import io.ktor.server.application.*
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
import org.sinytra.probe.core.service.TransformationService

data class EnvironmentConfig(
    val connectorVersion: String,
    val gameVersion: String,
    val neoForgeVersion: String
)

fun Application.configureRouting(platforms: GlobalPlatformService, transformation: TransformationService,
                                 env: EnvironmentConfig,
                                 persistence: PersistenceService) {
    val maxThreadCount = System.getenv("org.sinytra.probe.max_threads")?.toIntOrNull() ?: 10
    val asyncTransform = AsyncTransformationRunner(transformation, persistence, maxThreadCount)

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
                
                val testEnvironment = persistence.getOrCreateTestEnvironment(env.connectorVersion, env.gameVersion, env.neoForgeVersion)

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
            } catch (ex: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest)
            } catch (ex: JsonConvertException) {
                call.respond(HttpStatusCode.BadRequest)
            }
        }
    }
}
