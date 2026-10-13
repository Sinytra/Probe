@file:OptIn(ExperimentalPathApi::class)

package org.sinytra.probe.core.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

class ProjectFileTracker {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(ProjectFileTracker::class.java)
    }

    private val usages = mutableMapOf<Path, Int>()
    private val pendingDeletion = mutableSetOf<Path>()
    private val mutex = Mutex()

    suspend fun acquire(dirs: Set<Path>) = mutex.withLock {
        dirs.forEach { usages.merge(it, 1, Int::plus) }
    }

    suspend fun release(dirs: Set<Path>, delete: Set<Path> = emptySet()) = mutex.withLock {
        pendingDeletion += delete

        for (dir in dirs) {
            val remaining = usages.getValue(dir) - 1
            if (remaining > 0) {
                usages[dir] = remaining
                continue
            }

            usages.remove(dir)
            if (pendingDeletion.remove(dir)) {
                try {
                    withContext(Dispatchers.IO) { dir.deleteRecursively() }
                } catch (e: Exception) {
                    LOGGER.error("Failed to clean up directory {}", dir, e)
                }
            }
        }
    }
}
