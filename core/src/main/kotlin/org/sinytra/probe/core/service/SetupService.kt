package org.sinytra.probe.core.service

import org.sinytra.probe.core.service.DownloaderService.Companion.downloadFile
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

data class GameFiles(val cleanFile: Path, val loaderFiles: List<Path>)

class SetupService(
    private val baseDir: Path,
    private val useLocalCache: Boolean,
    private val nfrtVersion: String,
    private val neoForgeVersion: String,
    initialTransformerVersion: String?
) {
    companion object {
        private const val NEO_MAVEN = "https://maven.neoforged.net/releases"
    }

    private var downloader = DownloaderService(baseDir, initialTransformerVersion)

    fun installDependencies(): GameFiles {
        val outputDir: Path = baseDir / "output"
        outputDir.createDirectories()

        val cleanArtifact = outputDir / "clean.jar"
        val compiledArtifact = outputDir / "compiled.jar"

        val neoUniversal = baseDir / "neoforge-$neoForgeVersion-universal.jar"
        val neoUniversalUrl = getMavenUrl(NEO_MAVEN, "net.neoforged", "neoforge", neoForgeVersion, "universal")
        downloadFile(neoUniversalUrl, neoUniversal)

        val runtime = baseDir / "neoform-runtime-$nfrtVersion-all.jar"
        val nfrtUrl = getMavenUrl(NEO_MAVEN, "net.neoforged", "neoform-runtime", nfrtVersion, "all")
        downloadFile(nfrtUrl, runtime)

        val workDir = baseDir / ".temp"
        workDir.createDirectories()

        val neoMavenCoords = "net.neoforged:neoforge:$neoForgeVersion:userdev"
        val args = mutableListOf(
            "java", "-jar", runtime.absolutePathString(),
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

        // Initialize transform lib
        getTransformLib()

        return GameFiles(cleanArtifact, listOf(neoUniversal, compiledArtifact))
    }

    fun getTransformLib(): TransformLib = downloader.getTransformLib()

    fun updateTransformLib(): TransformLib? {
        val newDownloader = DownloaderService(baseDir, null, false)
        val newTransformer = newDownloader.getTransformLib()

        if (newTransformer.version != downloader.activeTransformerVersion) {
            downloader = newDownloader
            return newTransformer
        }

        return null
    }

    private fun getMavenUrl(repo: String, group: String, name: String, version: String, classifier: String? = null): String {
        return "$repo/${group.replace('.', '/')}/$name/$version/$name-$version${classifier?.let { "-$it" }}.jar"
    }
}