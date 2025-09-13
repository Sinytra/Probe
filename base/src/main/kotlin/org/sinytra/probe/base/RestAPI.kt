@file:OptIn(ExperimentalSerializationApi::class)

package org.sinytra.probe.base

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.sinytra.probe.base.db.ProjectPlatform

@Serializable
data class TestRequestBody(
    val platform: ProjectPlatform,
    val id: String,
    val gameVersion: String
)

@Serializable
sealed interface TestResponseBody {
    val project: TestProjectDTO
    val type: ResultType

    @Serializable
    data class Tested(
        val modid: String?,
        val versionNumber: String?,
        val versionId: String?,
        val passing: Boolean,

        val environment: TestEnvironmentDTO,
        val createdAt: LocalDateTime,

        override val project: TestProjectDTO,
        override val type: ResultType
    ) : TestResponseBody

    @Serializable
    data class Unavailable(
        val loader: String,
        val gameVersion: String,

        override val project: TestProjectDTO,
        override val type: ResultType
    ) : TestResponseBody

    @Serializable
    data class Skipped(
        val loader: String,
        val gameVersion: String,

        override val project: TestProjectDTO,
        override val type: ResultType
    ) : TestResponseBody
}

@Serializable
enum class ResultType {
    @SerialName("tested")
    TESTED,
    @SerialName("native")
    NATIVE,
    @SerialName("unavailable")
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
data class TestEnvironmentDTO(
    val connectorVersion: String,
    val gameVersion: String,
    val neoforgeVersion: String
)