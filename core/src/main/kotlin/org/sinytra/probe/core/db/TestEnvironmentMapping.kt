package org.sinytra.probe.core.db

import org.jetbrains.exposed.v1.dao.LongEntity
import org.jetbrains.exposed.v1.dao.LongEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime
import org.sinytra.probe.core.model.TestEnvironment

object TestEnvironmentTable : LongIdTable("test_environment") {
    val connectorVersion = text("connector_version")
    val gameVersion = text("game_version")
    val neoForgeVersion = text("neo_forge_version")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    init {
        uniqueIndex("test_environment_params", connectorVersion, gameVersion, neoForgeVersion)
    }
}

class TestEnvironmentDAO(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TestEnvironmentDAO>(TestEnvironmentTable)

    var connectorVersion by TestEnvironmentTable.connectorVersion
    var gameVersion by TestEnvironmentTable.gameVersion
    var neoForgeVersion by TestEnvironmentTable.neoForgeVersion
    var createdAt by TestEnvironmentTable.createdAt
}

fun daoToModel(dao: TestEnvironmentDAO) = TestEnvironment(
    dao.id.value,
    dao.connectorVersion,
    dao.gameVersion,
    dao.neoForgeVersion,
    dao.createdAt
)