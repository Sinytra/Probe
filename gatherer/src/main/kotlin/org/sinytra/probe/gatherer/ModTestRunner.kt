@file:OptIn(ExperimentalLettuceCoroutinesApi::class, ExperimentalPathApi::class)

package org.sinytra.probe.gatherer

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import org.sinytra.probe.TransformLibOutput
import org.sinytra.probe.service.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlin.time.measureTime

private val LOGGER: Logger = LoggerFactory.getLogger("BetterGatherer")

private const val ICON_RECYCLE = "\u267B\uFE0F"
private const val ICON_PACKAGE = "\uD83D\uDCE6"
private const val ICON_DOWNLOAD = "\u2B07\uFE0F"
private const val ICON_MAG_GLASS = "\uD83D\uDD0D"
private const val ICON_TRASH = "\uD83D\uDDD1"
private const val ICON_CHECK = "\u2705"
private const val ICON_X = "\u274C"
private const val ICON_WARN = "\u26A0\uFE0F"
private const val ICON_TEST = "\uD83E\uDDEA"
private const val ICON_EXCLAMATION = "\u2757"
private const val CONCURRENT_DOWNLOADS = 20
private const val CONCURRENT_TESTS = 10
private const val FORCE_RETAKE_TESTS = true
private val EXCLUDED_PROJECTS = listOf("P7dR8mSH", "Aqlf1Shp")

data class GathererParams(
    val nfrtVersion: String,
    val neoForgeVersion: String,
    val toolchainVersion: String,
    val gameVersion: String,
    val compatibleGameVersions: List<String>,
    val workDir: Path,
    val tests: Int
)

fun runGatherer(params: GathererParams) {
    val workDir = params.workDir
    val setupService = SetupService(
        (workDir / ".setup").also { it.createDirectories() },
        true,
        params.nfrtVersion,
        params.neoForgeVersion,
        params.toolchainVersion
    )

    val url = System.getenv("REDIS_URL") ?: throw RuntimeException("Missing REDIS_URL")
    LOGGER.trace("Connecting to redis instance")
    val redisClient = RedisClient.create(url)
    val redisConnection = redisClient.connect()

    val modrinthService = ModrinthService(workDir / "mods", redisConnection)

    val gatherer = BetterGatherer(workDir, setupService, modrinthService, redisConnection, params.gameVersion, params.compatibleGameVersions, params.tests)

    setupService.getTransformLibPath()
    setupService.installDependencies()

    gatherer.run()
}

