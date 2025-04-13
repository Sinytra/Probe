package org.sinytra.probe

import io.ktor.http.*
import io.ktor.serialization.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.sinytra.probe.model.ProjectPlatform
import org.sinytra.probe.service.ModrinthService

@Serializable
data class TestRequestBody(val platform: ProjectPlatform, val id: String)

@Serializable
data class TestResponseBody(val id: String, val name: String, val versionId: String)

fun Application.configureRouting() {
    routing {
        get("/") {
            call.respondText("Hello World!")
        }

        post("/api/v1/tests") {
            try {
                val body = call.receive<TestRequestBody>()
                if (body.platform != ProjectPlatform.MODRINTH) {
                    throw NotImplementedError("Unsupported platform ${body.platform}")
                }

                val mrProject = ModrinthService.getProject(body.id)
                if (mrProject == null) {
                    return@post call.respond(HttpStatusCode.NotFound)
                }

                val mrVersion = ModrinthService.getProjectVersion(mrProject.id, "1.21.1", "fabric");
                if (mrVersion == null) {
                    return@post call.respond(HttpStatusCode.NotFound)
                }

                call.respond(TestResponseBody(mrProject.id, mrProject.name, mrVersion.id))
            } catch (ex: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest)
            } catch (ex: JsonConvertException) {
                call.respond(HttpStatusCode.BadRequest)
            }
        }
    }
}
