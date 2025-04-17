package org.sinytra.probe.model

import kotlinx.serialization.Serializable

@Serializable
data class Mod(
    val modid: String,
    val projects: List<Project>
)