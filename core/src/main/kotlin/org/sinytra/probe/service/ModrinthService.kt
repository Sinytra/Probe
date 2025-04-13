package org.sinytra.probe.service

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val MR_API_HOST: String = "api.modrinth.com"
const val API_V3: String = "v3"

@Serializable
data class ModrinthProject(val id: String, val name: String)

@Serializable
data class ModrinthVersion(val id: String, val loaders: Set<String>)

fun HttpRequestBuilder.modrinthApi(vararg path: String) {
    url {
        protocol = URLProtocol.HTTPS
        host = MR_API_HOST
        path(API_V3, *path)
    }
}

object ModrinthService {
    private val client = HttpClient(CIO) {
        install(Logging)
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
            })
        }
    }

    suspend fun getProject(id: String): ModrinthProject? =
        client.get { modrinthApi("project", id) }
            .takeIf { it.status.isSuccess() }
            ?.body()

    suspend fun getProjectVersion(project: String, gameVersion: String, loader: String): ModrinthVersion? =
        client.get {
            modrinthApi("project", project, "version")
            parameter("loaders", "[\"${loader}\"]")
            parameter("game_versions", "[\"${gameVersion}\"]")
        }
            .takeIf { it.status.isSuccess() }
            ?.body<List<ModrinthVersion>>()
            ?.firstOrNull()
}