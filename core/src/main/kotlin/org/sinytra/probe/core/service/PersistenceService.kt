package org.sinytra.probe.core.service

import kotlinx.coroutines.coroutineScope
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
        return results.getTestResultFor(dbProject.internalModId, testEnvironment)
    }

    suspend fun saveResult(project: PlatformProject, modid: String?, versionId: String, passing: Boolean, testEnvironment: TestEnvironment): TestResult {
        val (dbProject, mod) = projects.projectByPlatformAndId(project.platform, project.id)
            ?.let { it to mods.modById(it.internalModId)!! }
            ?: coroutineScope {
                val mod = mods.addMod(Mod(id = 0, modid = modid, projects = listOf()))
                val saved = projects.addProject(Project(platform = project.platform, id = project.id, internalModId = mod.id))
                saved to mod
            }

        if (dbProject !in mod.projects) {
            projects.assignModToProject(dbProject, mod)
        }

        // Save result
        return results.addTestResult(BaseTestResult(dbProject, versionId, passing, testEnvironment))
    }
}