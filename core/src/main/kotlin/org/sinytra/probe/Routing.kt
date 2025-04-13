package org.sinytra.probe

import io.ktor.http.*
import io.ktor.serialization.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.sinytra.probe.game.ProbeTransformer
import org.sinytra.probe.model.ProjectPlatform
import org.sinytra.probe.service.ModrinthService
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.name

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

                // TODO Download deps
                val file = ModrinthService.downloadFile(mrVersion)
                val fileStoragePath = Path(System.getProperty("org.sinytra.probe.storage_path")!!)
                val output = fileStoragePath / "output" / "transformed_${file.name}"
                
                val cleanPath = Path(System.getProperty("org.sinytra.probe.clean.path")!!)
                val classPath = System.getProperty("org.sinytra.probe.transform.classpath")!!.split(";")
                    .map(::Path)
                    .toList()

                val result = ProbeTransformer().transform(file, output, cleanPath, classPath)
                // TODO Save result

                call.respond(TestResponseBody(mrProject.id, mrProject.name, mrVersion.id))
            } catch (ex: IllegalStateException) {
                call.respond(HttpStatusCode.BadRequest)
            } catch (ex: JsonConvertException) {
                call.respond(HttpStatusCode.BadRequest)
            }
        }
    }
}
