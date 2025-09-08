package org.sinytra.probe.core.model

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.sinytra.probe.core.db.ModDAO
import org.sinytra.probe.core.db.ModTable
import org.sinytra.probe.core.db.daoToModel
import org.sinytra.probe.core.db.suspendTransaction

interface ModRepository {
    suspend fun allMods(): List<Mod>
    suspend fun modById(id: Long): Mod?
    suspend fun modByModid(modid: String): Mod?
    suspend fun addMod(mod: Mod): Mod
    suspend fun removeMod(modid: String): Boolean
}

class PostgresModRepository : ModRepository {
    override suspend fun allMods(): List<Mod> = suspendTransaction {
        ModDAO.all().map(::daoToModel)
    }
    
    override suspend fun modById(id: Long): Mod? = suspendTransaction {
        ModDAO.findById(id)?.let(::daoToModel)
    }

    override suspend fun modByModid(modid: String): Mod? = suspendTransaction {
        ModDAO
            .find { (ModTable.modid eq modid) }
            .limit(1)
            .map(::daoToModel)
            .firstOrNull()
    }

    override suspend fun addMod(mod: Mod): Mod = suspendTransaction {
        ModDAO.new {
            modid = mod.modid
        }.let(::daoToModel)
    }

    override suspend fun removeMod(modid: String): Boolean = suspendTransaction {
        val rowsDeleted = ModTable.deleteWhere {
            ModTable.modid eq modid
        }
        rowsDeleted == 1
    }
}