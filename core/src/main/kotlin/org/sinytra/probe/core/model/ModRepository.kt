package org.sinytra.probe.core.model

import org.sinytra.probe.core.db.ModDAO
import org.sinytra.probe.core.db.daoToModel
import org.sinytra.probe.core.db.suspendTransaction

interface ModRepository {
    suspend fun allMods(): List<Mod>
    suspend fun modById(id: Long): Mod?
    suspend fun addMod(mod: Mod): Mod
}

class PostgresModRepository : ModRepository {
    override suspend fun allMods(): List<Mod> = suspendTransaction {
        ModDAO.all().map(::daoToModel)
    }
    
    override suspend fun modById(id: Long): Mod? = suspendTransaction {
        ModDAO.findById(id)?.let(::daoToModel)
    }

    override suspend fun addMod(mod: Mod): Mod = suspendTransaction {
        ModDAO.new {
            modid = mod.modid
        }.let(::daoToModel)
    }
}