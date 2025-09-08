package org.sinytra.probe.core.model

import kotlinx.serialization.Serializable
import org.sinytra.probe.base.db.Project

@Serializable
data class Mod(
    val modid: String,
    val projects: List<Project>
)