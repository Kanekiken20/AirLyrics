package com.andsi.airlyrics.floating

import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import com.andsi.airlyrics.core.model.WordByWordAnimationMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedLyricsTimelineTest {
    private var elapsed = 1_000L
    private fun renderer() = FloatingLyricsRenderer(
        textViewProvider = { null }, uptimeMillisProvider = { elapsed },
        contentModeProvider = { LyricsContentDisplayMode.ORIGINAL_ONLY },
        lineModeProvider = { LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT }
    )

    @Test fun sharedSnapshotNeedsNoViewAndContainsOnlyCurrentLine() {
        val renderer = renderer()
        renderer.updatePlayback(1_000, true)
        renderer.parseAndShow("[00:01.00]First\n[00:04.00]Second\n[00:08.00]Third", emptyText = "")
        assertEquals("First", renderer.displaySnapshot().currentLine)
        assertEquals(3_000L, renderer.nextPresentationDelayMs(false))
        renderer.setRenderingState(false, false)
        elapsed += 3_000
        assertEquals("Second", renderer.displaySnapshot().currentLine)
        assertEquals(4_000L, renderer.nextPresentationDelayMs(false))
    }

    @Test fun pauseHasNoBoundaryTimerAndResumeUsesPlaybackSpeed() {
        val renderer = renderer()
        renderer.updatePlayback(1_000, false)
        renderer.parseAndShow("[00:01.00]First\n[00:05.00]Second", emptyText = "")
        assertNull(renderer.nextPresentationDelayMs(false))
        elapsed += 10_000
        assertEquals("First", renderer.displaySnapshot().currentLine)
        renderer.updatePlayback(1_000, true, speed = 2f, sampledAtElapsedRealtimeMs = elapsed)
        assertEquals(2_000L, renderer.nextPresentationDelayMs(false))
        elapsed += 2_000
        assertEquals("Second", renderer.displaySnapshot().currentLine)
        assertNull(renderer.nextPresentationDelayMs(false))
    }

    @Test fun authoritativeSmallBackwardSeekAndClockSuspendAreRespected() {
        val renderer = renderer()
        renderer.updatePlayback(1_000, true)
        renderer.parseAndShow("[00:01.00]First\n[00:02.00]Second", emptyText = "")
        elapsed += 1_000
        assertEquals("Second", renderer.displaySnapshot().currentLine)
        renderer.updatePlayback(1_000, true, sampledAtElapsedRealtimeMs = elapsed)
        assertEquals("First", renderer.displaySnapshot().currentLine)
        elapsed += 60_000 // monotonic elapsed time includes deep sleep
        assertEquals(61_000L, renderer.displaySnapshot().effectivePositionMs)
    }

    @Test fun unavailableOrReplacedLyricsHaveNoIdleTimerOrStaleSnapshot() {
        val renderer = renderer()
        renderer.updatePlayback(1_000, true)
        renderer.parseAndShow("[00:01.00]Old\n[00:03.00]Old next", emptyText = "")
        renderer.show("Loading new song")
        assertNull(renderer.displaySnapshot().currentLine)
        assertNull(renderer.nextPresentationDelayMs(false))
        renderer.clear()
        assertNull(renderer.displaySnapshot().currentLine)
        assertNull(renderer.nextPresentationDelayMs(false))
    }

    @Test fun backgroundVocalsHaveIndependentEntryAndExitBoundaries() {
        val renderer = FloatingLyricsRenderer(textViewProvider = { null },
            uptimeMillisProvider = { elapsed }, wordByWordLyricsEnabledProvider = { true })
        renderer.updatePlayback(1_000, true)
        renderer.parseAndShow("[00:01.00]Lead\n[00:10.00]Next", wordByWordLines = listOf(
            WordByWordLine(3_000, 4_000, "Backing vocal", isBackground = true)
        ), emptyText = "")
        assertEquals(2_000L, renderer.nextPresentationDelayMs(false))
        elapsed += 2_000
        assertEquals(1_000L, renderer.nextPresentationDelayMs(false))
        elapsed += 1_000
        assertEquals(6_000L, renderer.nextPresentationDelayMs(false))
    }

    @Test fun sleepingWordAnimationResumesAfterGapsWithoutLockScreenWordTimers() {
        val renderer = FloatingLyricsRenderer(textViewProvider = { null },
            uptimeMillisProvider = { elapsed }, wordByWordLyricsEnabledProvider = { true },
            wordByWordAnimationModeProvider = { WordByWordAnimationMode.SPICY })
        renderer.updatePlayback(1_000, true)
        renderer.parseAndShow("[00:01.00]A B\n[00:11.00]Next", wordByWordLines = listOf(
            WordByWordLine(1_000, 6_000, "A B", listOf(
                WordByWordSegment("A ", 1_000, 2_000),
                WordByWordSegment("B", 4_000, 5_000)
            ))
        ), emptyText = "")
        elapsed += 1_300 // first word's frame callback has settled and stopped
        assertEquals(1_700L, renderer.nextPresentationDelayMs(animateWords = true))
        assertEquals(3_700L, renderer.nextPresentationDelayMs(animateWords = false))
    }
}
