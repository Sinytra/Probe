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
import org.sinytra.probe.service.SetupService
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

var baseStoragePathInternal: Path? = null
fun getBaseStoragePath(): Path = baseStoragePathInternal ?: throw IllegalStateException("Missing base storage path variable")

fun Application.module() {
    baseStoragePathInternal = environment.config.property("probe.storageBasePath").getString().let(::Path)

    val setupDir = getBaseStoragePath() / ".setup"
    setupDir.createDirectories()
    val setup = SetupService(setupDir)

    val gameFiles = setup.installLoader()

    val modRepository = PostgresModRepository()
    val projectRepository = PostgresProjectRepository()

    val redis = connectToRedis()
    val modrinth = ModrinthService(redis)
    val platforms = GlobalPlatformService(mapOf(ProjectPlatform.MODRINTH to modrinth))

    val transformation = TransformationService(platforms, gameFiles)

    configureSerialization()
    configureDatabases()
    configureRouting(platforms, transformation)

    transaction {
        SchemaUtils.create(ModTable)
    }
}
