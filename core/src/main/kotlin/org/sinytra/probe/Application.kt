package org.sinytra.probe

import io.ktor.server.application.*
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.sinytra.probe.db.ModTable
import org.sinytra.probe.model.PostgresModRepository
import org.sinytra.probe.model.PostgresProjectRepository

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    val modRepository = PostgresModRepository()
    val projectRepository = PostgresProjectRepository()

    configureSerialization(modRepository)
    configureDatabases()
    configureRouting()

    transaction {
        SchemaUtils.create(ModTable)
    }
}
