package org.sinytra.probe.core.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction

suspend fun <T> suspendTransaction(block: suspend JdbcTransaction.() -> T): T =
    newSuspendedTransaction(Dispatchers.IO, statement = block)
