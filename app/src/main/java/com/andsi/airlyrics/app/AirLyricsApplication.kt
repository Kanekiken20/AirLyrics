package com.andsi.airlyrics.app

import android.app.Application
import com.andsi.airlyrics.app.platform.AppNightMode
import com.andsi.airlyrics.i18n.LanguageSettingsStore

class AirLyricsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (com.andsi.airlyrics.BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectCustomSlowCalls()
                    .penaltyLog()
                    .build()
            )
        }
        com.andsi.airlyrics.core.text.ChineseText.ensureLoaded(this)
        LanguageSettingsStore.initialize(this)
        AppNightMode.applyStoredMode(this)
        com.andsi.airlyrics.lyrics.storage.LyricsStorage.scheduleCacheMaintenance(this)
    }
}
