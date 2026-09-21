package com.example

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

interface ServerCallback {
    fun onChannelAdded(name: String, url: String)
    fun onChannelListUpdated(channels: List<Channel>)
    fun onPlayChannelRequested(index: Int)
    fun getChannels(): List<Channel>
    fun onNetworkPlaylistRequested(url: String)
    fun logToTerminal(message: String)
    fun onCollectIptvRefreshRequested(onComplete: (Int) -> Unit)
    fun getSettings(): JSONObject
    fun saveSettings(settings: JSONObject)
}

class LocalTvServer(private val port: Int, private val callback: ServerCallback) {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        if (isRunning) return
        isRunning = true
        Thread {
            try {
                serverSocket = ServerSocket(port)
                Log.d("LocalTvServer", "Server started on port $port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    Thread { handleClient(socket) }.start()
                }
            } catch (e: Exception) {
                Log.e("LocalTvServer", "Error in server thread", e)
            }
        }.start()
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e("LocalTvServer", "Error closing server", e)
        }
    }

    private fun handleClient(socket: Socket) {
        var reader: BufferedReader? = null
        var output: OutputStream? = null
        try {
            reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            output = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            Log.d("LocalTvServer", "Request: $requestLine")

            val tokens = requestLine.split(" ")
            if (tokens.size < 2) return
            val method = tokens[0]
            val path = tokens[1]

            // Read request headers
            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                if (line!!.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line!!.substring(15).trim().toIntOrNull() ?: 0
                }
            }

            // Read request body if present
            val bodyBuilder = StringBuilder()
            if (contentLength > 0) {
                val buffer = CharArray(1024)
                var bytesRead = 0
                var totalRead = 0
                while (totalRead < contentLength && reader.read(buffer, 0, Math.min(buffer.size, contentLength - totalRead)).also { bytesRead = it } != -1) {
                    bodyBuilder.append(buffer, 0, bytesRead)
                    totalRead += bytesRead
                }
            }
            val requestBody = bodyBuilder.toString()

            when {
                // GET / -> Serve HTML
                method == "GET" && path == "/" -> {
                    serveHtml(output)
                }
                
                // GET /api/channels -> Return JSON list of channels
                method == "GET" && path == "/api/channels" -> {
                    serveJsonChannels(output)
                }

                // POST /api/add -> Add channel and return JSON
                method == "POST" && (path == "/api/add" || path == "/add") -> {
                    val (name, url) = parseAddRequest(requestBody)
                    if (name.isNotEmpty() && url.isNotEmpty()) {
                        if (!M3u8Validator.isValid(url)) {
                            serveError(output, 400, "Bad Request - Invalid stream URL format (Must be M3U8/MP4/etc.)")
                        } else {
                            mainHandler.post {
                                callback.onChannelAdded(name, url)
                            }
                            serveJsonSuccess(output, callback.getChannels() + Channel(name, url))
                        }
                    } else {
                        serveError(output, 400, "Bad Request - Invalid name or url")
                    }
                }

                // POST /api/config/url -> Import network playlist URL
                method == "POST" && path == "/api/config/url" -> {
                    try {
                        val obj = JSONObject(requestBody)
                        val urlStr = obj.optString("url", "").trim()
                        if (urlStr.isNotEmpty()) {
                            mainHandler.post {
                                callback.onNetworkPlaylistRequested(urlStr)
                            }
                            
                            val parsedChannels = OnlinePlaylistParser.fetchAndParse(urlStr) { progress ->
                                callback.logToTerminal(progress)
                            }
                            
                            if (parsedChannels.isNotEmpty()) {
                                mainHandler.post {
                                    callback.onChannelListUpdated(parsedChannels)
                                }
                                serveJsonSuccess(output, parsedChannels)
                            } else {
                                serveError(output, 400, "Error: No channels could be parsed from the provided URL.")
                            }
                        } else {
                            serveError(output, 400, "Bad Request - Invalid URL")
                        }
                    } catch (e: Exception) {
                        serveError(output, 400, "Bad Request")
                    }
                }

                // POST /api/channels -> Replace complete channel list (batch upload)
                method == "POST" && path == "/api/channels" -> {
                    try {
                        val trimmedBody = requestBody.trim()
                        val newList = mutableListOf<Channel>()
                        if (trimmedBody.startsWith("[")) {
                            val channelsArray = JSONArray(trimmedBody)
                            for (i in 0 until channelsArray.length()) {
                                val obj = channelsArray.getJSONObject(i)
                                val name = obj.optString("name", "Unnamed").trim().ifEmpty { "未命名" }
                                val url = obj.optString("url", "").trim()
                                if (url.isNotEmpty() && M3u8Validator.isValid(url)) {
                                    newList.add(Channel(name, url))
                                }
                            }
                        } else {
                            // Robust server-side raw text parsing (M3U or TXT)
                            val parsed = OnlinePlaylistParser.parseRawText(requestBody)
                            newList.addAll(parsed)
                        }

                        if (newList.isNotEmpty()) {
                            mainHandler.post {
                                callback.onChannelListUpdated(newList)
                            }
                            serveJsonSuccess(output, newList)
                        } else {
                            serveError(output, 400, "Error: No channels could be parsed from the provided input.")
                        }
                    } catch (e: Exception) {
                        serveError(output, 400, "Bad Request - Invalid format: ${e.message}")
                    }
                }

                // POST /api/play -> Select channel on TV
                method == "POST" && path == "/api/play" -> {
                    try {
                        val obj = JSONObject(requestBody)
                        val index = obj.getInt("index")
                        mainHandler.post {
                            callback.onPlayChannelRequested(index)
                        }
                        serveJsonSuccess(output, callback.getChannels())
                    } catch (e: Exception) {
                        serveError(output, 400, "Bad Request - Invalid index")
                    }
                }

                // POST or GET /api/collect-iptv/refresh -> Remote trigger for Collect-IPTV auto crawl and sync
                (method == "POST" || method == "GET") && (path == "/api/collect-iptv/refresh" || path == "/api/refresh") -> {
                    mainHandler.post {
                        callback.logToTerminal("收到远程接口请求：一键刷新并同步 Collect-IPTV 节目源...")
                        callback.onCollectIptvRefreshRequested { count ->
                            callback.logToTerminal("Collect-IPTV 节目源已自动合并去重，共上线 $count 个优质频道。")
                        }
                    }
                    // Wait a moment for processing to kick off
                    Thread.sleep(300)
                    serveJsonSuccess(output, callback.getChannels())
                }

                // GET /api/settings -> Return current settings JSON
                method == "GET" && path == "/api/settings" -> {
                    try {
                        val settings = callback.getSettings()
                        serveJsonResponse(output, settings.toString())
                    } catch (e: Exception) {
                        serveError(output, 500, "Internal Server Error: ${e.message}")
                    }
                }

                // POST /api/settings -> Save new settings from JSON
                method == "POST" && path == "/api/settings" -> {
                    try {
                        val obj = JSONObject(requestBody)
                        mainHandler.post {
                            callback.saveSettings(obj)
                        }
                        serveJsonResponse(output, "{\"status\":\"success\"}")
                    } catch (e: Exception) {
                        serveError(output, 400, "Bad Request: ${e.message}")
                    }
                }

                // POST /api/delete -> Delete channel
                method == "POST" && path == "/api/delete" -> {
                    try {
                        val obj = JSONObject(requestBody)
                        val index = obj.getInt("index")
                        val currentList = callback.getChannels().toMutableList()
                        if (index in currentList.indices) {
                            currentList.removeAt(index)
                            mainHandler.post {
                                callback.onChannelListUpdated(currentList)
                            }
                            serveJsonSuccess(output, currentList)
                        } else {
                            serveError(output, 404, "Channel Not Found")
                        }
                    } catch (e: Exception) {
                        serveError(output, 400, "Bad Request")
                    }
                }

                else -> {
                    serveError(output, 404, "Not Found")
                }
            }

        } catch (e: Exception) {
            Log.e("LocalTvServer", "Error handling socket client", e)
        } finally {
            try {
                output?.close()
                reader?.close()
                socket.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun parseAddRequest(body: String): Pair<String, String> {
        var name = ""
        var url = ""
        try {
            val trimmedBody = body.trim()
            if (trimmedBody.startsWith("{")) {
                // Parse as JSON object
                val obj = JSONObject(trimmedBody)
                name = obj.optString("name", "").trim()
                url = obj.optString("url", "").trim()
            } else {
                // Parse as formurlencoded name=xxx&url=yyy
                val pairs = trimmedBody.split("&")
                for (pair in pairs) {
                    val idx = pair.indexOf('=')
                    if (idx != -1) {
                        val key = pair.substring(0, idx).trim()
                        val rawValue = pair.substring(idx + 1)
                        val value = URLDecoder.decode(rawValue, "UTF-8").trim()
                        if (key == "name") name = value
                        if (key == "url") url = value
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LocalTvServer", "Error parsing body parameters", e)
        }
        return Pair(name, url)
    }

    private fun serveHtml(output: OutputStream) {
        val html = getHtmlContent()
        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${html.toByteArray(Charsets.UTF_8).size}\r\n" +
                "Connection: close\r\n\r\n" +
                html
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun serveJsonChannels(output: OutputStream) {
        val channels = callback.getChannels()
        val jsonArray = JSONArray()
        for (channel in channels) {
            val obj = JSONObject()
            obj.put("name", channel.name)
            obj.put("url", channel.url)
            jsonArray.put(obj)
        }
        val responseText = jsonArray.toString()
        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${responseText.toByteArray(Charsets.UTF_8).size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n" +
                responseText
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun serveJsonSuccess(output: OutputStream, currentChannelsList: List<Channel>) {
        val jsonArray = JSONArray()
        for (channel in currentChannelsList) {
            val obj = JSONObject()
            obj.put("name", channel.name)
            obj.put("url", channel.url)
            jsonArray.put(obj)
        }
        val responseText = jsonArray.toString()
        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${responseText.toByteArray(Charsets.UTF_8).size}\r\n" +
                "Connection: close\r\n\r\n" +
                responseText
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun serveJsonResponse(output: OutputStream, jsonText: String) {
        val response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${jsonText.toByteArray(Charsets.UTF_8).size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n" +
                jsonText
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun serveError(output: OutputStream, code: Int, message: String) {
        val body = "{\"error\": \"$message\"}"
        val response = "HTTP/1.1 $code Error\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                "Connection: close\r\n\r\n" +
                body
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun getHtmlContent(): String {
        return """<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>TV Live 远程配置后台</title>
    <style>
        :root {
            --primary: #FF2A6D;
            --secondary: #05D9E8;
            --bg: #0A0A0C;
            --card-bg: rgba(255, 255, 255, 0.05);
            --border: rgba(255, 255, 255, 0.1);
        }
        body {
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
            background-color: var(--bg);
            color: #FFFFFF;
            margin: 0;
            padding: 24px;
            display: flex;
            justify-content: center;
            align-items: center;
            min-height: 100vh;
            box-sizing: border-box;
            background-image: radial-gradient(circle at top right, rgba(255, 42, 109, 0.15), transparent 400px),
                              radial-gradient(circle at bottom left, rgba(5, 217, 232, 0.15), transparent 400px);
        }
        .container {
            width: 100%;
            max-width: 650px;
            background: var(--card-bg);
            backdrop-filter: blur(16px);
            -webkit-backdrop-filter: blur(16px);
            border: 1px solid var(--border);
            border-radius: 20px;
            padding: 32px;
            box-shadow: 0 10px 40px rgba(0,0,0,0.5);
        }
        h1 {
            font-size: 28px;
            font-weight: 800;
            margin-top: 0;
            margin-bottom: 8px;
            background: linear-gradient(45deg, var(--primary), var(--secondary));
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            letter-spacing: 1px;
            text-align: center;
        }
        p.subtitle {
            font-size: 14px;
            color: rgba(255, 255, 255, 0.5);
            text-align: center;
            margin-bottom: 32px;
        }
        .section-title {
            font-size: 16px;
            font-weight: 600;
            color: var(--secondary);
            margin-bottom: 16px;
            border-bottom: 1px solid var(--border);
            padding-bottom: 8px;
            margin-top: 24px;
        }
        form {
            display: flex;
            flex-direction: column;
            gap: 16px;
            margin-bottom: 24px;
        }
        .form-group {
            display: flex;
            flex-direction: column;
            gap: 6px;
        }
        label {
            font-size: 12px;
            font-weight: bold;
            color: rgba(255, 255, 255, 0.7);
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }
        input[type="text"], select, textarea {
            background: rgba(0, 0, 0, 0.3);
            border: 1px solid var(--border);
            border-radius: 8px;
            padding: 12px 16px;
            color: #FFFFFF;
            font-size: 15px;
            transition: all 0.25s ease;
            outline: none;
            width: 100%;
            box-sizing: border-box;
        }
        input[type="text"]:focus, select:focus, textarea:focus {
            border-color: var(--secondary);
            box-shadow: 0 0 10px rgba(5, 217, 232, 0.3);
        }
        .btn {
            background: linear-gradient(135deg, var(--primary) 0%, #D61C54 100%);
            color: white;
            border: none;
            border-radius: 8px;
            padding: 14px;
            font-size: 16px;
            font-weight: bold;
            cursor: pointer;
            transition: all 0.25s ease;
            text-transform: uppercase;
            letter-spacing: 0.5px;
            width: 100%;
        }
        .btn:hover {
            transform: translateY(-1px);
            box-shadow: 0 5px 15px rgba(255, 42, 109, 0.4);
        }
        .btn:active {
            transform: translateY(1px);
        }
        .btn-sec {
            background: rgba(255, 255, 255, 0.08);
            border: 1px solid var(--border);
            color: #FFFFFF;
            margin-top: 10px;
        }
        .btn-sec:hover {
            background: rgba(255, 255, 255, 0.15);
            box-shadow: none;
        }
        .tabs {
            display: flex;
            gap: 4px;
            margin-bottom: 20px;
            border-bottom: 1px solid var(--border);
            padding-bottom: 8px;
        }
        .tab {
            padding: 8px 16px;
            font-size: 13px;
            font-weight: bold;
            color: rgba(255, 255, 255, 0.5);
            cursor: pointer;
            border-radius: 6px;
            transition: all 0.2s ease;
        }
        .tab.active, .tab:hover {
            color: #FFFFFF;
            background: rgba(255, 255, 255, 0.08);
        }
        .tab.active {
            border-bottom: 2px solid var(--secondary);
            border-radius: 6px 6px 0 0;
            background: rgba(5, 217, 232, 0.05);
        }
        .tab-content {
            display: none;
        }
        .tab-content.active {
            display: block;
        }
        .channel-list {
            display: flex;
            flex-direction: column;
            gap: 10px;
            max-height: 250px;
            overflow-y: auto;
            margin-bottom: 24px;
            padding-right: 4px;
        }
        /* custom scrollbar */
        .channel-list::-webkit-scrollbar {
            width: 6px;
        }
        .channel-list::-webkit-scrollbar-thumb {
            background: rgba(255,255,255,0.2);
            border-radius: 4px;
        }
        .channel-item {
            display: flex;
            justify-content: space-between;
            align-items: center;
            background: rgba(255, 255, 255, 0.03);
            border: 1px solid var(--border);
            border-radius: 10px;
            padding: 12px 16px;
        }
        .channel-info {
            display: flex;
            flex-direction: column;
            gap: 2px;
            overflow: hidden;
            margin-right: 12px;
            flex: 1;
        }
        .channel-name {
            font-size: 15px;
            font-weight: 600;
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }
        .channel-url {
            font-size: 12px;
            color: rgba(255,255,255,0.4);
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
        }
        .actions {
            display: flex;
            gap: 8px;
        }
        .action-btn {
            background: transparent;
            border: none;
            padding: 6px;
            cursor: pointer;
            border-radius: 6px;
            display: flex;
            align-items: center;
            justify-content: center;
            transition: all 0.2s;
            font-size: 16px;
        }
        .play-btn {
            color: var(--secondary);
        }
        .play-btn:hover {
            background: rgba(5, 217, 232, 0.15);
        }
        .delete-btn {
            color: var(--primary);
        }
        .delete-btn:hover {
            background: rgba(255, 42, 109, 0.15);
        }
        .empty-state {
            text-align: center;
            padding: 24px;
            color: rgba(255,255,255,0.3);
            font-size: 14px;
        }
        .toast {
            position: fixed;
            bottom: 24px;
            left: 50%;
            transform: translateX(-50%);
            background: rgba(0,0,0,0.8);
            border: 1px solid var(--secondary);
            padding: 10px 20px;
            border-radius: 30px;
            font-size: 14px;
            display: none;
            box-shadow: 0 4px 12px rgba(0,0,0,0.5);
            z-index: 1000;
        }

        /* Styled Switch / Toggle */
        .switch-group {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding: 14px 16px;
            background: rgba(255, 255, 255, 0.02);
            border: 1px solid var(--border);
            border-radius: 10px;
            margin-bottom: 12px;
        }
        .switch-label {
            display: flex;
            flex-direction: column;
            gap: 2px;
            text-align: left;
        }
        .switch-title {
            font-size: 14px;
            font-weight: 600;
            color: #FFFFFF;
        }
        .switch-desc {
            font-size: 11px;
            color: rgba(255, 255, 255, 0.45);
        }
        .toggle-switch {
            position: relative;
            display: inline-block;
            width: 48px;
            height: 24px;
            flex-shrink: 0;
        }
        .toggle-switch input {
            opacity: 0;
            width: 0;
            height: 0;
        }
        .slider {
            position: absolute;
            cursor: pointer;
            top: 0;
            left: 0;
            right: 0;
            bottom: 0;
            background-color: rgba(255,255,255,0.1);
            transition: .3s;
            border-radius: 24px;
            border: 1px solid var(--border);
        }
        .slider:before {
            position: absolute;
            content: "";
            height: 16px;
            width: 16px;
            left: 3px;
            bottom: 3px;
            background-color: rgba(255,255,255,0.7);
            transition: .3s;
            border-radius: 50%;
        }
        input:checked + .slider {
            background-color: rgba(5, 217, 232, 0.2);
            border-color: var(--secondary);
        }
        input:checked + .slider:before {
            transform: translateX(24px);
            background-color: var(--secondary);
        }
    </style>
</head>
<body>
    <div class="container">
        <h1>📺 TV Live 控制中心</h1>
        <p class="subtitle">输入直播源信息，实时推送到电视大屏播放</p>

        <div class="tabs">
            <div class="tab active" data-tab="single">⚡ 单频道</div>
            <div class="tab" data-tab="batch">📦 M3U 批量</div>
            <div class="tab" data-tab="network">🌐 在线订阅源</div>
            <div class="tab" data-tab="collect">🌟 Collect-IPTV 同步</div>
            <div class="tab" data-tab="settings">⚙️ 系统设置</div>
        </div>

        <!-- Tab 1: Single channel -->
        <div id="tab-single" class="tab-content active">
            <form id="add-form" action="/add" method="POST">
                <div class="form-group">
                    <label>频道名称</label>
                    <input type="text" id="name-input" name="name" placeholder="例如：CCTV 1 综合" required>
                </div>
                <div class="form-group" style="margin-top: 12px;">
                    <label>M3U8 播放链接</label>
                    <input type="text" id="url-input" name="url" placeholder="http://.../index.m3u8" required>
                </div>
                <button type="submit" class="btn" style="margin-top: 16px;">🚀 推送到电视</button>
            </form>
        </div>

        <!-- Tab 2: Batch import -->
        <div id="tab-batch" class="tab-content">
            <div class="form-group">
                <label>粘贴 M3U 纯文本内容</label>
                <textarea id="m3u-input" rows="6" placeholder="#EXTM3U&#10;#EXTINF:-1,CCTV1&#10;http://..."></textarea>
            </div>
            <button type="button" id="m3u-btn" class="btn btn-sec" style="margin-top: 12px; width: 100%;">📥 批量导入</button>
        </div>

        <!-- Tab 3: Network subscription -->
        <div id="tab-network" class="tab-content">
            <div class="form-group">
                <label>在线订阅链接 URL (支持 m3u/m3u8/json/txt 格式)</label>
                <input type="text" id="network-url-input" placeholder="http://example.com/playlist.m3u">
            </div>
            <button type="button" id="network-import-btn" class="btn" style="margin-top: 16px;">⚡ 一键同步到电视</button>
        </div>

        <!-- Tab 4: Collect-IPTV Sync -->
        <div id="tab-collect" class="tab-content">
            <div style="background: rgba(5, 217, 232, 0.05); border: 1px solid rgba(5, 217, 232, 0.2); border-radius: 10px; padding: 16px; margin-bottom: 16px; font-size: 13px; line-height: 1.5; color: rgba(255, 255, 255, 0.85);">
                <strong style="color: var(--secondary);">🌟 关于 Collect-IPTV 服务端：</strong><br>
                本项目直连 zilong7728 的高可用 IPTV 直播源。自动在电视后台爬取最新可用频道、执行多重并查去重、自动归并重复项、全天候周期检测及移除失效链接，确保您的电视直播列表始终顺畅可用！
            </div>
            <button type="button" id="collect-sync-btn" class="btn" style="background: linear-gradient(135deg, var(--secondary) 0%, #03808a 100%);">🔥 立即一键抓取并合并去重</button>
        </div>

        <!-- Tab 5: Settings -->
        <div id="tab-settings" class="tab-content">
            <div class="form-group" style="margin-bottom: 16px;">
                <label>Collect-IPTV 节目源订阅地址</label>
                <input type="text" id="collect-iptv-url-input" placeholder="https://raw.githubusercontent.com/.../best_sorted.m3u">
            </div>

            <div class="switch-group">
                <div class="switch-label">
                    <span class="switch-title">开机自启动 APP</span>
                    <span class="switch-desc">允许电视/机顶盒通电开机后，自动加载并启动本款 TV Live 播放端</span>
                </div>
                <label class="toggle-switch">
                    <input type="checkbox" id="auto-start-switch">
                    <span class="slider"></span>
                </label>
            </div>

            <div class="switch-group">
                <div class="switch-label">
                    <span class="switch-title">启动时自动同步 Collect-IPTV</span>
                    <span class="switch-desc">每次启动播放器时，全自动抓取高连通直播源并执行智能去重合并</span>
                </div>
                <label class="toggle-switch">
                    <input type="checkbox" id="auto-sync-switch">
                    <span class="slider"></span>
                </label>
            </div>

            <div class="switch-group">
                <div class="switch-label">
                    <span class="switch-title">定时自动清理过期失效源</span>
                    <span class="switch-desc">每隔30分钟周期轮询检测全部节目连通性，秒级剔除及净化异常播放源</span>
                </div>
                <label class="toggle-switch">
                    <input type="checkbox" id="auto-clean-switch">
                    <span class="slider"></span>
                </label>
            </div>

            <div class="switch-group">
                <div class="switch-label">
                    <span class="switch-title">硬解优先 (硬件解码加速)</span>
                    <span class="switch-desc">优先使用本地芯片硬件加速解码高碼率/4K高清直播源，大幅降低能耗与卡顿</span>
                </div>
                <label class="toggle-switch">
                    <input type="checkbox" id="decoder-hardware-switch">
                    <span class="slider"></span>
                </label>
            </div>

            <div class="switch-group">
                <div class="switch-label">
                    <span class="switch-title">极速秒开 (低延迟缓冲优化)</span>
                    <span class="switch-desc">深度缩减底层播放器缓冲水位，秒级完成握手和数据渲染，实现换台瞬间开播</span>
                </div>
                <label class="toggle-switch">
                    <input type="checkbox" id="buffer-low-latency-switch">
                    <span class="slider"></span>
                </label>
            </div>

            <!-- LemonTV Premium Configurations -->
            <div style="margin-top: 24px; margin-bottom: 12px; border-top: 1px dashed rgba(255,255,255,0.15); padding-top: 16px;">
                <h4 style="color: var(--secondary); margin: 0 0 4px 0; font-size: 15px; letter-spacing: 0.5px;">🍋 LemonTV 智优高阶设置</h4>
                <p style="color: rgba(255, 255, 255, 0.4); margin: 0; font-size: 11px;">设置内置的精品直播源、高画质智能筛选、换台自动切源延迟等</p>
            </div>

            <div class="form-group" style="margin-bottom: 16px;">
                <label>内置精品节目源线路切换</label>
                <select id="lemon-source-mode-select" style="background: rgba(0,0,0,0.3); color: white; border: 1px solid var(--border); border-radius: 8px; padding: 12px 16px; font-size: 15px;">
                    <option value="merge">融合极速全源 (4路备用 + LemonTV 推荐)</option>
                    <option value="lemontv">LemonTV 原装精品源 (全4K精品原画)</option>
                    <option value="migu">咪咕极速专线 (精品体育/卫视)</option>
                    <option value="aptv">APTV高速源</option>
                </select>
            </div>

            <div class="form-group" style="margin-bottom: 16px;">
                <label>底层缓冲开播优化模式</label>
                <select id="playback-buffer-mode-select" style="background: rgba(0,0,0,0.3); color: white; border: 1px solid var(--border); border-radius: 8px; padding: 12px 16px; font-size: 15px;">
                    <option value="fast">极速秒开模式 (缓 1.5s - 极致换台体验)</option>
                    <option value="smart">智能均衡模式 (缓 4.0s - 速度与流畅兼备)</option>
                    <option value="stable">抗卡顿稳定模式 (缓 10.0s - 针对高频卡顿源优化)</option>
                </select>
            </div>

            <div class="form-group" style="margin-bottom: 16px;">
                <label>播放卡顿检测自动重连机制</label>
                <select id="stall-switch-timeout-select" style="background: rgba(0,0,0,0.3); color: white; border: 1px solid var(--border); border-radius: 8px; padding: 12px 16px; font-size: 15px;">
                    <option value="5s">5秒极速切换 (卡顿5s立即切备用源)</option>
                    <option value="10s">10秒平衡切换</option>
                    <option value="18s">18秒智能切换 (默认)</option>
                    <option value="30s">30秒稳定延迟</option>
                    <option value="none">不自动切换 (死守单线路)</option>
                </select>
            </div>

            <div class="switch-group">
                <div class="switch-label">
                    <span class="switch-title">智能画质极剪模式 (精品台保留)</span>
                    <span class="switch-desc">自动检测合并并仅保留央视、各省会级核心卫视、港澳等1080P/4K超清画质频道，秒级净化一切无用杂质小台</span>
                </div>
                <label class="toggle-switch">
                    <input type="checkbox" id="lemon-channel-filter-switch">
                    <span class="slider"></span>
                </label>
            </div>

            <button type="button" id="save-settings-btn" class="btn" style="margin-top: 8px;">💾 保存系统与爬虫设置</button>
        </div>

        <div class="section-title">电视端频道列表</div>
        <div class="channel-list" id="channels-container">
            <div class="empty-state">正在获取电视端列表...</div>
        </div>
    </div>
    <div class="toast" id="toast">已成功推送到电视</div>

    <script>
        function showToast(msg) {
            const t = document.getElementById('toast');
            t.innerText = msg;
            t.style.display = 'block';
            setTimeout(() => { t.style.display = 'none'; }, 2500);
        }

        async function fetchChannels() {
            try {
                const res = await fetch('/api/channels');
                const list = await res.json();
                renderChannels(list);
            } catch (e) {
                console.error("加载频道失败", e);
            }
        }

        function renderChannels(list) {
            const container = document.getElementById('channels-container');
            if (!list || list.length === 0) {
                container.innerHTML = '<div class="empty-state">电视端暂无频道</div>';
                return;
            }
            var html = "";
            for (var i = 0; i < list.length; i++) {
                var item = list[i];
                html += "<div class=\"channel-item\">" +
                    "<div class=\"channel-info\">" +
                        "<div class=\"channel-name\">" + escapeHtml(item.name) + "</div>" +
                        "<div class=\"channel-url\">" + escapeHtml(item.url) + "</div>" +
                    "</div>" +
                    "<div class=\"actions\">" +
                        "<button class=\"action-btn play-btn\" onclick=\"playChannel(" + i + ")\" title=\"播放\">▶</button>" +
                        "<button class=\"action-btn delete-btn\" onclick=\"deleteChannel(" + i + ")\" title=\"删除\">🗑</button>" +
                    "</div>" +
                "</div>";
            }
            container.innerHTML = html;
        }

        function escapeHtml(str) {
            return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#039;");
        }

        async function playChannel(index) {
            try {
                await fetch('/api/play', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ index })
                });
                showToast("⚡ 正在切换电视播放...");
            } catch (e) {
                showToast("❌ 操作失败");
            }
        }

        async function deleteChannel(index) {
            if (!confirm("确定要在电视端删除此频道吗？")) return;
            try {
                const res = await fetch('/api/delete', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ index })
                });
                const list = await res.json();
                renderChannels(list);
                showToast("🗑 频道已删除");
            } catch (e) {
                showToast("❌ 操作失败");
            }
        }

        document.getElementById('add-form').addEventListener('submit', async (e) => {
            e.preventDefault();
            const name = document.getElementById('name-input').value.trim();
            const url = document.getElementById('url-input').value.trim();
            if (!name || !url) return;

            try {
                const res = await fetch('/api/add', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ name, url })
                });
                if (res.ok) {
                    const list = await res.json();
                    renderChannels(list);
                    document.getElementById('name-input').value = '';
                    document.getElementById('url-input').value = '';
                    showToast("🎉 单频道推送并播放成功！");
                } else {
                    const err = await res.json();
                    showToast("❌ 推送失败: " + (err.error || "未知的流媒体格式/参数错误"));
                }
            } catch (e) {
                showToast("❌ 无法连接到电视后端");
            }
        });

        document.getElementById('m3u-btn').addEventListener('click', async () => {
            const raw = document.getElementById('m3u-input').value.trim();
            if (!raw) {
                showToast("❌ 请输入或粘贴播放列表内容");
                return;
            }

            try {
                showToast("⏳ 正在推送到电视并解析列表中...");
                const res = await fetch('/api/channels', {
                    method: 'POST',
                    headers: { 'Content-Type': 'text/plain; charset=utf-8' },
                    body: raw
                });
                if (res.ok) {
                    const list = await res.json();
                    renderChannels(list);
                    document.getElementById('m3u-input').value = '';
                    showToast("🎉 成功批量导入 " + list.length + " 个频道！");
                } else {
                    const err = await res.json();
                    showToast("❌ 导入失败: " + (err.error || "未解析出有效的播放链接"));
                }
            } catch (e) {
                showToast("❌ 导入失败，无法连接到电视");
            }
        });

        // Tab switching logic
        const tabs = document.querySelectorAll('.tab');
        tabs.forEach(tab => {
            tab.addEventListener('click', () => {
                document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
                document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
                tab.classList.add('active');
                document.getElementById('tab-' + tab.dataset.tab).classList.add('active');
            });
        });

        // Network subscription import logic
        document.getElementById('network-import-btn').addEventListener('click', async () => {
            const url = document.getElementById('network-url-input').value.trim();
            if (!url) {
                showToast("❌ 请输入有效的订阅链接 URL");
                return;
            }
            try {
                showToast("⏳ 正在请求电视下载并解析订阅源...");
                const res = await fetch('/api/config/url', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ url })
                });
                if (res.ok) {
                    const data = await res.json();
                    renderChannels(data);
                    showToast("🎉 网络订阅源导入并同步成功！");
                    document.getElementById('network-url-input').value = '';
                } else {
                    const err = await res.json();
                    showToast("❌ 导入失败: " + (err.error || "服务器错误"));
                }
            } catch (e) {
                showToast("❌ 无法连接到电视后端");
            }
        });

        // Collect-IPTV sync logic
        document.getElementById('collect-sync-btn').addEventListener('click', async () => {
            try {
                showToast("⏳ 正在请求电视下载、智能去重并同步 Collect-IPTV 节目源...");
                const res = await fetch('/api/collect-iptv/refresh', {
                    method: 'POST'
                });
                if (res.ok) {
                    const data = await res.json();
                    renderChannels(data);
                    showToast("🎉 Collect-IPTV 自动抓取与去重合并成功！");
                } else {
                    const err = await res.json();
                    showToast("❌ 自动同步失败: " + (err.error || "服务器检测异常"));
                }
            } catch (e) {
                showToast("❌ 无法连接到电视后端");
            }
        });

        // Fetch current system settings from backend
        async function fetchSettings() {
            try {
                const res = await fetch('/api/settings');
                if (res.ok) {
                    const settings = await res.json();
                    document.getElementById('collect-iptv-url-input').value = settings.collect_iptv_url || '';
                    document.getElementById('auto-start-switch').checked = !!settings.auto_start_enabled;
                    document.getElementById('auto-sync-switch').checked = !!settings.auto_sync_collect;
                    document.getElementById('auto-clean-switch').checked = !!settings.auto_clean_expired;
                    document.getElementById('decoder-hardware-switch').checked = !!settings.decoder_hardware;
                    document.getElementById('buffer-low-latency-switch').checked = !!settings.buffer_low_latency;
                    if (settings.lemon_source_mode) {
                        document.getElementById('lemon-source-mode-select').value = settings.lemon_source_mode;
                    }
                    if (settings.playback_buffer_mode) {
                        document.getElementById('playback-buffer-mode-select').value = settings.playback_buffer_mode;
                    }
                    if (settings.stall_switch_timeout) {
                        document.getElementById('stall-switch-timeout-select').value = settings.stall_switch_timeout;
                    }
                    document.getElementById('lemon-channel-filter-switch').checked = !!settings.lemon_channel_filter;
                }
            } catch (e) {
                console.error("加载系统配置失败", e);
            }
        }

        // Save system settings to backend
        document.getElementById('save-settings-btn').addEventListener('click', async () => {
            const url = document.getElementById('collect-iptv-url-input').value.trim();
            const autoStart = document.getElementById('auto-start-switch').checked;
            const autoSync = document.getElementById('auto-sync-switch').checked;
            const autoClean = document.getElementById('auto-clean-switch').checked;
            const decoderHardware = document.getElementById('decoder-hardware-switch').checked;
            const bufferLowLatency = document.getElementById('buffer-low-latency-switch').checked;
            const lemonSourceMode = document.getElementById('lemon-source-mode-select').value;
            const playbackBufferMode = document.getElementById('playback-buffer-mode-select').value;
            const stallSwitchTimeout = document.getElementById('stall-switch-timeout-select').value;
            const lemonChannelFilter = document.getElementById('lemon-channel-filter-switch').checked;
            
            try {
                showToast("⏳ 正在保存系统设置...");
                const res = await fetch('/api/settings', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        collect_iptv_url: url,
                        auto_start_enabled: autoStart,
                        auto_sync_collect: autoSync,
                        auto_clean_expired: autoClean,
                        decoder_hardware: decoderHardware,
                        buffer_low_latency: bufferLowLatency,
                        lemon_source_mode: lemonSourceMode,
                        playback_buffer_mode: playbackBufferMode,
                        stall_switch_timeout: stallSwitchTimeout,
                        lemon_channel_filter: lemonChannelFilter
                    })
                });
                if (res.ok) {
                    showToast("🎉 系统与爬虫配置已实时更新并同步到电视！");
                } else {
                    showToast("❌ 配置保存失败");
                }
            } catch (e) {
                showToast("❌ 无法连接到电视后端");
            }
        });

        // Initial fetch
        fetchChannels();
        fetchSettings();
    </script>
</body>
</html>"""
    }
}
