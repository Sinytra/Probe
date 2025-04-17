package org.sinytra.probe

import io.ktor.http.*
import io.ktor.serialization.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import org.sinytra.probe.model.ProjectPlatform
import org.sinytra.probe.model.TestResult
import org.sinytra.probe.service.GlobalPlatformService
import org.sinytra.probe.service.PersistenceService

@Serializable
data class TestRequestBody(val platform: ProjectPlatform, val id: String)

fun Application.configureRouting(platforms: GlobalPlatformService, transformation: TransformationService,
                                 gameVersion: String, toolchainVersion: String,
                                 persistence: PersistenceService) {
    routing {
        get("/") {
            call.respondText("Hello World!")
        }

        post("/api/v1/probe") {
            try {
                val body = call.receive<TestRequestBody>()
                if (body.platform != ProjectPlatform.MODRINTH) {
                    throw NotImplementedError("Unsupported platform ${body.platform}")
                }

                val project = platforms.getProject(body.platform, body.id)
                    ?: return@post call.respond(HttpStatusCode.NotFound)

                val resolved = platforms.resolveProject(project, gameVersion)
                    ?: return@post call.respond(HttpStatusCode.NotFound)

                val result: TestResult = persistence.getExistingResult(project, gameVersion, toolchainVersion) ?: coroutineScope { 
                    val transResult = transformation.runTransformation(resolved, gameVersion)
                    persistence.saveResult(project, transResult, gameVersion, toolchainVersion)
                }

                call.respond(result)
            } catch (ex: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest)
            } catch (ex: JsonConvertException) {
                call.respond(HttpStatusCode.BadRequest)
            }
        }
    }
}
