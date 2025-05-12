package org.sinytra.probe.gatherer

import picocli.CommandLine.Command
import picocli.CommandLine.ParentCommand
import java.util.concurrent.Callable

@Command(name = "run", description = ["Run probe mod tests"])
class RunTestsCommand : Callable<Int> {
    @ParentCommand
    var commonOptions: GathererMain? = null

    override fun call(): Int {
        val params = commonOptions!!.setupParams()
        val gatherer = setupGatherer(params)
        gatherer.run()
        return 0
    }
}