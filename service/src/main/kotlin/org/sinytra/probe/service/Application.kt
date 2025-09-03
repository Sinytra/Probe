package org.sinytra.probe.service

import io.ktor.server.application.*
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.sinytra.probe.core.db.ModTable
import org.sinytra.probe.core.db.ProjectTable
import org.sinytra.probe.core.db.TestResultTable
import org.sinytra.probe.core.model.PostgresModRepository
import org.sinytra.probe.core.model.PostgresProjectRepository
import org.sinytra.probe.core.model.PostgresTestResultRepository
import org.sinytra.probe.core.model.ProjectPlatform
import org.sinytra.probe.core.platform.GlobalPlatformService
import org.sinytra.probe.core.platform.ModrinthPlatform
import org.sinytra.probe.core.platform.PlatformCache
import org.sinytra.probe.core.service.*
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div

fun main(args: Array<String>) {
    val configPath = System.getenv("org.sinytra.probe.config_path")
    val appArgs = if (configPath != null) args + arrayOf("-config=$configPath") else args
    io.ktor.server.netty.EngineMain.main(appArgs)
}

fun Application.module() {
    val baseStoragePath = environment.config.propertyOrNull("probe.storageBasePath")?.getString()?.let(::Path) ?: Path(".")
    val useLocalCache = environment.config.propertyOrNull("probe.useLocalCache")?.getString() == "true"
    val nfrtVersion = environment.config.property("probe.nfrtVersion").getString()
    val neoForgeVersion = environment.config.property("probe.neoForgeVersion").getString()
    val gameVersion = environment.config.property("probe.gameVersion").getString()
    val transformerVersion = environment.config.property("probe.transformerVersion").getString()

    val setupDir = baseStoragePath / ".setup"
    setupDir.createDirectories()
    val setup = SetupService(setupDir, useLocalCache, nfrtVersion, neoForgeVersion, transformerVersion)

    val gameFiles = setup.installDependencies()

    val modRepository = PostgresModRepository()
    val projectRepository = PostgresProjectRepository()
    val testResultsRepository = PostgresTestResultRepository()

    val redis = connectToRedis()
    val cache = PlatformCache(redis)
    val modrinth = ModrinthPlatform(baseStoragePath, cache)
    val platforms = GlobalPlatformService(mapOf(ProjectPlatform.MODRINTH to modrinth))

    val transformation = TransformationService(baseStoragePath, platforms, gameFiles, setup)
    val persistence = PersistenceService(modRepository, projectRepository, testResultsRepository)

    configureSerialization()
    configureDatabases()
    configureRouting(platforms, transformation, gameVersion, transformerVersion, persistence)

    transaction {
        SchemaUtils.create(ModTable)
        SchemaUtils.create(ProjectTable)
        SchemaUtils.create(TestResultTable)
    }
}
