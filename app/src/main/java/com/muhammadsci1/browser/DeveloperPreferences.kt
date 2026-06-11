package com.muhammadsci1.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebSettings

class DeveloperPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "developer_settings",
        Context.MODE_PRIVATE
    )

    var developerMode: Boolean
        get() = preferences.getBoolean(KEY_DEVELOPER_MODE, false)
        set(value) = preferences.edit().putBoolean(KEY_DEVELOPER_MODE, value).apply()

    var trustedOrigin: String
        get() = preferences.getString(KEY_TRUSTED_ORIGIN, "") ?: ""
        set(value) = preferences.edit().putString(KEY_TRUSTED_ORIGIN, normalizeOrigin(value)).apply()

    var mixedContentMode: Int
        get() = preferences.getInt(KEY_MIXED_CONTENT_MODE, WebSettings.MIXED_CONTENT_NEVER_ALLOW)
        set(value) = preferences.edit().putInt(KEY_MIXED_CONTENT_MODE, value).apply()

    var allowUniversalAccessFromFileUrls: Boolean
        get() = preferences.getBoolean(KEY_ALLOW_UNIVERSAL_FILE_ACCESS, false)
        set(value) = preferences.edit().putBoolean(KEY_ALLOW_UNIVERSAL_FILE_ACCESS, value).apply()

    fun isTrustedPage(url: String?): Boolean {
        if (!developerMode) return false
        val trusted = trustedOrigin
        if (trusted.isBlank()) return false
        return originOf(url) == trusted
    }

    fun isTrustedOrigin(origin: String?): Boolean {
        if (!developerMode) return false
        val trusted = trustedOrigin
        if (trusted.isBlank()) return false
        return normalizeOrigin(origin.orEmpty()) == trusted
    }

    companion object {
        private const val KEY_DEVELOPER_MODE = "developer_mode"
        private const val KEY_TRUSTED_ORIGIN = "trusted_origin"
        private const val KEY_MIXED_CONTENT_MODE = "mixed_content_mode"
        private const val KEY_ALLOW_UNIVERSAL_FILE_ACCESS = "allow_universal_file_access"

        fun normalizeOrigin(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isBlank()) return ""

            val candidate = when {
                trimmed.equals("file://", ignoreCase = true) -> "file://"
                trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) ||
                    trimmed.startsWith("file://", ignoreCase = true) -> trimmed
                else -> "http://$trimmed"
            }

            return try {
                val uri = Uri.parse(candidate)
                val scheme = uri.scheme?.lowercase() ?: return ""
                if (scheme == "file") return "file://"
                val host = uri.host?.lowercase() ?: return ""
                val port = uri.port
                if (port > -1) "$scheme://$host:$port" else "$scheme://$host"
            } catch (_: Throwable) {
                ""
            }
        }

        fun originOf(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return try {
                val uri = Uri.parse(url)
                val scheme = uri.scheme?.lowercase() ?: return null
                if (scheme == "file") return "file://"
                val host = uri.host?.lowercase() ?: return null
                val port = uri.port
                if (port > -1) "$scheme://$host:$port" else "$scheme://$host"
            } catch (_: Throwable) {
                null
            }
        }
    }
}
