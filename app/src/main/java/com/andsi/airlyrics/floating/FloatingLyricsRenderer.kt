package com.andsi.airlyrics.floating

import android.graphics.Color
import android.os.SystemClock
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.AbsoluteSizeSpan
import com.andsi.airlyrics.core.model.displayText
import com.andsi.airlyrics.lyrics.LyricsAccess
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.andsi.airlyrics.lyrics.WordByWordLine
import com.andsi.airlyrics.lyrics.WordByWordSegment
import com.andsi.airlyrics.lyrics.display.PlainLyricsDisplayFormatter
import com.andsi.airlyrics.lyrics.parser.LrcLine
import com.andsi.airlyrics.core.text.ChineseText
import com.andsi.airlyrics.core.text.LyricsDisplayPolicy
import com.andsi.airlyrics.lyrics.parser.LrcParser
import com.andsi.airlyrics.core.model.LyricsContentDisplayMode
import com.andsi.airlyrics.core.model.LyricsScriptPreference
import com.andsi.airlyrics.core.model.LyricsLineDisplayMode
import com.andsi.airlyrics.core.model.LyricsSwitchAnimationMode
import com.andsi.airlyrics.core.model.WordByWordAnimationMode
import com.andsi.airlyrics.core.model.LyricsGlowStyle
import com.andsi.airlyrics.core.model.LyricPreview
import com.andsi.airlyrics.core.model.LyricPreviewLine
import com.andsi.airlyrics.design.tokens.AirUiTokens
import kotlin.math.roundToInt

internal enum class ParsedLyricsAvailability {
    AVAILABLE,
    EMPTY
}

/**
 * Maintains parsed lyric lines and renders the line matching the current playback position.
 */
