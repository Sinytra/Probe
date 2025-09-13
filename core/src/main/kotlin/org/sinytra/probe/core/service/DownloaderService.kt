package org.sinytra.probe.core.service

import kotlinx.coroutines.coroutineScope
import kotlinx.io.files.FileNotFoundException
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.net.URI
import java.nio.channels.Channels
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.Manifest
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.*

data class Library(
    val group: String, val name: String, val classifier: String?, val repository: String,
    val versionFilter: (gameVersion: String, version: String) -> Boolean
)

data class ResolvedLibrary(val path: Path, val version: String)

class DownloaderService(
    private val baseDir: Path,
    private val cache: CacheService,
    private val useCache: Boolean = true
) {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(DownloaderService::class.java)
        private const val NEO_MAVEN = "https://maven.neoforged.net/releases"
        private const val SINYTRA_MAVEN = "https://maven.sinytra.org"

        private val TRANSFORMER_LIB = Library("org.sinytra.connector", "transformer", "all", SINYTRA_MAVEN)
        { gameVersion, version ->
            version.contains("+") && version.split("+")[1] == gameVersion
        }
        private val NEO_UNIVERSAL_LIB = Library("net.neoforged", "neoforge", "universal", NEO_MAVEN)
        { gameVersion, version ->
            val qualifier = gameVersion.split(".", limit = 2)[1]
            version.startsWith("$qualifier.")
        }
        private val NFRT_LIB = Library("net.neoforged", "neoform-runtime", "all", NEO_MAVEN) { _, _ -> true }

        fun downloadFile(url: String, dest: Path) {
            if (!dest.exists()) {
                LOGGER.info("Downloading file $url to $dest")

                URI(url).toURL().openStream().use { input ->
                    FileOutputStream(dest.toFile()).use { output ->
                        output.channel.transferFrom(Channels.newChannel(input), 0, Long.MAX_VALUE)
                    }
                }
            }
        }
    }

    suspend fun getTransformerLib(gameVersion: String): ResolvedLibrary {
        return getLibrary(TRANSFORMER_LIB, gameVersion)
            ?: throw RuntimeException("Failed to resolve transformer library")
    }

    suspend fun getNeoForgeUniversalLib(gameVersion: String): ResolvedLibrary {
        return getLibrary(NEO_UNIVERSAL_LIB, gameVersion)
            ?: throw RuntimeException("Failed to resolve NeoForge Universal library")
    }

    suspend fun getNFRTRuntime(gameVersion: String, version: String): ResolvedLibrary {
        return getLibrary(NFRT_LIB, gameVersion, version)
            ?: throw RuntimeException("Failed to resolve NFRT library")
    }

    suspend fun clearCache(gameVersion: String) {
        val libraries = listOf(TRANSFORMER_LIB, NEO_UNIVERSAL_LIB, NFRT_LIB)
        for (library in libraries) {
            val key = "probe:library:${library.name}:game:$gameVersion"

            cache.del(key)
        }
    }

    private suspend fun getLibrary(library: Library, gameVersion: String, libVersion: String? = null): ResolvedLibrary? {
        val property = gameVersion.replace(".", "_")
        val provided = System.getProperty("org.sinytra.probe.lib.${library.name}.$property.path")
        if (provided != null) {
            val path = Path(provided).takeIf { it.exists() } ?: throw FileNotFoundException(provided)
            val version = readImplementationVersion(path) ?: throw RuntimeException("Library '${library.name}' version must be set")
            return ResolvedLibrary(path, version)
        }

        if (!useCache) return null

        val key = "probe:library:${library.name}:game:$gameVersion"
        val version = libVersion
            ?: System.getProperty("org.sinytra.probe.lib.${library.name}.$property.version")
            ?: cache.get(key)
            ?: coroutineScope {
                val remote = getRemoteLibraryVersion(library, gameVersion)
                cache.set(key, remote)
                remote
            }

        val group = library.group.replace('.', '/')
        val classifier = library.classifier?.let { "-$it" } ?: ""
        val mavenPath = "${group}/${library.name}/$version/${library.name}-$version$classifier.jar"

        val url = "${library.repository}/$mavenPath"
        val outputFile = baseDir / "maven" / mavenPath
        outputFile.createParentDirectories()

        downloadFile(url, outputFile)

        if (!outputFile.exists()) {
            throw IllegalStateException("Failed to download library ${library.name}")
        }

        return ResolvedLibrary(outputFile, version)
    }

    private fun getRemoteLibraryVersion(library: Library, gameVersion: String): String {
        try {
            val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val path = library.group.replace('.', '/')

            val dest = "${library.repository}/${path}/${library.name}/maven-metadata.xml"
            val doc = URI(dest).toURL().openStream().use(builder::parse)
            doc.documentElement.normalize()

            val versionElements = doc.getElementsByTagName("version")
            val versions = (0 until versionElements.length)
                .map { versionElements.item(it).textContent }
                .filter { library.versionFilter(gameVersion, it) }

            versions.lastOrNull()?.let { return it }
        } catch (e: Exception) {
            LOGGER.error("Error fetching '${library.name}' version", e)
        }

        throw RuntimeException("Could not fetch library version for '${library.name}'")
    }

    private fun readImplementationVersion(path: Path): String? {
        return FileSystems.newFileSystem(path)
            .use { fs ->
                val mfPath = fs.getPath("META-INF", "MANIFEST.MF")
                    .takeIf { it.exists() } ?: return null

                val manifest = Manifest()
                mfPath.inputStream().use(manifest::read)

                manifest.mainAttributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION)
            }
    }
}