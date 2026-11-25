package com.andsi.airlyrics.lyrics.providers

import android.content.Context
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Ultra-fast normalized memory + JSON disk cache for LRCLIB lookups.
 * Eliminates repeated network waits when replaying or seeking tracks even with noisy metadata tags.
 */
internal object LyricsFastCache {
    private const val MAX_MEM_ENTRIES = 64
    private const val CACHE_DIR_NAME = "airlyrics_fast_lrc"
    private const val TTL_MS = 14L * 24L * 60L * 60L * 1000L

    private val memoryCache = LruCache<String, LrclibPlainLyricsResult>(MAX_MEM_ENTRIES)

    fun get(context: Context?, title: String, artist: String, durationMs: Long): LrclibPlainLyricsResult? {
        val key = LyricsMetadataNormalizer.cacheKey(title, artist, durationMs)
        synchronized(memoryCache) {
            memoryCache.get(key)?.let { return it }
        }
        val appContext = context?.applicationContext ?: return null
        val file = cacheFile(appContext, key)
        if (!file.exists()) return null
        val ageMs = System.currentTimeMillis() - file.lastModified()
        if (ageMs > TTL_MS) {
            runCatching { file.delete() }
            return null
        }
        return runCatching {
            val json = JSONObject(file.readText())
            val lrc = json.optString("lrc", "")
            val untimed = json.optString("untimedText", "").takeIf { it.isNotBlank() }
            if (lrc.isBlank() && untimed == null) return@runCatching null
            LrclibPlainLyricsResult(
                plainSource = json.optString("plainSource", "lrclib-fast-cache"),
                trackId = json.optString("trackId", ""),
                title = json.optString("title", title),
                artist = json.optString("artist", artist),
                album = json.optString("album", ""),
                durationMs = json.optLong("durationMs", durationMs),
                lrc = lrc,
                translatedLrc = json.optString("translatedLrc", "").takeIf { it.isNotBlank() },
                untimedText = untimed
            ).also { parsed ->
                synchronized(memoryCache) {
                    memoryCache.put(key, parsed)
                }
            }
        }.getOrNull()
    }

    fun put(context: Context?, queryTitle: String, queryArtist: String, queryDurationMs: Long, result: LrclibPlainLyricsResult) {
        if (result.lrc.isBlank() && result.untimedText.isNullOrBlank()) return
        val key = LyricsMetadataNormalizer.cacheKey(queryTitle, queryArtist, queryDurationMs)
        synchronized(memoryCache) {
            memoryCache.put(key, result)
        }
        val appContext = context?.applicationContext ?: return
        runCatching {
            val file = cacheFile(appContext, key)
            file.parentFile?.mkdirs()
            val payload = JSONObject().apply {
                put("plainSource", result.plainSource)
                put("trackId", result.trackId)
                put("title", result.title)
                put("artist", result.artist)
                put("album", result.album)
                put("durationMs", result.durationMs)
                put("lrc", result.lrc)
                put("translatedLrc", result.translatedLrc.orEmpty())
                put("untimedText", result.untimedText.orEmpty())
            }
            file.writeText(payload.toString())
        }
    }

    private fun cacheFile(context: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
        return File(File(context.cacheDir, CACHE_DIR_NAME), "$digest.json")
    }
}
