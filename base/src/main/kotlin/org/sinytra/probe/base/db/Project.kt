package org.sinytra.probe.base.db

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// WARNING: STABLE API
@Serializable
enum class ProjectPlatform {
    @SerialName("curseforge")
    CURSEFORGE,
    @SerialName("modrinth")
    MODRINTH
}

@Serializable
data class Project(
    val platform: ProjectPlatform,
    val id: String,

    val internalModId: Long,

    val internalId: Long? = null
)