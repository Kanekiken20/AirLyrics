package com.andsi.airlyrics.lockscreen

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Suppresses other notifications on the lock screen while lyrics are active.
 *
 * Tier 1 (cleanest, lock-screen-only): Uses WRITE_SECURE_SETTINGS if granted via ADB
 *   to toggle `lock_screen_show_notifications` = 0 on lock and restore on unlock.
 * Tier 2 (fallback without ADB): Uses DND visual policy (SUPPRESSED_EFFECT_NOTIFICATION_LIST)
 *   if Notification Policy Access is granted, restoring original filter/policy on unlock.
 */
class LockScreenNotificationSuppressor(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var activeMode: SuppressionMode = SuppressionMode.NONE
    private var savedLockScreenShowNotifs: Int? = null
    private var savedInterruptionFilter: Int? = null
    private var savedPolicy: NotificationManager.Policy? = null

    fun setSuppressed(shouldSuppress: Boolean) {
        if (shouldSuppress) {
            if (activeMode != SuppressionMode.NONE) return
            if (!tryEnableSecureSettingSuppression()) {
                tryEnableDndSuppression()
            }
        } else {
            restoreIfNeeded()
        }
    }

    fun restoreIfNeeded() {
        when (activeMode) {
            SuppressionMode.SECURE_SETTINGS -> {
                val original = savedLockScreenShowNotifs ?: 1
                runCatching {
                    Settings.Secure.putInt(
                        context.contentResolver,
                        SECURE_KEY_LOCK_SCREEN_NOTIFS,
                        original
                    )
                }
                savedLockScreenShowNotifs = null
            }

            SuppressionMode.DND_POLICY -> {
                if (notificationManager.isNotificationPolicyAccessGranted) {
                    runCatching {
                        savedPolicy?.let { notificationManager.notificationPolicy = it }
                        savedInterruptionFilter?.let {
                            notificationManager.setInterruptionFilter(it)
                        }
                    }
                }
                savedPolicy = null
                savedInterruptionFilter = null
            }

            SuppressionMode.NONE -> Unit
        }
        activeMode = SuppressionMode.NONE
    }

    fun hasSecureSettingsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasDndPolicyPermission(): Boolean {
        return notificationManager.isNotificationPolicyAccessGranted
    }

    private fun tryEnableSecureSettingSuppression(): Boolean {
        if (!hasSecureSettingsPermission()) return false
        return runCatching {
            val current = Settings.Secure.getInt(
                context.contentResolver,
                SECURE_KEY_LOCK_SCREEN_NOTIFS,
                1
            )
            savedLockScreenShowNotifs = current
            if (current != 0) {
                Settings.Secure.putInt(
                    context.contentResolver,
                    SECURE_KEY_LOCK_SCREEN_NOTIFS,
                    0
                )
            }
            activeMode = SuppressionMode.SECURE_SETTINGS
            true
        }.getOrDefault(false)
    }

    private fun tryEnableDndSuppression(): Boolean {
        if (!hasDndPolicyPermission()) return false
        return runCatching {
            savedInterruptionFilter = notificationManager.currentInterruptionFilter
            savedPolicy = notificationManager.notificationPolicy

            val base = savedPolicy
            val suppressedEffects = NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST or
                NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK or
                NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_ON

            val lockPolicy = NotificationManager.Policy(
                base?.priorityCategories ?: NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS,
                base?.priorityCallSenders ?: NotificationManager.Policy.PRIORITY_SENDERS_STARRED,
                base?.priorityMessageSenders ?: NotificationManager.Policy.PRIORITY_SENDERS_STARRED,
                suppressedEffects
            )
            notificationManager.notificationPolicy = lockPolicy
            notificationManager.setInterruptionFilter(
                NotificationManager.INTERRUPTION_FILTER_PRIORITY
            )
            activeMode = SuppressionMode.DND_POLICY
            true
        }.getOrDefault(false)
    }

    private enum class SuppressionMode {
        NONE,
        SECURE_SETTINGS,
        DND_POLICY
    }

    companion object {
        private const val SECURE_KEY_LOCK_SCREEN_NOTIFS = "lock_screen_show_notifications"
    }
}
