package org.sinytra.probe.base

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import org.sinytra.probe.base.db.ProjectPlatform

@Serializable
data class TestRequestBody(val platform: ProjectPlatform, val id: String)

interface ResponseBase {
    val type: ResultType
}

@Serializable
enum class ResultType {
    TESTED,
    NATIVE,
    UNAVAILABLE
}

@Serializable
data class UnavailableResponseBody(
    val slug: String,
    val loader: String,
    val gameVersion: String,
    override val type: ResultType
) : ResponseBase

@Serializable
data class TestEnvironmentDTO(
    val connectorVersion: String,
    val gameVersion: String,
    val neoForgeVersion: String
)

@Serializable
data class TestResponseBody(
    val modid: String?,
    val iconUrl: String,
    val projectUrl: String,
    val version: String,
    val passing: Boolean,
    val environment: TestEnvironmentDTO,
    val createdAt: LocalDateTime,
    override val type: ResultType
): ResponseBase

@Serializable
data class SkippedResponseBody(
    val slug: String,
    val iconUrl: String,
    val projectUrl: String,
    val gameVersion: String, 
    override val type: ResultType
) : ResponseBase