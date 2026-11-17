package com.andsi.airlyrics.settings.store

import android.content.Context

/** New overlay geometry is separate from the legacy style keys, so old settings still migrate. */
object FloatingOverlayStore {
    private const val PREFS = "floating_overlay_geometry"

    enum class DockSide { NONE, LEFT, RIGHT }

    data class Geometry(
        val widthFraction: Float? = null,
        val heightFraction: Float? = null,
        val dockSide: DockSide = DockSide.NONE,
        val dockVerticalFraction: Float = 0.5f
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isHoldToEditEnabled(context: Context): Boolean = prefs(context).getBoolean("hold_to_edit", true)
    fun setHoldToEditEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("hold_to_edit", enabled).apply()
    }
    fun isDockingEnabled(context: Context): Boolean = prefs(context).getBoolean("docking_enabled", true)
    fun setDockingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("docking_enabled", enabled).apply()
    }

    internal fun geometry(context: Context): Geometry {
        val prefs = prefs(context)
        fun fraction(key: String): Float? = prefs.getFloat(key, Float.NaN)
            .takeIf { it.isFinite() && it > 0f }?.coerceIn(0.05f, 1f)
        return Geometry(
            widthFraction = fraction("width_fraction"),
            heightFraction = fraction("height_fraction"),
            dockSide = runCatching { DockSide.valueOf(prefs.getString("dock_side", "NONE")!!) }
                .getOrDefault(DockSide.NONE),
            dockVerticalFraction = prefs.getFloat("dock_vertical", 0.5f)
                .takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
        )
    }

    internal fun saveGeometry(context: Context, geometry: Geometry) {
        fun Float?.valid() = this?.takeIf { it.isFinite() }?.coerceIn(0.05f, 1f) ?: Float.NaN
        prefs(context).edit()
            .putFloat("width_fraction", geometry.widthFraction.valid())
            .putFloat("height_fraction", geometry.heightFraction.valid())
            .putString("dock_side", geometry.dockSide.name)
            .putFloat("dock_vertical", geometry.dockVerticalFraction.takeIf { it.isFinite() }
                ?.coerceIn(0f, 1f) ?: 0.5f)
            .apply()
    }

    /** The existing width slider becomes authoritative again after the user changes it. */
    fun clearWidthOverride(context: Context) {
        prefs(context).edit().remove("width_fraction").apply()
    }

    fun resetGeometry(context: Context) {
        prefs(context).edit().remove("width_fraction").remove("height_fraction")
            .remove("dock_side").remove("dock_vertical").apply()
    }
}
