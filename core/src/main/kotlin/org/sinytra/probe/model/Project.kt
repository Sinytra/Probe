package org.sinytra.probe.model

import kotlinx.serialization.Serializable

enum class ProjectPlatform {
    CURSEFORGE,
    MODRINTH
}

@Serializable
data class Project(
    val platform: ProjectPlatform,
    val id: String
)