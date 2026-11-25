package com.andsi.airlyrics.lyrics.providers

import android.content.Context
import android.util.Log
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.lyrics.LyricsLookupCancellationToken
import com.andsi.airlyrics.lyrics.LyricsLookupErrorType
import com.andsi.airlyrics.lyrics.LyricsProviderResult
import com.andsi.airlyrics.lyrics.PlainLyricsProvider
import com.andsi.airlyrics.lyrics.PlainLyricsSearchRequest
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs

data class LrclibPlainLyricsResult(
    val plainSource: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val lrc: String,
    val translatedLrc: String?,
    val untimedText: String? = null
)

object LrclibPlainLyricsProvider : PlainLyricsProvider {
    override val id: String = "lrclib"
    override val name: String = "LRCLIB"

    private const val CONNECT_TIMEOUT_MS = 2_800
    private const val READ_TIMEOUT_MS = 3_500
    private val fastLookupExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "airlyrics-lrclib-fast").apply { isDaemon = true }
    }

    override fun fetch(request: PlainLyricsSearchRequest): Result<LyricsProviderResult?> {
        return fetchBestPlainLyrics(
            title = request.title,
            artist = request.artist,
            album = request.album,
            durationMs = request.durationMs,
            cancellationToken = request.cancellationToken,
            context = request.context
        ).map(::toProviderResult)
    }

    fun fetchBestPlainLyrics(
        title: String,
        artist: String,
        album: String = "",
        durationMs: Long,
        cancellationToken: LyricsLookupCancellationToken? = null,
        context: Context? = null
    ): Result<LrclibPlainLyricsResult?> {
        return runCatching {
            cancellationToken?.throwIfCancellationRequested()

            // 1. Instant 0ms normalized memory / disk cache hit before any HTTP/FFI overhead.
            LyricsFastCache.get(context, title, artist, durationMs)?.let { cached ->
                return@runCatching cached
            }

            if (BuildConfig.DEBUG) {
                Log.d(
                    "AirLyricsLyrics",
                    "source=lrclib stage=lookup_input title=${title.debugLogValue()} " +
                        "artist=${artist.debugLogValue()} album=${album.debugLogValue()} " +
                        "durationMs=$durationMs",
                )
            }

            // 2. Fast concurrent Kotlin HTTP race (/api/get raw + cleaned + /api/search).
            val fastHit = runCatching {
                fetchConcurrentFastLrclib(
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    cancellationToken = cancellationToken
                )
            }.getOrNull()
            if (fastHit != null) {
                LyricsFastCache.put(context, title, artist, durationMs, fastHit)
                return@runCatching fastHit
            }

            cancellationToken?.throwIfCancellationRequested()

            // 3. Fallback to native Rust implementation for deeper fuzzy/CJK/offline handling.
            val jsonText = withNativeLyricsCancellation(
                token = cancellationToken
            ) { lookupId ->
                LrclibLyricsNative.fetchBestLyricsJson(
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = durationMs,
                    lookupId = lookupId
                )
            }
            cancellationToken?.throwIfCancellationRequested()

            mapNativePlainLyricsResultJson(
                jsonText = jsonText,
                fallbackTitle = title,
                fallbackArtist = artist,
                fallbackAlbum = album,
                fallbackDurationMs = durationMs,
            )?.also { nativeHit ->
                LyricsFastCache.put(context, title, artist, durationMs, nativeHit)
            }
        }.recoverNativeLoadFailure(
            providerId = id,
            providerName = name
        )
    }

    private fun fetchConcurrentFastLrclib(
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        cancellationToken: LyricsLookupCancellationToken?
    ): LrclibPlainLyricsResult? {
        val rawTitle = title.trim()
        if (rawTitle.isEmpty()) return null
        val rawArtist = artist.trim()
        val normTitle = LyricsMetadataNormalizer.cleanTitle(rawTitle).ifEmpty { rawTitle }
        val normArtist = LyricsMetadataNormalizer.cleanArtist(rawArtist).ifEmpty { rawArtist }
        val durationSec = (durationMs / 1_000L).takeIf { it > 0L }

        val urls = linkedSetOf<String>()
        urls += buildGetUrl(rawTitle, rawArtist, album.trim(), durationSec)
        if (!normTitle.equals(rawTitle, ignoreCase = true) ||
            !normArtist.equals(rawArtist, ignoreCase = true)
        ) {
            urls += buildGetUrl(normTitle, normArtist, "", durationSec)
        }
        urls += buildSearchUrl(normTitle, normArtist)

        val activeConnections = CopyOnWriteArrayList<HttpURLConnection>()
        val futures = mutableListOf<Future<LrclibPlainLyricsResult?>>()
        val completion = ExecutorCompletionService<LrclibPlainLyricsResult?>(fastLookupExecutor)
        val cancelRegistration = cancellationToken?.invokeOnCancellation {
            futures.forEach { it.cancel(true) }
            activeConnections.forEach { conn -> runCatching { conn.disconnect() } }
        }

        try {
            urls.forEach { urlString ->
                val callable = Callable<LrclibPlainLyricsResult?> {
                    cancellationToken?.throwIfCancellationRequested()
                    val isSearch = urlString.contains("/api/search")
                    val body = executeFastHttpGet(urlString, activeConnections, cancellationToken)
                        ?: return@Callable null
                    if (isSearch) {
                        parseFastSearchJson(body, normTitle, normArtist, durationMs)
                    } else {
                        parseFastTrackJson(JSONObject(body), rawTitle, rawArtist, album, durationMs)
                    }
                }
                futures += completion.submit(callable)
            }

            var untimedFallback: LrclibPlainLyricsResult? = null
            repeat(futures.size) {
                cancellationToken?.throwIfCancellationRequested()
                val candidate = runCatching { completion.take().get() }.getOrNull() ?: return@repeat
                if (candidate.lrc.isNotBlank()) {
                    return candidate
                }
                if (untimedFallback == null && !candidate.untimedText.isNullOrBlank()) {
                    untimedFallback = candidate
                }
            }
            return untimedFallback
        } finally {
            cancelRegistration?.dispose()
            futures.forEach { it.cancel(true) }
            activeConnections.forEach { conn -> runCatching { conn.disconnect() } }
        }
    }

    private fun executeFastHttpGet(
        urlString: String,
        activeConnections: CopyOnWriteArrayList<HttpURLConnection>,
        cancellationToken: LyricsLookupCancellationToken?
    ): String? {
        cancellationToken?.throwIfCancellationRequested()
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "AirLyrics/1.3 (Android)")
        }
        activeConnections += conn
        return try {
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            activeConnections.remove(conn)
            runCatching { conn.disconnect() }
        }
    }

    private fun buildGetUrl(title: String, artist: String, album: String, durationSec: Long?): String {
        val params = mutableListOf(
            "track_name=${enc(title)}",
            "artist_name=${enc(artist)}"
        )
        if (album.isNotEmpty()) params += "album_name=${enc(album)}"
        if (durationSec != null) params += "duration=$durationSec"
        return "https://lrclib.net/api/get?${params.joinToString("&")}"
    }

    private fun buildSearchUrl(title: String, artist: String): String {
        val params = mutableListOf("track_name=${enc(title)}")
        if (artist.isNotEmpty()) params += "artist_name=${enc(artist)}"
        return "https://lrclib.net/api/search?${params.joinToString("&")}"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun parseFastTrackJson(
        obj: JSONObject,
        fallbackTitle: String,
        fallbackArtist: String,
        fallbackAlbum: String,
        fallbackDurationMs: Long
    ): LrclibPlainLyricsResult? {
        val synced = obj.optString("syncedLyrics", "").trim()
        val plain = obj.optString("plainLyrics", "").trim()
        if (synced.isEmpty() && plain.isEmpty()) return null
        val embedded = if (synced.isNotEmpty()) splitLrclibEmbeddedTranslations(synced) else null
        val durationSec = obj.optDouble("duration", -1.0)
        val resolvedDuration = if (durationSec > 0.0) (durationSec * 1_000.0).toLong() else fallbackDurationMs
        return LrclibPlainLyricsResult(
            plainSource = "lrclib-fast",
            trackId = obj.opt("id")?.toString().orEmpty(),
            title = obj.optString("trackName", "").ifBlank { fallbackTitle },
            artist = obj.optString("artistName", "").ifBlank { fallbackArtist },
            album = obj.optString("albumName", "").ifBlank { fallbackAlbum },
            durationMs = resolvedDuration,
            lrc = embedded?.originalLrc.orEmpty(),
            translatedLrc = embedded?.translatedLrc,
            untimedText = if (synced.isEmpty()) plain else null
        )
    }

    private fun parseFastSearchJson(
        body: String,
        normTitle: String,
        normArtist: String,
        durationMs: Long
    ): LrclibPlainLyricsResult? {
        val array = JSONArray(body)
        if (array.length() == 0) return null
        var bestSynced: LrclibPlainLyricsResult? = null
        var bestUntimed: LrclibPlainLyricsResult? = null
        var bestDelta = Long.MAX_VALUE

        for (i in 0 until minOf(array.length(), 10)) {
            val item = array.optJSONObject(i) ?: continue
            val candidate = parseFastTrackJson(item, normTitle, normArtist, "", durationMs) ?: continue
            val delta = if (durationMs > 0L && candidate.durationMs > 0L) {
                abs(candidate.durationMs - durationMs)
            } else {
                0L
            }
            if (durationMs > 0L && candidate.durationMs > 0L && delta > 7_000L) continue
            if (candidate.lrc.isNotBlank() && delta < bestDelta) {
                bestDelta = delta
                bestSynced = candidate
            } else if (bestUntimed == null && !candidate.untimedText.isNullOrBlank()) {
                bestUntimed = candidate
            }
        }
        return bestSynced ?: bestUntimed
    }

    internal fun mapNativePlainLyricsResultJson(
        jsonText: String,
        fallbackTitle: String,
        fallbackArtist: String,
        fallbackAlbum: String,
        fallbackDurationMs: Long,
    ): LrclibPlainLyricsResult? {
        val nativeResult = NativePlainLyricsResultParser.parse(
            jsonText = jsonText,
            defaultSource = "lrclib-rust",
            fallbackTitle = fallbackTitle,
            fallbackArtist = fallbackArtist,
            fallbackAlbum = fallbackAlbum,
            fallbackDurationMs = fallbackDurationMs,
        )
        if (!nativeResult.ok) {
            if (nativeResult.errorType == LyricsLookupErrorType.NotFound) {
                return null
            }

            throw nativeResult.toNativePlainLyricsLookupException(
                providerId = id,
                providerName = name,
                defaultMessage = "LRCLIB lookup failed",
            )
        }

        val lrc = nativeResult.primaryPlainLrc()
        if (lrc.isBlank() && nativeResult.untimedText == null) return null
        val lyricsTracks = nativeResult.translatedLrc?.let { translatedLrc ->
            LrclibEmbeddedLyricsTracks(
                originalLrc = lrc,
                translatedLrc = translatedLrc
            )
        } ?: splitLrclibEmbeddedTranslations(lrc)

        return LrclibPlainLyricsResult(
            plainSource = nativeResult.plainSource,
            trackId = nativeResult.id,
            title = nativeResult.title,
            artist = nativeResult.artist,
            album = nativeResult.album,
            durationMs = nativeResult.durationMs,
            lrc = lyricsTracks.originalLrc,
            translatedLrc = lyricsTracks.translatedLrc,
            untimedText = nativeResult.untimedText,
        )
    }

    internal fun toProviderResult(result: LrclibPlainLyricsResult?): LyricsProviderResult? {
        return result?.let {
            LyricsProviderResult(
                plainProviderId = id,
                plainProviderName = name,
                plainLrc = it.lrc,
                translatedLrc = it.translatedLrc,
                matchedTitle = it.title,
                matchedArtist = it.artist,
                matchedAlbum = it.album,
                matchedDurationMs = it.durationMs,
                untimedLyrics = it.untimedText?.let { text -> com.andsi.airlyrics.lyrics.UntimedLyrics(text) },
            )
        }
    }
}

private fun String.debugLogValue(): String =
    replace(Regex("[\\r\\n\\t]+"), " ").trim().take(200)
