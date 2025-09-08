package org.sinytra.probe.core.db

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.sinytra.probe.core.model.Mod

object ModTable : LongIdTable("mod") {
    val modid = varchar("modid", 255).nullable().uniqueIndex()
}

class ModDAO(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<ModDAO>(ModTable)

    var modid by ModTable.modid

    val projects by ProjectDAO referrersOn ProjectTable.mod
}

fun daoToModel(dao: ModDAO) = Mod(
    dao.id.value,
    dao.modid,
    dao.projects.map(::daoToModel)
)