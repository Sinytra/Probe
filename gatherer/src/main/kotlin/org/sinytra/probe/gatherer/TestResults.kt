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
data class TestEnvironment(
    val transformerVersion: String?,
    val transformerHash: String?,
    val runnerVersion: String?,
    val neoFormRuntimeVersion: String,
    val neoForgeVersion: String,
    val gameVersion: String,
    val compatibleGameVersions: List<String>
)

@Serializable
data class TestReport(
    val results: List<SerializableTransformResult>,
    val environment: TestEnvironment,
    val durationSeconds: Long,
    val testedAt: Instant
)