@file:OptIn(ExperimentalSerializationApi::class)

package org.sinytra.probe.service.api

import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.ExampleEncoder
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.jetbrains.annotations.ApiStatus
import org.sinytra.probe.base.TestReport
import org.sinytra.probe.service.impl.RoutingImpl

@ApiStatus.Internal
fun Application.configureInternalRouting(routingImpl: RoutingImpl) {
    routing {
        install(OpenApi) {
            val json = Json {
                namingStrategy = JsonNamingStrategy.SnakeCase
                decodeEnumsCaseInsensitive = true
            }

            pathFilter = { _, url -> url.size < 3 || url[2] != "internal" }

            info {
                title = "Probe API"
                version = "1.0.0"
                description = "Connector compatibility testing service"

                contact {
                    name = "Sinytra"
                    url = "https://github.com/Sinytra/Probe"
                }
                license {
                    name = "MIT"
                    url = "https://github.com/Sinytra/Probe/blob/master/LICENSE"
                }
            }
            server {
                url = "http://localhost:8080"
                description = "Development Server"
            }
            server {
                url = "https://probe.sinytra.org"
                description = "Production Server"
            }
            schemas {
                generator = SchemaGenerator.kotlinx(json)
            }
            examples {
                encoder(ExampleEncoder.kotlinx(json))
            }
        }

        routing {
            route("api.json") {
                openApi()
            }

            route("swagger") {
                swaggerUI("/api.json")
            }
        }

        authenticate("api-key") {
            get("/api/v1/internal/stats") {
                val limit = call.request.queryParameters["limit"]?.toInt() ?: 50
                val stats = routingImpl.statsService.getModRequestedStats(limit)

                call.respond(HttpStatusCode.OK, stats)
            }

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