package org.sinytra.probe.core.db

import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.sinytra.probe.core.model.Mod

object ModTable : LongIdTable("mod") {
    val modid = varchar("modid", 255).nullable()
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