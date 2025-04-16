package org.sinytra.probe

import kotlinx.serialization.Serializable
import org.sinytra.probe.game.ProbeTransformer
import org.sinytra.probe.model.ProjectPlatform
import org.sinytra.probe.service.*
import java.nio.file.Path

@Serializable
data class TransformationResult(
    val projectId: String,
    val dependencyProjectId: List<String>,
    val success: Boolean
)

class TransformationService(private val platforms: GlobalPlatformService, private val gameFiles: GameFiles) {

    suspend fun runTransformation(project: ResolvedProject, gameVersion: String): TransformationResult {
        val mainFile = project.version
        val otherFiles = project.flattenDependencies()

        val allFiles = (listOf(mainFile) + otherFiles).map(ProjectVersion::getFilePath)

        val classPath = gameFiles.loaderFiles.toMutableList() + resolveMandatedLibraries(gameVersion)

        val result = ProbeTransformer().transform(allFiles, project.version.getFilePath(), gameFiles.cleanFile, classPath, gameVersion)
        // TODO Save result

        return TransformationResult(project.version.projectId, otherFiles.map(ProjectVersion::projectId), result)
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
}