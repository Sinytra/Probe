package org.sinytra.probe.core.service

import org.sinytra.probe.base.db.Project
import org.sinytra.probe.core.model.*
import org.sinytra.probe.core.platform.PlatformProject

class PersistenceService(
    private val mods: ModRepository,
    private val projects: ProjectRepository,
    private val results: TestResultRepository,
    private val testEnvironments: TestEnvironmentRepository
) {

    suspend fun getOrCreateTestEnvironment(connectorVersion: String, gameVersion: String, neoForgeVersion: String): TestEnvironment {
        testEnvironments.getTestEnvironment(connectorVersion, gameVersion, neoForgeVersion)?.let { return it }

        return testEnvironments.addTestEnvironment(TestEnvironmentStub(connectorVersion, gameVersion, neoForgeVersion))
    }

    suspend fun getExistingResult(project: PlatformProject, testEnvironment: TestEnvironment): TestResult? {
        val dbProject = projects.projectByPlatformAndId(project.platform, project.id) ?: return null
        return results.getTestResultFor(dbProject.modid, testEnvironment)
    }

    suspend fun saveResult(project: PlatformProject, result: TransformationResult, testEnvironment: TestEnvironment): TestResult {
        val mod = mods.modByModid(result.modid)
            ?: mods.addMod(Mod(modid = result.modid, projects = listOf()))

        val dbProject = projects.projectByPlatformAndId(project.platform, project.id)
            ?: projects.addProject(Project(platform = project.platform, id = project.id, modid = mod.modid))

        if (dbProject !in mod.projects) {
            projects.assignModToProject(dbProject, mod)
        }

        // Save result
        return results.addTestResult(BaseTestResult(dbProject, result.version, result.success, testEnvironment))
    }
}