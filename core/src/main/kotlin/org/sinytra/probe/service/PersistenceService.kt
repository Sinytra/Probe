package org.sinytra.probe.service

import org.sinytra.probe.TransformationResult
import org.sinytra.probe.model.*

class PersistenceService(private val mods: ModRepository, private val projects: ProjectRepository, private val results: TestResultRepository) {

    suspend fun getExistingResult(project: PlatformProject, gameVersion: String, toolchainVersion: String): TestResult? {
        val dbProject = projects.projectByPlatformAndId(project.platform, project.id) ?: return null
        return results.getTestResultFor(dbProject.modid, gameVersion, toolchainVersion)
    }

    suspend fun saveResult(project: PlatformProject, result: TransformationResult, gameVersion: String, toolchainVersion: String): TestResult {
        val mod = mods.modByModid(result.modid)
            ?: mods.addMod(Mod(modid = result.modid, projects = listOf()))

        val dbProject = projects.projectByPlatformAndId(project.platform, project.id)
            ?: projects.addProject(Project(platform = project.platform, id = project.id, modid = mod.modid))

        if (dbProject !in mod.projects) {
            projects.assignModToProject(dbProject, mod)
        }

        // Save result
        return results.addTestResult(BaseTestResult(dbProject, result.version, gameVersion, toolchainVersion, result.success))
    }
}