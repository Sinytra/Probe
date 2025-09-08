package org.sinytra

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.sinytra.probe.base.ResponseBase
import org.sinytra.probe.base.ResultType
import org.sinytra.probe.base.SkippedResponseBody
import org.sinytra.probe.base.TestResponseBody
import org.sinytra.probe.base.UnavailableResponseBody

@Serializable
data class ResponseBaseData(
    override val type: ResultType
) : ResponseBase

class ProjectNotFoundException(message: String) : Exception(message)

object TransformRunner {
    @Serializable
    private data class ProbeRequestBody(val platform: String, val id: String)

    suspend fun runTransformation(platform: String, slug: String): ResponseBase {
        val endpoint = System.getenv("CORE_API_URL") ?: "localhost:8080"

        val client = HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
                    namingStrategy = JsonNamingStrategy.SnakeCase
                })
            }
            defaultRequest {
                url {
                    host = endpoint
                }
            }
        }

        val resp = client.post("/api/v1/probe") {
            contentType(ContentType.Application.Json)
            setBody(ProbeRequestBody(platform, slug))
            timeout { 
                socketTimeoutMillis = 60000
                requestTimeoutMillis = 60000
            }
        }

        if (resp.status == HttpStatusCode.NotFound) {
            throw ProjectNotFoundException("Project not found")
        }

        val base = resp.body<ResponseBaseData>()
        return when (base.type) {
            ResultType.TESTED -> resp.body<TestResponseBody>()
            ResultType.UNAVAILABLE -> resp.body<UnavailableResponseBody>()
            else -> resp.body<SkippedResponseBody>()
        }
    }
}