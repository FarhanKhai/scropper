package com.scropper.app

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

data class ShieldConfig(
    val hideYtShorts: Boolean = true,
    val hideIgReels: Boolean = true,
    val hideIgExplore: Boolean = false,
    val backgroundAudio: Boolean = false,
)

/** SharedPreferences-backed toggles. [config] is safe to read from WebView IO threads. */
class ShieldSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("shield", Context.MODE_PRIVATE)

    @Volatile
    var config: ShieldConfig = ShieldConfig(
        hideYtShorts = prefs.getBoolean(KEY_YT_SHORTS, true),
        hideIgReels = prefs.getBoolean(KEY_IG_REELS, true),
        hideIgExplore = prefs.getBoolean(KEY_IG_EXPLORE, false),
        backgroundAudio = prefs.getBoolean(KEY_BG_AUDIO, false),
    )
        private set

    private val _state = mutableStateOf(config)
    val state: State<ShieldConfig> get() = _state

    fun update(transform: (ShieldConfig) -> ShieldConfig) {
        val next = transform(config)
        if (next == config) return
        config = next
        _state.value = next
        prefs.edit()
            .putBoolean(KEY_YT_SHORTS, next.hideYtShorts)
            .putBoolean(KEY_IG_REELS, next.hideIgReels)
            .putBoolean(KEY_IG_EXPLORE, next.hideIgExplore)
            .putBoolean(KEY_BG_AUDIO, next.backgroundAudio)
            .apply()
    }

    private companion object {
        const val KEY_YT_SHORTS = "hide_yt_shorts"
        const val KEY_IG_REELS = "hide_ig_reels"
        const val KEY_IG_EXPLORE = "hide_ig_explore"
        const val KEY_BG_AUDIO = "background_audio"
    }
}
