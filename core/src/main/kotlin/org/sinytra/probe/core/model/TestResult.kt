package org.sinytra.probe.core.model

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class TestResult(
    val id: Long,
    val modid: String?,
    val projectId: Long,
    val versionId: String,
    val passing: Boolean,
    val testEnvironmentId: Long,
    val createdAt: LocalDateTime
)