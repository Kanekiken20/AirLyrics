package com.andsi.airlyrics.app

import android.animation.ValueAnimator
import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import com.andsi.airlyrics.app.platform.AppNightMode
import com.andsi.airlyrics.core.model.LyricPreview
import com.andsi.airlyrics.core.model.LyricPreviewLine
import com.andsi.airlyrics.floating.FloatingInAppPreview
import com.andsi.airlyrics.ui.navigation.Page
import com.andsi.airlyrics.ui.navigation.SettingsSubPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AirLyricsPolishLayoutTest {
    @Test fun primaryScreensRenderWithThePreviewAndNavigationSeparated() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val oldMode = AppCompatDelegate.getDefaultNightMode()
        val oldFrameMode = ShadowChoreographer.isPaused()
        val oldDurationScale = ValueAnimator.getDurationScale()
        val captures = File("build/reports/airlyrics-polish").apply { mkdirs() }
        ShadowSettings.setCanDrawOverlays(true)
        ShadowChoreographer.setPaused(true)
        setAnimationDurationScale(0f)
        try {
            for (dark in listOf(false, true)) {
                val mode = if (dark) "dark" else "light"
                RuntimeEnvironment.setQualifiers("w392dp-h832dp-port-mdpi")
                AppNightMode.setDark(app, dark)
                Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                    val activity = controller.get()
                    val host = activity.graph.uiHost
                    capture(activity, captures, "media-$mode", 392, 832)
                    host.setFloatingPreviewExpanded(true)
                    host.uiActions.selectPage(Page.FLOATING)
                    FloatingInAppPreview.publish(LyricPreview(LyricPreviewLine("Stay a little longer", ""), null, null))
                    capture(activity, captures, "floating-$mode", 392, 832)
                    assertEquals(activity.getString(R.string.ui_floating), host.tabViews[Page.FLOATING]?.text?.toString())
                    assertTrue(host.floatingPageRefs?.visibilityButton?.let { it.isShown && it.width > 0 } == true)
                    host.uiActions.selectPage(Page.SETTINGS)
                    capture(activity, captures, "settings-$mode", 392, 832)
                    host.uiActions.openSettingsSubPage(SettingsSubPage.LYRICS)
                    capture(activity, captures, "lyrics-$mode", 392, 832)
                }
            }
            RuntimeEnvironment.setQualifiers("w832dp-h392dp-land-mdpi")
            val largeText = Configuration(app.resources.configuration).apply { fontScale = 1.5f }
            @Suppress("DEPRECATION")
            app.resources.updateConfiguration(largeText, app.resources.displayMetrics)
            Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
                val activity = controller.get()
                activity.graph.uiHost.uiActions.selectPage(Page.FLOATING)
                capture(activity, captures, "floating-landscape-large-text", 832, 392)
            }
        } finally {
            setAnimationDurationScale(oldDurationScale)
            ShadowChoreographer.setPaused(oldFrameMode)
            AppCompatDelegate.setDefaultNightMode(oldMode)
        }
    }

    private fun setAnimationDurationScale(scale: Float) {
        ReflectionHelpers.callStaticMethod<Unit>(ValueAnimator::class.java, "setDurationScale",
            ClassParameter.from(Float::class.javaPrimitiveType, scale))
    }

    private fun capture(activity: MainActivity, directory: File, name: String, width: Int, height: Int) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            root.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
