package org.sinytra.probe.gatherer

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ScopeType
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val main = GathererMain()
    val commandLine = CommandLine(main)
    commandLine.parseArgs(*args)

    val exitCode = CommandLine(main).execute(*args)
    exitProcess(exitCode)
}

@Command(name = "probe", subcommands = [CommandLine.HelpCommand::class, RunTestsCommand::class, RunRegressionTestsCommand::class], mixinStandardHelpOptions = true)
class GathererMain {
    companion object {
        val LOGGER: Logger = LoggerFactory.getLogger(GathererMain::class.java)

        fun getVersion(): String? {
            return GathererMain::class.java.getPackage().implementationVersion ?: System.getProperty("org.sinytra.probe.version")
        }
    }

    @Option(names = ["--nfrt-version"], scope = ScopeType.INHERIT, defaultValue = "\${NFRT_VERSION}", description = ["Specifies NeoFormRuntime used"], required = true)
    var nfrtVersion: String? = null

    @Option(names = ["--neoforge-version"], scope = ScopeType.INHERIT, defaultValue = "\${NEOFORGE_VERSION}", description = ["Specifies NeoForge used"], required = true)
    var neoForgeVersion: String? = null

    @Option(names = ["--toolchain-version"], scope = ScopeType.INHERIT, defaultValue = "\${TOOLCHAIN_VERSION}", description = ["Specifies Transformer version used"], required = true)
    var toolchainVersion: String? = null

    @Option(names = ["--game-version"], scope = ScopeType.INHERIT, defaultValue = "\${GAME_VERSION}", description = ["Specifies game version used"], required = true)
    var gameVersion: String? = null

    @Option(names = ["--compatible-version"], arity = "*", scope = ScopeType.INHERIT, split = ",", defaultValue = "\${COMPATIBLE_VERSIONS}", description = ["Specifies compatible game versions for resolving mod dependencies"], required = true)
    var compatibleGameVersions: MutableList<String> = mutableListOf()

    @Option(names = ["--work-dir"], scope = ScopeType.INHERIT, defaultValue = "\${WORK_DIR}", description = ["Where temporary working directories are stored."], required = true)
    var workDir: Path? = null

    @Option(names = ["--tests"], scope = ScopeType.INHERIT, defaultValue = "\${TEST_COUNT:-1000}", description = ["Specifies number of tests performed"])
    var tests: Int? = null

    @Option(names = ["--download-jobs"], scope = ScopeType.INHERIT, defaultValue = "\${DOWNLOAD_JOBS:-20}", description = ["Max parallel downloads"])
    var concurrentDownloads: Int? = null

    @Option(names = ["--test-jobs"], scope = ScopeType.INHERIT, defaultValue = "\${TEST_JOBS:-10}", description = ["Max parallel tests"])
    var concurrentTests: Int? = null

    @Option(names = ["--write-report"], scope = ScopeType.INHERIT, defaultValue = "\${ENABLE_TEST_REPORT:-true}", description = ["Write markdown test report"])
    var writeReport: Boolean? = null
    
    @Option(names = ["--cleanup"], scope = ScopeType.INHERIT, defaultValue = "\${CLEANUP_OUTPUT:-false}", description = ["Clean up after testing"])
    var cleanup: Boolean? = null

    init {
        LOGGER.info("Running Probe Transformer version {}", getVersion() ?: "(unknown)")
    }

    fun setupParams(): TestRunnerParams {
        if (!workDir!!.exists()) {
            workDir!!.createDirectories()
        }

        return TestRunnerParams(
            nfrtVersion!!,
            neoForgeVersion!!,
            toolchainVersion!!,
            gameVersion!!,
            compatibleGameVersions,
            workDir!!,
            tests!!,
            cleanup!!,
            concurrentDownloads!!,
            concurrentTests!!,
            writeReport!!
        )
    }
}