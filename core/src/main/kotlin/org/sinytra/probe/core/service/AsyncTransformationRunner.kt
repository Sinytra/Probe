package org.sinytra.probe.core.service

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.sinytra.probe.base.db.ProjectPlatform
import org.sinytra.probe.core.model.TestEnvironment
import org.sinytra.probe.core.model.TestResult
import org.sinytra.probe.core.platform.PlatformProject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class AsyncTransformationRunner(
    private val transfomer: TransformationService,
    private val persistence: PersistenceService,
    maxThreadCount: Int
) {
    private data class TaskKey(val platform: ProjectPlatform, val id: String, val gameVersion: String)

    private val threadId = AtomicInteger(1)
    private val pending = mutableMapOf<TaskKey, Deferred<TestResult?>>()
    private val mutex = Mutex()
    private val dispatcher = Executors.newFixedThreadPool(maxThreadCount) {
        val id = threadId.getAndIncrement()
        Thread(it, "Transform-Worker-$id")
    }.asCoroutineDispatcher()

    suspend fun transform(project: PlatformProject, testEnvironment: TestEnvironment): TestResult? = coroutineScope {
        val existing = mutex.withLock { persistence.getExistingResult(project, testEnvironment) }
        if (existing != null) {
            return@coroutineScope existing
        }

        val key = TaskKey(project.platform, project.id, testEnvironment.gameVersion)
        val task = mutex.withLock {
            val runningTask = pending[key]
            if (runningTask != null) {
                return@withLock runningTask
            }

            val existing = persistence.getExistingResult(project, testEnvironment)
            if (existing != null) {
                return@coroutineScope existing
            }

            async(dispatcher) {
                try {
                    computeAndSave(project, testEnvironment)
                } finally {
                    withContext(NonCancellable) { mutex.withLock { pending.remove(key) } }
                }
            }.also { pending[key] = it }
        }

        return@coroutineScope task.await()
    }

    suspend fun computeAndSave(project: PlatformProject, testEnvironment: TestEnvironment): TestResult? {
        val result = transfomer.runTransformation(project, testEnvironment.gameVersion) ?: return null
        val testResult = mutex.withLock { 
            persistence.saveResult(project, result.modid, result.version, result.success, testEnvironment)
        }
        return testResult
    }
}