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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.*

const val MR_API_HOST: String = "api.modrinth.com"
const val API_V3: String = "v3"
const val REQUIRED_DEP: String = "required"
const val FAPI_ID: String = "P7dR8mSH"
const val FFAPI_ID: String = "Aqlf1Shp"
const val LOADER_NEOFORGE = "neoforge"

// TODO Api response cache

@Serializable
data class ModrinthProject(val id: String, val name: String)

@Serializable
data class ModrinthVersion(
    val id: String,
    val projectId: String,
    val loaders: Set<String>,
    val files: List<VersionFile>,
    val dependencies: List<VersionDependency>
)

@Serializable
data class VersionFile(val filename: String, val url: String)

@Serializable
data class VersionDependency(val projectId: String, val dependencyType: String)

@Suppress("unused")
@Resource("/$API_V3/project")
class Projects {
    @Resource("{id}")
    class Id(val parent: Projects = Projects(), val id: String) {
        @Resource("version")
        class Version(val parent: Id, val loaders: String, val loader_fields: String)
    }
}

@Serializable
data class ResolvedVersion(val projectId: String, val versionId: String, val file: Path)

object ModrinthService {
    private val LOGGER = LoggerFactory.getLogger(ModrinthService::class.java)

    private val client = HttpClient(CIO) {
        install(Logging) {
            level = LogLevel.INFO
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

    suspend fun getProject(id: String): ModrinthProject? = client.get(Projects.Id(id = id)).body()

    suspend fun getProjectVersion(project: String, gameVersion: String, loader: String): ModrinthVersion? =
        client.get(Projects.Id.Version(
            Projects.Id(id = project),
            loaders = "[\"${loader}\"]",
            loader_fields = "{\"game_versions\":[\"${gameVersion}\"]}")
        )
            .body<List<ModrinthVersion>>()
            .firstOrNull()

    suspend fun resolveVersion(version: ModrinthVersion): ResolvedVersion {
        val file = version.files.first()

        val fileStoragePath = Path(System.getProperty("org.sinytra.probe.storage_path")!!)
        val projectFolder = fileStoragePath / version.projectId
        val filePath = projectFolder / (version.id + "-" + file.filename)

        if (filePath.exists() && filePath.isRegularFile()) {
            LOGGER.info("Reusing cached version '{}' file at {}", version.id, filePath)
            return ResolvedVersion(version.projectId, version.id, filePath)
        }

        filePath.deleteIfExists()
        filePath.createParentDirectories()

        val response = client.get(file.url)
        val channel: ByteReadChannel = response.body()
        channel.copyAndClose(filePath.toFile().writeChannel())

        LOGGER.info("Version '{}' file saved to {}", version.id, filePath)

        return ResolvedVersion(version.projectId, version.id, filePath)
    }

    suspend fun resolveVersionDependencies(version: ModrinthVersion, gameVersion: String, loader: String): List<ResolvedVersion> {
        val visited = ConcurrentHashMap.newKeySet<String>()
        return downloadVersionDependenciesFlow(version, gameVersion, loader, visited)
            .flowOn(Dispatchers.IO)
            .toList()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun downloadVersionDependenciesFlow(version: ModrinthVersion, gameVersion: String, loader: String, visited: MutableSet<String>): Flow<ResolvedVersion> =
        version.dependencies
            .filter { visited.add(it.projectId) }
            .filter { it.dependencyType == REQUIRED_DEP && it.projectId != FAPI_ID }
            .asFlow()
            .flatMapMerge(concurrency = 8) { dep ->
                flow {
                    // Try getting neoforge dep version first
                    val depVersion = getProjectVersion(dep.projectId, gameVersion, loader)
                        // Fallback to fabric version
                        ?: (if (loader != LOADER_NEOFORGE) getProjectVersion(dep.projectId, gameVersion, loader) else null)
                        ?: throw RuntimeException("Failed to get version for dependency")
                    emitAll(downloadVersionDependenciesFlow(depVersion, gameVersion, loader, visited))
                    emit(resolveVersion(depVersion))
                }
            }
}