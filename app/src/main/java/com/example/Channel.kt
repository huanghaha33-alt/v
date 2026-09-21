package com.example

enum class ChannelStatus {
    PENDING,  // Validation/Checking in progress
    ONLINE,   // Stream is reachable and online
    OFFLINE   // Stream is broken, timed out or unreachable
}

data class Channel(
    val name: String,
    var url: String,
    var urls: List<String> = listOf(url),
    var currentUrlIndex: Int = 0,
    var isValidFormat: Boolean = M3u8Validator.isValid(url),
    var status: ChannelStatus = ChannelStatus.PENDING
) {
    init {
        val upperName = name.uppercase()
        if (upperName.contains("CCTV") || upperName.contains("央视")) {
            val miguUrls = urls.filter { it.lowercase().contains("migu") || it.lowercase().contains("mgsp") }
            val otherUrls = urls.filter { !it.lowercase().contains("migu") && !it.lowercase().contains("mgsp") }
            val reordered = otherUrls + miguUrls
            if (reordered.isNotEmpty() && reordered != urls) {
                urls = reordered
                url = reordered[0]
            }
        }
    }

    // Get the currently active URL for playback
    fun getActiveUrl(): String {
        return if (currentUrlIndex in urls.indices) {
            urls[currentUrlIndex]
        } else {
            url
        }
    }
}
