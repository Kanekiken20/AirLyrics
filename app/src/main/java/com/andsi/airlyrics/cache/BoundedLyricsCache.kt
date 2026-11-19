package com.andsi.airlyrics.cache

import android.util.LruCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class CachedLyricLine(
    val timestampMs: Long,
    val text: String
)

data class CachedLyricsEntry(
    val trackKey: String,
    val title: String,
    val artist: String,
    val lines: List<CachedLyricLine>,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    fun estimatedWeightKb(): Int {
        val charBytes = title.length * 2 + artist.length * 2 +
            lines.sumOf { it.text.length * 2 + 16 }
        return ((charBytes + 1023) / 1024).coerceAtLeast(1)
    }
}

/**
 * Two-tier LRU lyrics cache:
 * - L1: Byte-weighted in-memory LruCache (default 2048 KB).
 * - L2: Disk directory with atomic writes, corruption recovery, and deterministic LRU pruning.
 */
class BoundedLyricsCache(
    private val cacheDir: File,
    maxMemoryKb: Int = 2048,
    private val maxDiskBytes: Long = 16L * 1024L * 1024L,
    private val maxDiskFiles: Int = 250,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val FILE_EXT = ".lyrics.json"

        fun buildTrackKey(artist: String, title: String): String {
            val raw = "${artist.trim().lowercase()}|${title.trim().lowercase()}"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(32)
        }
    }

    private val l1Cache = object : LruCache<String, CachedLyricsEntry>(maxMemoryKb) {
        override fun sizeOf(key: String, value: CachedLyricsEntry): Int = value.estimatedWeightKb()
    }

    private val pruneMutex = Mutex()

    suspend fun get(trackKey: String): CachedLyricsEntry? {
        l1Cache.get(trackKey)?.let { return it }

        val file = fileForKey(trackKey)
        val rawJson = withContext(ioDispatcher) {
            AtomicFileUtils.readTextSafely(file)
        } ?: return null

        val parsed = withContext(cpuDispatcher) {
            deserialize(rawJson)
        }

        if (parsed == null) {
            // Quarantine/evict corrupted disk entry cleanly
            withContext(ioDispatcher) { AtomicFileUtils.deleteSafely(file) }
            return null
        }

        withContext(ioDispatcher) {
            file.setLastModified(System.currentTimeMillis())
        }
        l1Cache.put(trackKey, parsed)
        return parsed
    }

    suspend fun put(entry: CachedLyricsEntry) {
        l1Cache.put(entry.trackKey, entry)

        val serialized = withContext(cpuDispatcher) {
            serialize(entry)
        }
        val file = fileForKey(entry.trackKey)
        withContext(ioDispatcher) {
            AtomicFileUtils.writeTextAtomically(file, serialized)
            pruneDiskIfNeeded()
        }
    }

    fun memorySizeKb(): Int = l1Cache.size()

    fun clearMemory() {
        l1Cache.evictAll()
    }

    suspend fun clearAll() {
        clearMemory()
        withContext(ioDispatcher) {
            pruneMutex.withLock {
                cacheDir.listFiles { f -> f.name.endsWith(FILE_EXT) }?.forEach { it.delete() }
            }
        }
    }

    internal suspend fun pruneDiskIfNeeded() {
        pruneMutex.withLock {
            val files = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(FILE_EXT) }
                ?: return

            var totalBytes = files.sumOf { it.length() }
            var remainingCount = files.size
            if (totalBytes <= maxDiskBytes && remainingCount <= maxDiskFiles) return

            // Oldest lastModified first (LRU eviction)
            val sortedOldestFirst = files.sortedBy { it.lastModified() }
            for (victim in sortedOldestFirst) {
                if (totalBytes <= maxDiskBytes && remainingCount <= maxDiskFiles) break
                val len = victim.length()
                if (victim.delete()) {
                    totalBytes -= len
                    remainingCount -= 1
                }
            }
        }
    }

    private fun fileForKey(trackKey: String): File = File(cacheDir, "$trackKey$FILE_EXT")

    private fun serialize(entry: CachedLyricsEntry): String {
        val root = JSONObject().apply {
            put("v", SCHEMA_VERSION)
            put("trackKey", entry.trackKey)
            put("title", entry.title)
            put("artist", entry.artist)
            put("updatedAtMs", entry.updatedAtMs)
            val arr = JSONArray()
            for (line in entry.lines) {
                arr.put(JSONObject().apply {
                    put("t", line.timestampMs)
                    put("l", line.text)
                })
            }
            put("lines", arr)
        }
        return root.toString()
    }

    private fun deserialize(raw: String): CachedLyricsEntry? {
        return try {
            val root = JSONObject(raw)
            if (root.optInt("v", -1) != SCHEMA_VERSION) return null
            val trackKey = root.getString("trackKey")
            val title = root.optString("title", "")
            val artist = root.optString("artist", "")
            val updatedAtMs = root.optLong("updatedAtMs", 0L)
            val arr = root.getJSONArray("lines")
            val lines = ArrayList<CachedLyricLine>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                lines.add(
                    CachedLyricLine(
                        timestampMs = obj.getLong("t"),
                        text = obj.getString("l")
                    )
                )
            }
            CachedLyricsEntry(
                trackKey = trackKey,
                title = title,
                artist = artist,
                lines = lines,
                updatedAtMs = updatedAtMs
            )
        } catch (_: JSONException) {
            null
        }
    }
}
