@file:OptIn(ExperimentalPathApi::class)

package org.sinytra.probe.gatherer.internal

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import org.sinytra.probe.core.service.CleanupService
import org.sinytra.probe.core.platform.ModrinthPlatform
import org.sinytra.probe.core.platform.ModrinthPlatform.Companion.FAPI_ID
import org.sinytra.probe.core.platform.ModrinthPlatform.Companion.FFAPI_ID
import org.sinytra.probe.core.platform.ModrinthPlatform.Companion.FFAPI_SLUG
import org.sinytra.probe.core.platform.ModrinthPlatform.Companion.LOADER_FABRIC
import org.sinytra.probe.core.platform.ModrinthPlatform.Companion.LOADER_NEOFORGE
import org.sinytra.probe.core.platform.ProjectSearchResult
import org.sinytra.probe.core.platform.ResolvedProject
import org.sinytra.probe.core.service.SetupService
import org.sinytra.probe.gatherer.SerializableTransformResult
import org.sinytra.probe.gatherer.TestRunnerParams
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.*
import kotlin.io.path.*
import kotlin.time.measureTime
import kotlin.time.measureTimedValue

class ModTestRunner(
    private val workingDir: Path,
    private val setup: SetupService,
    private val modrinth: ModrinthPlatform,
    private val cleanupService: CleanupService,
    private val params: TestRunnerParams
) {
    companion object {
        private val LOGGER: Logger = LoggerFactory.getLogger("ModTestRunner")
        private val EXCLUDED_PROJECTS = listOf(
            FAPI_ID,
            FFAPI_ID
        )
    }

    private val gameVersion: String = params.gameVersion
    private val compatibleGameVersions: List<String> = params.compatibleGameVersions
    private val maxCount: Int = params.tests
    private val transformerInvoker: TransformerInvoker = TransformerInvoker(params.gameVersion, params.cleanupOutput)
    val concurrentDownloads: Int = params.concurrentDownloads
    val concurrentTests: Int = params.concurrentTests
    val writeReport: Boolean = params.writeReport

    fun run() {
        // Read gathered projects
        val candidatesFile = workingDir / "candidates.json"
        val candidates = readCandidatesFile(maxCount, candidatesFile) ?: gatherCandidateProjects(maxCount, candidatesFile)
        run(candidates)
    }

    fun run(candidates: List<ProjectSearchResult>) {
        // Resolve versions
        val dependencies = downloadCandidateMods(candidates)
        val candidateIds = candidates.map(ProjectSearchResult::id)
        val missingDeps = dependencies.values.flatten().distinct().filterNot(candidateIds::contains)

        val resolvedDeps = downloadDependencies(missingDeps)
        LOGGER.info("Resolved {} additional dependencies", resolvedDeps.size)
        val unresolved = missingDeps.size - resolvedDeps.size
        if (unresolved > 0) {
            LOGGER.info("{} Failed to resolve {} dependencies", ICON_WARN, unresolved)
        }

        // Run tests
        runBlocking {
            val testCandidates = candidates.filterNot { EXCLUDED_PROJECTS.contains(it.id) }.take(maxCount)
            val (results, duration) = measureTimedValue { runTests(testCandidates, resolvedDeps, missingDeps) }

            ResultReporter.processResults(results, duration, workingDir, writeReport, setup, params)

            val flatDeps = resolvedDeps.flattenRecursively { it.dependencies }
                .mapNotNull {
                    val slug = modrinth.getProject(it.version.projectId)?.slug ?: return@mapNotNull null
                    return@mapNotNull CleanupService.ProjectCoordinates(it.version.projectId, slug, it.version.versionId)
                }
            cleanupService.cleanupFiles(
                candidates.map { CleanupService.ProjectCoordinates(it.id, it.slug, it.versionId) } + flatDeps,
                listOf("output", "$FFAPI_SLUG-$FFAPI_ID")
            )
        }
    }

    private fun <T> List<T>.flattenRecursively(getChildren: (T) -> List<T>): List<T> {
        return flatMap { item ->
            listOf(item) + getChildren(item).flattenRecursively(getChildren)
        }
    }

    private suspend fun runTests(candidates: List<ProjectSearchResult>, dependencies: List<ResolvedProject>, missingDeps: List<String>): List<SerializableTransformResult> {
        val transformerPath = setup.getTransformLibPath()
        val gameFiles = setup.installDependencies()

        val modsDir = workingDir / "mods"
        val classPath = gameFiles.loaderFiles.toMutableList() + resolveMandatedLibraries(modsDir)
        val depsMap = dependencies.associate {
            it.version.projectId to it.dependencies
                .flattenRecursively { d -> d.dependencies }
                .filterNot { d -> EXCLUDED_PROJECTS.contains(d.version.projectId) }
        }

        val dispatcher = Dispatchers.IO.limitedParallelism(concurrentTests)
        val semaphore = Semaphore(concurrentTests)
        val retakeTests = System.getenv("FORCE_RETAKE_TESTS") == "true"
        if (retakeTests) {
            LOGGER.warn("Retaking tests is enabled, previous results will be ignored")
        }

        val mutex = Mutex()
        var done = 0
        val completeTest: suspend () -> Unit = { mutex.withLock { LOGGER.info("Test progress: [{} / {}] completed", ++done, candidates.size) } }

        return coroutineScope {
            val results = candidates.map { proj ->
                async(dispatcher) {
                    semaphore.withPermit {
                        val version = modrinth.getVersion(proj.slug, proj.versionId)
                            ?: throw IllegalStateException("Missing version for ${proj.slug}")
                        val versionFile = modsDir / version.path

                        val depsFiles = depsMap[proj.id] ?: emptyList()
                        if (depsFiles.any { missingDeps.contains(it.version.projectId) }) {
                            LOGGER.error("Skipping test for ${proj.slug} due to missing deps")
                            completeTest()
                            return@withPermit SerializableTransformResult(proj, version.versionNumber, null)
                        }
                        val depsPaths = depsFiles.map { modsDir / it.version.path }
                        depsPaths.firstOrNull { it.notExists() }?.let {
                            throw IllegalStateException("Dep path $it does not exist")
                        }

                        try {
                            val result = transformerInvoker.invokeTransform(
                                proj.slug,
                                transformerPath,
                                versionFile.parent.resolve("output").also {
                                    if (retakeTests) it.deleteRecursively()
                                    it.createDirectories()
                                },
                                listOf(versionFile) + depsPaths,
                                gameFiles.cleanFile,
                                classPath
                            )
                            val output = result.output
                            LOGGER.info("{} Transformed project ${proj.slug}: ID ${output.primaryModid} Success: ${output.success}", if (output.success) "\u2705" else "\u274C")

                            return@withPermit SerializableTransformResult(proj, version.versionNumber, result)
                        } catch (e: Exception) {
                            LOGGER.error("Error during transforming ${proj.slug}", e)

                            return@withPermit SerializableTransformResult(proj, version.versionNumber, null)
                        } finally {
                            completeTest()
                        }
                    }
                }
            }
            results.awaitAll()
        }
    }

    private fun downloadDependencies(deps: List<String>): List<ResolvedProject> {
        if (deps.isNotEmpty()) {
            LOGGER.info("{} Downloading {} additional dependencies", ICON_PACKAGE, deps.size)
        }

        val dispatcher = Dispatchers.IO.limitedParallelism(concurrentDownloads)
        val semaphore = Semaphore(concurrentDownloads)

        return runBlocking {
            val results: List<Deferred<ResolvedProject?>> = deps.map {
                async(dispatcher) {
                    semaphore.withPermit {
                        val project = modrinth.getProject(it)
                            ?: run {
                                LOGGER.error("Project $it not found")
                                return@withPermit null
                            }
                        modrinth.resolveProject(project, compatibleGameVersions, true)
                            ?: run {
                                LOGGER.error("Failed to resolve project ${project.slug}")
                                null
                            }
                    }
                }
            }
            results.awaitAll().filterNotNull()
        }
    }

    private fun downloadCandidateMods(candidates: List<ProjectSearchResult>): Map<String, List<String>> {
        val dispatcher = Dispatchers.IO.limitedParallelism(concurrentDownloads)
        val mutex = Mutex()
        var done = 0

        val semaphore = Semaphore(concurrentDownloads)
        val dependencies = Collections.synchronizedMap<String, List<String>>(mutableMapOf())
        try {
            runBlocking {
                val downloadQueue = candidates.filterNot { modrinth.hasVersion(it.versionId) }

                if (downloadQueue.isNotEmpty()) {
                    LOGGER.info("{} Downloading {} mods", ICON_PACKAGE, downloadQueue.size)
                }

                try {
                    candidates
                        .forEach {
                            launch(dispatcher) {
                                semaphore.withPermit {
                                    try {
                                        val needsDownload = !modrinth.hasVersion(it.versionId)

                                        if (needsDownload) {
                                            LOGGER.info("{} Downloading {}", ICON_DOWNLOAD, it.slug)
                                        }
                                        val version = modrinth.getVersion(it.slug, it.versionId) ?: run {
                                            LOGGER.error("Failed to download mod {}", it.slug)
                                            return@withPermit
                                        }

                                        LOGGER.debug("Found {} dependencies for {}: {}", version.dependencies.size, it.id, version.dependencies)
                                        version.dependencies.filterNot(EXCLUDED_PROJECTS::contains)
                                            .takeIf(List<String>::isNotEmpty)
                                            ?.let { l -> dependencies[it.id] = l }

                                        mutex.withLock {
                                            if (needsDownload) {
                                                LOGGER.info("Done {} {}", done++, it.slug)
                                            } else {
                                                done++
                                            }
                                        }
                                    } catch (e: Exception) {
                                        LOGGER.error("Encountered exception while downloading mods", e)
                                        this@runBlocking.cancel()
                                    }
                                }
                            }
                        }
                } catch (e: Exception) {
                    LOGGER.error("Encountered exception while downloading mods", e)
                }
            }
        } catch (e: Exception) {
            LOGGER.error("Encountered exception running downloader", e)
        }

        return dependencies
    }

    private fun gatherCandidateProjects(count: Int, output: Path): List<ProjectSearchResult> {
        LOGGER.info("{} Gathering {} candidates", ICON_MAG_GLASS, count)

        val batch = 100
        val exclusiveKeys = Collections.synchronizedList(mutableListOf<String>())
        val exclusiveMods = mutableMapOf<String, ProjectSearchResult>()
        val elapsed = measureTime {
            var i = 0
            while (exclusiveMods.size < count) {
                LOGGER.debug("Gathering round {}", i)
                val (keys, map) = runBlocking {
                    gatherCandidatesBatch(i++, batch)
                }
                exclusiveKeys += keys
                exclusiveMods += map
                LOGGER.debug("Got {} exclusive keys and {} projects", exclusiveKeys.size, exclusiveMods.size)
            }
        }

        LOGGER.debug("Retrieved {} fabric-exclusive mods in {} ms", exclusiveMods.size, elapsed.inWholeMilliseconds)

        val projectList: List<ProjectSearchResult> = exclusiveMods.values
            .sortedWith { a, b -> exclusiveKeys.indexOf(a.id) - exclusiveKeys.indexOf(b.id) }
            .take(count)

        val json = Json.encodeToString(projectList)
        output.writeText(json)

        return projectList
    }

    private suspend fun gatherCandidatesBatch(offset: Int, batch: Int): Pair<List<String>, Map<String, ProjectSearchResult>> = coroutineScope {
        val batchOffset = 10 * batch * offset

        val fabricTasks = (0..9).map { i ->
            async(Dispatchers.IO) {
                modrinth.searchProjects(batch, batchOffset + i * batch, gameVersion, LOADER_FABRIC, LOADER_NEOFORGE)
                    ?.filterNot { EXCLUDED_PROJECTS.contains(it.id) }
            }
        }
        val neoTasks = (0..9).map { i ->
            async(Dispatchers.IO) {
                modrinth.searchProjects(batch, batchOffset + i * batch, gameVersion, LOADER_NEOFORGE, null)
                    ?.filterNot { EXCLUDED_PROJECTS.contains(it.id) }
            }
        }
        val fabricResults = fabricTasks.awaitAll().filterNotNull().map(::processResults)
        val neoResults = neoTasks.awaitAll().filterNotNull().map(::processResults)

        val fabricKeys = fabricResults.flatMap { it.first }.toMutableList()
        val fabricProjectIDs = mutableMapOf<String, ProjectSearchResult>().also { map -> fabricResults.forEach { map += it.second } }

        val neoKeys = neoResults.flatMap { it.first }
        val neoProjectIDs = mutableMapOf<String, ProjectSearchResult>().also { map -> neoResults.forEach { map += it.second } }

        fabricKeys -= neoKeys.toSet()
        val map = fabricProjectIDs.filterKeys { !neoProjectIDs.contains(it) }

        return@coroutineScope fabricKeys to map
    }

    private fun processResults(results: List<ProjectSearchResult>): Pair<List<String>, Map<String, ProjectSearchResult>> {
        val keys = results.map { it.id }
        val map = results.associateBy { it.id }
        return keys to map
    }

    private suspend fun resolveMandatedLibraries(dest: Path): List<Path> {
        val resolved = modrinth.resolveProjectVersion(FFAPI_ID, gameVersion, LOADER_NEOFORGE)!!
        return listOf(dest / resolved.path)
    }

    private fun readCandidatesFile(count: Int, file: Path): List<ProjectSearchResult>? {
        return file.takeIf { it.exists() }?.readText()
            ?.let { 
                try {
                    Json.decodeFromString<List<ProjectSearchResult>>(it)
                } catch (e: Exception) {
                    LOGGER.error("Error while parsing candidates file, discarding", e)
                    null
                }
            }
            ?.let {
                if (it.size < count) {
                    LOGGER.info("{} Discarding candidate cache, was {}, expected {}", ICON_TRASH, it.size, count)
                    null
                } else {
                    it
                }
            }
            ?.also { LOGGER.info("{} Read {} candidates from cache", ICON_RECYCLE, it.size) }
    }
}