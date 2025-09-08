package org.sinytra.probe.core.db

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.sinytra.probe.base.db.Project
import org.sinytra.probe.base.db.ProjectPlatform

object ProjectTable : LongIdTable("project") {
    val mod = reference("mod", ModTable)

    val platform = varchar("platform", 50)
    val projectId = varchar("project_id", 50)
}

class ProjectDAO(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<ProjectDAO>(ProjectTable)

    var platform by ProjectTable.platform
    var projectId by ProjectTable.projectId

    var mod by ModDAO referencedOn ProjectTable.mod
}

fun daoToModel(dao: ProjectDAO) = Project(
    ProjectPlatform.valueOf(dao.platform),
    dao.projectId,
    dao.mod.id.value,
    dao.mod.modid,
    dao.id.value,
)