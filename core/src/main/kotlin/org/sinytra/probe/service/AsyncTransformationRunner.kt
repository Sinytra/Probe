package org.sinytra.probe.service

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.sinytra.probe.TransformationService
import org.sinytra.probe.model.ProjectPlatform
import org.sinytra.probe.model.TestResult
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class AsyncTransformationRunner(private val transfomer: TransformationService, private val persistence: PersistenceService) {
    private data class TaskKey(val platform: ProjectPlatform, val id: String)

    private val threadId = AtomicInteger(1)
    private val pending = mutableMapOf<TaskKey, Deferred<TestResult>>()
    private val lock = ReentrantReadWriteLock()
    private val dispatcher = Executors.newCachedThreadPool {
        val id = threadId.getAndIncrement()
        Thread(it, "Transform-Worker-$id")
    }.asCoroutineDispatcher()

    suspend fun transform(project: PlatformProject, resolved: ResolvedProject, gameVersion: String, toolchainVersion: String): TestResult = coroutineScope {
        val existing = lock.read { persistence.getExistingResult(project, gameVersion, toolchainVersion) }
        if (existing != null) {
            return@coroutineScope existing
        }

        val key = TaskKey(project.platform, project.id)
        val task = lock.write {
            val runningTask = pending[key]
            if (runningTask != null) {
                return@write runningTask
            }

            val existing = persistence.getExistingResult(project, gameVersion, toolchainVersion)
            if (existing != null) {
                return@coroutineScope existing
            }

            async(dispatcher) {
                computeAndSave(project, resolved, gameVersion, toolchainVersion)
                    .also { lock.write { pending.remove(key) } }
            }.also { pending[key] = it }
        }

        return@coroutineScope task.await()
    }

    suspend fun computeAndSave(project: PlatformProject, resolved: ResolvedProject, gameVersion: String, toolchainVersion: String): TestResult {
        val result = transfomer.runTransformation(resolved, gameVersion)
        val testResult = lock.write { persistence.saveResult(project, result, gameVersion, toolchainVersion) }
        return testResult
    }
}