package org.sinytra.probe.core.service

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.plugins.resources.Resources
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.sinytra.probe.core.model.ProjectPlatform
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.*

private const val MR_API_HOST: String = "api.modrinth.com"
private const val API_V3: String = "v3"
private const val REQUIRED_DEP: String = "required"
private const val FAPI_ID: String = "P7dR8mSH"
const val MR_FFAPI_ID: String = "Aqlf1Shp"
const val LOADER_FABRIC = "fabric"
const val LOADER_NEOFORGE = "neoforge"

@Serializable
internal data class MRProject(
    override val id: String,
    override val slug: String,
    override val name: String,
    override val iconUrl: String?
) : PlatformProject {
    override val url: String = "https://modrinth.com/mod/$slug"
    override val platform: ProjectPlatform = ProjectPlatform.MODRINTH
}

@Serializable
private data class MRVersion(
    val id: String,
    val versionNumber: String,
    val projectId: String,
    val loaders: Set<String>,
    val files: List<MRVersionFile>,
    val dependencies: List<MRVersionDependency>,
    val gameVersions: List<String>
)

@Serializable
private data class MRVersionFile(
    val filename: String,
    val url: String
)

@Serializable
private data class MRVersionDependency(
    val projectId: String,
    val dependencyType: String
)

@Serializable
data class MRResolvedVersion(
    override val projectId: String,
    override val versionId: String,
    override val versionNumber: String,
    override val path: String,
    override val dependencies: List<String>
) : ProjectVersion

@Serializable
private data class MRSearchResults(val hits: List<MRSearchResult>)

@Serializable
data class MRSearchResult(
    val projectId: String,
    val slug: String,
    val name: String,
    val iconUrl: String?,
    val versionId: String
) {
    internal fun toMRProject(): MRProject = MRProject(projectId, slug, name, iconUrl ?: "")
}

@Serializable
data class ProjectSearchResult(
    val id: String,
    val name: String,
    val iconUrl: String?,
    val slug: String,
    val versionId: String
)

@Suppress("unused")
@Resource("/$API_V3/project")
class Projects {
    @Resource("{id}")
    class Id(val parent: Projects = Projects(), val id: String) {
        @Resource("version")
        class Version(val parent: Id, val loaders: String, val loader_fields: String)
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

@OptIn(ExperimentalLettuceCoroutinesApi::class)
class ModrinthService(
    private val storagePath: Path,
    private val redis: StatefulRedisConnection<String, String>
) : PlatformService {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(ModrinthService::class.java)
    }

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

    override suspend fun getProject(slug: String): PlatformProject? {
        val cmd = redis.coroutines()
        val slugKey = "modrinth:slug:$slug"

        // In case someone passes in an ID
        getCachedProject("modrinth:project:$slug")?.let { return it }
        cmd.get(slugKey)?.let { getCachedProject("modrinth:project:$it") }?.let { return it }

        val data = client.get(Projects.Id(id = slug))
            .takeIf { it.status.isSuccess() }
            ?.body<MRProject>()
            ?: return null

        cmd.set("modrinth:project:${data.id}", Json.encodeToString(data))
        cmd.set(slugKey, data.slug)
        return data
    }

    suspend fun getCachedProject(key: String): PlatformProject? {
        val cmd = redis.coroutines()
        val cached = cmd.get(key)?.let { Json.decodeFromString<MRProject>(it) }
        return cached
    }

    override suspend fun getVersion(slug: String, versionId: String): ProjectVersion? {
        val cmd = redis.coroutines()
        val key = "modrinth:version:$versionId"

        val data = cmd.get(key) ?: return resolveVersion(versionId)

        val parsed = Json.decodeFromString<MRResolvedVersion>(data)
        val actualPath = storagePath.resolve(parsed.path)
        if (!actualPath.exists()) {
            cmd.del(key)
            return resolveVersion(versionId)
        }

        return parsed
    }

    suspend fun resolveVersion(versionId: String): ProjectVersion? {
        val cmd = redis.coroutines()
        val key = "modrinth:version:$versionId"

        LOGGER.info("Fetching version {}", versionId)

        val ver = findProjectVersion(versionId) ?: return null

        val res = computeVersionFile(ver)
        cmd.set(key, Json.encodeToString(res))
        return res
    }

    override suspend fun isNeoForgeAvailable(project: PlatformProject, gameVersion: String): Boolean {
        val cmd = redis.coroutines()
        val key = "modrinth:project:${project.id}:neoforge"

        val data = cmd.get(key)

        if (data == null) {
            val available = computeProjectVersion(project.id, listOf(gameVersion), LOADER_NEOFORGE) != null

            cmd.set(key, available.toString())
            return available
        }

        return data == "true"
    }

    override suspend fun resolveProject(project: PlatformProject, gameVersion: String): ResolvedProject? {
        project as MRProject

        return resolveProjectInternal(project.id, gameVersion, listOf(gameVersion), false)
    }

    suspend fun resolveProject(project: PlatformProject, gameVersions: List<String>, fallbackLoader: Boolean): ResolvedProject? {
        project as MRProject

        return resolveProjectInternal(project.id, gameVersions.first(), gameVersions, fallbackLoader)
    }

