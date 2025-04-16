package org.sinytra.probe.service

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.sinytra.probe.getBaseStoragePath
import org.sinytra.probe.model.ProjectPlatform
import org.slf4j.LoggerFactory
import kotlin.io.path.*

private const val MR_API_HOST: String = "api.modrinth.com"
private const val API_V3: String = "v3"
private const val REQUIRED_DEP: String = "required"
private const val FAPI_ID: String = "P7dR8mSH"
const val MR_FFAPI_ID: String = "Aqlf1Shp"
const val LOADER_FABRIC = "fabric"
const val LOADER_NEOFORGE = "neoforge"

@Serializable
private data class MRProject(
    val id: String,
    override val name: String
) : PlatformProject {
    override val platform: ProjectPlatform = ProjectPlatform.MODRINTH
}

@Serializable
private data class MRVersion(
    val id: String,
    val projectId: String,
    val loaders: Set<String>,
    val files: List<MRVersionFile>,
    val dependencies: List<MRVersionDependency>
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
    val versionId: String,
    override val path: String,
    val dependencies: List<String>
) : ProjectVersion

@Suppress("unused")
@Resource("/$API_V3/project")
class Projects {
    @Resource("{id}")
    class Id(val parent: Projects = Projects(), val id: String) {
        @Resource("version")
        class Version(val parent: Id, val loaders: String, val loader_fields: String)
    }
}

@OptIn(ExperimentalLettuceCoroutinesApi::class)
class ModrinthService(
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
                @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
                namingStrategy = JsonNamingStrategy.SnakeCase
            })
        }
        defaultRequest {
            url {
                protocol = URLProtocol.HTTPS
                host = MR_API_HOST
            }
        }
        expectSuccess = true
    }

    override suspend fun getProject(slug: String): PlatformProject {
        val cmd = redis.coroutines()
        val key = "modrinth:$slug"

        val cached = cmd.get(key)?.let { Json.decodeFromString<MRProject>(it) }
        if (cached != null) {
            return cached
        }

        val data = client.get(Projects.Id(id = slug)).body<MRProject>()
        cmd.set(key, Json.encodeToString(data))
        return data
    }

    override suspend fun resolveProject(project: PlatformProject, gameVersion: String): ResolvedProject? {
        project as MRProject

        return resolveProjectInternal(project.id, gameVersion)
    }

    private suspend fun resolveProjectInternal(project: String, gameVersion: String): ResolvedProject? {
        val ver = getOrComputeVersion(project, gameVersion, LOADER_FABRIC) ?: throw RuntimeException("Error resolving project $project")

        val deps: List<ResolvedProject> = channelFlow {
            ver.dependencies.forEach { dep ->
                launch {
                    send(resolveProjectInternal(dep, gameVersion) ?: throw RuntimeException("Failed to resolve dependent project $dep"))
                }
            }
        }.toList()

        return ResolvedProject(ver, deps)
    }

    override suspend fun resolveProjectVersion(slug: String, gameVersion: String, loader: String): ProjectVersion? =
        getOrComputeVersion(slug, gameVersion, loader)

    private suspend fun getOrComputeVersion(project: String, gameVersion: String, loader: String): MRResolvedVersion? {
        val cmd = redis.coroutines()
        val key = "modrinth:$project:$gameVersion"
        val data = cmd.get(key)

        if (data == null) {
            LOGGER.info("Fetching version for project $project")

            val ver = computeProjectVersion(project, gameVersion, LOADER_NEOFORGE)
                ?: (if (loader != LOADER_NEOFORGE) computeProjectVersion(project, gameVersion, loader) else null)
                ?: return null

            val res = computeVersionFile(ver)
            cmd.set(key, Json.encodeToString(res))
            return res
        }

        val parsed = Json.decodeFromString<MRResolvedVersion>(data)
        // Validate file
        val path = parsed.getFilePath()
        if (!path.exists()) {
            cmd.del(key)
            return getOrComputeVersion(project, gameVersion, loader)
        }
        return parsed
    }

    private suspend fun computeProjectVersion(project: String, gameVersion: String, loader: String): MRVersion? =
        client.get(
            Projects.Id.Version(
                Projects.Id(id = project),
                loaders = "[\"${loader}\"]",
                loader_fields = "{\"game_versions\":[\"${gameVersion}\"]}"
            )
        )
            .body<List<MRVersion>>()
            .firstOrNull()

    private suspend fun computeVersionFile(version: MRVersion): MRResolvedVersion {
        val file = version.files.first()

        val basePath = getBaseStoragePath()
        val projectFolder = basePath / version.projectId
        val fileName = version.id + "-" + file.filename
        val filePath = projectFolder / fileName
        val relativePath = basePath.relativize(filePath).toString()
        val dependencies = version.dependencies
            .filter { it.dependencyType == REQUIRED_DEP && it.projectId != FAPI_ID }
            .map(MRVersionDependency::projectId)

        if (filePath.exists() && filePath.isRegularFile()) {
            return MRResolvedVersion(version.projectId, version.id, relativePath, dependencies)
        }

        filePath.deleteIfExists()
        filePath.createParentDirectories()

        val response = client.get(file.url)
        val channel: ByteReadChannel = response.body()
        channel.copyAndClose(filePath.toFile().writeChannel())

        return MRResolvedVersion(version.projectId, version.id, relativePath, dependencies)
    }
}