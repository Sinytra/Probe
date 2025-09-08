package org.sinytra.probe.core.model

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class TestEnvironment(
    val id: Long,
    val connectorVersion: String,
    val gameVersion: String,
    val neoForgeVersion: String,
    val createdAt: LocalDateTime
)
