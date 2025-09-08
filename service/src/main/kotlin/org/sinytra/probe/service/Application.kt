package org.sinytra.probe.service

import io.ktor.server.application.*
import io.ktor.server.auth.*
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.sinytra.probe.base.db.ProjectPlatform
import org.sinytra.probe.core.db.ModTable
import org.sinytra.probe.core.db.ProjectTable
import org.sinytra.probe.core.db.TestEnvironmentTable
import org.sinytra.probe.core.db.TestResultTable
import org.sinytra.probe.core.model.PostgresModRepository
import org.sinytra.probe.core.model.PostgresProjectRepository
import org.sinytra.probe.core.model.PostgresTestEnvironmentRepository
import org.sinytra.probe.core.model.PostgresTestResultRepository
import org.sinytra.probe.core.platform.GlobalPlatformService
import org.sinytra.probe.core.platform.ModrinthPlatform
import org.sinytra.probe.core.platform.PlatformCache
import org.sinytra.probe.core.service.PersistenceService
import org.sinytra.probe.core.service.SetupService
import org.sinytra.probe.core.service.TransformationService
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div

fun main(args: Array<String>) {
    val configPath = System.getenv("org.sinytra.probe.config_path")
    val appArgs = if (configPath != null) args + arrayOf("-config=$configPath") else args
    io.ktor.server.netty.EngineMain.main(appArgs)
}

fun Application.module() {
    val apiKey = environment.config.propertyOrNull("probe.apiKey")?.getString()
    val baseStoragePath = environment.config.propertyOrNull("probe.storageBasePath")?.getString()?.let(::Path) ?: Path(".")
    val useLocalCache = environment.config.propertyOrNull("probe.useLocalCache")?.getString() == "true"
    val nfrtVersion = environment.config.property("probe.nfrtVersion").getString()
    val neoForgeVersion = environment.config.property("probe.neoForgeVersion").getString()
    val gameVersion = environment.config.property("probe.gameVersion").getString()
    val initialTransformerVersion = environment.config.propertyOrNull("probe.transformerVersion")?.getString()

    val setupDir = baseStoragePath / ".setup"
    setupDir.createDirectories()
    val setup = SetupService(setupDir, useLocalCache, nfrtVersion, neoForgeVersion, initialTransformerVersion)

    val gameFiles = setup.installDependencies()

    val testEnvironmentRepository = PostgresTestEnvironmentRepository()
    val modRepository = PostgresModRepository()
    val projectRepository = PostgresProjectRepository()
    val testResultsRepository = PostgresTestResultRepository()

    val redis = connectToRedis()
    val cache = PlatformCache(redis)
    val modrinth = ModrinthPlatform(baseStoragePath, cache)
    val platforms = GlobalPlatformService(mapOf(ProjectPlatform.MODRINTH to modrinth))

    val transformation = TransformationService(baseStoragePath, platforms, gameFiles, setup)
    val persistence = PersistenceService(modRepository, projectRepository, testResultsRepository, testEnvironmentRepository)
    val envConfig = EnvironmentConfig(gameVersion, neoForgeVersion)

    install(Authentication) {
        bearer("api-key") {
            realm = "Access to the '/api/v1/internal' path"
            authenticate { credential ->
                if (apiKey == null || credential.token == apiKey) {
                    UserIdPrincipal("anonymous")
                } else {
                    null
                }
            }
        }
    }

    configureSerialization()
    configureDatabases()
    configureRouting(setup, platforms, transformation, persistence, envConfig)

    transaction {
        SchemaUtils.create(TestEnvironmentTable)
        SchemaUtils.create(ModTable)
        SchemaUtils.create(ProjectTable)
        SchemaUtils.create(TestResultTable)
    }
}