class FloatingLyricsRenderer(
    private val textViewProvider: () -> TextView?,
    private val contentModeProvider: () -> LyricsContentDisplayMode = { LyricsContentDisplayMode.default },
    private val scriptPreferenceProvider: () -> LyricsScriptPreference = { LyricsScriptPreference.default },
    private val lineModeProvider: () -> LyricsLineDisplayMode = { LyricsLineDisplayMode.default },
    private val switchAnimationModeProvider: () -> LyricsSwitchAnimationMode = { LyricsSwitchAnimationMode.default },
    private val wordByWordLyricsEnabledProvider: () -> Boolean = { false },
    private val wordByWordHighlightColorProvider: () -> Int = { Color.rgb(120, 220, 255) },
    private val noTranslationTextProvider: () -> String = { "No translation for this lyric" },
    private val uptimeMillisProvider: () -> Long = { SystemClock.elapsedRealtime() },
    private val wordByWordAnimationModeProvider: () -> WordByWordAnimationMode = { WordByWordAnimationMode.CLASSIC },
    private val durationMsProvider: () -> Long = { 0L },
    private val glowStyleProvider: () -> LyricsGlowStyle = { LyricsGlowStyle() }
) {
    internal var onTimelineChanged: (() -> Unit)? = null
    private var interactiveRendering = true
    private var motionAllowed = true
    private var playbackSpeed = 1f
    private var playbackAnchored = false
    private var hasWordTimings = false
    private var plainBoundaries = LongArray(0)
    private var allBoundaries = LongArray(0)
    private var animationBoundaries = LongArray(0)
    private var access: LyricsAccess? = null

    /** Disabling rendering retains parsed data and cancels all View animation callbacks. */
    internal fun setRenderingState(visible: Boolean, animate: Boolean) {
        if (interactiveRendering == visible && motionAllowed == animate) return
        interactiveRendering = visible
        motionAllowed = animate
        resetTextAnimationState()
        (textViewProvider() as? FloatingLyricsTextView)?.updateSpicyPlayback(null, false)
        if (visible) refresh()
    }

    internal fun displaySnapshot(): LyricsLineSnapshot {
        if (expireIfNeeded()) return LyricsLineSnapshot(null, null, getEstimatedPositionMs(), currentIsPlaying)
        val position = getEstimatedPositionMs()
        val index = LrcParser.findCurrentIndex(currentPlainLines, position)
        val line = index?.let { currentPlainLines.getOrNull(it) }
        val preview = if (index != null && line != null && !line.isMetadata) {
            fun neighbor(step: Int): LyricPreviewLine? {
                var candidate = index + step
                while (candidate in currentPlainLines.indices) {
                    val next = currentPlainLines[candidate]
                    if (!next.isMetadata && next.timeMs != line.timeMs && next.text.isNotBlank()) {
                        return LyricPreviewLine(next.text, next.translation.orEmpty())
                    }
                    candidate += step
                }
                return null
            }
            line.text.takeIf(String::isNotBlank)?.let {
                LyricPreview(LyricPreviewLine(it, line.translation.orEmpty()), neighbor(-1), neighbor(1))
            }
        } else if (currentPlainLines.isEmpty() && wordByWordLyricsEnabledProvider()) {
            findCurrentWordByWordIndex(position)?.let { wordIndex ->
                fun lineAt(at: Int) = currentWordByWordLines.getOrNull(at)?.let(::wordByWordOriginalText)
                    ?.takeIf(String::isNotBlank)?.let { LyricPreviewLine(it, "") }
                lineAt(wordIndex)?.let { LyricPreview(it, lineAt(wordIndex - 1), lineAt(wordIndex + 1)) }
            }
        } else null
        val text = if (line != null && !line.isMetadata) {
            PlainLyricsDisplayFormatter.format(
                currentPlainLines, index, contentModeProvider(), scriptPreferenceProvider(),
                LyricsLineDisplayMode.CURRENT_ONLY, noTranslationTextProvider()
            ).toString()
        } else preview?.current?.let { renderSnapshotLine(it) }
        val creditedLine = text?.takeIf { it.isNotBlank() }?.let { body ->
            access?.attribution?.let { "$body\n${it.displayText()}" } ?: body
        }
        return LyricsLineSnapshot(creditedLine, index, position, currentIsPlaying,
            access?.attribution?.displayText()?.toString(),
            preview?.previous?.let(::renderSnapshotLine), preview?.next?.let(::renderSnapshotLine), preview)
    }

    private fun renderSnapshotLine(line: LyricPreviewLine): String? =
        LyricsDisplayPolicy.render(contentModeProvider(), line.original, line.translation, scriptPreferenceProvider())
            .primary.trim().takeIf(String::isNotBlank)

    /** A single predicted boundary; null means idle until a playback/content event. */
    internal fun nextPresentationDelayMs(animateWords: Boolean): Long? {
        if (!currentIsPlaying || !playbackAnchored) return null
        val position = getEstimatedPositionMs()
        val boundaries = when {
            !wordByWordLyricsEnabledProvider() -> plainBoundaries
            animateWords -> animationBoundaries
            else -> allBoundaries
        }
        val match = boundaries.binarySearch(position)
        val nextIndex = if (match >= 0) match + 1 else -match - 1
        val next = boundaries.getOrNull(nextIndex)
        val boundaryDelay = next?.let { kotlin.math.ceil((it - position) / playbackSpeed.toDouble()).toLong().coerceAtLeast(1) }
        // Spicy has a View frame clock; classic highlighting requires short visible-only ticks.
        if (animateWords && isWordByWordActive() &&
            wordByWordAnimationModeProvider() == WordByWordAnimationMode.CLASSIC && next != null) {
            return minOf(boundaryDelay ?: 40L, 40L)
        }
        return boundaryDelay
    }

    private val expiryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val expiryCheck = Runnable { expireIfNeeded() }

    fun setLyricsAccess(value: LyricsAccess?) {
        expiryHandler.removeCallbacks(expiryCheck)
        access = value
        value?.let { expiryHandler.postDelayed(expiryCheck, (it.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0)) }
    }

    private fun expireIfNeeded(): Boolean {
        if (access?.isExpired() != true) return false
        clear()
        show { textViewProvider()?.context?.getString(com.andsi.airlyrics.R.string.ui_lyrics_expired).orEmpty() }
        return true
    }

    private fun credited(text: CharSequence): CharSequence {
        val credit = access?.attribution ?: return text
        val view = textViewProvider() ?: return text
        return SpannableStringBuilder(text).apply {
            append("\n")
            val start = length
            append(credit.displayText())
            val size = ((11 * view.resources.configuration.fontScale).toInt().coerceAtLeast(10) / 2f).roundToInt()
            setSpan(AbsoluteSizeSpan(size, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(ColorUtils.setAlphaComponent(view.currentTextColor, 90)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private var currentPlainLines: List<LrcLine> = emptyList()
    private var backgroundLines: List<WordByWordLine> = emptyList()
    private var currentWordByWordLines: List<WordByWordLine> = emptyList()
    private var currentPositionMs: Long = 0L
    private var lastPositionUpdateUptimeMs: Long = 0L
    private var currentIsPlaying: Boolean = false
    private var lyricsOffsetMs: Long = 0L
    private var currentMessage: (() -> String)? = null
    private var lastRenderedText: String? = null

    fun updatePlayback(positionMs: Long, isPlaying: Boolean, speed: Float = 1f, sampledAtElapsedRealtimeMs: Long = 0L) {
        val nowUptimeMs = uptimeMillisProvider()
        val validSpeed = speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        val incomingPositionMs = positionMs.coerceAtLeast(0L)
        val estimatedBeforeUpdateMs = getEstimatedPlaybackPositionMs(nowUptimeMs)
        currentPositionMs = if (sampledAtElapsedRealtimeMs <= 0L && isStalePlayingBacktrack(incomingPositionMs, isPlaying, estimatedBeforeUpdateMs)) {
            estimatedBeforeUpdateMs
        } else {
            incomingPositionMs
        }
        currentIsPlaying = isPlaying
        playbackSpeed = validSpeed
        playbackAnchored = true
        lastPositionUpdateUptimeMs = if (sampledAtElapsedRealtimeMs > 0L) sampledAtElapsedRealtimeMs.coerceAtMost(nowUptimeMs) else nowUptimeMs
        updateSpicyPlayback()
    }

    private data class SpicyCacheKey(
        val line: WordByWordLine,
        val text: String,
        val segments: List<WordByWordSegment>,
        val color: Int
    )
    private val spicyTextCache = object : LinkedHashMap<SpicyCacheKey, CharSequence>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SpicyCacheKey, CharSequence>?) = size > 8
    }
    private val spicyClock: () -> Long = { getEstimatedPositionMs() }
    private fun usesSpicy() = motionAllowed && isWordByWordActive() && wordByWordAnimationModeProvider() == WordByWordAnimationMode.SPICY && textViewProvider() is FloatingLyricsTextView
    private fun usesSpicyLines() = motionAllowed && !isWordByWordActive() &&
        switchAnimationModeProvider() == LyricsSwitchAnimationMode.SPICY &&
        textViewProvider() is FloatingLyricsTextView

    private fun usesLineEffects() = motionAllowed && !isWordByWordActive() && textViewProvider() is FloatingLyricsTextView &&
        (switchAnimationModeProvider() == LyricsSwitchAnimationMode.SPICY || glowStyleProvider().enabled)

    private fun updateSpicyPlayback() {
        val view = textViewProvider() as? FloatingLyricsTextView ?: return
        if (!interactiveRendering || !motionAllowed) {
            view.updateSpicyPlayback(null, false)
            return
        }
        view.updateLyricsGlow(glowStyleProvider())
        val text = view.text as? Spanned
        val active = text != null && (
            text.getSpans(0, text.length, SpicyLyricsSpan::class.java).isNotEmpty() ||
                text.getSpans(0, text.length, SpicyLineSpan::class.java).isNotEmpty()
            )
        view.updateSpicyPlayback(if (active) spicyClock else null, currentIsPlaying)
    }

    /** Wake at a lyric boundary instead of allowing the ordinary 300ms poll to delay a switch. */
    internal fun nextSyncDelayMs(maxDelayMs: Long): Long {
        if (!currentIsPlaying) return maxDelayMs
        val position = getEstimatedPositionMs()
        val index = LrcParser.findCurrentIndex(currentPlainLines, position) ?: -1
        val next = currentPlainLines.getOrNull(index + 1)?.timeMs ?: return maxDelayMs
        return kotlin.math.ceil((next - position) / playbackSpeed.toDouble()).toLong().coerceIn(1L, maxDelayMs)
    }

    fun clear() {
        spicyTextCache.clear()
        (textViewProvider() as? FloatingLyricsTextView)?.updateSpicyPlayback(null, false)
        setLyricsAccess(null)
        currentPlainLines = emptyList()
        currentWordByWordLines = emptyList()
        hasWordTimings = false
        plainBoundaries = LongArray(0)
        allBoundaries = LongArray(0)
        animationBoundaries = LongArray(0)
        backgroundLines = emptyList()
        currentPositionMs = 0L
        lastPositionUpdateUptimeMs = 0L
        currentIsPlaying = false
        lyricsOffsetMs = 0L
        currentMessage = null
        lastRenderedText = null
        playbackAnchored = false
        resetTextAnimationState()
        onTimelineChanged?.invoke()
    }

    fun show(text: String) = show { text }

    fun show(text: () -> String) {
        setLyricsAccess(null)
        currentPlainLines = emptyList()
        currentWordByWordLines = emptyList()
        hasWordTimings = false
        plainBoundaries = LongArray(0)
        allBoundaries = LongArray(0)
        animationBoundaries = LongArray(0)
        backgroundLines = emptyList()
        currentMessage = text
        setTextImmediately(text())
        onTimelineChanged?.invoke()
    }

    /**
     * Updates the timing offset. Rendering is caller-controlled so parse/clear flows do
     * not repaint stale lyrics before replacing the renderer state.
     */
    fun setLyricsOffset(offsetMs: Long): Boolean {
        if (lyricsOffsetMs == offsetMs) return false
        lyricsOffsetMs = offsetMs
        return true
    }

    internal fun parseAndShow(
        plainLrc: String,
        translatedLrc: String? = null,
        wordByWordLines: List<WordByWordLine> = emptyList(),
        emptyText: String
    ): ParsedLyricsAvailability = parseAndShow(plainLrc, translatedLrc, wordByWordLines) { emptyText }

    internal fun parseAndShow(
        plainLrc: String,
        translatedLrc: String? = null,
        wordByWordLines: List<WordByWordLine> = emptyList(),
        emptyText: () -> String
    ): ParsedLyricsAvailability {
        if (expireIfNeeded()) return ParsedLyricsAvailability.EMPTY
        currentPlainLines = LrcParser.parseWithTranslation(plainLrc, translatedLrc)
        currentWordByWordLines = wordByWordLines.filterNot { it.isBackground }
        backgroundLines = wordByWordLines.filter { it.isBackground }
        hasWordTimings = currentWordByWordLines.any { line ->
            line.segments.any { it.text.isNotBlank() && it.endMs > it.startMs }
        }
        plainBoundaries = currentPlainLines.map { it.timeMs }.distinct().sorted().toLongArray()
        allBoundaries = (plainBoundaries.asList() + wordByWordLines.flatMap { listOf(it.startMs, it.endMs) })
            .distinct().sorted().toLongArray()
        // A frame callback can sleep during a gap between words. Only the visible motion
        // surface wakes at segment starts; lock-screen notifications keep sentence timing.
        animationBoundaries = (allBoundaries.asList() + wordByWordLines.flatMap { line ->
            (line.segments + line.translatedSegments).map { it.startMs }
        }).distinct().sorted().toLongArray()
        currentMessage = emptyText
        val availability = if (hasRenderableLyrics()) {
            ParsedLyricsAvailability.AVAILABLE
        } else {
            ParsedLyricsAvailability.EMPTY
        }

        val text = if (availability == ParsedLyricsAvailability.AVAILABLE) {
            renderAtCurrentPosition().takeIf { it.isNotBlankText() }
                ?: renderPlainTextAtIndex(0).takeIf { it.isNotBlankText() }
                ?: emptyText()
        } else {
            emptyText()
        }

        setTextImmediately(text)
        onTimelineChanged?.invoke()
        return availability
    }

    fun tick() {
        if (expireIfNeeded() || !interactiveRendering) return
        if (currentPlainLines.isEmpty() && currentWordByWordLines.isEmpty()) return

        val text = renderAtCurrentPosition()
        if (text.isNotBlankText() || usesLineEffects()) setTextWithOptionalAnimation(text)
    }

    fun isWordByWordActive(): Boolean {
        return wordByWordLyricsEnabledProvider() && hasWordTimings
    }

    fun refresh() {
        spicyTextCache.clear()
        if (expireIfNeeded()) return
        val text = if (currentPlainLines.isEmpty() && currentWordByWordLines.isEmpty()) {
            currentMessage?.invoke() ?: return
        } else {
            renderAtCurrentPosition().takeIf { it.isNotBlankText() }
                ?: renderPlainTextAtIndex(0).takeIf { it.isNotBlankText() }
                ?: currentMessage?.invoke()
                ?: return
        }
        setTextImmediately(text)
    }

    private fun hasRenderableLyrics(): Boolean {
        val hasPlainLyrics = currentPlainLines.any { line ->
            !line.isMetadata && (line.text.isNotBlank() || line.hasTranslation())
        }
        val hasWordByWordLyrics = currentWordByWordLines.any { line ->
            line.text.isNotBlank() || line.segments.any { it.text.isNotBlank() }
        }
        return hasPlainLyrics || hasWordByWordLyrics
    }

    private fun renderAtCurrentPosition(): CharSequence {
        val primary = renderPrimaryAtCurrentPosition()
        if (!wordByWordLyricsEnabledProvider() || contentModeProvider() == LyricsContentDisplayMode.TRANSLATION_ONLY) return primary
        val position = getEstimatedPositionMs()
        val activeBackground = backgroundLines.filter { position in it.startMs until it.endMs }
        if (activeBackground.isEmpty()) return primary
        return SpannableStringBuilder(primary).apply {
            activeBackground.forEach { line ->
                if (isNotEmpty()) append("\n")
                append(wordByWordLineSpan(line, line.text, position))
            }
        }
    }

    private fun renderPrimaryAtCurrentPosition(): CharSequence {
        val positionMs = getEstimatedPositionMs()
        val currentIndex = LrcParser.findCurrentIndex(currentPlainLines, positionMs)

        if (currentIndex != null) {
            if (isWordByWordActive()) {
                renderTextAtIndexWithWordByWord(currentIndex, positionMs)
                    .takeIf { it.isNotBlankText() }
                    ?.let { return it }
            }
            return renderPlainTextAtIndex(currentIndex)
        }

        // Safety fallback for unusual payloads. Word-by-word lyrics are independent segment
        // timing data, so they can still render when the accompanying plain LRC has no usable line.
        if (isWordByWordActive()) {
            val wordByWordIndex = findCurrentWordByWordIndex(positionMs)
            if (wordByWordIndex != null) {
                renderWordByWordOnlyAtIndex(wordByWordIndex, positionMs)
                    .takeIf { it.isNotBlankText() }
                    ?.let { return it }
            }
        }

        return ""
    }

    private fun renderPlainTextAtIndex(index: Int): CharSequence {
        if (usesLineEffects()) {
            return spicyLineText(PlainLyricsDisplayFormatter.formatWithTiming(
                plainLines = currentPlainLines,
                currentIndex = index,
                contentMode = contentModeProvider(),
                scriptPreference = scriptPreferenceProvider(),
                lineMode = lineModeProvider(),
                noTranslationText = noTranslationTextProvider(),
                durationMs = durationMsProvider()
            ), wordByWordHighlightColorProvider(), animated = usesSpicyLines())
        }
        return PlainLyricsDisplayFormatter.format(
            plainLines = currentPlainLines,
            currentIndex = index,
            contentMode = contentModeProvider(),
            scriptPreference = scriptPreferenceProvider(),
            lineMode = lineModeProvider(),
            noTranslationText = noTranslationTextProvider()
        )
    }

    /**
     * Renders exactly the same content modes as [PlainLyricsDisplayFormatter], but replaces only
     * the current original line with wrap-safe word-by-word highlighting when a matching local word-by-word line exists.
     * This keeps “original only / translation only / original + translation” independent of
     * word-by-word highlighting and prevents timed text from leaking translations into original-only mode.
     */
    private fun renderTextAtIndexWithWordByWord(currentIndex: Int, positionMs: Long): CharSequence {
        if (currentPlainLines.isEmpty() || currentIndex !in currentPlainLines.indices) return ""

        val indexes = visiblePlainLineIndexes(currentIndex)
        if (indexes.isEmpty()) return ""

        val renderedLines = mutableListOf<CharSequence>()
        val contentMode = contentModeProvider()

        indexes.forEach { index ->
            val line = currentPlainLines[index]
            val original = line.text.trim()
            val translation = line.translation.orEmpty().trim()
            if (line.isMetadata) {
                if (original.isNotBlank()) renderedLines += original
                return@forEach
            }

            val isCurrent = index == currentIndex
            val wordByWordLine = if (isCurrent) findWordByWordLineForPlainLine(line, positionMs) else null

            val rendered = LyricsDisplayPolicy.render(
                contentMode, original, translation, scriptPreferenceProvider()
            )
            if (rendered.primary.isNotBlank()) {
                // When the preferred script replaces the line with its translation, keep the
                // translation's own timing; otherwise the original tokens stay authoritative.
                val showsTranslationOnly = contentMode == LyricsContentDisplayMode.TRANSLATION_ONLY ||
                    (rendered.secondary == null && rendered.primary.trim() != original.trim())
                renderedLines += when {
                    wordByWordLine == null -> rendered.primary
                    showsTranslationOnly && wordByWordLine.translatedSegments.isNotEmpty() ->
                        wordByWordLineSpan(
                            wordByWordLine,
                            rendered.primary,
                            positionMs,
                            wordByWordLine.translatedSegments
                        )
                    showsTranslationOnly -> rendered.primary
                    else -> wordByWordLineSpan(wordByWordLine, rendered.primary, positionMs)
                }
            }
            rendered.secondary?.takeIf { it.isNotBlank() }?.let { secondary ->
                val translatedSegments = wordByWordLine?.translatedSegments.orEmpty()
                renderedLines += when {
                    wordByWordLine == null -> secondary
                    translatedSegments.isNotEmpty() -> wordByWordLineSpan(
                        wordByWordLine,
                        secondary,
                        positionMs,
                        translatedSegments
                    )
                    else -> secondary
                }
            }
        }

        if (renderedLines.isEmpty()) {
            return if (contentMode == LyricsContentDisplayMode.TRANSLATION_ONLY) {
                noTranslationTextProvider()
            } else {
                ""
            }
        }

        return SpannableStringBuilder().apply {
            renderedLines.forEachIndexed { renderedIndex, renderedLine ->
                if (renderedIndex > 0) append('\n')
                append(renderedLine)
            }
        }
    }

    private fun visiblePlainLineIndexes(currentIndex: Int): List<Int> {
        val indexes = when (lineModeProvider()) {
            LyricsLineDisplayMode.CURRENT_ONLY -> listOf(currentIndex)
            LyricsLineDisplayMode.PREVIOUS_AND_CURRENT -> listOf(currentIndex - 1, currentIndex)
            LyricsLineDisplayMode.CURRENT_AND_NEXT -> listOf(currentIndex, currentIndex + 1)
            LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> listOf(currentIndex - 1, currentIndex, currentIndex + 1)
        }
        return indexes.filter { it in currentPlainLines.indices }
    }

    private fun findWordByWordLineForPlainLine(plainLine: LrcLine, positionMs: Long): WordByWordLine? {
        if (plainLine.isMetadata) return null
        if (currentWordByWordLines.isEmpty()) return null

        fun List<WordByWordLine>.bestCompatible(maxDistanceMs: Long): WordByWordLine? {
            return sortedBy { kotlin.math.abs(it.startMs - plainLine.timeMs) }
                .firstOrNull { candidate ->
                    kotlin.math.abs(candidate.startMs - plainLine.timeMs) <= maxDistanceMs &&
                        isTextCompatible(plainLine.text, wordByWordOriginalText(candidate))
                }
        }

        // Apple/other providers can normalize punctuation or split whitespace differently between
        // the line LRC and the word-timed payload. When both payloads carry the same line start,
        // the timestamp is the stronger identity than a long-text comparison. Keep a narrow
        // timestamp fallback so long lines do not lose karaoke rendering while still avoiding a
        // neighboring line accidentally matching.
        currentWordByWordLines
            .filter { kotlin.math.abs(it.startMs - plainLine.timeMs) <= 120L }
            .minByOrNull { kotlin.math.abs(it.startMs - plainLine.timeMs) }
            ?.takeIf { candidate ->
                isTextCompatible(plainLine.text, wordByWordOriginalText(candidate))
            }
            ?.let { return it }

        val aroundPosition = currentWordByWordLines
            .filter { positionMs in (it.startMs - 350L)..(it.endMs + 700L) }
            .bestCompatible(maxDistanceMs = 2_500L)
        if (aroundPosition != null) return aroundPosition

        return currentWordByWordLines.bestCompatible(maxDistanceMs = 1_500L)
    }

    private fun renderWordByWordOnlyAtIndex(index: Int, positionMs: Long): CharSequence {
        if (contentModeProvider() == LyricsContentDisplayMode.TRANSLATION_ONLY) {
            return noTranslationTextProvider()
        }

        val renderedLines = visibleWordByWordIndexes(index).mapNotNull { visibleIndex ->
            val wordByWordLine = currentWordByWordLines.getOrNull(visibleIndex) ?: return@mapNotNull null
            val original = wordByWordOriginalText(wordByWordLine)
            if (original.isBlank()) {
                null
            } else if (visibleIndex == index) {
                wordByWordLineSpan(wordByWordLine, original, positionMs)
            } else {
                original
            }
        }

        if (renderedLines.isEmpty()) return ""

        return SpannableStringBuilder().apply {
            renderedLines.forEachIndexed { renderedIndex, renderedLine ->
                if (renderedIndex > 0) append('\n')
                append(renderedLine)
            }
        }
    }

    private fun visibleWordByWordIndexes(currentIndex: Int): List<Int> {
        val indexes = when (lineModeProvider()) {
            LyricsLineDisplayMode.CURRENT_ONLY -> listOf(currentIndex)
            LyricsLineDisplayMode.PREVIOUS_AND_CURRENT -> listOf(currentIndex - 1, currentIndex)
            LyricsLineDisplayMode.CURRENT_AND_NEXT -> listOf(currentIndex, currentIndex + 1)
            LyricsLineDisplayMode.PREVIOUS_CURRENT_NEXT -> listOf(currentIndex - 1, currentIndex, currentIndex + 1)
        }
        return indexes.filter { it in currentWordByWordLines.indices }
    }

    private fun wordByWordLineSpan(
        wordByWordLine: WordByWordLine,
        displayText: String,
        positionMs: Long,
        displaySegments: List<WordByWordSegment> = wordByWordLine.segments
    ): CharSequence {
        val text = displayText.trim()
        if (text.isBlank()) return ""

        val sourceText = displaySegments.joinToString("") { it.text }.trim()
        val segments = if (sourceText != text &&
            sourceText.filterNot { it.isWhitespace() } == text.filterNot { it.isWhitespace() }) {
            mapDisplaySegments(sourceText, text, displaySegments) ?: return text
        } else if (sourceText != text && (ChineseText.toSimplified(sourceText) != sourceText ||
                ChineseText.toSimplified(text) != text)) {
            if (displaySegments === wordByWordLine.segments &&
                sourceText != wordByWordOriginalText(wordByWordLine)) return text
            val mapped = if (displaySegments === wordByWordLine.translatedSegments) {
                com.andsi.airlyrics.lyrics.providers.ApplePlainLyricsProvider
                    .conversionSegments(text, displaySegments)
            } else {
                mapDisplaySegments(sourceText, text, displaySegments)
            }
            mapped ?: return text
        } else {
            displaySegments
        }

        if (usesSpicy()) {
            val key = SpicyCacheKey(wordByWordLine, text, segments, wordByWordHighlightColorProvider())
            spicyTextCache[key]?.let { return it }
            val timings = spicyTimings(wordByWordLine, text, segments)
            if (timings.isNotEmpty()) return SpannableString(text).apply {
                for (timing in timings) setSpan(SpicyLyricsSpan(timing, wordByWordHighlightColorProvider()),
                    timing.start, timing.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }.also { spicyTextCache[key] = it }
        }
        val progress = wordByWordHighlightProgress(
            wordByWordLine, text, positionMs, segments, clearAtLineEnd = segments !== displaySegments
        )
        val span = SpannableString(text)
        if (progress.completedEnd > 0 || progress.hasActiveCharacter) {
            val highlightColor = wordByWordHighlightColorProvider()
            if (progress.completedEnd > 0) {
                span.setSpan(
                    ForegroundColorSpan(highlightColor),
                    0,
                    progress.completedEnd.coerceIn(0, text.length),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            if (progress.hasActiveCharacter) {
                val baseColor = textViewProvider()?.currentTextColor ?: Color.WHITE
                val transitioningColor = ColorUtils.blendARGB(
                    baseColor,
                    highlightColor,
                    progress.activeFraction
                )
                span.setSpan(
                    ForegroundColorSpan(transitioningColor),
                    progress.activeStart.coerceIn(0, text.length),
                    progress.activeEnd.coerceIn(0, text.length),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        if (motionAllowed && glowStyleProvider().enabled && textViewProvider() is FloatingLyricsTextView) {
            for (timing in spicyTimings(wordByWordLine, text, segments, allowEmphasis = false)) {
                span.setSpan(SpicyLyricsSpan(timing, wordByWordHighlightColorProvider(), haloOnly = true),
                    timing.start, timing.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return span
    }

    private fun mapDisplaySegments(
        source: String,
        display: String,
        segments: List<WordByWordSegment>
    ): List<WordByWordSegment>? {
        // Require complete, ordered coverage. Do not guess punctuation, missing tokens or offsets.
        if (segments.joinToString("") { it.text } != source) return null
        // Apple can omit spaces at token boundaries (especially before a sung echo).
        // Slice the complete display string, retaining every token's original time range.
        if (source.filterNot { it.isWhitespace() } == display.filterNot { it.isWhitespace() }) {
            var cursor = 0
            val mapped = segments.map { segment ->
                val start = cursor
                for (character in segment.text.filterNot { it.isWhitespace() }) {
                    while (cursor < display.length && display[cursor].isWhitespace()) cursor++
                    if (cursor >= display.length || display[cursor] != character) return null
                    cursor++
                }
                segment.copy(text = display.substring(start, cursor))
            }.toMutableList()
            if (mapped.isEmpty()) return null
            val last = mapped.last()
            mapped[mapped.lastIndex] = last.copy(text = last.text + display.substring(cursor))
            return mapped
        }
        val (converted, sourceBoundaries) = ChineseText.simplifiedBoundaries(source)
        val (convertedDisplay, displayBoundaries) = ChineseText.simplifiedBoundaries(display)
        if (converted != convertedDisplay) return null
        val reverse = displayBoundaries.withIndex().filter { it.value >= 0 }
            .groupBy({ it.value }, { it.index })
        var offset = 0
        var previousEndMs = Long.MIN_VALUE
        return segments.map { segment ->
            if (segment.startMs < previousEndMs || segment.endMs <= segment.startMs) return null
            previousEndMs = segment.endMs
            val start = sourceBoundaries[offset]
            offset += segment.text.length
            val end = sourceBoundaries[offset]
            val displayStart = reverse[start]?.singleOrNull() ?: return null
            val displayEnd = reverse[end]?.singleOrNull() ?: return null
            if (start !in 0 until end || displayEnd <= displayStart) return null
            segment.copy(text = display.substring(displayStart, displayEnd))
        }
    }

    private fun wordByWordOriginalText(wordByWordLine: WordByWordLine): String {
        return wordByWordLine.text
            .replace(" / ", "\n")
            .replace("／", "\n")
            .lines()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
    }

    private fun normalizeWordByWordMatchText(text: String): String {
        return ChineseText.toSimplified(text).lowercase()
            .replace(Regex("""[^\p{L}\p{N}]"""), "")
            .trim()
    }

    private fun isTextCompatible(lrcText: String, wordByWordText: String): Boolean {
        val lrc = normalizeWordByWordMatchText(lrcText)
        val wordByWordLyrics = normalizeWordByWordMatchText(wordByWordText)
        if (lrc.isBlank() || wordByWordLyrics.isBlank()) return false
        if (lrc == wordByWordLyrics) return true
        if (lrc.length >= 2 && wordByWordLyrics.length >= 2 && (lrc.contains(wordByWordLyrics) || wordByWordLyrics.contains(lrc))) {
            return true
        }

        val shorter = if (lrc.length <= wordByWordLyrics.length) lrc else wordByWordLyrics
        val longer = if (lrc.length <= wordByWordLyrics.length) wordByWordLyrics else lrc
        val minCommonLength = when {
            shorter.length >= 12 -> 8
            shorter.length >= 6 -> 4
            else -> return false
        }

        return (0..(shorter.length - minCommonLength)).any { start ->
            longer.contains(shorter.substring(start, start + minCommonLength))
        }
    }

    private fun findCurrentWordByWordIndex(positionMs: Long): Int? {
        if (currentWordByWordLines.isEmpty()) return null

        var left = 0
        var right = currentWordByWordLines.lastIndex
        var result: Int? = null

        while (left <= right) {
            val mid = (left + right) / 2
            val line = currentWordByWordLines[mid]

            if (line.startMs <= positionMs) {
                result = mid
                left = mid + 1
            } else {
                right = mid - 1
            }
        }

        return result
    }

    private fun setTextImmediately(text: CharSequence) {
        if (!interactiveRendering) return
        val view = textViewProvider() ?: return
        resetTextAnimationState(view)
        view.text = credited(text)
        lastRenderedText = text.toString()
        updateSpicyPlayback()
    }

    private fun setTextWithOptionalAnimation(text: CharSequence) {
        if (!interactiveRendering) return
        if (!motionAllowed || !currentIsPlaying) { setTextImmediately(text); return }
        val textKey = text.toString()
        val isWordByWordTick = isWordByWordActive()
        if (usesSpicyLines()) {
            val view = textViewProvider()!!
            if (textKey != lastRenderedText || !sameSpicyLineSpans(view.text, text)) setTextImmediately(text)
            else updateSpicyPlayback()
            return
        }
        if (!isWordByWordTick && textKey == lastRenderedText &&
            sameSpicyLineSpans(textViewProvider()?.text ?: "", text)) return

        if (usesSpicy() && textKey == lastRenderedText) {
            val view = textViewProvider()!!
            val old = view.text as? Spanned
            val next = text as? Spanned
            val oldSpans = old?.getSpans(0, old.length, SpicyLyricsSpan::class.java).orEmpty()
            val nextSpans = next?.getSpans(0, next.length, SpicyLyricsSpan::class.java).orEmpty()
            if (oldSpans.isNotEmpty() && oldSpans.size == nextSpans.size && oldSpans.indices.all {
                oldSpans[it].timing == nextSpans[it].timing && oldSpans[it].highlight == nextSpans[it].highlight && old!!.getSpanStart(oldSpans[it]) == next!!.getSpanStart(nextSpans[it])
            }) { updateSpicyPlayback(); return }
        }
        val mode = switchAnimationModeProvider()
        if (isWordByWordTick || lastRenderedText == null) {
            setTextImmediately(text)
            return
        }

        when (mode) {
            LyricsSwitchAnimationMode.NONE, LyricsSwitchAnimationMode.SPICY -> setTextImmediately(text)
            LyricsSwitchAnimationMode.FADE -> startTextSwitchAnimation(
                text = text,
                textKey = textKey,
                initialTranslationYDp = 0f,
                initialScale = AirUiTokens.Motion.RestScale,
                durationMs = AirUiTokens.Layout.LyricsFadeMs
            )

            LyricsSwitchAnimationMode.SLIDE_UP -> startTextSwitchAnimation(
                text = text,
                textKey = textKey,
                initialTranslationYDp = AirUiTokens.Layout.LyricsSlideDistanceDp.toFloat(),
                initialScale = AirUiTokens.Layout.LyricsScaleStart,
                durationMs = AirUiTokens.Layout.LyricsSlideMs
            )

            LyricsSwitchAnimationMode.SCALE_FADE -> startTextSwitchAnimation(
                text = text,
                textKey = textKey,
                initialTranslationYDp = 0f,
                initialScale = AirUiTokens.Layout.LyricsScaleStart,
                durationMs = AirUiTokens.Layout.LyricsScaleFadeMs
            )
        }
    }

    private fun sameSpicyLineSpans(oldText: CharSequence, nextText: CharSequence): Boolean {
        val old = oldText as? Spanned
        val next = nextText as? Spanned
        val oldSpans = old?.getSpans(0, old.length, SpicyLineSpan::class.java).orEmpty()
        val nextSpans = next?.getSpans(0, next.length, SpicyLineSpan::class.java).orEmpty()
        return oldSpans.size == nextSpans.size && oldSpans.indices.all {
            oldSpans[it].timing == nextSpans[it].timing &&
                oldSpans[it].animated == nextSpans[it].animated &&
                oldSpans[it].highlight == nextSpans[it].highlight &&
                old!!.getSpanStart(oldSpans[it]) == next!!.getSpanStart(nextSpans[it]) &&
                old.getSpanEnd(oldSpans[it]) == next.getSpanEnd(nextSpans[it])
        }
    }

    private fun startTextSwitchAnimation(
        text: CharSequence,
        textKey: String,
        initialTranslationYDp: Float,
        initialScale: Float,
        durationMs: Long
    ) {
        val view = prepareTextSwitchAnimation(text, textKey) ?: return
        setTextAnimationState(
            view = view,
            alpha = 0f,
            translationY = initialTranslationYDp * view.resources.displayMetrics.density,
            scaleX = initialScale,
            scaleY = initialScale
        )
        animateTextToRest(view, durationMs)
    }

    private fun prepareTextSwitchAnimation(text: CharSequence, textKey: String): TextView? {
        val view = textViewProvider() ?: return null
        cancelTextAnimation(view)
        view.text = credited(text)
        lastRenderedText = textKey
        updateSpicyPlayback()
        return view
    }

    private fun resetTextAnimationState() {
        textViewProvider()?.let(::resetTextAnimationState)
    }

    private fun resetTextAnimationState(view: TextView) {
        cancelTextAnimation(view)
        setTextAnimationState(
            view = view,
            alpha = AirUiTokens.Motion.RestAlpha,
            translationY = 0f,
            scaleX = AirUiTokens.Motion.RestScale,
            scaleY = AirUiTokens.Motion.RestScale
        )
    }

    private fun cancelTextAnimation(view: TextView) {
        val target = view as? LyricsTextAnimationTarget
        if (target != null) target.cancelLyricsTextAnimation() else view.animate().cancel()
    }

    private fun setTextAnimationState(
        view: TextView,
        alpha: Float,
        translationY: Float,
        scaleX: Float,
        scaleY: Float
    ) {
        val textTarget = view as? LyricsTextAnimationTarget
        if (textTarget != null) {
            // The TextView is also the WindowManager root. Keep its background and hit area fixed.
            textTarget.setLyricsTextAnimationState(alpha, translationY, scaleX, scaleY)
        } else {
            view.alpha = alpha
            view.translationY = translationY
            view.scaleX = scaleX
            view.scaleY = scaleY
        }
    }

    private fun animateTextToRest(view: TextView, durationMs: Long) {
        val interpolator = DecelerateInterpolator()
        val textTarget = view as? LyricsTextAnimationTarget
        if (textTarget != null) {
            textTarget.animateLyricsTextToRest(durationMs, interpolator)
        } else {
            view.animate()
                .alpha(AirUiTokens.Motion.RestAlpha)
                .translationY(0f)
                .scaleX(AirUiTokens.Motion.RestScale)
                .scaleY(AirUiTokens.Motion.RestScale)
                .setDuration(durationMs)
                .setInterpolator(interpolator)
                .start()
        }
    }

    private fun CharSequence.isNotBlankText(): Boolean = toString().isNotBlank()

    fun getEstimatedPositionMs(): Long {
        return (getEstimatedPlaybackPositionMs() + lyricsOffsetMs).coerceAtLeast(0L)
    }

    private fun getEstimatedPlaybackPositionMs(nowUptimeMs: Long = uptimeMillisProvider()): Long {
        if (!currentIsPlaying || !playbackAnchored) {
            return currentPositionMs.coerceAtLeast(0L)
        }

        val elapsedMs = nowUptimeMs - lastPositionUpdateUptimeMs
        return (currentPositionMs + (elapsedMs.coerceAtLeast(0L) * playbackSpeed.toDouble()).toLong()).coerceAtLeast(0L)
    }

    private fun isStalePlayingBacktrack(
        positionMs: Long,
        incomingIsPlaying: Boolean,
        estimatedBeforeUpdateMs: Long
    ): Boolean {
        if (!currentIsPlaying || !incomingIsPlaying || !playbackAnchored) {
            return false
        }

        val backtrackMs = estimatedBeforeUpdateMs - positionMs
        return backtrackMs in 1L..STALE_PLAYING_BACKTRACK_MS
    }

    companion object {
        private const val STALE_PLAYING_BACKTRACK_MS = 1_500L
    }
}
