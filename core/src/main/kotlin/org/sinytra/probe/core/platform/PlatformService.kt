package org.sinytra.probe.core.platform

import org.sinytra.probe.base.db.ProjectPlatform
import java.nio.file.Path
import kotlin.io.path.div

interface PlatformProject {
    val id: String
    val slug: String
    val name: String
    val summary: String
    val iconUrl: String?
    val url: String
    val platform: ProjectPlatform
}

interface ProjectVersion {
    val projectId: String // Uniquely distinguishable ID
    val versionId: String
    val versionNumber: String
    val dependencies: List<String>
}

interface ProjectResolvedVersion {
    val projectId: String // Uniquely distinguishable ID
    val versionId: String
    val versionNumber: String
    val path: String
    val dependencies: List<String>
}

data class ResolvedProject(
    val version: ProjectResolvedVersion,
    val dependencies: List<ResolvedProject>
)

interface PlatformService {
    suspend fun getProject(slug: String): PlatformProject?

    // Does not guarantee that the version file exists
    suspend fun getVersion(slug: String, versionId: String): ProjectVersion?
    suspend fun getResolvedVersion(slug: String, versionId: String): ProjectResolvedVersion?
    suspend fun isNeoForgeAvailable(project: PlatformProject, gameVersion: String): Boolean
    suspend fun resolveProject(project: PlatformProject, gameVersion: String): ResolvedProject?
    suspend fun resolveProjectVersion(slug: String, gameVersion: String, loader: String): ProjectResolvedVersion?
}

class GlobalPlatformService(private val platforms: Map<ProjectPlatform, PlatformService>) {
    private fun getPlatform(plat: ProjectPlatform): PlatformService {
        return platforms[plat] ?: throw IllegalArgumentException("No service for platform ${plat.name}")
    }

    suspend fun getProject(platform: ProjectPlatform, slug: String): PlatformProject? =
        getPlatform(platform).getProject(slug)

    suspend fun isNeoForgeAvailable(project: PlatformProject, gameVersion: String): Boolean =
        getPlatform(project.platform).isNeoForgeAvailable(project, gameVersion)

    suspend fun getVersion(project: PlatformProject, versionId: String): ProjectVersion? =
        getPlatform(project.platform).getVersion(project.id, versionId)

    suspend fun getResolvedVersion(project: PlatformProject, versionId: String): ProjectResolvedVersion? =
        getPlatform(project.platform).getResolvedVersion(project.id, versionId)

    suspend fun resolveProject(project: PlatformProject, gameVersion: String): ResolvedProject? =
        getPlatform(project.platform).resolveProject(project, gameVersion)

    suspend fun resolveProjectVersion(platform: ProjectPlatform, id: String, gameVersion: String, loader: String): ProjectResolvedVersion? =
        getPlatform(platform).resolveProjectVersion(id, gameVersion, loader)
}

fun ProjectResolvedVersion.getFilePath(basePath: Path): Path {
    return basePath / path
}