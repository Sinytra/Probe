package org.sinytra.probe.service.api

import io.ktor.http.*
import io.ktor.serialization.*
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.sinytra.probe.base.TestReport
import org.sinytra.probe.base.TestRequestBody
import org.sinytra.probe.core.platform.GlobalPlatformService
import org.sinytra.probe.core.service.AsyncTransformationRunner
import org.sinytra.probe.core.service.PersistenceService
import org.sinytra.probe.core.service.SetupService
import org.sinytra.probe.core.service.TransformationService
import org.sinytra.probe.service.impl.RoutingImpl
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Routing")

fun Application.configureStableRouting(
    setup: SetupService, platforms: GlobalPlatformService,
    transformation: TransformationService, persistence: PersistenceService
) {
    val maxThreadCount = System.getenv("org.sinytra.probe.max_threads")?.toIntOrNull() ?: 10
    val asyncTransform = AsyncTransformationRunner(transformation, persistence, maxThreadCount)
    val routingImpl = RoutingImpl(setup, platforms, persistence, asyncTransform)

    // WARNING: STABLE API, DO NOT ALTER
    routing {
        post("/api/v1/probe") {
            try {
                val body = call.receive<TestRequestBody>()

                routingImpl.testMod(body, call)
            } catch (ex: IllegalStateException) {
                LOGGER.error("Error in test response", ex)
                call.respond(HttpStatusCode.BadRequest)
            } catch (ex: JsonConvertException) {
                call.respond(HttpStatusCode.BadRequest)
            }
        }

        get("/api/v1/versions") {
            call.respond(setup.gameVersions)
        }

        authenticate("api-key") {
            post("/api/v1/internal/import") {
                val body = call.receive<TestReport>()

                if (body.environment.transformerVersion == null) {
                    call.respond(HttpStatusCode.BadRequest, "environment.transformerVersion must not be null")
                    return@post
                }

                routingImpl.importTestReport(body)

                call.respond(HttpStatusCode.OK)
            }

            post("/api/v1/internal/update") {
                routingImpl.updateLibraries()

                call.respond(HttpStatusCode.OK)
            }
        }
    }
}