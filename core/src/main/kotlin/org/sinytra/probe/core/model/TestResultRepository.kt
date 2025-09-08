package org.sinytra.probe.core.model

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.sinytra.probe.base.db.Project
import org.sinytra.probe.core.db.*

data class BaseTestResult(
    val project: Project,
    val versionId: String,
    val passing: Boolean,
    val testEnvironment: TestEnvironment
)

interface TestResultRepository {
    suspend fun allTestResults(): List<TestResult>
    suspend fun testResultByModid(modid: String): TestResult?
    suspend fun getTestResultFor(modid: String, testEnvironment: TestEnvironment): TestResult?
    suspend fun addTestResult(result: BaseTestResult): TestResult
    suspend fun removeTestResult(result: TestResult): Boolean
}

class PostgresTestResultRepository : TestResultRepository {
    override suspend fun allTestResults(): List<TestResult> = suspendTransaction {
        TestResultDAO.all().map(::daoToModel)
    }

    override suspend fun testResultByModid(modid: String): TestResult? = suspendTransaction {
        TestResultTable
            .innerJoin(ProjectTable).innerJoin(ModTable)
            .select(TestResultTable.columns)
            .where { ModTable.modid eq modid }
            .limit(1)
            .map(TestResultDAO.Companion::wrapRow)
            .map(::daoToModel)
            .firstOrNull()
    }

    override suspend fun getTestResultFor(modid: String, testEnvironment: TestEnvironment): TestResult? = suspendTransaction {
        TestResultTable
            .innerJoin(ProjectTable).innerJoin(ModTable)
            .select(TestResultTable.columns)
            .where {
                (ModTable.modid eq modid) and (TestResultTable.testEnvironment eq testEnvironment.id)
            }
            .limit(1)
            .map(TestResultDAO.Companion::wrapRow)
            .map(::daoToModel)
            .firstOrNull()
    }

    override suspend fun addTestResult(result: BaseTestResult): TestResult = suspendTransaction {
        maxAttempts = 1

        val dbProject = ProjectDAO.find { ProjectTable.id eq result.project.internalId }.single()
        val dbTestEnvironment = TestEnvironmentDAO.find { TestEnvironmentTable.id eq result.testEnvironment.id }.single()
        TestResultDAO.new {
            project = dbProject
            versionId = result.versionId
            passing = result.passing
            testEnvironment = dbTestEnvironment
        }.let(::daoToModel)
    }

    override suspend fun removeTestResult(result: TestResult): Boolean = suspendTransaction {
        TestResultTable.deleteWhere { TestResultTable.id eq result.id } > 0
    }
}