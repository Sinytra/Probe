package org.sinytra.probe.core.service

class StatsService(
    private val cache: CacheService
) {
    companion object {
        private const val STATS_HASH_KEY = "query_stats"
    }

    suspend fun addModRequestedStat(slug: String) =
        cache.hashIncrBy(STATS_HASH_KEY, slug, 1)

    suspend fun getModRequestedStats(limit: Int): Map<String, Long> =
        cache.hashGetStats(STATS_HASH_KEY)
            .sortedByDescending { it.second }
            .take(limit)
            .toMap()
}