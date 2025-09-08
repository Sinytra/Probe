package org.sinytra.probe.core.service

import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.net.URI
import java.nio.channels.Channels
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.exists

fun getMavenUrl(repo: String, group: String, name: String, version: String, classifier: String? = null): String {
    return "$repo/${group.replace('.', '/')}/$name/$version/$name-$version${classifier?.let { "-$it" }}.jar"
}

data class TransformLib(val path: Path, val version: String)

class DownloaderService(private val baseDir: Path, private val initialTransformerVersion: String?, private val useProvided: Boolean = true) {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(DownloaderService::class.java)
        private const val SINYTRA_MAVEN = "https://maven.sinytra.org"

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

    var activeTransformerVersion: String? = null
        private set

    fun getTransformLib(): TransformLib {
        val provided = System.getProperty("org.sinytra.transformer.path")
        if (useProvided && provided != null) {
            val version = initialTransformerVersion ?: throw RuntimeException("Transformer version must be set")
            return TransformLib(Path(provided), version)
        }

        if (activeTransformerVersion == null) {
            activeTransformerVersion = computeTransformerVersion()
        }

        val outputFile = baseDir / "transformer-$activeTransformerVersion-all.jar"
        outputFile.createParentDirectories()

        val transformerUrl = getMavenUrl(SINYTRA_MAVEN, "org.sinytra.connector", "transformer", activeTransformerVersion!!, "all")
        downloadFile(transformerUrl, outputFile)
        if (!outputFile.exists()) {
            throw IllegalStateException("Failed to download transformer lib")
        }

        return TransformLib(outputFile, activeTransformerVersion!!)
    }

    private fun computeTransformerVersion(): String {
        if (initialTransformerVersion != null) {
            return initialTransformerVersion
        }

        try {
            val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val dest = "$SINYTRA_MAVEN/org/sinytra/connector/transformer/maven-metadata.xml"
            val doc = URI(dest).toURL().openStream().use(builder::parse)
            doc.documentElement.normalize()

            val latest = doc.getElementsByTagName("latest")
                ?.takeIf { it.length > 0 }
                ?.item(0)?.textContent

            if (latest != null) {
                return latest
            }
        } catch (e: Exception) {
            LOGGER.error("Error fetching transformer version", e)
        }

        throw RuntimeException("Could not fetch transformer version")
    }
}