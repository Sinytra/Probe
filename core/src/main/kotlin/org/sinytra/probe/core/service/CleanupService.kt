@file:OptIn(ExperimentalPathApi::class)

package org.sinytra.probe.core.service

import org.slf4j.LoggerFactory
import java.nio.file.FileVisitResult
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.visitFileTree

class CleanupService(private val workDir: Path) {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(CleanupService::class.java)
    }

    data class ProjectCoordinates(
        val id: String,
        val slug: String,
        val versionId: String
    )

    fun cleanupFiles(keep: List<ProjectCoordinates>, ignoreProjects: List<String>) {
        val storagePath = workDir / "mods"

        val remove = mutableListOf<Path>()
        val keepModFolders = keep.map { "${it.slug}-${it.id}" }.toSet()
        val keepModFiles = keep.map { "${it.slug}-${it.versionId}.jar" }.toSet()

        storagePath.visitFileTree(maxDepth = 2) {
            onVisitFile { path, attr ->
                if (!ignoreProjects.contains(path.fileName.toString())
                    && !keepModFiles.contains(path.fileName.toString())
                ) {
                    remove.add(path)
                }

                return@onVisitFile FileVisitResult.CONTINUE
            }

            onPreVisitDirectory { path, attrs ->
                if (path != storagePath) {
                    if (ignoreProjects.contains(path.fileName.toString())) {
                        return@onPreVisitDirectory FileVisitResult.SKIP_SUBTREE
                    }

                    if (!keepModFolders.contains(path.fileName.toString())) {
                        remove.add(path)
                        return@onPreVisitDirectory FileVisitResult.SKIP_SUBTREE
                    }
                }

                return@onPreVisitDirectory FileVisitResult.CONTINUE
            }
        }

        if (remove.isNotEmpty()) {
            LOGGER.info("Cleaning up {} unused file paths", remove.size)
            remove.forEach {
                LOGGER.info("Deleting {}", it)
                it.deleteRecursively()
            }
        }
    }
}