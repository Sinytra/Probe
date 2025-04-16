package org.sinytra.probe.service

import org.sinytra.probe.getBaseStoragePath
import org.sinytra.probe.model.ProjectPlatform
import java.nio.file.Path
import kotlin.io.path.div

interface PlatformProject {
    val name: String
    val platform: ProjectPlatform
}

interface ProjectVersion {
    val projectId: String // Uniquely distinguishable ID
    val path: String
}

data class ResolvedProject(
    val version: ProjectVersion,
    val dependencies: List<ResolvedProject>
)

interface PlatformService {
    suspend fun getProject(slug: String): PlatformProject?
    suspend fun resolveProject(project: PlatformProject, gameVersion: String): ResolvedProject?
    suspend fun resolveProjectVersion(slug: String, gameVersion: String, loader: String): ProjectVersion?
}

class GlobalPlatformService(private val platforms: Map<ProjectPlatform, PlatformService>) {
    private fun getPlatform(plat: ProjectPlatform): PlatformService {
        return platforms[plat] ?: throw IllegalArgumentException("No service for platform ${plat.name}")
    }

    suspend fun getProject(platform: ProjectPlatform, slug: String): PlatformProject? =
        getPlatform(platform).getProject(slug)

    suspend fun resolveProject(project: PlatformProject, gameVersion: String): ResolvedProject? =
        getPlatform(project.platform).resolveProject(project, gameVersion)

    suspend fun resolveProjectVersion(platform: ProjectPlatform, id: String, gameVersion: String, loader: String): ProjectVersion? =
        getPlatform(platform).resolveProjectVersion(id, gameVersion, loader)
}

fun ProjectVersion.getFilePath(): Path {
    val basePath = getBaseStoragePath()
    return basePath / path
}