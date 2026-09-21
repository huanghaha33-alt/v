package com.example

import android.util.Log
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object OnlinePlaylistParser {

    /**
     * Downloads the playlist content from the given URL and parses it into a List of Channels.
     * Uses a standard web user-agent to bypass basic hotlink protection.
     */
    fun fetchAndParse(urlStr: String, onProgressUpdate: (String) -> Unit): List<Channel> {
        val channels = mutableListOf<Channel>()
        try {
            onProgressUpdate("正在建立连接...")
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            // Set User-Agent to act as a browser (very important for some streams/hosts!)
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            connection.setRequestProperty("Accept", "*/*")
            
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                onProgressUpdate("连接失败，服务器状态码 $responseCode")
                return emptyList()
            }

            val contentLength = connection.contentLength
            val sizeKb = if (contentLength > 0) "${contentLength / 1024} KB" else "未知大小"
            onProgressUpdate("连接成功，正在下载数据 ($sizeKb)...")

            val inputStream = connection.inputStream
            val bytes = inputStream.readBytes()
            inputStream.close()
            connection.disconnect()

            // Auto-detect encoding (UTF-8 or GBK/GB2312)
            var textContent = String(bytes, Charsets.UTF_8)
            if (textContent.contains("\uFFFD")) {
                try {
                    val gbkCharset = java.nio.charset.Charset.forName("GBK")
                    val gbkText = String(bytes, gbkCharset)
                    val gbkReplacementCount = gbkText.count { it == '\uFFFD' }
                    val utfReplacementCount = textContent.count { it == '\uFFFD' }
                    if (gbkReplacementCount < utfReplacementCount) {
                        textContent = gbkText
                    }
                } catch (e: Exception) {
                    // ignore and fallback to UTF-8
                }
            }

            val lines = textContent.split("\n").map { line ->
                var cleaned = line.trim()
                if (cleaned.startsWith("\uFEFF")) {
                    cleaned = cleaned.substring(1).trim()
                }
                cleaned
            }

            val sizeDisplay = if (textContent.length > 1024) "${textContent.length / 1024} KB" else "${textContent.length} Bytes"
            onProgressUpdate("下载完成 ($sizeDisplay)，正在解析...")

            return parseLines(lines, onProgressUpdate)

        } catch (e: Exception) {
            Log.e("OnlinePlaylistParser", "Error parsing remote playlist", e)
            onProgressUpdate("网络请求/解析时发生错误: ${e.message}")
        }
        return channels
    }

    /**
     * Parses raw text content directly (from a pasted textarea, etc.)
     */
    fun parseRawText(rawText: String, onProgressUpdate: (String) -> Unit = {}): List<Channel> {
        val lines = rawText.split("\n").map { line ->
            var cleaned = line.trim()
            if (cleaned.startsWith("\uFEFF")) {
                cleaned = cleaned.substring(1).trim()
            }
            cleaned
        }
        return parseLines(lines, onProgressUpdate)
    }

    /**
     * Highly robust parser for Lines of JSON, M3U, or TXT playlist formats.
     */
    fun parseLines(lines: List<String>, onProgressUpdate: (String) -> Unit): List<Channel> {
        val channels = mutableListOf<Channel>()
        try {
            val textContent = lines.joinToString("\n")
            // Try JSON parsing first
            if (textContent.trim().startsWith("[")) {
                try {
                    val array = JSONArray(textContent)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val name = obj.optString("name", "未命名").trim()
                        val streamUrl = obj.optString("url", "").trim()
                        if (streamUrl.isNotEmpty() && M3u8Validator.isValid(streamUrl)) {
                            channels.add(Channel(name, streamUrl))
                        }
                    }
                    onProgressUpdate("JSON 格式解析成功: ${channels.size} 个频道")
                    return channels
                } catch (e: Exception) {
                    // fall through
                }
            }

            // Check if it's M3U format
            val hasM3uHeader = lines.any { it.startsWith("#EXTM3U", ignoreCase = true) || it.startsWith("#EXTINF", ignoreCase = true) }
            if (hasM3uHeader) {
                var currentName = ""
                for (l in lines) {
                    if (l.isEmpty()) continue
                    if (l.startsWith("#EXTINF", ignoreCase = true)) {
                        val idx = l.lastIndexOf(',')
                        if (idx != -1) {
                            currentName = l.substring(idx + 1).trim()
                        }
                        if (currentName.isEmpty()) {
                            // Fallback to extraction of tvg-name attribute
                            try {
                                val tvgPattern = java.util.regex.Pattern.compile("""tvg-name="([^"]+)"""", java.util.regex.Pattern.CASE_INSENSITIVE)
                                val matcher = tvgPattern.matcher(l)
                                if (matcher.find()) {
                                    currentName = (matcher.group(1) ?: "").trim()
                                }
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    } else if (l.startsWith("http://", ignoreCase = true) ||
                               l.startsWith("https://", ignoreCase = true) ||
                               l.startsWith("rtmp://", ignoreCase = true) ||
                               l.startsWith("rtsp://", ignoreCase = true)) {
                        val streamUrl = l
                        if (M3u8Validator.isValid(streamUrl)) {
                            channels.add(Channel(currentName.ifEmpty { "未命名频道 ${channels.size + 1}" }, streamUrl))
                        }
                        currentName = ""
                    }
                }
                onProgressUpdate("M3U 格式解析完毕: ${channels.size} 个频道")
                return channels
            }

            // Try TXT format (supports CCTV1,http://... / http://...,CCTV1 / bare URLs)
            for (l in lines) {
                if (l.isEmpty()) continue
                
                val lLower = l.lowercase()
                val isUrlOnly = lLower.startsWith("http://") || lLower.startsWith("https://") || lLower.startsWith("rtmp://") || lLower.startsWith("rtsp://")
                
                if (l.startsWith("#") && !isUrlOnly) {
                    continue
                }

                if (isUrlOnly) {
                    if (M3u8Validator.isValid(l)) {
                        channels.add(Channel("未命名频道 ${channels.size + 1}", l))
                    }
                    continue
                }

                // Split by common delimiters
                val delimiters = listOf(",", "#", ";")
                var delimMatched = false
                for (delim in delimiters) {
                    if (l.contains(delim)) {
                        val parts = l.split(delim, limit = 2)
                        if (parts.size == 2) {
                            val part1 = parts[0].trim()
                            val part2 = parts[1].trim()
                            val part1Lower = part1.lowercase()
                            val part2Lower = part2.lowercase()
                            
                            val isPart1Url = part1Lower.startsWith("http://") || part1Lower.startsWith("https://") || part1Lower.startsWith("rtmp://") || part1Lower.startsWith("rtsp://")
                            val isPart2Url = part2Lower.startsWith("http://") || part2Lower.startsWith("https://") || part2Lower.startsWith("rtmp://") || part2Lower.startsWith("rtsp://")
                            
                            if (isPart2Url && M3u8Validator.isValid(part2)) {
                                channels.add(Channel(part1.ifEmpty { "未命名频道 ${channels.size + 1}" }, part2))
                                delimMatched = true
                                break
                            } else if (isPart1Url && M3u8Validator.isValid(part1)) {
                                channels.add(Channel(part2.ifEmpty { "未命名频道 ${channels.size + 1}" }, part1))
                                delimMatched = true
                                break
                            }
                        }
                    }
                }
                
                // Fallback: If line contains no delimiter but is a valid URL by itself
                if (!delimMatched) {
                    val trimmed = l.trim()
                    val trimmedLower = trimmed.lowercase()
                    if (trimmedLower.startsWith("http://") || trimmedLower.startsWith("https://") || trimmedLower.startsWith("rtmp://") || trimmedLower.startsWith("rtsp://")) {
                        if (M3u8Validator.isValid(trimmed)) {
                            channels.add(Channel("未命名频道 ${channels.size + 1}", trimmed))
                        }
                    }
                }
            }
            onProgressUpdate("TXT 列表/文本格式解析完毕: ${channels.size} 个频道")

        } catch (e: Exception) {
            Log.e("OnlinePlaylistParser", "Error parsing lines", e)
            onProgressUpdate("解析时发生错误: ${e.message}")
        }
        return channels
    }
}
