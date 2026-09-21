package com.example

object M3u8Validator {
    /**
     * Validates if a URL is a valid live stream format.
     */
    fun isValid(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        val lower = trimmed.lowercase()
        return lower.startsWith("http://") ||
               lower.startsWith("https://") ||
               lower.startsWith("rtmp://") ||
               lower.startsWith("rtsp://")
    }
}
