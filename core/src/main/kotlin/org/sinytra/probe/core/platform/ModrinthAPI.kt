@file:OptIn(ExperimentalSerializationApi::class)

package org.sinytra.probe.core.platform

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.sinytra.probe.base.db.ProjectPlatform
import java.nio.file.Path

private const val MR_API_HOST: String = "api.modrinth.com"
private const val API_V3: String = "v3"

object ModrinthAPI {
    @Serializable
    data class ModrinthProject(
        override val id: String,
        override val slug: String,
        override val name: String,
        override val iconUrl: String?
    ) : PlatformProject {
        override val url: String = "https://modrinth.com/mod/$slug"
        override val platform: ProjectPlatform = ProjectPlatform.MODRINTH
    }

    @Serializable
    data class ModrinthVersion(
        val id: String,
        val versionNumber: String,
        val projectId: String,
        val loaders: Set<String>,
        val files: List<ModrinthVersionFile>,
        val dependencies: List<ModrinthVersionDependency>,
        val gameVersions: List<String>
    )

    @Serializable
    data class ModrinthVersionFile(
        val filename: String,
        val url: String
    )

    @Serializable
    data class ModrinthVersionDependency(
        val projectId: String,
        val dependencyType: String
    )

    @Serializable
    data class ModrinthSearchResult(
        val projectId: String,
        val slug: String,
        val name: String,
        val iconUrl: String?,
        val versionId: String
    ) {
        internal fun toMRProject() = ModrinthProject(projectId, slug, name, iconUrl ?: "")
    }

    @Serializable
    data class ModrinthSearchResults(val hits: List<ModrinthSearchResult>)

    @Suppress("unused")
    @Resource("/${API_V3}/project")
    class Projects {
        @Resource("{id}")
        class Id(val parent: Projects = Projects(), val id: String) {
            @Resource("version")
            class Version(val parent: Id, val loaders: String, @SerialName("loader_fields") val loaderFields: String)
        }
    }

    @Suppress("unused")
    @Resource("/$API_V3/version")
    class Versions {
        @Resource("{id}")
        class Id(val versions: Versions = Versions(), val id: String)
    }

    @Suppress("unused")
    @Resource("/$API_V3/search")
    class Search(val facets: String, val index: String, val limit: Int, val offset: Int)

    private val client = HttpClient(CIO) {
        install(Logging) {
            level = LogLevel.NONE
        }
        install(Resources)
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                @OptIn(ExperimentalSerializationApi::class)
                namingStrategy = JsonNamingStrategy.SnakeCase
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60000
            connectTimeoutMillis = 60000
            socketTimeoutMillis = 60000
        }
        defaultRequest {
            url {
                protocol = URLProtocol.HTTPS
                host = MR_API_HOST
            }
        }
    }

    suspend fun getProject(slug: String): ModrinthProject? =
        client.get(Projects.Id(id = slug))
            .takeIf { it.status.isSuccess() }
            ?.body<ModrinthProject>()

    suspend fun getVersion(id: String): ModrinthVersion? =
        client.get(Versions.Id(id = id))
            .takeIf { it.status.isSuccess() }
            ?.body<ModrinthVersion>()

    suspend fun getCandidateVersion(projectId: String, gameVersions: List<String>, loader: String): ModrinthVersion? =
        client.get(
            Projects.Id.Version(
                Projects.Id(id = projectId),
                loaders = "[\"${loader}\"]",
                loaderFields = "{\"game_versions\":[${gameVersions.joinToString(separator = ",") { "\"$it\"" }}]}"
            )
        )
            .takeIf { it.status.isSuccess() }
            ?.body<List<ModrinthVersion>>()
            ?.minByOrNull {
                val fileGameVersions = it.gameVersions.sortedBy(gameVersions::indexOf)
                gameVersions.indexOf(fileGameVersions.first())
            }

    suspend fun searchProjects(limit: Int, offset: Int, gameVersion: String, loader: String, excludeLoader: String?): ModrinthSearchResults? =
        client
            .get(
                Search(
                    facets = "[[\"project_types:mod\"],[\"categories:$loader\"],[\"game_versions:$gameVersion\"]${if (excludeLoader != null) ",[\"categories!=$excludeLoader\"]" else ""}]",
                    index = "downloads",
                    limit = limit,
                    offset = offset
                )
            )
            .takeIf { it.status.isSuccess() }
            ?.body<ModrinthSearchResults>()

    suspend fun downloadFile(url: String, dest: Path) {
        val response = client.get(url)
        if (!response.status.isSuccess()) {
            throw RuntimeException("Failed to download file $url")
        }

        val channel: ByteReadChannel = response.body()
        channel.copyAndClose(dest.toFile().writeChannel())
    }
}