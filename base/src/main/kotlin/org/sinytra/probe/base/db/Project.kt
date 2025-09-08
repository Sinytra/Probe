package org.sinytra.probe.base.db

import kotlinx.serialization.Serializable

enum class ProjectPlatform {
    CURSEFORGE,
    MODRINTH
}

@Serializable
data class Project(
    val platform: ProjectPlatform,
    val id: String,
    val modid: String,
    val internalId: Int? = null
)