package org.sinytra.probe.service

import io.ktor.http.*
import io.ktor.serialization.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import org.sinytra.probe.core.model.ProjectPlatform
import org.sinytra.probe.core.model.TestResult
import org.sinytra.probe.core.service.AsyncTransformationRunner
import org.sinytra.probe.core.service.GlobalPlatformService
import org.sinytra.probe.core.service.LOADER_FABRIC
import org.sinytra.probe.core.service.PersistenceService
import org.sinytra.probe.core.service.TransformationService

@Serializable
data class TestRequestBody(val platform: ProjectPlatform, val id: String)

@Serializable
enum class ResultType {
    TESTED,
    NATIVE,
    UNAVAILABLE
}

@Serializable
data class UnavailableResponseBody(
    val slug: String,
    val loader: String,
    val gameVersion: String,
    val type: ResultType
)

@Serializable
data class TestResponseBody(
    val modid: String,
    val iconUrl: String,
    val projectUrl: String,
    val version: String,
    val gameVersion: String,
    val toolchainVersion: String,
    val passing: Boolean,
    val createdAt: LocalDateTime,
    val type: ResultType
)

@Serializable
data class SkippedResponseBody(
    val slug: String,
    val iconUrl: String,
    val projectUrl: String,
    val gameVersion: String,
    val type: ResultType
)

fun Application.configureRouting(platforms: GlobalPlatformService, transformation: TransformationService,
                                 gameVersion: String, toolchainVersion: String,
                                 persistence: PersistenceService) {
    val maxThreadCount = System.getenv("org.sinytra.probe.max_threads")?.toIntOrNull() ?: 10
    val asyncTransform = AsyncTransformationRunner(transformation, persistence, maxThreadCount)

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

                val ifNeoForge = platforms.isNeoForgeAvailable(project, gameVersion)
                if (ifNeoForge) {
                    return@post call.respond(SkippedResponseBody(
                        project.slug,
                        project.iconUrl ?: "",
                        project.url,
                        gameVersion,
                        ResultType.NATIVE
                    ))
                }

                val resolved = platforms.resolveProject(project, gameVersion)
                    ?: return@post call.respond(UnavailableResponseBody(
                        project.slug,
                        LOADER_FABRIC,
                        gameVersion,
                        ResultType.UNAVAILABLE
                    ))

                val result: TestResult = asyncTransform.transform(project, resolved, gameVersion, toolchainVersion)

                val version = platforms.getVersion(project, result.versionId)

                val response = TestResponseBody(
                    result.modid,
                    project.iconUrl ?: "",
                    project.url,
                    version?.versionNumber ?: "unknown",
                    result.gameVersion,
                    result.toolchainVersion,
                    result.passing,
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
