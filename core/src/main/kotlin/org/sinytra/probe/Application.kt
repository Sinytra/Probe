package org.sinytra.probe

import io.ktor.server.application.*
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.sinytra.probe.db.ModTable
import org.sinytra.probe.db.ProjectTable
import org.sinytra.probe.db.TestResultTable
import org.sinytra.probe.model.PostgresModRepository
import org.sinytra.probe.model.PostgresProjectRepository
import org.sinytra.probe.model.PostgresTestResultRepository
import org.sinytra.probe.model.ProjectPlatform
import org.sinytra.probe.service.GlobalPlatformService
import org.sinytra.probe.service.ModrinthService
import org.sinytra.probe.service.PersistenceService
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
    baseStoragePathInternal = environment.config.propertyOrNull("probe.storageBasePath")?.getString()?.let(::Path) ?: Path(".")
    val useLocalCache = environment.config.propertyOrNull("probe.useLocalCache")?.getString() == "true"
    val nfrtVersion = environment.config.property("probe.nfrtVersion").getString()
    val neoForgeVersion = environment.config.property("probe.neoForgeVersion").getString()
    val gameVersion = environment.config.property("probe.gameVersion").getString()
    val toolchainVersion = environment.config.property("probe.toolchainVersion").getString()

    val setupDir = getBaseStoragePath() / ".setup"
    setupDir.createDirectories()
    val setup = SetupService(setupDir, useLocalCache, nfrtVersion, neoForgeVersion)

    val gameFiles = setup.installLoader()

    val modRepository = PostgresModRepository()
    val projectRepository = PostgresProjectRepository()
    val testResultsRepository = PostgresTestResultRepository()

    val redis = connectToRedis()
    val modrinth = ModrinthService(redis)
    val platforms = GlobalPlatformService(mapOf(ProjectPlatform.MODRINTH to modrinth))

    val transformation = TransformationService(platforms, gameFiles)
    val persistence = PersistenceService(modRepository, projectRepository, testResultsRepository)

    configureSerialization()
    configureDatabases()
    configureRouting(platforms, transformation, gameVersion, toolchainVersion, persistence)

    transaction {
        SchemaUtils.create(ModTable)
        SchemaUtils.create(ProjectTable)
        SchemaUtils.create(TestResultTable)
    }
}
