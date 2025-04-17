package org.sinytra.probe.model

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.sinytra.probe.db.*

data class BaseTestResult(
    val mod: Mod,
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
        TestResultDAO.all().map(::daoToModel)
    }

    override suspend fun testResultByModid(modid: String): TestResult? = suspendTransaction {
        val dbMod = ModDAO.find { ModTable.modid eq modid }.single()
        TestResultDAO
            .find { (TestResultTable.mod eq dbMod.id) }
            .limit(1)
            .map(::daoToModel)
            .firstOrNull()
    }

    override suspend fun getTestResultFor(modid: String, gameVersion: String, toolchainVersion: String): TestResult? = suspendTransaction {
        val dbMod = ModDAO.find { ModTable.modid eq modid }.single()
        TestResultDAO
            .find { (TestResultTable.mod eq dbMod.id) and
                    (TestResultTable.gameVersion eq gameVersion) and
                    (TestResultTable.toolchainVersion eq toolchainVersion)
            }
            .limit(1)
            .map(::daoToModel)
            .firstOrNull()
    }

    override suspend fun addTestResult(result: BaseTestResult): TestResult = suspendTransaction {
        val dbMod = ModDAO.find { ModTable.modid eq result.mod.modid }.single()
        TestResultDAO.new {
            mod = dbMod
            gameVersion = result.gameVersion
            toolchainVersion = result.toolchainVersion
            passing = result.passing
        }.let(::daoToModel)
    }

    override suspend fun removeTestResult(result: TestResult): Boolean = suspendTransaction {
        TestResultTable.deleteWhere { TestResultTable.id eq result.id } > 0
    }
}