package org.sinytra.probe.core.model

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.update
import org.sinytra.probe.core.db.ModDAO
import org.sinytra.probe.core.db.ModTable
import org.sinytra.probe.core.db.ProjectDAO
import org.sinytra.probe.core.db.ProjectTable
import org.sinytra.probe.core.db.daoToModel
import org.sinytra.probe.core.db.suspendTransaction

interface ProjectRepository {
    suspend fun allProjects(): List<Project>
    suspend fun projectByPlatformAndId(platform: ProjectPlatform, id: String): Project?
    suspend fun addProject(project: Project): Project
    suspend fun removeProject(id: Int): Boolean
    suspend fun assignModToProject(project: Project, mod: Mod)
}

class PostgresProjectRepository : ProjectRepository {
    override suspend fun allProjects(): List<Project> = suspendTransaction {
        ProjectDAO.Companion.all().map(::daoToModel)
    }

    override suspend fun projectByPlatformAndId(platform: ProjectPlatform, id: String): Project? = suspendTransaction {
        ProjectDAO.Companion
            .find {
                (ProjectTable.platform eq platform.toString()) and
                        (ProjectTable.projectId eq id)
            }
            .limit(1)
            .map(::daoToModel)
            .firstOrNull()
    }

    override suspend fun addProject(project: Project): Project = suspendTransaction {
        val dbMod = ModDAO.Companion.find { ModTable.modid eq project.modid }.single()
        ProjectDAO.Companion.new {
            platform = project.platform.toString()
            projectId = project.id
            mod = dbMod
        }.let(::daoToModel)
    }

    override suspend fun removeProject(id: Int): Boolean = suspendTransaction {
        val rowsDeleted = ProjectTable.deleteWhere {
            ProjectTable.id eq id
        }
        rowsDeleted == 1
    }

    override suspend fun assignModToProject(project: Project, modObj: Mod): Unit = suspendTransaction {
        val dbMod = ModDAO.Companion.find { ModTable.modid eq modObj.modid }.single()
        ProjectTable.update({ ProjectTable.id eq project.internalId }) {
            it[mod] = dbMod.id
        }
    }
}