package org.sinytra.probe.db

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.sinytra.probe.model.Mod

object ModTable : IntIdTable("mod") {
    val modid = varchar("modid", 255).uniqueIndex()
}

class ModDAO(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<ModDAO>(ModTable)

    var modid by ModTable.modid

    val projects by ProjectDAO referrersOn ProjectTable.mod
}

fun daoToModel(dao: ModDAO) = Mod(
    dao.modid,
    dao.projects.map(::daoToModel)
)