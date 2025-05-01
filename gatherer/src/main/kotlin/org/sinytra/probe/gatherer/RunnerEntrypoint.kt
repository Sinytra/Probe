package org.sinytra.probe.gatherer

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Path
import java.util.concurrent.Callable
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

@Command(name = "run", subcommands = [CommandLine.HelpCommand::class], mixinStandardHelpOptions = true)
class GathererMain : Callable<Int> {
    companion object {
        val LOGGER: Logger = LoggerFactory.getLogger(GathererMain::class.java)

        private fun getVersion(): String {
            val ver = GathererMain::class.java.getPackage().implementationVersion ?: System.getProperty("org.sinytra.probe.version")
            return ver ?: "(unknown)"
        }
    }

    @Option(names = ["--nfrt-version"], scope = CommandLine.ScopeType.INHERIT, description = ["Specifies NeoFormRuntime used"], required = true)
    var nfrtVersion: String? = null

    @Option(names = ["--neoforge-version"], scope = CommandLine.ScopeType.INHERIT, description = ["Specifies NeoForge used"], required = true)
    var neoForgeVersion: String? = null

    @Option(names = ["--toolchain-version"], scope = CommandLine.ScopeType.INHERIT, description = ["Specifies Transformer version used"], required = true)
    var toolchainVersion: String? = null

    @Option(names = ["--game-version"], scope = CommandLine.ScopeType.INHERIT, description = ["Specifies game version used"], required = true)
    var gameVersion: String? = null

    @Option(names = ["--compatible-version"], arity = "*", scope = CommandLine.ScopeType.INHERIT, description = ["Specifies compatible game versions for resolving mod dependencies"], required = true)
    var compatibleGameVersions: MutableList<String> = mutableListOf()

    @Option(names = ["--work-dir"], scope = CommandLine.ScopeType.INHERIT, description = ["Where temporary working directories are stored."], required = true)
    var workDir: Path? = null

    @Option(names = ["--tests"], scope = CommandLine.ScopeType.INHERIT, defaultValue = "\${TEST_COUNT:-1000}", description = ["Specifies number of tests performed"])
    var tests: Int? = null

    init {
        LOGGER.info("Running Probe Transformer version {}", getVersion())
    }

    override fun call(): Int {
        if (!workDir!!.exists()) {
            workDir!!.createDirectories()
        }
        val params = GathererParams(
            nfrtVersion!!,
            neoForgeVersion!!,
            toolchainVersion!!,
            gameVersion!!,
            compatibleGameVersions,
            workDir!!,
            tests!!
        )
        runGatherer(params)
        return 0
    }
}