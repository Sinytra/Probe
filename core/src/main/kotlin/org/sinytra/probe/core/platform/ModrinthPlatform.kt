package org.sinytra.probe.core.platform

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.sinytra.probe.base.ProjectSearchResult
import org.sinytra.probe.core.platform.ModrinthAPI.ModrinthVersionDependency
import org.sinytra.probe.core.platform.ModrinthAPI.ModrinthVersion
import org.sinytra.probe.core.platform.ModrinthAPI.ModrinthSearchResult
import org.sinytra.probe.core.platform.ModrinthAPI.ModrinthProject
import org.sinytra.probe.core.service.CacheService
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.*

private const val REQUIRED_DEP: String = "required"

@Serializable
data class ModrinthProjectVersion(
    override val projectId: String,
    override val versionId: String,
    override val versionNumber: String,
    override val dependencies: List<String>
) : ProjectVersion

@Serializable
data class ModrinthResolvedVersion(
    override val projectId: String,
    override val versionId: String,
    override val versionNumber: String,
    override val path: String,
    override val dependencies: List<String>
) : ProjectResolvedVersion

class ModrinthPlatform(
    private val storagePath: Path,
    private val cache: CacheService
) : PlatformService {
    companion object {
        const val FAPI_ID: String = "P7dR8mSH"
        const val FFAPI_ID: String = "Aqlf1Shp"
        const val FFAPI_SLUG: String = "forgified-fabric-api"
        const val LOADER_FABRIC = "fabric"
        const val LOADER_NEOFORGE = "neoforge"

        private val LOGGER = LoggerFactory.getLogger(ModrinthPlatform::class.java)
    }

    private fun projectSlugToIdKey(slug: String): String = "modrinth:slug:$slug"
    private fun projectKey(id: String): String = "modrinth:project:$id"
    private fun versionKey(id: String): String = "modrinth:version:$id"
    private fun neoForgeKey(id: String): String = "modrinth:project:$id:neoforge"
    private fun projectGameVersionKey(id: String, gameVersion: String): String = "modrinth:project:$id:game:$gameVersion"

    override suspend fun getProject(slug: String): PlatformProject? {
        // In case someone passes in an ID
        cache.getObject<ModrinthProject>(projectKey(slug))?.let { return it }

        val key = projectSlugToIdKey(slug)
        cache.get(key)
            ?.let { cache.getObject<ModrinthProject>(projectKey(it)) }
            ?.let { return it }

        val project = ModrinthAPI.getProject(slug) ?: return null

        cache.set(key, project.id)
        cache.setObject(projectKey(project.id), project)

        return project
    }

    override suspend fun getVersion(slug: String, versionId: String): ProjectVersion? {
        val version = getCachedMRVersion(slug, versionId) ?: return null
        val dependencies = version.dependencies
            .filter { it.dependencyType == REQUIRED_DEP && it.projectId != FAPI_ID }
            .map(ModrinthVersionDependency::projectId)
        val projectVersion = ModrinthProjectVersion(version.projectId, version.id, version.versionNumber, dependencies)

        return projectVersion
    }

    override suspend fun getResolvedVersion(slug: String, versionId: String): ProjectResolvedVersion? {
        val version = getCachedMRVersion(slug, versionId) ?: return null
        val resolved = downloadVersionFile(version)
        return resolved
    }

    suspend fun getCachedMRVersion(slug: String, versionId: String): ModrinthVersion? {
        val key = versionKey(versionId)
        val version = cache.getObject<ModrinthVersion>(key)
            ?: coroutineScope {
                LOGGER.info("Fetching '{}' version {}", slug, versionId)
                ModrinthAPI.getVersion(versionId)
            }
            ?: return null
        cache.setObject(key, version)
        return version
    }

    suspend fun hasVersion(versionId: String): Boolean {
        val key = versionKey(versionId)
        return cache.exists(key)
    }

    override suspend fun isNeoForgeAvailable(project: PlatformProject, gameVersion: String): Boolean {
        val key = neoForgeKey(project.id)

        val data = cache.get(key)?.toBoolean() ?: coroutineScope {
            val available = ModrinthAPI.getCandidateVersion(
                project.id,
                listOf(gameVersion),
                LOADER_NEOFORGE
            ) != null

            cache.set(key, available.toString())

            available
        }

        return data
    }

    override suspend fun resolveProject(project: PlatformProject, gameVersion: String): ResolvedProject? {
        project as ModrinthProject

        return resolveProjectInternal(project.id, gameVersion, listOf(gameVersion), false)
    }

    suspend fun resolveProject(project: PlatformProject, gameVersions: List<String>, fallbackLoader: Boolean): ResolvedProject? {
        project as ModrinthProject

        return resolveProjectInternal(project.id, gameVersions.first(), gameVersions, fallbackLoader)
    }

    override suspend fun resolveProjectVersion(slug: String, gameVersion: String, loader: String): ProjectResolvedVersion? =
        getOrComputeVersion(slug, gameVersion, listOf(gameVersion), loader, false)

    suspend fun searchProjects(limit: Int, offset: Int, gameVersion: String, loader: String, excludeLoader: String?): List<ProjectSearchResult>? =
        ModrinthAPI.searchProjects(limit, offset, gameVersion, loader, excludeLoader)
            ?.hits
            ?.onEach { cacheSearchResult(it) }
            ?.map { ProjectSearchResult(it.projectId, it.name, it.iconUrl, it.slug, it.versionId) }

    private suspend fun cacheSearchResult(result: ModrinthSearchResult) {
        val project = result.toMRProject()

        val slugKey = projectSlugToIdKey(project.slug)
        if (!cache.exists(slugKey)) {
            cache.set(slugKey, project.id)
        }

        val key = projectKey(project.id)
        if (!cache.exists(key)) {
            cache.setObject(key, project)
        }
    }

    private suspend fun downloadVersionFile(version: ModrinthVersion): ModrinthResolvedVersion {
        val file = version.files.first()
        val project = getProject(version.projectId)
            ?: throw IllegalStateException("Project ${version.projectId} not found")

        val basePath = storagePath
        val filePath = basePath / "${project.slug}-${project.id}/${project.slug}-${version.id}.jar"

        val relativePath = basePath.relativize(filePath).toString()
        val dependencies = version.dependencies
            .filter { it.dependencyType == REQUIRED_DEP && it.projectId != FAPI_ID }
            .map(ModrinthVersionDependency::projectId)

        if (filePath.exists() && filePath.isRegularFile()) {
            return ModrinthResolvedVersion(version.projectId, version.id, version.versionNumber, relativePath, dependencies)
        }

        filePath.deleteIfExists()
        filePath.createParentDirectories()

        ModrinthAPI.downloadFile(file.url, filePath)

        return ModrinthResolvedVersion(version.projectId, version.id, version.versionNumber, relativePath, dependencies)
    }

    private suspend fun resolveProjectInternal(project: String, gameVersion: String, allowedVersions: List<String>, fallbackLoader: Boolean): ResolvedProject? {
        val ver = getOrComputeVersion(project, gameVersion, allowedVersions, LOADER_FABRIC, fallbackLoader) ?: return null

        val deps: List<ResolvedProject> = channelFlow {
            ver.dependencies.forEach { dep ->
                launch {
                    send(
                        resolveProjectInternal(dep, gameVersion, allowedVersions, true)
                            ?: throw RuntimeException("Failed to resolve dependent project $dep")
                    )
                }
            }
        }.toList()

        return ResolvedProject(ver, deps)
    }

    private suspend fun getOrComputeVersion(projectId: String, gameVersion: String, allowedVersions: List<String>, loader: String, fallbackLoader: Boolean): ModrinthResolvedVersion? {
        if (projectId.length != 8) {
            throw IllegalArgumentException("Invalid Project ID: $projectId")
        }

        val key = projectGameVersionKey(projectId, gameVersion)

        return cache.getObject<ModrinthResolvedVersion>(key)
            ?.let {
                // Validate file
                val path = storagePath / it.path
                if (!path.exists()) {
                    cache.del(key)
                    return getOrComputeVersion(projectId, gameVersion, allowedVersions, loader, fallbackLoader)
                }
                it
            }
            ?: coroutineScope {
                LOGGER.info("Fetching version for project $projectId")

                val ver = ModrinthAPI.getCandidateVersion(projectId, allowedVersions, if (fallbackLoader) LOADER_NEOFORGE else loader)
                    ?: (if (fallbackLoader && loader != LOADER_NEOFORGE) ModrinthAPI.getCandidateVersion(projectId, allowedVersions, loader) else null)
                    ?: return@coroutineScope null

                val res = downloadVersionFile(ver)
                cache.setObject(key, res)
                cache.setObject(versionKey(ver.id), ver)
                res
            }
    }
}