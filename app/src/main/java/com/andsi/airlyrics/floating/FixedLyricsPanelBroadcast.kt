package com.andsi.airlyrics.floating

import android.content.Context
import android.content.Intent
import android.content.IntentFilter

internal object FixedLyricsPanelBroadcast {
    private const val ACTION_CHANGED = "com.andsi.airlyrics.FIXED_PANEL_CHANGED"
    fun filter() = IntentFilter(ACTION_CHANGED)
    fun send(context: Context) = context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
}
