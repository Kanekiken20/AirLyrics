package com.andsi.airlyrics.floating

import com.andsi.airlyrics.core.model.LyricPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local, main-thread handoff between the activity and its lyrics service. */
internal object FloatingInAppPreview {
    private val current = MutableStateFlow<LyricPreview?>(null)
    val lyrics = current.asStateFlow()
    var onVisibilityChanged: (() -> Unit)? = null
    var appVisible = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) current.value = null
            onVisibilityChanged?.invoke()
        }

    fun publish(line: LyricPreview?) {
        current.value = line.takeIf { appVisible }
    }
}
