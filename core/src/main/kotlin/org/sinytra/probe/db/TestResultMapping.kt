package org.sinytra.probe.db

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.sinytra.probe.model.TestResult

object TestResultTable : IntIdTable("test_result") {
    val mod = reference("mod", ModTable)

    val gameVersion = varchar("game_version", 255)
    val toolchainVersion = varchar("toolchain_version", 255)
    val passing = bool("passing")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
}

class TestResultDAO(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TestResultDAO>(TestResultTable)

    var gameVersion by TestResultTable.gameVersion
    var toolchainVersion by TestResultTable.toolchainVersion
    var passing by TestResultTable.passing
    var createdAt by TestResultTable.createdAt

    var mod by ModDAO referencedOn TestResultTable.mod
}

fun daoToModel(dao: TestResultDAO) = TestResult(
    dao.id.value,
    dao.mod.modid,
    dao.gameVersion,
    dao.toolchainVersion,
    dao.passing,
    dao.createdAt
)