class BetterGatherer(
    private val gathererDir: Path,
    private val setup: SetupService,
    private val modrinth: ModrinthService,
    private val redisConnection: StatefulRedisConnection<String, String>,
    private val gameVersion: String,
    private val compatibleGameVersions: List<String>,
    private val maxCount: Int
) {

    fun run() {
        // Read gathered projects
        val candidatesFile = gathererDir / "candidates.json"
        val candidates = readCandidatesFile(maxCount, candidatesFile) ?: gatherCandidateProjects(maxCount, candidatesFile)

        // Resolve versions
        val dependencies = downloadCandidateMods(candidates)
        val candidateIds = candidates.map { it.projectId }
        val missingDeps = dependencies.values.flatten().distinct().filterNot(candidateIds::contains)

        val resolvedDeps = downloadDependencies(missingDeps)
        LOGGER.info("Resolved {} additional dependencies", resolvedDeps.size)
        val unresolved = missingDeps.size - resolvedDeps.size
        if (unresolved > 0) {
            LOGGER.info("{} Failed to resolve {} dependencies", ICON_WARN, unresolved)
        }

        // Run tests
        runBlocking {
            val testCandidates = candidates.filterNot { EXCLUDED_PROJECTS.contains(it.projectId) }.take(maxCount)
            val results = runTests(testCandidates, resolvedDeps, missingDeps)
            val compatible = results.count { it?.output?.success == true }
            val incompatible = results.count { it?.output?.success == false }
            val errored = results.count { it?.errors == true }
            val failed = results.count { it == null }

            LOGGER.info("==== Test results summary ====")
            LOGGER.info("{} Compatible:\t\t\t{}", ICON_CHECK, compatible)
            LOGGER.info("{} Incompatible:\t\t\t{}", ICON_X, incompatible)
            LOGGER.info("{} Errored:\t\t\t\t{}", ICON_WARN, errored)
            LOGGER.info("{} Failed:\t\t\t\t{}", ICON_EXCLAMATION, failed)
            LOGGER.info("==============================")
        }
    }

    private fun <T> List<T>.flattenRecursively(getChildren: (T) -> List<T>): List<T> {
        return flatMap { item ->
            listOf(item) + getChildren(item).flattenRecursively(getChildren)
        }
    }

    private suspend fun runTests(data: List<ProjectSearchResult>, dependencies: List<ResolvedProject>, missingDeps: List<String>): List<TransformResult?> {
        val transformerPath = setup.getTransformLibPath()
        val gameFiles = setup.installDependencies()

        val modsDir = gathererDir / "mods"
        val classPath = gameFiles.loaderFiles.toMutableList() + resolveMandatedLibraries(modsDir)
        val depsMap = dependencies.associate {
            it.version.projectId to it.dependencies
                .flattenRecursively { d -> d.dependencies }
                .filterNot { d -> EXCLUDED_PROJECTS.contains(d.version.projectId) }
        }

        val dispatcher = Dispatchers.IO.limitedParallelism(CONCURRENT_TESTS)
        val semaphore = Semaphore(CONCURRENT_TESTS)
        val candidates = data

        return coroutineScope {
            val results = candidates.map { proj ->
                async(dispatcher) {
                    semaphore.withPermit {
                        val version = modrinth.getVersion(proj.slug, proj.versionId)
                            ?: throw IllegalStateException("Missing version for ${proj.slug}")
                        val versionFile = modsDir / version.path

                        val depsFiles = depsMap[proj.projectId] ?: emptyList()
                        if (depsFiles.any { missingDeps.contains(it.version.projectId) }) {
                            LOGGER.error("Skipping test for ${proj.slug} due to missing deps")
                            return@withPermit null
                        }
                        val depsPaths = depsFiles.map { modsDir / it.version.path }
                        depsPaths.firstOrNull { it.notExists() }?.let { 
                            throw IllegalStateException("Dep path $it does not exist")
                        }

                        try {
                            val result = runGathererTransformer(
                                proj.slug,
                                transformerPath,
                                versionFile.parent.resolve("output").also {
                                    if (FORCE_RETAKE_TESTS) it.deleteRecursively()
                                    it.createDirectories()
                                },
                                listOf(versionFile) + depsPaths,
                                gameFiles.cleanFile,
                                classPath,
                                gameVersion
                            )
                            val output = result.output
                            LOGGER.info("{} Transformed project ${proj.slug}: ID ${output.primaryModid} Success: ${output.success}", if (output.success) "\u2705" else "\u274C")
                            return@withPermit result
                        } catch (e: Exception) {
                            LOGGER.error("Error during transforming ${proj.slug}", e)
                            return@withPermit null
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

        val dispatcher = Dispatchers.IO.limitedParallelism(CONCURRENT_DOWNLOADS)
        val semaphore = Semaphore(CONCURRENT_DOWNLOADS)

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
        val dispatcher = Dispatchers.IO.limitedParallelism(CONCURRENT_DOWNLOADS)
        val mutex = Mutex()
        var done = 0

        val semaphore = Semaphore(CONCURRENT_DOWNLOADS)
        val dependencies = Collections.synchronizedMap<String, List<String>>(mutableMapOf())
        try {
            runBlocking {
                val cmd = redisConnection.coroutines()
                val downloadQueue = candidates.filter { cmd.exists("modrinth:version:${it.versionId}") != 1L }

                if (downloadQueue.isNotEmpty()) {
                    LOGGER.info("{} Downloading {} mods", ICON_PACKAGE, downloadQueue.size)
                }

                try {
                    candidates
                        .forEach {
                            launch(dispatcher) {
                                semaphore.withPermit {
                                    try {
                                        val needsDownload = cmd.exists("modrinth:version:${it.versionId}") != 1L

                                        if (needsDownload) {
                                            LOGGER.info("{} Downloading {}", ICON_DOWNLOAD, it.slug)
                                        }
                                        val version = modrinth.getVersion(it.slug, it.versionId) ?: run {
                                            LOGGER.error("Failed to download mod {}", it.slug)
                                            return@withPermit
                                        }

                                        LOGGER.debug("Found {} dependencies for {}: {}", version.dependencies.size, it.projectId, version.dependencies)
                                        version.dependencies.filterNot(EXCLUDED_PROJECTS::contains)
                                            .takeIf(List<String>::isNotEmpty)
                                            ?.let { l -> dependencies[it.projectId] = l }

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
            .sortedWith { a, b -> exclusiveKeys.indexOf(a.projectId) - exclusiveKeys.indexOf(b.projectId) }
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
                    ?.filterNot { EXCLUDED_PROJECTS.contains(it.projectId) }
            }
        }
        val neoTasks = (0..9).map { i ->
            async(Dispatchers.IO) {
                modrinth.searchProjects(batch, batchOffset + i * batch, gameVersion, LOADER_NEOFORGE, null)
                    ?.filterNot { EXCLUDED_PROJECTS.contains(it.projectId) }
            }
        }
        val fabricResults = fabricTasks.awaitAll().filterNotNull().map(::processResults)
        val neoResults = neoTasks.awaitAll().filterNotNull().map(::processResults)

        val fabricKeys = fabricResults.flatMap { it.first }.toMutableList()
        val fabricProjectIDs = mutableMapOf<String, ProjectSearchResult>().also { map -> fabricResults.forEach { map += it.second } }

        val neoKeys = neoResults.flatMap { it.first }
        val neoProjectIDs = mutableMapOf<String, ProjectSearchResult>().also { map -> neoResults.forEach { map += it.second } }

        fabricKeys -= neoKeys
        val map = fabricProjectIDs.filterKeys { !neoProjectIDs.contains(it) }

        return@coroutineScope fabricKeys to map
    }

    private fun processResults(results: List<ProjectSearchResult>): Pair<List<String>, Map<String, ProjectSearchResult>> {
        val keys = results.map { it.projectId }
        val map = results.associateBy { it.projectId }
        return keys to map
    }

    private suspend fun resolveMandatedLibraries(dest: Path): List<Path> {
        val resolved = modrinth.resolveProjectVersion(MR_FFAPI_ID, gameVersion, LOADER_NEOFORGE)!!
        return listOf(dest / resolved.path)
    }

    private fun readCandidatesFile(count: Int, file: Path): List<ProjectSearchResult>? {
        return file.takeIf { it.exists() }?.readText()?.let { Json.decodeFromString<List<ProjectSearchResult>>(it) }
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
    
    // TODO
    @OptIn(ExperimentalSerializationApi::class)
    private fun runGathererTransformer(slug: String, transformerPath: Path, workDir: Path, sources: List<Path>, cleanPath: Path, classPath: List<Path>, gameVersion: String): TransformResult {
        val baseArgs = listOf(
            "java",
            "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
            "-jar", transformerPath.absolutePathString(),
            "--clean", cleanPath.absolutePathString(),
            "--game-version", gameVersion,
            "--work-dir", workDir.absolutePathString(),
        )
        val sourceArgs = sources.flatMap { listOf("--source", it.absolutePathString()) }
        val classPathArgs = classPath.flatMap { listOf("--classpath", it.absolutePathString()) }
    
        val output = workDir / "output.json"
        var errors: Boolean? = null
        if (!output.exists()) {
            LOGGER.info("{} Testing {}", ICON_TEST, slug)
            val outputLog = workDir / "output.txt"
            val errorLog = workDir / "errors.txt"
    
            val process = ProcessBuilder(baseArgs + sourceArgs + classPathArgs)
                .directory(workDir.toFile())
                .redirectOutput(outputLog.toFile())
                .redirectError(errorLog.toFile())
                .start()
                .apply { waitFor(60, TimeUnit.MINUTES) }
            
            stripAnsiCodes(outputLog)
            stripAnsiCodes(errorLog)

            if (errorLog.readText().isNotEmpty()) {
                LOGGER.error("{} Got errors while transforming {}", ICON_WARN, slug)
                errors = true
            }
    
            if (process.exitValue() != 0) {
                throw IllegalStateException("Failed to run transformations, see log for details")
            }
        }
    
        val parsed: TransformLibOutput = output.inputStream().use(Json::decodeFromStream)
    
        return TransformResult(parsed, errors)
    }

    private fun stripAnsiCodes(file: Path) {
        val ansiRegex = Regex("\u001B\\[[;\\d]*m")
        val cleaned = file.readText().replace(ansiRegex, "")
        file.writeText(cleaned)
    }
    
    data class TransformResult(val output: TransformLibOutput, val errors: Boolean?)
}