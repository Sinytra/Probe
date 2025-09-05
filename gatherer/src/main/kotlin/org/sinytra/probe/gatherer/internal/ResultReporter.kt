package org.sinytra.probe.gatherer.internal

import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import net.steppschuh.markdowngenerator.table.Table
import net.steppschuh.markdowngenerator.text.TextBuilder
import net.steppschuh.markdowngenerator.text.code.Code
import net.steppschuh.markdowngenerator.text.emphasis.BoldText
import org.sinytra.probe.core.service.SetupService
import org.sinytra.probe.gatherer.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.FileSystems
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.Attributes
import java.util.jar.Manifest
import kotlin.io.path.*
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

object ResultReporter {
    private val LOGGER: Logger = LoggerFactory.getLogger("ResultReporter")

    fun processResults(results: List<SerializableTransformResult>, duration: Duration, resultDir: Path, writeReport: Boolean, setup: SetupService, params: TestRunnerParams) {
        val compatible = results.count { it.result?.output?.success == true }
        val incompatible = results.count { it.result?.output?.success == false }
        val errored = results.count { it.result?.errors == true }
        val failed = results.count { it.result == null }

        LOGGER.info("==== Test results summary ====")
        LOGGER.info("{} Compatible:\t\t\t{}", ICON_CHECK, compatible)
        LOGGER.info("{} Incompatible:\t\t\t{}", ICON_X, incompatible)
        LOGGER.info("{} Errored:\t\t\t\t{}", ICON_WARN, errored)
        LOGGER.info("{} Failed:\t\t\t\t{}", ICON_EXCLAMATION, failed)
        LOGGER.info("==============================")

        val resultsFile = resultDir / "results.json"

        val toolchainInfo = TestToolchain(
            getImplementationVersion(setup.getTransformLibPath()),
            getSHA256(setup.getTransformLibPath())
        )
        val probeInfo = TestToolchain(
            GathererMain.getVersion(),
            getSHA256(Path.of(javaClass.protectionDomain.codeSource.location.toURI()))
        )

        val report = TestReport(results, toolchainInfo, probeInfo, duration.inWholeSeconds, Clock.System.now())
        resultsFile.writeText(Json.encodeToString(report))

        if (writeReport) {
            val reportFile = resultDir / "report.md"

            val durationStr = if (duration < 1.toDuration(DurationUnit.SECONDS))
                duration.toString(DurationUnit.MILLISECONDS)
            else if (duration < 1.toDuration(DurationUnit.MINUTES))
                duration.toString(DurationUnit.SECONDS, 3)
            else
                duration.toString(DurationUnit.MINUTES, 2)

            writeReport(reportFile, results, durationStr, params)
        }
    }

    fun writeReport(dest: Path, results: List<SerializableTransformResult>, duration: String, params: TestRunnerParams) {
        val compatible = results.count { it.result?.output?.success == true }
        val incompatible = results.count { it.result?.output?.success == false }
        val errored = results.count { it.result?.errors == true }
        val failed = results.count { it.result == null }

        val orderer = results.sortedBy { it.project.slug }
        val resultStatus: (SerializableTransformResult) -> String = {
            if (it.result == null) "$ICON_EXCLAMATION Failed"
            else {
                (if (it.result.output.success) "$ICON_CHECK Compatible" else "$ICON_X Incompatible")
                    .let { s -> if (it.result.errors) "$s ($ICON_WARN)" else s }
            }
        }

        val result = TextBuilder()
            .heading("Probe Test Runner Results")
            .text("$ICON_TEST Tested ").bold(params.tests).text(" mods in ").bold(duration).text(".").newParagraph()
            .text("Connector Transformer version: ").code(params.toolchainVersion).newParagraph()
            .heading("Overview", 2)
            .append {
                Table.Builder()
                    .withAlignments(Table.ALIGN_LEFT, Table.ALIGN_CENTER)
                    .addRow("Result", "Value")
                    .addRow(BoldText("$ICON_CHECK Compatible"), compatible)
                    .addRow(BoldText("$ICON_X Incompatible"), incompatible)
                    .addRow(BoldText("$ICON_WARN Errored"), errored)
                    .addRow(BoldText("$ICON_EXCLAMATION Failed"), failed)
                    .addRow(BoldText("$ICON_CLOCK Duration"), duration)
                    .build()
            }
            .heading("Full results", 2)
            .text("Click below to expand a table of all test cases").newParagraph()
            .text("<details>").newLine()
            .text("<summary>Show complete results</summary>").newLine()
            .text("&nbsp;").newParagraph()
            .append {
                Table.Builder()
                    .withAlignments(Table.ALIGN_LEFT, Table.ALIGN_LEFT)
                    .addRow("Project Slug", "Version", "Mod ID", "Result", "Link")
                    .also { t ->
                        orderer.forEach { res ->
                            t.addRow(
                                res.project.slug,
                                res.versionNumber,
                                res.result?.output?.primaryModid?.let(::Code) ?: "-",
                                resultStatus(res),
                                "[Link](https://modrinth.com/mod/${res.project.id})"
                            )
                        }
                    }
                    .build()
            }
            .newParagraph()
            .text("</details>").newParagraph()
            .heading("System information", 2)
            .text("NeoFormRuntime version: ${Code(params.nfrtVersion)}  ").newLine()
            .text("NeoForge version: ${Code(params.neoForgeVersion)}  ").newLine()
            .text("Connector Transformer version: ${Code(params.toolchainVersion)}  ").newLine()
            .text("Game version: ${Code(params.gameVersion)}  ").newLine()
            .text("Compatible game versions: ${params.compatibleGameVersions.joinToString(separator = ", ", transform = { Code(it).toString() })}").newLine()

        dest.writeText(result.build().toString())
    }

    private fun getSHA256(path: Path): String? {
        if (!path.isRegularFile()) return null

        val hash = MessageDigest.getInstance("SHA-256")
        val bytes = path.readBytes()
        val result = hash.digest(bytes)

        return result.fold("") { str, it -> str + "%02x".format(it) }
    }

    private fun getImplementationVersion(path: Path): String? {
        if (!path.isRegularFile()) throw IllegalArgumentException("Path must be a regular file")

        FileSystems.newFileSystem(path).use {
            val mfPath = it.getPath("META-INF", "MANIFEST.MF")
            if (mfPath.exists() && mfPath.isRegularFile()) {
                val manifest = mfPath.inputStream().use(::Manifest)
                return manifest.mainAttributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION)
            }
        }
        return null
    }
}