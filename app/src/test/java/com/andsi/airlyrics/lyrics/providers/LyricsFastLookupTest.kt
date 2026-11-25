package com.andsi.airlyrics.lyrics.providers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LyricsFastLookupTest {

    @Test
    fun metadataNormalizer_stripsRemixFeaturingAndLosslessTags() {
        assertEquals(
            "Midnight City",
            LyricsMetadataNormalizer.cleanTitle("Midnight City (Remastered 2024) [Lossless]")
        )
        assertEquals(
            "Blinding Lights",
            LyricsMetadataNormalizer.cleanTitle("Blinding Lights (feat. Rosalía) - Single Version")
        )
        assertEquals(
            "M83",
            LyricsMetadataNormalizer.cleanArtist("M83, Anthony Gonzalez & Friends")
        )
    }

    @Test
    fun fastCache_hitsNormalizedMetadataImmediatelyAcrossVariants() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val entry = LrclibPlainLyricsResult(
            plainSource = "lrclib-fast",
            trackId = "123",
            title = "Midnight City",
            artist = "M83",
            album = "Hurry Up, We're Dreaming",
            durationMs = 243_000L,
            lrc = "[00:10.00]Waiting in a car",
            translatedLrc = null
        )

        LyricsFastCache.put(
            context = context,
            queryTitle = "Midnight City",
            queryArtist = "M83",
            queryDurationMs = 243_000L,
            result = entry
        )

        val hit = LyricsFastCache.get(
            context = context,
            title = "Midnight City (Remastered 2021) [Hi-Res]",
            artist = "M83 feat. Guest",
            durationMs = 244_000L
        )
        assertNotNull(hit)
        assertEquals("[00:10.00]Waiting in a car", hit!!.lrc)
    }
}
