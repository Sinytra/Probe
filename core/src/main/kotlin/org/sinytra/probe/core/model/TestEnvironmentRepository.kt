package org.sinytra.probe.core.model

import org.jetbrains.exposed.sql.and
import org.sinytra.probe.core.db.TestEnvironmentDAO
import org.sinytra.probe.core.db.TestEnvironmentTable
import org.sinytra.probe.core.db.daoToModel
import org.sinytra.probe.core.db.suspendTransaction

data class TestEnvironmentStub(
    val connectorVersion: String,
    val gameVersion: String,
    val neoForgeVersion: String
)

interface TestEnvironmentRepository {
    suspend fun getTestEnvironment(connectorVersion: String, gameVersion: String, neoForgeVersion: String): TestEnvironment?
    suspend fun addTestEnvironment(environment: TestEnvironmentStub): TestEnvironment
}

class PostgresTestEnvironmentRepository : TestEnvironmentRepository {
    override suspend fun getTestEnvironment(connectorVersion: String, gameVersion: String, neoForgeVersion: String): TestEnvironment? = suspendTransaction {
        TestEnvironmentDAO
            .find {
                (TestEnvironmentTable.connectorVersion eq connectorVersion) and
                (TestEnvironmentTable.gameVersion eq gameVersion) and
                (TestEnvironmentTable.neoForgeVersion eq neoForgeVersion)
            }
            .limit(1)
            .map(::daoToModel)
            .firstOrNull()
    }

    override suspend fun addTestEnvironment(environment: TestEnvironmentStub): TestEnvironment = suspendTransaction {
        TestEnvironmentDAO.new {
            connectorVersion = environment.connectorVersion
            gameVersion = environment.gameVersion
            neoForgeVersion = environment.neoForgeVersion
        }.let(::daoToModel)
    }
}