package org.sinytra.probe.model

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.sinytra.probe.db.*

interface ProjectRepository {
    suspend fun allProjects(): List<Project>
    suspend fun projectByPlatformAndId(platform: ProjectPlatform, id: String): Project?
    suspend fun addProject(project: Project)
    suspend fun removeProject(id: Int): Boolean
}

class PostgresProjectRepository : ProjectRepository {
    override suspend fun allProjects(): List<Project> = suspendTransaction {
        ProjectDAO.all().map(::daoToModel)
    }

    override suspend fun projectByPlatformAndId(platform: ProjectPlatform, id: String): Project? = suspendTransaction {
        ProjectDAO
            .find { (ProjectTable.platform eq platform.toString()) and
                    (ProjectTable.projectId eq id) }
            .limit(1)
            .map(::daoToModel)
            .firstOrNull()
    }

    override suspend fun addProject(project: Project): Unit = suspendTransaction {
        ProjectDAO.new {
            platform = project.platform.toString()
            projectId = project.id
        }
    }

    override suspend fun removeProject(id: Int): Boolean = suspendTransaction {
        val rowsDeleted = ProjectTable.deleteWhere {
            ProjectTable.id eq id
        }
        rowsDeleted == 1
    }
}