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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.*

const val MR_API_HOST: String = "api.modrinth.com"
const val API_V3: String = "v3"

@Serializable
data class ModrinthProject(val id: String, val name: String)

@Serializable
data class ModrinthVersion(val id: String, val projectId: String, val loaders: Set<String>, val files: List<VersionFile>)

@Serializable
data class VersionFile(val filename: String, val url: String)

@Suppress("unused")
@Resource("/$API_V3/project")
class Projects {
    @Resource("{id}")
    class Id(val parent: Projects = Projects(), val id: String) {
        @Resource("version")
        class Version(val parent: Id, val loaders: String, val gameVersions: String)
    }
}

object ModrinthService {
    private val LOGGER = LoggerFactory.getLogger(ModrinthService::class.java)
    
    private val client = HttpClient(CIO) {
        install(Logging)
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
        client.get(Projects.Id.Version(Projects.Id(id = project), loaders = "[\"${loader}\"]", gameVersions = "[\"${gameVersion}\"]"))
            .body<List<ModrinthVersion>>()
            .firstOrNull()

    suspend fun downloadFile(version: ModrinthVersion): Path {
        val file = version.files.first()

        val fileStoragePath = Path(System.getProperty("org.sinytra.probe.storage_path")!!)
        val projectFolder = fileStoragePath / version.projectId
        val filePath = projectFolder / (version.id + "-" + file.filename)

        if (filePath.exists() && filePath.isRegularFile()) {
            LOGGER.info("Reusing cached version '{}' file at {}", version.id, filePath)
            return filePath
        }

        filePath.deleteIfExists()
        filePath.createParentDirectories()

        val response = client.get(file.url)
        val channel: ByteReadChannel = response.body()
        channel.copyAndClose(filePath.toFile().writeChannel())

        LOGGER.info("Version '{}' file saved to {}", version.id, filePath)

        return filePath
    }
}