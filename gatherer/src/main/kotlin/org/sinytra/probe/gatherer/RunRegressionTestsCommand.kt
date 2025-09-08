package org.sinytra.probe.gatherer

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.sinytra.probe.base.TestReport
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import picocli.CommandLine.*
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.io.path.exists
import kotlin.io.path.inputStream

@OptIn(ExperimentalSerializationApi::class)
@Command(name = "regressions", description = ["Run probe mod regression tests"])
class RunRegressionTestsCommand : Callable<Int> {
    companion object {
        val LOGGER: Logger = LoggerFactory.getLogger("RegressionTests")
    }

    @ParentCommand
    var commonOptions: GathererMain? = null

    @Option(names = ["--results"], scope = ScopeType.INHERIT, description = ["Previous test results"], required = true)
    var results: Path? = null

    @Option(names = ["--only-failed"], scope = ScopeType.INHERIT, defaultValue = "\${RUN_ONLY_FAILED:-true}", description = ["Only test incompatible mods"])
    var onlyFailed: Boolean? = null

    override fun call(): Int {
        if (!results!!.exists())
            throw IllegalArgumentException("Missing old results file")

        val oldResults: TestReport = results!!.inputStream().use(Json::decodeFromStream)
        if (oldResults.results.isEmpty()) {
            LOGGER.error("Results file is empty, skipping tests")
            return 0
        }

        val candidates = oldResults.results
            .filter { it.result != null }
            .let { if (onlyFailed!!) it.filter { t -> !t.result!!.output.success } else oldResults.results }
            .map { it.project }

        val params = commonOptions!!.setupParams()
        val gatherer = createTestRunner(params)

        LOGGER.info("Running regression tests on {} mods", candidates.size)

        gatherer.run(candidates)

        return 0
    }
}