package org.sinytra.probe.core.model

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.sinytra.probe.core.db.ModTable
import org.sinytra.probe.core.db.ProjectDAO
import org.sinytra.probe.core.db.ProjectTable
import org.sinytra.probe.core.db.TestResultDAO
import org.sinytra.probe.core.db.TestResultTable
import org.sinytra.probe.core.db.daoToModel
import org.sinytra.probe.core.db.suspendTransaction

data class BaseTestResult(
    val project: Project,
    val versionId: String,
    val gameVersion: String,
    val toolchainVersion: String,
    val passing: Boolean
)

interface TestResultRepository {
    suspend fun allTestResults(): List<TestResult>
    suspend fun testResultByModid(modid: String): TestResult?
    suspend fun getTestResultFor(modid: String, gameVersion: String, toolchainVersion: String): TestResult?
    suspend fun addTestResult(result: BaseTestResult): TestResult
    suspend fun removeTestResult(result: TestResult): Boolean
}

class PostgresTestResultRepository : TestResultRepository {
    override suspend fun allTestResults(): List<TestResult> = suspendTransaction {
        TestResultDAO.Companion.all().map(::daoToModel)
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

    override suspend fun getTestResultFor(modid: String, gameVersion: String, toolchainVersion: String): TestResult? = suspendTransaction {
        TestResultTable
            .innerJoin(ProjectTable).innerJoin(ModTable)
            .select(TestResultTable.columns)
            .where {
                (ModTable.modid eq modid) and
                        (TestResultTable.gameVersion eq gameVersion) and
                        (TestResultTable.toolchainVersion eq toolchainVersion)
            }
            .limit(1)
            .map(TestResultDAO.Companion::wrapRow)
            .map(::daoToModel)
            .firstOrNull()
    }

    override suspend fun addTestResult(result: BaseTestResult): TestResult = suspendTransaction {
        val dbProject = ProjectDAO.Companion.find { ProjectTable.id eq result.project.internalId }.single()
        TestResultDAO.Companion.new {
            project = dbProject
            versionId = result.versionId
            gameVersion = result.gameVersion
            toolchainVersion = result.toolchainVersion
            passing = result.passing
        }.let(::daoToModel)
    }

    override suspend fun removeTestResult(result: TestResult): Boolean = suspendTransaction {
        TestResultTable.deleteWhere { TestResultTable.id eq result.id } > 0
    }
}