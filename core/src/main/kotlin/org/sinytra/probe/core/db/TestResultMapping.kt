package org.sinytra.probe.core.db

import org.jetbrains.exposed.dao.LongEntity
import org.jetbrains.exposed.dao.LongEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.kotlin.datetime.CurrentDateTime
import org.jetbrains.exposed.sql.kotlin.datetime.datetime
import org.sinytra.probe.core.model.TestResult

object TestResultTable : LongIdTable("test_result") {
    val project = reference("project", ProjectTable)
    val testEnvironment = reference("test_environment", TestEnvironmentTable)

    val versionId = varchar("version_id", 255)
    val passing = bool("passing")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)

    init {
        uniqueIndex("test_result_params", project, testEnvironment, versionId)
    }
}

class TestResultDAO(id: EntityID<Long>) : LongEntity(id) {
    companion object : LongEntityClass<TestResultDAO>(TestResultTable)

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