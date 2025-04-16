package org.sinytra.probe

import io.ktor.server.application.*
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.sinytra.probe.db.ModTable
import org.sinytra.probe.model.PostgresModRepository
import org.sinytra.probe.model.PostgresProjectRepository
import org.sinytra.probe.model.ProjectPlatform
import org.sinytra.probe.service.GlobalPlatformService
import org.sinytra.probe.service.ModrinthService
import java.nio.file.Path
import kotlin.io.path.Path

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

var baseStoragePathInternal: Path? = null
fun getBaseStoragePath(): Path = baseStoragePathInternal ?: throw IllegalStateException("Missing base storage path variable")

fun Application.module() {
    baseStoragePathInternal = environment.config.property("probe.storageBasePath").getString().let(::Path)

    val modRepository = PostgresModRepository()
    val projectRepository = PostgresProjectRepository()

    val redis = connectToRedis()
    val modrinth = ModrinthService(redis)
    val platforms = GlobalPlatformService(mapOf(ProjectPlatform.MODRINTH to modrinth))

    configureSerialization()
    configureDatabases()
    configureRouting(platforms)

    transaction {
        SchemaUtils.create(ModTable)
    }
}
