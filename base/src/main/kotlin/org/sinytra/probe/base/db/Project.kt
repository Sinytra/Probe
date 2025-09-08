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

    val internalModId: Long,

    val internalId: Long? = null
)