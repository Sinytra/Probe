package org.sinytra.probe.core.model

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class TestResult(
    val id: Int,
    val modid: String,
    val projectId: Int,
    val versionId: String,
    val gameVersion: String,
    val toolchainVersion: String,
    val passing: Boolean,
    val createdAt: LocalDateTime
)