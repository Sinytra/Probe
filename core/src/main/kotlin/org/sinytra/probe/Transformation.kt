package org.sinytra.probe

import kotlinx.serialization.Serializable
import org.sinytra.probe.game.ProbeTransformer
import org.sinytra.probe.service.ModrinthService
import org.sinytra.probe.service.ResolvedVersion
import kotlin.io.path.Path

@Serializable
data class TransformationResult(
    val projectId: String,
    val versionId: String,
    val dependencyProjectId: List<String>,
    val success: Boolean
)

suspend fun runTransformation(projectId: String, gameVersion: String): TransformationResult? {
    val mrProject = ModrinthService.getProject(projectId) ?: return null
    val mrVersion = ModrinthService.getProjectVersion(mrProject.id, gameVersion, "fabric") ?: return null

    val resolved = ModrinthService.resolveVersion(mrVersion)
    val resolvedDeps = ModrinthService.resolveVersionDependencies(mrVersion, gameVersion, "fabric")
    val allFiles = (listOf(resolved) + resolvedDeps).map(ResolvedVersion::file)

    val cleanPath = Path(System.getProperty("org.sinytra.probe.clean.path")!!)
    val classPath = System.getProperty("org.sinytra.probe.transform.classpath")!!.split(";")
        .map(::Path)
        .toList()

    val result = ProbeTransformer().transform(allFiles, resolved.file, cleanPath, classPath, gameVersion)
    // TODO Save result

    return TransformationResult(mrProject.id, mrVersion.id, resolvedDeps.map(ResolvedVersion::projectId), result)
}