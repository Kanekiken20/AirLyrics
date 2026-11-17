package com.andsi.airlyrics.floating

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.andsi.airlyrics.R
import com.andsi.airlyrics.core.model.LockScreenLyricPrivacy
import com.andsi.airlyrics.core.model.LyricsDisplaySettings
import com.andsi.airlyrics.settings.store.LyricsDisplaySettingsStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LockScreenLyricsNotificationTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val manager = application.getSystemService(NotificationManager::class.java)
    private lateinit var presenter: LockScreenLyricsNotification
    private val line = LockScreenLyricContent(1L, "Song title", "Artist name", "First lyric", true)

    @Before
    fun setUp() {
        application.getSharedPreferences("lyrics_display", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowNotificationManager.reset()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(true)
        presenter = LockScreenLyricsNotification(application)
    }

    @After
    fun tearDown() {
        application.getSharedPreferences("lyrics_display", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowNotificationManager.reset()
    }

    @Test
    fun optInIsRequiredAndDisablingRemovesTheExistingLine() {
        assertFalse(presenter.update(line, visible = true))
        assertNull(posted())
        LyricsDisplaySettingsStore.setEnabled(application, true)
        assertTrue(presenter.update(line, visible = true))
        assertNotNull(posted())
        LyricsDisplaySettingsStore.setEnabled(application, false)
        assertFalse(presenter.update(line, visible = true))
        assertNull(posted())
    }

    @Test
    fun privateDefaultHasNoSongOrLyricInPublicVersion() {
        val notification = presenter.build(line, LyricsDisplaySettings(enabled = true))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(line.line, notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        val publicVersion = notification.publicVersion
        assertNotNull(publicVersion)
        assertEquals(application.getString(R.string.ui_lock_lyrics_title),
            publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(application.getString(R.string.ui_lock_lyrics_redacted),
            publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(publicVersion.extras.toString().contains(line.title))
        assertFalse(publicVersion.extras.toString().contains(line.artist))
        assertFalse(publicVersion.extras.toString().contains(line.line))
    }

    @Test
    fun privacyChoiceUsesNativeVisibilityAndNeverImpersonatesMedia() {
        LockScreenLyricPrivacy.entries.forEach { privacy ->
            val notification = presenter.build(line, LyricsDisplaySettings(enabled = true, privacy = privacy))
            val expected = when (privacy) {
                LockScreenLyricPrivacy.PRIVATE -> Notification.VISIBILITY_PRIVATE
                LockScreenLyricPrivacy.PUBLIC -> Notification.VISIBILITY_PUBLIC
                LockScreenLyricPrivacy.HIDDEN -> Notification.VISIBILITY_SECRET
            }
            assertEquals(expected, notification.visibility)
            assertEquals(Notification.CATEGORY_STATUS, notification.category)
            assertFalse(notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
            assertNotNull(notification.contentIntent)
        }
    }

    @Test
    fun metadataAndCompactPreferencesChangeOnlyPresentation() {
        val multiline = line.copy(line = "First line\nSecond line")
        val compact = presenter.build(multiline, LyricsDisplaySettings(enabled = true, showMetadata = false))
        assertEquals(application.getString(R.string.ui_lock_lyrics_title),
            compact.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("First line · Second line", compact.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(compact.extras.containsKey(Notification.EXTRA_BIG_TEXT))
        val expanded = presenter.build(multiline, LyricsDisplaySettings(enabled = true, compact = false))
        assertEquals(multiline.line, expanded.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertTrue(expanded.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains(line.title))
    }

    @Test
    fun unchangedLineIsDeduplicatedAndNewerGenerationRejectsStaleResults() {
        LyricsDisplaySettingsStore.setEnabled(application, true)
        assertTrue(presenter.update(line, visible = true))
        val first = posted()
        assertFalse(presenter.update(line, visible = true))
        assertSame(first, posted())
        assertTrue(presenter.update(line.copy(generation = 2L, line = "Newest lyric"), visible = true))
        assertFalse(presenter.update(line, visible = true))
        assertEquals("Newest lyric", posted()!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun seekOrPrivacyChangeReplacesTheSameNotificationId() {
        LyricsDisplaySettingsStore.setEnabled(application, true)
        assertTrue(presenter.update(line, visible = true))
        assertTrue(presenter.update(line.copy(line = "Seeked line"), visible = true))
        LyricsDisplaySettingsStore.setPrivacy(application, LockScreenLyricPrivacy.PUBLIC)
        assertTrue(presenter.update(line.copy(line = "Seeked line"), visible = true))
        assertEquals(Notification.VISIBILITY_PUBLIC, posted()!!.visibility)
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertFalse(presenter.update(line, visible = false))
        assertNull(posted())
    }

    @Test
    fun notificationPermissionDenialClearsAndDoesNotRetryOrThrow() {
        LyricsDisplaySettingsStore.setEnabled(application, true)
        assertTrue(presenter.update(line, visible = true))
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(presenter.update(line.copy(line = "Private update"), visible = true))
        assertNull(posted())
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(presenter.update(line, visible = true))
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(presenter.update(line, visible = true))
        assertNull(posted())
    }

    @Test
    fun disabledChannelIsRespected() {
        LyricsDisplaySettingsStore.setEnabled(application, true)
        manager.createNotificationChannel(NotificationChannel(
            LockScreenLyricsNotification.CHANNEL_ID, "Blocked", NotificationManager.IMPORTANCE_NONE))
        assertFalse(presenter.update(line, visible = true))
        assertNull(posted())
    }

    @Test
    fun channelAndNotificationStaySilentAcrossLineChanges() {
        LyricsDisplaySettingsStore.setEnabled(application, true)
        assertTrue(presenter.update(line, visible = true))
        val channel = manager.getNotificationChannel(LockScreenLyricsNotification.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.shouldShowLights())
        assertFalse(channel.canShowBadge())
        val notification = posted()!!
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertNull(notification.sound)
        assertNull(notification.vibrate)
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertFalse(presenter.update(null, visible = true))
        assertNull(posted())
    }

    private fun posted(): Notification? = shadowOf(manager)
        .getNotification(LockScreenLyricsNotification.NOTIFICATION_ID)
}
