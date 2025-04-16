package org.sinytra.probe.service

import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.lang.ProcessBuilder.Redirect
import java.net.URI
import java.nio.channels.Channels
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists

const val NFRT_URL = "https://maven.neoforged.net/releases/net/neoforged/neoform-runtime/1.0.21/neoform-runtime-1.0.21-all.jar"
const val NEO_VERSION = "net.neoforged:neoforge:21.1.148:userdev"
const val NEO_UNIVERSAL_URL = "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.148/neoforge-21.1.148-universal.jar"

data class GameFiles(val cleanFile: Path, val loaderFiles: List<Path>)

class SetupService(private val baseDir: Path) {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(SetupService::class.java)
    }

    fun installLoader(): GameFiles {
        val installDir = baseDir / "install"
        installDir.createDirectories()

        val cleanArtifact = installDir / "clean.jar"
        val compiledArtifact = installDir / "compiled.jar"
        
        val neoUniversal = baseDir / "neo-universal.jar"
        downloadFile(NEO_UNIVERSAL_URL, neoUniversal)

        if (cleanArtifact.exists() && compiledArtifact.exists()) {
            return GameFiles(cleanArtifact, listOf(neoUniversal, compiledArtifact))
        }

        val runtime = baseDir / "runtime.jar"
        downloadFile(NFRT_URL, runtime)

        val args = listOf(
            "java", "-jar", runtime.absolutePathString(),
            "run", "--neoforge", NEO_VERSION, "--dist", "joined",
            "--write-result=compiled:${compiledArtifact.fileName}",
            "--write-result=vanillaDeobfuscated:${cleanArtifact.fileName}"
        )

        ProcessBuilder(args)
            .directory(installDir.toFile())
            .redirectOutput(Redirect.INHERIT)
            .redirectError(Redirect.INHERIT)
            .start()
            .waitFor(60, TimeUnit.MINUTES)

        return GameFiles(cleanArtifact, listOf(neoUniversal, compiledArtifact))
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
}