package org.sinytra.probe.model

import org.sinytra.probe.db.ModDAO
import org.sinytra.probe.db.ModTable
import org.sinytra.probe.db.daoToModel
import org.sinytra.probe.db.suspendTransaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere

interface ModRepository {
    suspend fun allMods(): List<Mod>
    suspend fun modByModid(modid: String): Mod?
    suspend fun addMod(mod: Mod)
    suspend fun removeMod(modid: String): Boolean
}

class PostgresModRepository : ModRepository {
    override suspend fun allMods(): List<Mod> = suspendTransaction {
        ModDAO.all().map(::daoToModel)
    }

    override suspend fun modByModid(modid: String): Mod? = suspendTransaction {
        ModDAO
            .find { (ModTable.modid eq modid) }
            .limit(1)
            .map(::daoToModel)
            .firstOrNull()
    }

    override suspend fun addMod(mod: Mod): Unit = suspendTransaction {
        ModDAO.new {
            modid = mod.modid
        }
    }

    override suspend fun removeMod(modid: String): Boolean = suspendTransaction {
        val rowsDeleted = ModTable.deleteWhere {
            ModTable.modid eq modid
        }
        rowsDeleted == 1
    }
}