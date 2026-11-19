package com.andsi.airlyrics.cache

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.LruCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Memory-bounded, off-main-thread bitmap decoder & scaler for Lock Screen / AOD RemoteViews.
 * Enforces a strict 128x128 px ceiling so notification IPC never approaches Android's 1MB Binder limit.
 */
class ArtworkMemoryPipeline(
    maxCacheKilobytes: Int = defaultCacheSizeKb(),
    private val targetSizePx: Int = DEFAULT_NOTIFICATION_ART_PX,
    private val cornerRadiusPx: Float = 24f,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    companion object {
        const val DEFAULT_NOTIFICATION_ART_PX = 128
        private const val MAX_CACHE_KB = 4 * 1024 // 4 MB hard cap

        fun defaultCacheSizeKb(): Int {
            val maxMemKb = (Runtime.getRuntime().maxMemory() / 1024L).toInt()
            return min(maxMemKb / 16, MAX_CACHE_KB).coerceAtLeast(512)
        }

        internal fun calculateInSampleSize(
            rawWidth: Int,
            rawHeight: Int,
            reqWidth: Int,
            reqHeight: Int
        ): Int {
            var inSampleSize = 1
            if (rawHeight > reqHeight || rawWidth > reqWidth) {
                val halfHeight = rawHeight / 2
                val halfWidth = rawWidth / 2
                while ((halfHeight / inSampleSize) >= reqHeight &&
                    (halfWidth / inSampleSize) >= reqWidth
                ) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize.coerceAtLeast(1)
        }
    }

    private val memoryCache = object : LruCache<String, Bitmap>(maxCacheKilobytes) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return (bitmap.allocationByteCount / 1024).coerceAtLeast(1)
        }
        // Intentionally do NOT call bitmap.recycle() on eviction: RemoteViews/NotificationManager
        // may still hold native pixel references across Binder on Android 8+.
    }

    private val inFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    fun getCached(cacheKey: String): Bitmap? = memoryCache.get(cacheKey)

    fun currentSizeKb(): Int = memoryCache.size()

    fun maxSizeKb(): Int = memoryCache.maxSize()

    fun clear() {
        memoryCache.evictAll()
    }

    /**
     * Decodes encoded image bytes strictly off the main thread using two-pass inSampleSize,
     * crops to a rounded square matching One UI 6 card geometry, and caches in L1 LRU.
     */
    suspend fun decodeAndCache(cacheKey: String, encodedBytes: ByteArray): Bitmap? {
        memoryCache.get(cacheKey)?.let { return it }

        return coroutineScope {
            val deferred = inFlight.computeIfAbsent(cacheKey) {
                async(decodeDispatcher) {
                    try {
                        val decoded = decodeDownsampled(encodedBytes, targetSizePx) ?: return@async null
                        val normalized = scaleAndRound(decoded, targetSizePx, cornerRadiusPx)
                        if (normalized !== decoded) {
                            decoded.recycle() // Safe: local unshared intermediate bitmap
                        }
                        memoryCache.put(cacheKey, normalized)
                        normalized
                    } finally {
                        inFlight.remove(cacheKey)
                    }
                }
            }
            deferred.await()
        }
    }

    /**
     * Downscales an existing MediaMetadata Bitmap off the main thread to keep Binder payloads <= 64KB.
     */
    suspend fun normalizeBitmapAndCache(cacheKey: String, source: Bitmap): Bitmap? {
        memoryCache.get(cacheKey)?.let { return it }

        return withContext(decodeDispatcher) {
            if (source.isRecycled) return@withContext null
            val normalized = scaleAndRound(source, targetSizePx, cornerRadiusPx)
            memoryCache.put(cacheKey, normalized)
            normalized
        }
    }

    private fun decodeDownsampled(bytes: ByteArray, reqSizePx: Int): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                boundsOpts.outWidth,
                boundsOpts.outHeight,
                reqSizePx,
                reqSizePx
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
    }

    private fun scaleAndRound(source: Bitmap, sizePx: Int, radiusPx: Float): Bitmap {
        val scaled = if (source.width == sizePx && source.height == sizePx) {
            source
        } else {
            Bitmap.createScaledBitmap(source, sizePx, sizePx, true)
        }

        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val path = Path().apply {
            addRoundRect(
                RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()),
                radiusPx,
                radiusPx,
                Path.Direction.CW
            )
        }
        canvas.clipPath(path)
        canvas.drawBitmap(scaled, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

        if (scaled !== source && scaled !== output) {
            scaled.recycle() // Safe: unshared temporary bitmap
        }
        return output
    }
}
