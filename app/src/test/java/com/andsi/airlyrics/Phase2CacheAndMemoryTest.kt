package com.andsi.airlyrics

import com.andsi.airlyrics.cache.ArtworkMemoryPipeline
import com.andsi.airlyrics.cache.AtomicFileUtils
import com.andsi.airlyrics.cache.BoundedLyricsCache
import com.andsi.airlyrics.cache.CachedLyricLine
import com.andsi.airlyrics.cache.CachedLyricsEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class Phase2CacheAndMemoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun inSampleSize_downsamplesLargeAlbumArtToSafeBinderCeiling() {
        val sample1080 = ArtworkMemoryPipeline.calculateInSampleSize(1080, 1080, 128, 128)
        assertEquals(8, sample1080) // 1080 / 8 = 135 -> closest power of 2 >= 128

        val sample2048 = ArtworkMemoryPipeline.calculateInSampleSize(2048, 2048, 128, 128)
        assertEquals(16, sample2048) // 2048 / 16 = 128 -> exact power of 2

        val sampleSmall = ArtworkMemoryPipeline.calculateInSampleSize(128, 128, 128, 128)
        assertEquals(1, sampleSmall)
    }

    @Test
    fun atomicFileUtils_concurrentWritesOnSamePathNeverCorruptOrFailOnWindows() = runBlocking {
        val target = File(tempFolder.root, "concurrent_track.lyrics.json")

        val jobs = (1..16).map { idx ->
            async(Dispatchers.IO) {
                val payload = """{"v":1,"trackKey":"k","title":"Song $idx","artist":"A","lines":[]}"""
                AtomicFileUtils.writeTextAtomically(target, payload)
            }
        }
        jobs.awaitAll()

        val finalContent = AtomicFileUtils.readTextSafely(target)
        assertNotNull(finalContent)
        assertTrue(finalContent!!.startsWith("""{"v":1"""))
        assertTrue(finalContent.endsWith("""}"""))

        // Ensure no leftover .tmp files remain
        val leftovers = tempFolder.root.listFiles { f -> f.name.endsWith(".tmp") }.orEmpty()
        assertEquals(0, leftovers.size)
    }

    @Test
    fun boundedLyricsCache_evictsCorruptFilesAndPrunesOldestDiskEntries() = runBlocking {
        val cacheDir = tempFolder.newFolder("lyrics_cache")
        val cache = BoundedLyricsCache(
            cacheDir = cacheDir,
            maxMemoryKb = 8,
            maxDiskBytes = 1024L * 1024L,
            maxDiskFiles = 3,
            ioDispatcher = Dispatchers.IO,
            cpuDispatcher = Dispatchers.Default
        )

        // Write 5 entries -> disk must prune oldest 2 leaving 3
        for (i in 1..5) {
            val key = "track_$i"
            cache.put(
                CachedLyricsEntry(
                    trackKey = key,
                    title = "Title $i",
                    artist = "Artist",
                    lines = listOf(CachedLyricLine(1000L * i, "Line $i"))
                )
            )
            Thread.sleep(15) // Ensure distinct file lastModified timestamps
        }

        val diskFiles = cacheDir.listFiles { f -> f.name.endsWith(".lyrics.json") }.orEmpty()
        assertEquals(3, diskFiles.size)

        // Inject corrupt file and verify recovery deletes it
        val corruptKey = "corrupt_track"
        val corruptFile = File(cacheDir, "$corruptKey.lyrics.json")
        corruptFile.writeText("{not_valid_json")

        cache.clearMemory()
        val recovered = cache.get(corruptKey)
        assertNull(recovered)
        assertFalse("Corrupt cache file must be quarantined/deleted", corruptFile.exists())
    }
}
