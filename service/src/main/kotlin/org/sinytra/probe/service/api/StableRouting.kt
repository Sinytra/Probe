package org.sinytra.probe.service.api

import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.route
import io.ktor.http.*
import io.ktor.serialization.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.sinytra.probe.base.TestRequestBody
import org.sinytra.probe.base.TestResponseBody
import org.sinytra.probe.core.service.SetupService
import org.sinytra.probe.service.impl.RoutingImpl
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Routing")

// WARNING: STABLE API, DO NOT ALTER
fun Application.configureStableRouting(setup: SetupService, routingImpl: RoutingImpl) {
    routing {
        stableApiRouting(setup, routingImpl)
    }
}

fun Route.stableApiRouting(setup: SetupService, routingImpl: RoutingImpl) {
    route({ 
        tags("Public API")    
    }) {
        get("/", {
            summary = "Check for service health"
            description = "Succeeds if the service is operational"
            response {
                HttpStatusCode.OK to {
                    description = "Service is operational"
                }
            }
        }) {
            call.respondText("Service operational")
        }

        get("/api/v1/versions", {
            summary = "Supported game versions"
            description = "Get a list of game versions supported for testing mods"
            response {
                HttpStatusCode.OK to {
                    description = "List of supported game versions"
                    body<List<String>>()
                }
            }
        }) {
            call.respond(setup.gameVersions)
        }

        post("/api/v1/probe", {
            summary = "Test a mod"
            description = "Check if a mod is compatible with Connector"
            request {
                body<TestRequestBody> {
                    description = "Project and game version to test"
                    required = true
                }
            }
            response {
                HttpStatusCode.OK to {
                    description = "Compatibility test result"
                    body<TestResponseBody>()
                }
                HttpStatusCode.BadRequest to {
                    description = "Invalid request body"
                }
            }
        }) {
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
    }
}