package org.sinytra.probe.core.service

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.sinytra.probe.core.model.ProjectPlatform
import org.sinytra.probe.core.model.TestResult
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class AsyncTransformationRunner(
    private val transfomer: TransformationService,
    private val persistence: PersistenceService,
    maxThreadCount: Int
) {
    private data class TaskKey(val platform: ProjectPlatform, val id: String)

    private val threadId = AtomicInteger(1)
    private val pending = mutableMapOf<TaskKey, Deferred<TestResult>>()
    private val mutex = Mutex()
    private val dispatcher = Executors.newFixedThreadPool(maxThreadCount) {
        val id = threadId.getAndIncrement()
        Thread(it, "Transform-Worker-$id")
    }.asCoroutineDispatcher()

    suspend fun transform(project: PlatformProject, resolved: ResolvedProject, gameVersion: String, toolchainVersion: String): TestResult = coroutineScope {
        val existing = mutex.withLock { persistence.getExistingResult(project, gameVersion, toolchainVersion) }
        if (existing != null) {
            return@coroutineScope existing
        }

        val key = TaskKey(project.platform, project.id)
        val task = mutex.withLock {
            val runningTask = pending[key]
            if (runningTask != null) {
                return@withLock runningTask
            }

            val existing = persistence.getExistingResult(project, gameVersion, toolchainVersion)
            if (existing != null) {
                return@coroutineScope existing
            }

            async(dispatcher) {
                computeAndSave(project, resolved, gameVersion, toolchainVersion)
                    .also { mutex.withLock { pending.remove(key) } }
            }.also { pending[key] = it }
        }

        return@coroutineScope task.await()
    }

    suspend fun computeAndSave(project: PlatformProject, resolved: ResolvedProject, gameVersion: String, toolchainVersion: String): TestResult {
        val result = transfomer.runTransformation(resolved, gameVersion)
        val testResult = mutex.withLock { persistence.saveResult(project, result, gameVersion, toolchainVersion) }
        return testResult
    }
}