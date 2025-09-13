package org.sinytra.probe.core.service

import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

data class GameFiles(val cleanFile: Path, val loaderFiles: List<Path>)

class SetupService(
    private val baseDir: Path,
    private val useLocalCache: Boolean,
    private val nfrtVersion: String,
    val gameVersions: List<String>,
    cache: CacheService
) {
    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger(SetupService::class.java)
    }

    private var downloader = DownloaderService(baseDir, cache)

    suspend fun installDependencies() {
        for (gameVersion in gameVersions) {
            LOGGER.info("Installing game files for $gameVersion")

            installGameFiles(gameVersion)
        }
    }

    fun getGameFiles(gameVersion: String): GameFiles {
        return runBlocking { installGameFiles(gameVersion) }
    }

    fun getTransformLib(gameVersion: String): ResolvedLibrary {
        validateGameVersion(gameVersion)
        return runBlocking { downloader.getTransformerLib(gameVersion) }
    }

    fun getNeoForgeVersion(gameVersion: String): String {
        validateGameVersion(gameVersion)
        return runBlocking { downloader.getNeoForgeUniversalLib(gameVersion).version }
    }

    suspend fun updateLibraries() {
        LOGGER.info("Updating libraries")

        gameVersions.forEach {
            downloader.clearCache(it)
        }

        installDependencies()
    }

    fun hasGameVersion(gameVersion: String): Boolean =
        gameVersions.contains(gameVersion)

    private suspend fun installGameFiles(gameVersion: String): GameFiles {
        val outputDir: Path = baseDir / "neoforge" / gameVersion
        outputDir.createDirectories()

        val cleanArtifact = outputDir / "clean.jar"
        val compiledArtifact = outputDir / "compiled.jar"

        val neoUniversal = downloader.getNeoForgeUniversalLib(gameVersion)
        val nfrt = downloader.getNFRTRuntime(gameVersion, nfrtVersion)

        val workDir = baseDir / ".temp"
        workDir.createDirectories()

        val neoMavenCoords = "net.neoforged:neoforge:${neoUniversal.version}:userdev"
        val args = mutableListOf(
            "java", "-jar", nfrt.path.absolutePathString(),
            "run", "--neoforge", neoMavenCoords, "--dist", "joined",
            "--write-result=compiled:${compiledArtifact.fileName}",
            "--write-result=vanillaDeobfuscated:${cleanArtifact.fileName}",
            "--work-dir", workDir.absolutePathString()
        )

        if (useLocalCache) {
            val localCachePath = baseDir / ".neoformruntime"
            localCachePath.createDirectories()
            args += listOf("--home-dir", localCachePath.absolutePathString())
        }

        if (System.getenv("org.sinytra.probe.core.verbose") == "true") {
            args += "--verbose"
        }

        ProcessBuilder(args)
            .directory(outputDir.toFile())
            .redirectOutput(Redirect.INHERIT)
            .redirectError(Redirect.INHERIT)
            .start()
            .waitFor(60, TimeUnit.MINUTES)

        getTransformLib(gameVersion)

        return GameFiles(cleanArtifact, listOf(neoUniversal.path, compiledArtifact))
    }

    private fun validateGameVersion(gameVersion: String) {
        if (!gameVersions.contains(gameVersion)) {
            throw IllegalArgumentException("Game version $gameVersion is not supported")
        }
    }
}