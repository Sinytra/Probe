package org.sinytra.probe.service

import org.sinytra.probe.getBaseStoragePath
import org.sinytra.probe.model.ProjectPlatform
import java.nio.file.Path
import kotlin.io.path.div

interface PlatformProject {
    val id: String
    val slug: String
    val name: String
    val iconUrl: String?
    val url: String
    val platform: ProjectPlatform
}

interface ProjectVersion {
    val projectId: String // Uniquely distinguishable ID
    val versionId: String
    val versionNumber: String
    val path: String
    val dependencies: List<String>
}

data class ResolvedProject(
    val version: ProjectVersion,
    val dependencies: List<ResolvedProject>
)

interface PlatformService {
    suspend fun getProject(slug: String): PlatformProject?
    suspend fun getVersion(slug: String, versionId: String): ProjectVersion?
    suspend fun isNeoForgeAvailable(project: PlatformProject, gameVersion: String): Boolean
    suspend fun resolveProject(project: PlatformProject, gameVersion: String): ResolvedProject?
    suspend fun resolveProjectVersion(slug: String, gameVersion: String, loader: String): ProjectVersion?
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

    suspend fun resolveProject(project: PlatformProject, gameVersion: String): ResolvedProject? =
        getPlatform(project.platform).resolveProject(project, gameVersion)

    suspend fun resolveProjectVersion(platform: ProjectPlatform, id: String, gameVersion: String, loader: String): ProjectVersion? =
        getPlatform(platform).resolveProjectVersion(id, gameVersion, loader)
}

fun ProjectVersion.getFilePath(): Path {
    val basePath = getBaseStoragePath()
    return basePath / path
}