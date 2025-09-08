package org.sinytra.probe.gatherer

import io.lettuce.core.RedisClient
import org.sinytra.probe.core.service.CleanupService
import org.sinytra.probe.core.platform.ModrinthPlatform
import org.sinytra.probe.core.platform.PlatformCache
import org.sinytra.probe.core.service.SetupService
import org.sinytra.probe.gatherer.internal.ModTestRunner
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div

private val LOGGER: Logger = LoggerFactory.getLogger("TestRunnerSetup")

data class TestRunnerParams(
    val nfrtVersion: String,
    val neoForgeVersion: String,
    val toolchainVersion: String,
    val gameVersion: String,
    val compatibleGameVersions: List<String>,
    val workDir: Path,
    val tests: Int,
    val cleanupOutput: Boolean,
    val concurrentDownloads: Int,
    val concurrentTests: Int,
    val writeReport: Boolean
)

fun createTestRunner(params: TestRunnerParams): ModTestRunner {
    val workDir = params.workDir
    val setupService = SetupService(
        (workDir / ".setup").also { it.createDirectories() },
        true,
        params.nfrtVersion,
        params.neoForgeVersion,
        params.toolchainVersion
    )

    val url = System.getenv("REDIS_URL") ?: throw RuntimeException("Missing REDIS_URL")
    LOGGER.trace("Connecting to redis instance")

    val redisClient = RedisClient.create(url)
    val redisConnection = redisClient.connect()
    val cache = PlatformCache(redisConnection)

    val modrinthService = ModrinthPlatform(workDir / "mods", cache)
    val cleanupService = CleanupService(workDir)

    val gatherer = ModTestRunner(
        workDir,
        setupService,
        modrinthService,
        cleanupService,
        params
    )

    setupService.getTransformLib()
    setupService.installDependencies()

    return gatherer
}