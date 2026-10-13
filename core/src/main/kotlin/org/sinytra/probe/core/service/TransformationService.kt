package org.sinytra.probe.core.service

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.sinytra.probe.base.TransformLibOutput
import org.sinytra.probe.base.db.ProjectPlatform
import org.sinytra.probe.core.platform.*
import org.slf4j.LoggerFactory
import java.lang.ProcessBuilder.Redirect
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.*

@Serializable
data class TransformationResult(
    val projectId: String,
    val version: String,
    val dependencyProjectId: List<String>,
    val success: Boolean,
    val modid: String?
)

class TransformationService(
    private val storagePath: Path,
    private val platforms: GlobalPlatformService,
    private val setup: SetupService
) {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(TransformationService::class.java)
        private const val MAX_RESOLVE_ATTEMPTS = 3
    }

    private class AcquiredProject(
        val project: ResolvedProject,
        val dependencies: List<ProjectResolvedVersion>,
        val sources: List<Path>,
        val libraries: List<Path>
    ) {
        val projectDir: Path = sources.first().parent
        val directories: Set<Path> = (sources + libraries).map { it.parent }.toSet()
    }

    private val fileTracker = ProjectFileTracker()

    suspend fun runTransformation(project: PlatformProject, gameVersion: String): TransformationResult? {
        val acquired = resolveAndAcquire(project, gameVersion) ?: return null
        val mainFile = acquired.project.version

        try {
            val gameFiles = setup.getGameFiles(gameVersion)
            val classPath = gameFiles.loaderFiles + acquired.libraries
            val workDir = createTempDirectory(acquired.projectDir, "output-$gameVersion-")

            val result = try {
                runTransformer(workDir, acquired.sources, gameFiles.cleanFile, classPath, gameVersion)
            } catch (e: Exception) {
                LOGGER.error("Error transforming project ${mainFile.projectId} version ${mainFile.versionId}", e)
                null
            }

            return TransformationResult(
                mainFile.projectId,
                mainFile.versionId,
                acquired.dependencies.map(ProjectResolvedVersion::projectId),
                result?.success ?: false,
                result?.primaryModid
            )
        } finally {
            fileTracker.release(acquired.directories, delete = setOf(acquired.projectDir))
        }
    }

    private suspend fun resolveAndAcquire(project: PlatformProject, gameVersion: String): AcquiredProject? {
        repeat(MAX_RESOLVE_ATTEMPTS) {
            val resolved = platforms.resolveProject(project, gameVersion) ?: return null
            val dependencies = resolved.flattenDependencies()
            val sources = (listOf(resolved.version) + dependencies).map { it.getFilePath(storagePath) }
            val acquired = AcquiredProject(resolved, dependencies, sources, resolveMandatedLibraries(gameVersion))

            fileTracker.acquire(acquired.directories)
            if ((acquired.sources + acquired.libraries).all { it.exists() }) {
                return acquired
            }
            fileTracker.release(acquired.directories)

            LOGGER.warn("Files for project {} were removed during resolution, retrying", project.id)
        }

        throw IllegalStateException("Failed to resolve files for project ${project.id} after $MAX_RESOLVE_ATTEMPTS attempts")
    }

    private suspend fun resolveMandatedLibraries(gameVersion: String): List<Path> {
        val ffapi = platforms.resolveProjectVersion(ProjectPlatform.MODRINTH, ModrinthPlatform.FFAPI_ID, gameVersion, ModrinthPlatform.LOADER_NEOFORGE)
            ?: throw RuntimeException("Unable to resolve required dep '${ModrinthPlatform.FFAPI_ID}'")

        return listOf(ffapi.getFilePath(storagePath))
    }

    private fun ResolvedProject.flattenDependencies(): List<ProjectResolvedVersion> =
        generateSequence(dependencies) { projects ->
            projects.flatMap { it.dependencies }.takeIf { it.isNotEmpty() }
        }
            .flatten()
            .distinctBy { it.version.projectId }
            .map { it.version }
            .toList()

    @OptIn(ExperimentalSerializationApi::class)
    private fun runTransformer(workDir: Path, sources: List<Path>, cleanPath: Path, classPath: List<Path>, gameVersion: String): TransformLibOutput {
        val transformer = setup.getTransformLib(gameVersion)

        val baseArgs = listOf(
            "java",
            "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
            "-jar", transformer.path.absolutePathString(),
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