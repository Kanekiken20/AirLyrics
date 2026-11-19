package com.andsi.airlyrics.util

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Ensures atomic file writes correctly overwrite existing files across all host platforms
 * (including Windows JVM during unit tests, where AtomicFile.renameTo() fails if target exists).
 */
fun AtomicFile.safeFinishWrite(stream: FileOutputStream) {
    finishWrite(stream)
    val newFile = File(baseFile.path + ".new")
    if (newFile.exists()) {
        try {
            Files.move(
                newFile.toPath(),
                baseFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Throwable) {
            newFile.copyTo(baseFile, overwrite = true)
            newFile.delete()
        }
    }
}
