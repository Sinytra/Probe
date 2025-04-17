package org.sinytra.probe.db

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.sinytra.probe.model.Project
import org.sinytra.probe.model.ProjectPlatform

object ProjectTable : IntIdTable("project") {
    val mod = reference("mod", ModTable)

    val platform = varchar("platform", 50)
    val projectId = varchar("project_id", 50)
}

class ProjectDAO(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ProjectDAO>(ProjectTable)

    var platform by ProjectTable.platform
    var projectId by ProjectTable.projectId

    var mod by ModDAO referencedOn ProjectTable.mod
}

fun daoToModel(dao: ProjectDAO) = Project(
    ProjectPlatform.valueOf(dao.platform),
    dao.projectId,
    dao.mod.modid,
    dao.id.value,
)