package org.sinytra.probe.base

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import org.sinytra.probe.base.db.ProjectPlatform

@Serializable
data class TestRequestBody(
    val platform: ProjectPlatform,
    val id: String,
    val gameVersion: String
)

interface ResponseBase {
    val project: TestProjectDTO
    val type: ResultType
}

@Serializable
enum class ResultType {
    TESTED,
    NATIVE,
    UNAVAILABLE
}

@Serializable
data class TestProjectDTO(
    val id: String,
    val slug: String,
    val title: String,
    val description: String,
    val iconUrl: String?,
    val url: String,
    val platform: ProjectPlatform
)

@Serializable
data class UnavailableResponseBody(
    val loader: String,
    val gameVersion: String,

    override val project: TestProjectDTO,
    override val type: ResultType
) : ResponseBase

@Serializable
data class TestEnvironmentDTO(
    val connectorVersion: String,
    val gameVersion: String,
    val neoforgeVersion: String
)

@Serializable
data class TestResponseBody(
    val modid: String?,
    val versionNumber: String?,
    val versionId: String?,
    val passing: Boolean,

    val environment: TestEnvironmentDTO,
    val createdAt: LocalDateTime,

    override val project: TestProjectDTO,
    override val type: ResultType
) : ResponseBase

@Serializable
data class SkippedResponseBody(
    val gameVersion: String,

    override val project: TestProjectDTO,
    override val type: ResultType
) : ResponseBase