package com.example.plex_tv_app.arr

import com.example.plex_tv_app.settings.AppSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What Radarr and Sonarr are tracking, shared by Discover and "More like this", kept 5 minutes. */
object ArrTracked {
    private var keys: Set<String>? = null
    private var at = 0L
    private val lock = Mutex()

    suspend fun get(settings: AppSettings): Set<String> = lock.withLock {
        val now = System.currentTimeMillis()
        keys?.takeIf { now - at < 5 * 60_000 }?.let { return@withLock it }
        val fresh = coroutineScope {
            val movies = async { settings.arr(ArrKind.Movie)?.let { runCatching { it.trackedKeys() }.getOrNull() }.orEmpty() }
            val shows = async { settings.arr(ArrKind.Show)?.let { runCatching { it.trackedKeys() }.getOrNull() }.orEmpty() }
            movies.await() + shows.await()
        }
        keys = fresh
        at = now
        fresh
    }

    /** After adding something, so the next look is fresh. */
    fun forget() {
        keys = null
    }
}
