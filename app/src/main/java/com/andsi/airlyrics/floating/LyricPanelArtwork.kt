package com.andsi.airlyrics.floating

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Handler
import com.andsi.airlyrics.media.CurrentMediaReader
import com.andsi.airlyrics.media.MediaArtwork
import com.andsi.airlyrics.media.mediaArtwork
import com.andsi.airlyrics.media.model.CurrentMediaInfo
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Bounded local decoding cached by track/artwork revision. The existing service owns its lifetime. */
internal class LyricPanelArtwork(
    private val context: Context,
    private val handler: Handler,
    private val onChanged: () -> Unit,
    private val readArtwork: ((CurrentMediaInfo) -> MediaArtwork?)? = null,
    private val executorFactory: () -> java.util.concurrent.ExecutorService = {
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "AirLyrics-PanelArtwork").apply { isDaemon = true }
        }
    }
) : AutoCloseable {
    private var worker: java.util.concurrent.ExecutorService? = null
    private var pending: Future<*>? = null
    private var track: PlaybackLyricsKey? = null
    private var generation = 0L
    private var closed = false
    private var refreshQueued = false
    // Accessed by the single worker only; retain colors/keys, never a player's Bitmap.
    private var decodedKey: Pair<PlaybackLyricsKey, String?>? = null
    private var decodedColors = LyricPanelPalette.fallback
    var colors: LyricPanelColors = LyricPanelPalette.fallback
        private set

    fun update(media: CurrentMediaInfo, refreshMetadata: Boolean = false) {
        val key = media.playbackLyricsKey()
        if (closed) return
        if (key != track) {
            clear()
            track = key
        } else {
            if (!refreshMetadata) return
            if (pending?.isDone == false) {
                refreshQueued = true
                return
            }
        }
        refreshQueued = false
        val request = ++generation
        val executor = worker ?: executorFactory().also { worker = it }
        pending = executor.submit {
            val palette = runCatching {
                val artwork = if (readArtwork == null) readVerifiedArtwork(media) else readArtwork.invoke(media)
                val revision = key to artwork?.refreshKey
                if (revision == decodedKey) return@runCatching decodedColors
                val colors = when (artwork) {
                    is MediaArtwork.BitmapImage -> LyricPanelPalette.fromBitmap(artwork.bitmap)
                    is MediaArtwork.UriImage -> {
                        val bitmap = decodeLocal(artwork.uri)
                        try { LyricPanelPalette.fromBitmap(bitmap) } finally { bitmap?.recycle() }
                    }
                    null -> LyricPanelPalette.fallback
                }
                decodedKey = revision
                decodedColors = colors
                colors
            }.getOrDefault(LyricPanelPalette.fallback)
            handler.post {
                if (!closed && generation == request && track == key) {
                    pending = null
                    if (colors != palette) {
                        colors = palette
                        onChanged()
                    }
                    if (refreshQueued) update(media, refreshMetadata = true)
                }
            }
        }
    }

    private fun readVerifiedArtwork(media: CurrentMediaInfo): MediaArtwork? {
        val controller = CurrentMediaReader.selectedController(
            CurrentMediaReader.getActiveControllers(context), media.sourcePackage
        ) ?: return null
        val current = CurrentMediaReader.currentMediaFromController(controller)
        if (current?.playbackLyricsKey() != media.playbackLyricsKey()) return null
        return controller.metadata.mediaArtwork()
    }

    private fun decodeLocal(uri: android.net.Uri): Bitmap? {
        if (uri.scheme !in setOf("content", "file", "android.resource")) return null
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val scale = 32f / maxOf(info.size.width, info.size.height).coerceAtLeast(1)
                decoder.setTargetSize((info.size.width * scale).toInt().coerceIn(1, 32),
                    (info.size.height * scale).toInt().coerceIn(1, 32))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 32) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    fun clear() {
        generation++
        pending?.cancel(true)
        pending = null
        track = null
        refreshQueued = false
        colors = LyricPanelPalette.fallback
    }

    override fun close() {
        closed = true
        clear()
        worker?.shutdownNow()
        worker = null
    }
}
