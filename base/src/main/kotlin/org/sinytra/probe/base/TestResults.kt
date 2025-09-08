package org.sinytra.probe.base

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ProjectSearchResult(
    val id: String,
    val name: String,
    val iconUrl: String?,
    val slug: String,
    val versionId: String
)

@Serializable
data class TransformLibOutput(
    val success: Boolean,
    val primaryModid: String
)

@Serializable
data class TransformResult(
    val output: TransformLibOutput,
    val errors: Boolean
)

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
    val neoformRuntimeVersion: String,
    val neoforgeVersion: String,
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