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
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

interface ResponseBase {
    val type: ResultType
}

@Serializable
data class ResponseBaseData(
    override val type: ResultType
) : ResponseBase

// TODO Shared types
@Serializable
enum class ResultType {
    TESTED,
    NATIVE
}

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
    override val type: ResultType = ResultType.TESTED
) : ResponseBase

@Serializable
data class SkippedResponseBody(
    val slug: String,
    val iconUrl: String,
    val projectUrl: String,
    val gameVersion: String,
    override val type: ResultType = ResultType.NATIVE
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
        }

        if (resp.status == HttpStatusCode.NotFound) {
            throw ProjectNotFoundException("Project not found")
        }

        val base = resp.body<ResponseBaseData>()
        return if (base.type == ResultType.TESTED) resp.body<TestResponseBody>()
        else resp.body<SkippedResponseBody>()
    }
}