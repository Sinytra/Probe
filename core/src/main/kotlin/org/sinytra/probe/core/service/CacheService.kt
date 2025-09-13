package org.sinytra.probe.core.service

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines
import kotlinx.serialization.json.Json

@OptIn(ExperimentalLettuceCoroutinesApi::class)
class CacheService(
    redis: StatefulRedisConnection<String, String>
) {
    val cmd = redis.coroutines()

    suspend inline fun get(key: String): String? =
        cmd.get(key)

    suspend inline fun set(key: String, value: String) =
        cmd.set(key, value)

    suspend inline fun del(key: String) =
        cmd.del(key)

    suspend inline fun exists(key: String): Boolean =
        cmd.exists(key) == 1L

    suspend inline fun <reified T> getObject(key: String): T? =
        cmd.get(key)?.let { Json.decodeFromString<T>(it) }

    suspend inline fun <reified T> setObject(key: String, value: T) {
        cmd.set(key, Json.encodeToString(value))
    }
}