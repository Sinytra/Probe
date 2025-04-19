package org.sinytra.probe.service

import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.lang.ProcessBuilder.Redirect
import java.net.URI
import java.nio.channels.Channels
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists

data class GameFiles(val cleanFile: Path, val loaderFiles: List<Path>)

class SetupService(private val baseDir: Path, private val useLocalCache: Boolean, private val nfrtVersion: String, private val neoForgeVersion: String) {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(SetupService::class.java)
    }

    fun installLoader(): GameFiles {
        val outputDir = baseDir / "output"
        outputDir.createDirectories()

        val cleanArtifact = outputDir / "clean.jar"
        val compiledArtifact = outputDir / "compiled.jar"
        
        val neoUniversal = baseDir / "neo-universal.jar"
        val neoUniversalUrl = getMavenUrl("net.neoforged", "neoforge", neoForgeVersion, "universal")
        downloadFile(neoUniversalUrl, neoUniversal)

        val runtime = baseDir / "runtime.jar"
        val nfrtUrl = getMavenUrl("net.neoforged", "neoform-runtime", nfrtVersion, "all")
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

        ProcessBuilder(args)
            .directory(outputDir.toFile())
            .redirectOutput(Redirect.INHERIT)
            .redirectError(Redirect.INHERIT)
            .start()
            .waitFor(60, TimeUnit.MINUTES)

        return GameFiles(cleanArtifact, listOf(neoUniversal, compiledArtifact))
    }

    fun getTransformLibPath(): Path {
        return System.getProperty("org.sinytra.transformer.path")?.let(::Path) ?: throw RuntimeException("Transform lib not found")
    }

    private fun downloadFile(url: String, dest: Path) {
        if (!dest.exists()) {
            LOGGER.info("Downloading file $url to $dest")

            URI(url).toURL().openStream().use { input ->
                FileOutputStream(dest.toFile()).use { output ->
                    output.channel.transferFrom(Channels.newChannel(input), 0, Long.MAX_VALUE)
                }
            }
        }
    }
    
    private fun getMavenUrl(group: String, name: String, version: String, classifier: String? = null): String {
        return "https://maven.neoforged.net/releases/${group.replace('.', '/')}/$name/$version/$name-$version${classifier?.let { "-$it" }}.jar"
    }
}