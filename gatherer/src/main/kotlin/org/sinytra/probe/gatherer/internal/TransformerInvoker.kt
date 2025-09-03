@file:OptIn(ExperimentalSerializationApi::class, ExperimentalPathApi::class)

package org.sinytra.probe.gatherer.internal

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.sinytra.probe.core.service.TransformLibOutput
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

class TransformerInvoker(
    private val gameVersion: String,
    private val cleanupOutput: Boolean
) {
    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger("TransformerInvoker")
    }

    @Serializable
    data class TransformResult(
        val output: TransformLibOutput,
        val errors: Boolean
    )

    // TODO Unify with TransformationService
    fun invokeTransform(slug: String, transformerPath: Path, workDir: Path, sources: List<Path>, cleanPath: Path, classPath: List<Path>): TransformResult {
        val baseArgs = listOf(
            "java",
            "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
            "-jar", transformerPath.absolutePathString(),
            "--clean", cleanPath.absolutePathString(),
            "--game-version", gameVersion,
            "--work-dir", workDir.absolutePathString(),
        )
        val sourceArgs = sources.flatMap { listOf("--source", it.absolutePathString()) }
        val classPathArgs = classPath.flatMap { listOf("--classpath", it.absolutePathString()) }

        val output = workDir / "output.json"
        var errors = false
        if (!output.exists()) {
            LOGGER.info("{} Testing {}", ICON_TEST, slug)
            val outputLog = workDir / "output.txt"
            val errorLog = workDir / "errors.txt"

            val process = ProcessBuilder(baseArgs + sourceArgs + classPathArgs)
                .directory(workDir.toFile())
                .redirectOutput(outputLog.toFile())
                .redirectError(errorLog.toFile())
                .start()
                .apply { waitFor(60, TimeUnit.MINUTES) }

            stripAnsiCodes(outputLog)
            stripAnsiCodes(errorLog)

            if (errorLog.readText().isNotEmpty()) {
                LOGGER.error("{} Got errors while transforming {}", ICON_WARN, slug)
                errors = true
            }

            if (process.exitValue() != 0) {
                if (cleanupOutput) {
                    workDir.deleteRecursively()
                }
                throw IllegalStateException("Failed to run transformations, see log for details")
            }
        }

        val parsed: TransformLibOutput = output.inputStream().use(Json::decodeFromStream)

        if (cleanupOutput) {
            workDir.deleteRecursively()
        }

        return TransformResult(parsed, errors)
    }

    private fun stripAnsiCodes(file: Path) {
        val ansiRegex = Regex("\u001B\\[[;\\d]*m")
        val cleaned = file.readText().replace(ansiRegex, "")
        file.writeText(cleaned)
    }
}