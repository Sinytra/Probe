package org.sinytra.probe.gatherer

import net.steppschuh.markdowngenerator.table.Table
import net.steppschuh.markdowngenerator.text.TextBuilder
import net.steppschuh.markdowngenerator.text.code.Code
import net.steppschuh.markdowngenerator.text.emphasis.BoldText
import java.nio.file.Path
import kotlin.io.path.writeText

fun writeReport(dest: Path, results: List<BetterGatherer.SerializableTransformResult>, duration: String, params: GathererParams) {
    val compatible = results.count { it.result?.output?.success == true }
    val incompatible = results.count { it.result?.output?.success == false }
    val errored = results.count { it.result?.errors == true }
    val failed = results.count { it.result == null }

    val orderer = results.sortedBy { it.project.slug }
    val resultStatus: (BetterGatherer.SerializableTransformResult) -> String = { 
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
                            "[Link](https://modrinth.com/mod/${res.project.projectId})"
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