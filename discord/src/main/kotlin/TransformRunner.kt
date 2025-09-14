package org.sinytra

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.sinytra.probe.base.ResultType
import org.sinytra.probe.base.TestProjectDTO
import org.sinytra.probe.base.TestRequestBody
import org.sinytra.probe.base.TestResponseBody
import org.sinytra.probe.base.db.ProjectPlatform

@Serializable
data class ResponseBaseData(
    val project: TestProjectDTO,
    val type: ResultType
)

class ProjectNotFoundException(message: String) : Exception(message)

object TransformRunner {
    suspend fun runTransformation(platform: String, slug: String, gameVersion: String): TestResponseBody {
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

        val parsedPlatform = ProjectPlatform.valueOf(platform)
        val resp = client.post("/api/v1/probe") {
            contentType(ContentType.Application.Json)
            setBody(TestRequestBody(parsedPlatform, slug, gameVersion))
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
            ResultType.TESTED -> resp.body<TestResponseBody.Tested>()
            ResultType.UNAVAILABLE -> resp.body<TestResponseBody.Unavailable>()
            else -> resp.body<TestResponseBody.Native>()
        }
    }
}