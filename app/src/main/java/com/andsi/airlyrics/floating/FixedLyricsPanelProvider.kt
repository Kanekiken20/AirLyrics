package com.andsi.airlyrics.floating

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.util.Log
import com.andsi.airlyrics.BuildConfig
import com.andsi.airlyrics.settings.store.LyricsDisplaySettingsStore

/** The system owns placement. A provider declaration does not establish Samsung AOD support. */
class FixedLyricsPanelProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isNotEmpty()) {
            LyricsDisplaySettingsStore.enablePanelOnFirstPlacement(context)
        }
        if (BuildConfig.DEBUG) Log.i("AirLyricsPanel", "Widget update ids=${appWidgetIds.contentToString()}")
        FixedLyricsPanel.renderCached(context, manager, appWidgetIds)
        requestRefresh(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle
    ) {
        if (BuildConfig.DEBUG) {
            val category = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY)
            val width = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            val height = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            val maxW = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            val maxH = newOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            @Suppress("DEPRECATION")
            val sizes = newOptions.getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            Log.i(
                "AirLyricsPanel",
                "Widget options id=$appWidgetId category=$category min=${width}x${height}dp max=${maxW}x${maxH}dp sizes=$sizes"
            )
        }
        FixedLyricsPanel.renderCached(context, manager, intArrayOf(appWidgetId))
        requestRefresh(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        if (BuildConfig.DEBUG) Log.i("AirLyricsPanel", "Widget deleted ids=${appWidgetIds.contentToString()}")
        requestRefresh(context)
    }

    override fun onDisabled(context: Context) {
        if (BuildConfig.DEBUG) Log.i("AirLyricsPanel", "Widget disabled")
        requestRefresh(context)
    }

    private fun requestRefresh(context: Context) {
        // Reconcile an already-running service; never start an FGS from a widget broadcast.
        FixedLyricsPanelBroadcast.send(context)
    }
}