    suspend fun searchProjects(limit: Int, offset: Int, gameVersion: String, loader: String, excludeLoader: String?): List<ProjectSearchResult>? {
        return client
            .get(
                Search(
                    facets = "[[\"project_types:mod\"],[\"categories:$loader\"],[\"game_versions:$gameVersion\"]${if (excludeLoader != null) ",[\"categories!=$excludeLoader\"]" else ""}]",
                    index = "downloads",
                    limit = limit,
                    offset = offset
                )
            )
            .takeIf { it.status.isSuccess() }
            ?.body<MRSearchResults>()
            ?.hits
            ?.onEach { cacheProject(it.toMRProject()) }
            ?.map { ProjectSearchResult(it.projectId, it.name, it.iconUrl, it.slug, it.versionId) }
    }

    private suspend fun cacheProject(project: MRProject) {
        val cmd = redis.coroutines()

        val slugKey = "modrinth:slug:${project.slug}"
        if (cmd.exists(slugKey) != 1L) {
            cmd.set(slugKey, project.id)
        }

        val key = "modrinth:project:${project.id}"
        if (cmd.exists(key) != 1L) {
            cmd.set(key, Json.encodeToString(project))
        }
    }

    private suspend fun resolveProjectInternal(project: String, gameVersion: String, allowedVersions: List<String>, fallbackLoader: Boolean): ResolvedProject? {
        val ver = getOrComputeVersion(project, gameVersion, allowedVersions, LOADER_FABRIC, fallbackLoader) ?: return null

        val deps: List<ResolvedProject> = channelFlow {
            ver.dependencies.forEach { dep ->
                launch {
                    send(resolveProjectInternal(dep, gameVersion, allowedVersions, true) ?: throw RuntimeException("Failed to resolve dependent project $dep"))
                }
            }
        }.toList()

        return ResolvedProject(ver, deps)
    }

    override suspend fun resolveProjectVersion(slug: String, gameVersion: String, loader: String): ProjectVersion? =
        getOrComputeVersion(slug, gameVersion, listOf(gameVersion), loader, false)

    private suspend fun getOrComputeVersion(projectId: String, gameVersion: String, allowedVersions: List<String>, loader: String, fallbackLoader: Boolean): MRResolvedVersion? {
        if (projectId.length != 8) {
            throw IllegalArgumentException("Invalid Project ID: $projectId")
        }

        val cmd = redis.coroutines()
        val key = "modrinth:project:$projectId:$gameVersion"
        val data = cmd.get(key)

        if (data == null) {
            LOGGER.info("Fetching version for project $projectId")

            val ver = computeProjectVersion(projectId, allowedVersions, if (fallbackLoader) LOADER_NEOFORGE else loader)
                ?: (if (fallbackLoader && loader != LOADER_NEOFORGE) computeProjectVersion(projectId, allowedVersions, loader) else null)
                ?: return null

            val res = computeVersionFile(ver)
            val serialized = Json.encodeToString(res)
            cmd.set(key, serialized)
            cmd.set("modrinth:version:${ver.id}", serialized)
            return res
        }

        val parsed = Json.decodeFromString<MRResolvedVersion>(data)
        // Validate file
        val path = storagePath / parsed.path
        if (!path.exists()) {
            cmd.del(key)
            return getOrComputeVersion(projectId, gameVersion, allowedVersions, loader, fallbackLoader)
        }
        return parsed
    }

    private suspend fun computeProjectVersion(projectId: String, gameVersions: List<String>, loader: String): MRVersion? =
        client.get(
            Projects.Id.Version(
                Projects.Id(id = projectId),
                loaders = "[\"${loader}\"]",
                loader_fields = "{\"game_versions\":[${gameVersions.joinToString(separator = ",") { "\"$it\"" }}]}"
            )
        )
            .body<List<MRVersion>>()
            .minByOrNull {
                val fileGameVersions = it.gameVersions.sortedBy(gameVersions::indexOf)
                gameVersions.indexOf(fileGameVersions.first())
            }

    private suspend fun findProjectVersion(id: String): MRVersion =
        client.get(Versions.Id(id = id))
            .body<MRVersion>()

    private suspend fun computeVersionFile(version: MRVersion): MRResolvedVersion {
        val file = version.files.first()
        val project = getProject(version.projectId) ?: throw IllegalStateException("Project ${version.projectId} not found")

        val basePath = storagePath
        val filePath = basePath / "${project.slug}-${project.id}/${project.slug}-${version.id}.jar"
        val relativePath = basePath.relativize(filePath).toString()
        val dependencies = version.dependencies
            .filter { it.dependencyType == REQUIRED_DEP && it.projectId != FAPI_ID }
            .map(MRVersionDependency::projectId)

        if (filePath.exists() && filePath.isRegularFile()) {
            return MRResolvedVersion(version.projectId, version.id, version.versionNumber, relativePath, dependencies)
        }

        filePath.deleteIfExists()
        filePath.createParentDirectories()

        val response = client.get(file.url)
        val channel: ByteReadChannel = response.body()
        channel.copyAndClose(filePath.toFile().writeChannel())

        return MRResolvedVersion(version.projectId, version.id, version.versionNumber, relativePath, dependencies)
    }
}