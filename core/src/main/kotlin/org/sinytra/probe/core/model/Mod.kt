package org.sinytra.probe.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Mod(
    val modid: String,
    val projects: List<Project>
)