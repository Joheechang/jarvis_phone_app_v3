package com.rainbowcomputer.jarvis

import android.content.Context

/**
 * PC 쪽 "폰 연동 서버"(LocalCommandServer.gd) 접속 정보를 SharedPreferences에
 * 저장/로드하는 아주 얇은 래퍼. PC 쪽은 Settings.gd의
 * local_voice_port / local_voice_use_tls / get_or_create_local_voice_token()에
 * 대응하는 값들이라, 여기 필드 이름도 그와 짝이 맞게 지었다.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, 8765)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var useHttps: Boolean
        get() = prefs.getBoolean(KEY_HTTPS, false)
        set(value) = prefs.edit().putBoolean(KEY_HTTPS, value).apply()

    var alwaysOnNotification: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_ALWAYS_ON, value).apply()

    /** 사용자가 직접 정하는 호출어(예: "자비스", "레인보우" 등). 기본값 "자비스". */
    var wakeWordText: String
        get() = prefs.getString(KEY_WAKE_WORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WAKE_WORD, value).apply()

    /** "호출어로 항상 대기" 기능을 켰는지 여부. */
    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, value).apply()

    fun effectiveWakeWord(): String = wakeWordText.ifBlank { "자비스" }

    fun isConfigured(): Boolean = host.isNotBlank() && token.isNotBlank()

    fun baseUrl(): String {
        val scheme = if (useHttps) "https" else "http"
        return "$scheme://$host:$port"
    }

    companion object {
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_HTTPS = "https"
        private const val KEY_ALWAYS_ON = "always_on_notification"
        private const val KEY_WAKE_WORD = "wake_word_text"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
    }
}
