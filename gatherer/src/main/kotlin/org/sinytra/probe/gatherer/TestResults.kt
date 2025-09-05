package org.sinytra.probe.gatherer

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import org.sinytra.probe.core.platform.ProjectSearchResult
import org.sinytra.probe.gatherer.internal.TransformerInvoker.TransformResult

@Serializable
data class SerializableTransformResult(
    val project: ProjectSearchResult,
    val versionNumber: String,
    val result: TransformResult?
)

@Serializable
data class TestToolchain(
    val version: String?,
    val sha256: String?
)

@Serializable
data class TestReport(
    val results: List<SerializableTransformResult>,
    val toolchain: TestToolchain,
    val runner: TestToolchain,
    val durationSeconds: Long,
    val testedAt: Instant
)