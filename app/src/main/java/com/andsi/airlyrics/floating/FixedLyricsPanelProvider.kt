package com.andsi.airlyrics.floating

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** The system owns placement. A provider declaration does not establish Samsung AOD support. */
class FixedLyricsPanelProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        FixedLyricsPanel.renderCached(context, manager, appWidgetIds)
        requestRefresh(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle
    ) {
        FixedLyricsPanel.renderCached(context, manager, intArrayOf(appWidgetId))
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) = requestRefresh(context)
    override fun onDisabled(context: Context) = requestRefresh(context)

    private fun requestRefresh(context: Context) {
        // Reconcile an already-running service; never start an FGS from a widget broadcast.
        FixedLyricsPanelBroadcast.send(context)
    }
}
