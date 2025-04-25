package org.sinytra.probe

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.sinytra.probe.model.ProjectPlatform
import org.sinytra.probe.service.*
import org.slf4j.LoggerFactory
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.inputStream

@Serializable
data class TransformationResult(
    val projectId: String,
    val dependencyProjectId: List<String>,
    val success: Boolean,
    val modid: String
)

@Serializable
private data class TransformLibOutput(
    val success: Boolean,
    val primaryModid: String
) 

class TransformationService(private val platforms: GlobalPlatformService, private val gameFiles: GameFiles, private val setup: SetupService) {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(TransformationService::class.java)
    }

    suspend fun runTransformation(project: ResolvedProject, gameVersion: String): TransformationResult {
        val mainFile = project.version
        val otherFiles = project.flattenDependencies()

        val allFiles = (listOf(mainFile) + otherFiles).map(ProjectVersion::getFilePath)

        val classPath = gameFiles.loaderFiles.toMutableList() + resolveMandatedLibraries(gameVersion)
        val workDir = project.version.getFilePath().parent / "output"
        workDir.createDirectories()

        val result = runTransformer(workDir, allFiles, gameFiles.cleanFile, classPath, gameVersion)

        return TransformationResult(
            project.version.projectId,
            otherFiles.map(ProjectVersion::projectId),
            result.success,
            result.primaryModid
        )
    }

    private suspend fun resolveMandatedLibraries(gameVersion: String): List<Path> {
        val ffapi = platforms.resolveProjectVersion(ProjectPlatform.MODRINTH, MR_FFAPI_ID, gameVersion, LOADER_NEOFORGE)
            ?: throw RuntimeException("Unable to resolve required dep '$MR_FFAPI_ID'")

        return listOf(ffapi.getFilePath())
    }

    private fun ResolvedProject.flattenDependencies(): List<ProjectVersion> =
        generateSequence(dependencies) { projects ->
            projects.flatMap { it.dependencies }.takeIf { it.isNotEmpty() }
        }
            .flatten()
            .distinctBy { it.version.projectId }
            .map { it.version }
            .toList()

    @OptIn(ExperimentalSerializationApi::class)
    private fun runTransformer(workDir: Path, sources: List<Path>, cleanPath: Path, classPath: List<Path>, gameVersion: String): TransformLibOutput {
        val transformerPath = setup.getTransformLibPath()

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

        LOGGER.info("Launching Probe Transformer")
        val process = ProcessBuilder(baseArgs + sourceArgs + classPathArgs)
            .directory(workDir.toFile())
            .redirectOutput(Redirect.INHERIT)
            .redirectError(Redirect.INHERIT)
            .start()
            .apply { waitFor(60, TimeUnit.MINUTES) }
        LOGGER.info("Finished Probe Transformer")

        if (process.exitValue() != 0) {
            throw IllegalStateException("Failed to run transformations, see log for details")
        }

        val parsed: TransformLibOutput = output.inputStream().use(Json::decodeFromStream)

        return parsed
    }
}