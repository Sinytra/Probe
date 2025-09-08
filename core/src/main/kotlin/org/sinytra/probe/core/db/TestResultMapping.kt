package org.sinytra.probe.core.db

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.sinytra.probe.core.model.TestResult

object TestResultTable : IntIdTable("test_result") {
    val project = reference("project", ProjectTable)
    val testEnvironment = reference("test_environment", TestEnvironmentTable)

    val versionId = varchar("version_id", 255)
    val passing = bool("passing")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
}

class TestResultDAO(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<TestResultDAO>(TestResultTable)

    var versionId by TestResultTable.versionId
    var passing by TestResultTable.passing
    var createdAt by TestResultTable.createdAt

    var project by ProjectDAO referencedOn TestResultTable.project
    var testEnvironment by TestEnvironmentDAO referencedOn TestResultTable.testEnvironment
}

fun daoToModel(dao: TestResultDAO) = TestResult(
    dao.id.value,
    dao.project.mod.modid,
    dao.project.id.value,
    dao.versionId,
    dao.passing,
    dao.testEnvironment.id.value,
    dao.createdAt
)