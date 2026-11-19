package com.andsi.airlyrics.cache

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe, crash-safe, and Windows-JVM-safe atomic file persistence helper.
 */
object AtomicFileUtils {

    private val pathMutexes = ConcurrentHashMap<String, Mutex>()

    private fun mutexFor(target: File): Mutex =
        pathMutexes.computeIfAbsent(target.absolutePath) { Mutex() }

    /**
     * Serializes writes per target path, flushes + fsyncs the descriptor, closes the stream
     * prior to move (required on Windows), and atomically replaces the target file.
     */
    suspend fun writeTextAtomically(target: File, content: String) {
        writeBytesAtomically(target, content.toByteArray(StandardCharsets.UTF_8))
    }

    suspend fun writeBytesAtomically(target: File, bytes: ByteArray) {
        mutexFor(target).withLock {
            val parent = target.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }

            val tempFile = File(parent, "${target.name}.${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(tempFile).use { fos ->
                    fos.write(bytes)
                    fos.flush()
                    try {
                        fos.fd.sync()
                    } catch (_: IOException) {
                        // Some virtual filesystems / test mocks do not support fsync
                    }
                }
                safeFinishWrite(tempFile, target)
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
    }

    suspend fun readTextSafely(target: File): String? {
        return mutexFor(target).withLock {
            if (!target.exists() || !target.isFile) return@withLock null
            try {
                target.readText(StandardCharsets.UTF_8)
            } catch (_: IOException) {
                null
            }
        }
    }

    suspend fun deleteSafely(target: File) {
        mutexFor(target).withLock {
            if (target.exists()) {
                target.delete()
            }
        }
    }

    fun safeFinishWrite(tempFile: File, targetFile: File) {
        val source = tempFile.toPath()
        val destination = targetFile.toPath()
        try {
            Files.move(
                source,
                destination,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source,
                destination,
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }
}
