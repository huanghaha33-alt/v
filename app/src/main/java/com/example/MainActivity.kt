package com.example

import android.util.Log

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource

import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.Spinner
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), ServerCallback {

    private lateinit var rvChannels: RecyclerView
    private lateinit var playerView: PlayerView
    private var exoPlayer: ExoPlayer? = null
    private lateinit var progressLoader: ProgressBar
    private lateinit var tvRemoteAddress: TextView
    private lateinit var layoutPlaybackInfo: LinearLayout
    private lateinit var layoutNoVideoPlaceholder: LinearLayout
    private lateinit var tvNowPlayingTitle: TextView
    private lateinit var btnFooterSettings: LinearLayout
    private lateinit var btnFooterRemoteImport: LinearLayout
    private lateinit var btnHeaderRemote: LinearLayout

    private lateinit var channelAdapter: ChannelAdapter
    private val channelList = mutableListOf<Channel>()
    private var server: LocalTvServer? = null
    private var activeTerminalView: ScrollingLogCanvasView? = null
    private var onStatsUpdateNeeded: (() -> Unit)? = null

    // Full screen and inactivity state variables
    private var isFullScreen = false
    private var isSettingsOpen = false
    private var isRemoteImportOpen = false
    private var remoteImportAdapter: SettingsStatusAdapter? = null
    private var currentPlayingChannel: Channel? = null
    private var isPlayingChannel = false
    private var lastBackPressTime = 0L

    private fun confirmExitApp() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackPressTime < 2000) {
            finish()
        } else {
            Toast.makeText(this, "再按一次退出软件", Toast.LENGTH_SHORT).show()
            lastBackPressTime = currentTime
        }
    }

    private val inactivityHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val inactivityRunnable = Runnable {
        if (!isFullScreen && !isSettingsOpen && !isRemoteImportOpen && isPlayingChannel && exoPlayer?.isPlaying == true) {
            setFullScreen(true)
        }
    }

    private val stallHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val stallRunnable = Runnable {
        val channel = currentPlayingChannel
        if (channel != null && isPlayingChannel && exoPlayer?.playbackState == Player.STATE_BUFFERING) {
            switchToNextBackupUrl(channel, "当前线路卡顿/不流畅")
        }
    }

    private val playbackMonitorHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastPlaybackPosition = -1L
    private var positionStallCount = 0
    private val playbackMonitorRunnable = object : Runnable {
        override fun run() {
            val player = exoPlayer
            val channel = currentPlayingChannel
            if (player != null && channel != null && isPlayingChannel && player.playWhenReady) {
                val currentPos = player.currentPosition
                val isBuffering = player.playbackState == Player.STATE_BUFFERING
                
                Log.d("PlaybackMonitor", "Checking position: current=$currentPos, last=$lastPlaybackPosition, state=${player.playbackState}")
                
                if (currentPos == lastPlaybackPosition || isBuffering) {
                    positionStallCount++
                    Log.w("PlaybackMonitor", "Potential stall detected! count=$positionStallCount")
                    
                    if (positionStallCount >= 5) { // 5 * 4s = 20 seconds of no progress
                        positionStallCount = 0
                        lastPlaybackPosition = -1L
                        Log.e("PlaybackMonitor", "Stall count exceeded threshold. Re-connecting/switching to backup...")
                        switchToNextBackupUrl(channel, "播放卡顿/无数据传输")
                    }
                } else {
                    positionStallCount = 0
                    lastPlaybackPosition = currentPos
                }
            } else {
                positionStallCount = 0
                lastPlaybackPosition = -1L
            }
            playbackMonitorHandler.postDelayed(this, 4000)
        }
    }

    private val hudHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val hudRunnable = Runnable {
        layoutPlaybackInfo.visibility = View.GONE
    }

    private fun showPlaybackInfo() {
        layoutPlaybackInfo.visibility = View.VISIBLE
        hudHandler.removeCallbacks(hudRunnable)
        hudHandler.postDelayed(hudRunnable, 5000) // Hides after 5 seconds
    }

    private fun playPreviousChannel() {
        if (channelList.isEmpty()) return
        val currentIndex = channelList.indexOf(currentPlayingChannel)
        val prevIndex = if (currentIndex == -1) {
            channelList.size - 1
        } else {
            (currentIndex - 1 + channelList.size) % channelList.size
        }
        val targetChannel = channelList[prevIndex]
        playChannel(targetChannel, false)
    }

    private fun playNextChannel() {
        if (channelList.isEmpty()) return
        val currentIndex = channelList.indexOf(currentPlayingChannel)
        val nextIndex = if (currentIndex == -1) {
            0
        } else {
            (currentIndex + 1) % channelList.size
        }
        val targetChannel = channelList[nextIndex]
        playChannel(targetChannel, false)
    }

    private fun resetInactivityTimer() {
        inactivityHandler.removeCallbacks(inactivityRunnable)
        if (!isFullScreen && !isSettingsOpen && isPlayingChannel) {
            inactivityHandler.postDelayed(inactivityRunnable, 5000)
        }
    }

    private val periodicCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val periodicCheckRunnable = object : Runnable {
        override fun run() {
            val sharedPrefs = getSharedPreferences("TV_LIVE_PREFS", MODE_PRIVATE)
            val isPeriodicCleanEnabled = sharedPrefs.getBoolean("AUTO_CLEAN_EXPIRED", true)
            if (isPeriodicCleanEnabled) {
                Log.d("MainActivity", "Starting periodic check and cleanup of expired channels...")
                checkAllChannelsConnectivity {
                    runOnUiThread {
                        channelAdapter.notifyDataSetChanged()
                        onStatsUpdateNeeded?.invoke()
                    }
                }
            }

            // Periodic auto-update for three GitHub project TV sources (Every 12 Hours)
            val isPeriodicSyncEnabled = sharedPrefs.getBoolean("PERIODIC_SYNC_GITHUB_ENABLED", true)
            if (isPeriodicSyncEnabled) {
                val lastSyncTime = sharedPrefs.getLong("LAST_GITHUB_SYNC_TIME", 0L)
                val currentTime = System.currentTimeMillis()
                val twelveHoursMs = 12 * 60 * 60 * 1000L
                if (currentTime - lastSyncTime >= twelveHoursMs) {
                    Log.d("MainActivity", "12 hours have passed since last GitHub TV source sync. Starting background periodic update...")
                    fetchAndSyncCollectIptv({ progress ->
                        Log.d("PeriodicGithubSync", progress)
                        runOnUiThread {
                            activeTerminalView?.addLog("[定时更新] $progress")
                        }
                    }, { count ->
                        Log.i("PeriodicGithubSync", "Periodic auto-update of GitHub TV sources complete: Loaded $count channels.")
                        runOnUiThread {
                            channelAdapter.notifyDataSetChanged()
                            onStatsUpdateNeeded?.invoke()
                        }
                    })
                }
            }

            // Repeat check every 30 minutes (1800000 ms)
            periodicCheckHandler.postDelayed(this, 1800000)
        }
    }

    private fun startPeriodicCheck() {
        periodicCheckHandler.removeCallbacks(periodicCheckRunnable)
        periodicCheckHandler.postDelayed(periodicCheckRunnable, 1800000)
    }

    private fun stopPeriodicCheck() {
        periodicCheckHandler.removeCallbacks(periodicCheckRunnable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind Views
        rvChannels = findViewById(R.id.rv_channels)
        playerView = findViewById(R.id.player_view)
        progressLoader = findViewById(R.id.progress_loader)
        tvRemoteAddress = findViewById(R.id.tv_remote_address)
        layoutPlaybackInfo = findViewById(R.id.layout_playback_info)
        layoutNoVideoPlaceholder = findViewById(R.id.layout_no_video_placeholder)
        tvNowPlayingTitle = findViewById(R.id.tv_now_playing_title)
        btnFooterSettings = findViewById(R.id.btn_footer_settings)
        btnFooterRemoteImport = findViewById(R.id.btn_footer_remote_import)
        btnHeaderRemote = findViewById(R.id.btn_header_remote)

        // Load Persistent Channels
        channelList.addAll(loadChannelsFromPrefs())

        // Set up Channel List Adapter with double click and single click support
        channelAdapter = ChannelAdapter(channelList, { selectedChannel ->
            playChannel(selectedChannel)
        }, { doubleClickedChannel ->
            playChannel(doubleClickedChannel)
            setFullScreen(true)
        })
        rvChannels.layoutManager = LinearLayoutManager(this)
        rvChannels.adapter = channelAdapter

        // Bind Player Focus View and setup OK / click full screen toggle
        val layoutPlayerFocus = findViewById<FrameLayout>(R.id.layout_player_focus)
        layoutPlayerFocus.setOnClickListener {
            if (isPlayingChannel) {
                setFullScreen(true)
            } else {
                Toast.makeText(this, "当前无播放内容，无法全屏", Toast.LENGTH_SHORT).show()
            }
        }

        // Set up VideoView listeners
        setupVideoPlayer()

        // Get local IP address and display configuration server URL
        val ipAddress = getLocalIpAddress()
        tvRemoteAddress.text = "http://$ipAddress:8080"

        // Display current local time on clock
        val tvClock = findViewById<TextView>(R.id.tv_clock)
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        tvClock.text = timeFormat.format(Date())

        // Start embedded HTTP Server
        server = LocalTvServer(8080, this)
        server?.start()

        // Setup settings button click listener on footer
        btnFooterSettings.setOnClickListener {
            showSettingsDialog()
        }

        btnFooterRemoteImport.setOnClickListener {
            showRemoteImportDialog()
        }

        btnHeaderRemote.setOnClickListener {
            showRemoteImportDialog()
        }

        // Trigger background connectivity checks or auto-sync from Collect-IPTV
        val sharedPrefs = getSharedPreferences("TV_LIVE_PREFS", MODE_PRIVATE)
        val isFirstLaunch = sharedPrefs.getBoolean("IS_FIRST_LAUNCH_EVER", true)
        
        if (isFirstLaunch || channelList.isEmpty()) {
            sharedPrefs.edit().putBoolean("IS_FIRST_LAUNCH_EVER", false).apply()
            Toast.makeText(this, "⚡ 首次打开软件：正在自动拉取并更新所有最新直播源，请稍候...", Toast.LENGTH_LONG).show()
            
            fetchAndSyncCollectIptv({ progress ->
                Log.d("FirstLaunchSync", progress)
            }, { count ->
                runOnUiThread {
                    Toast.makeText(this, "🎉 首次启动直播源全量自动更新完成，共加载 $count 个频道！", Toast.LENGTH_LONG).show()
                    if (channelList.isNotEmpty() && currentPlayingChannel == null) {
                        val firstChannel = channelList[0]
                        playChannel(firstChannel, true)
                        setFullScreen(true)
                    }
                }
            })
        } else {
            // Auto-play the last played channel (or the first channel) in full screen immediately on startup
            if (channelList.isNotEmpty()) {
                val lastPlayedName = sharedPrefs.getString("LAST_PLAYED_CHANNEL_NAME", null)
                val targetChannel = channelList.find { it.name == lastPlayedName } ?: channelList[0]
                playChannel(targetChannel, false)
                setFullScreen(true)
            }

            val isAutoSyncEnabled = sharedPrefs.getBoolean("AUTO_SYNC_COLLECT", true)
            if (isAutoSyncEnabled) {
                fetchAndSyncCollectIptv({ progress ->
                    Log.d("BootSync", progress)
                }, { count ->
                    Log.i("BootSync", "Collect-IPTV boot synchronization complete. Loaded $count channels.")
                    runOnUiThread {
                        channelAdapter.notifyDataSetChanged()
                    }
                })
            } else {
                checkAllChannelsConnectivity {
                    runOnUiThread {
                        channelAdapter.notifyDataSetChanged()
                    }
                }
            }
        }

        // Start periodic background checks for expired channels
        startPeriodicCheck()

        // Start playback progress and stall/freeze monitor
        playbackMonitorHandler.postDelayed(playbackMonitorRunnable, 4000)

        Toast.makeText(this, "后台远程配置与自动同步服务已开启！", Toast.LENGTH_LONG).show()
    }

    private fun setupVideoPlayer() {
        // Handled in playChannel with ExoPlayer instance creation
    }

    private fun playChannel(channel: Channel, resetIndex: Boolean = false) {
        if (resetIndex) {
            channel.currentUrlIndex = 0
        }
        currentPlayingChannel = channel
        isPlayingChannel = true
        layoutNoVideoPlaceholder.visibility = View.GONE
        progressLoader.visibility = View.VISIBLE
        
        // Hide the player view during the switch transition so the old channel doesn't freeze
        playerView.visibility = View.INVISIBLE
        
        // Remind of the current channel and automatically hide it after 5 seconds
        showPlaybackInfo()
        
        // Remove any pending stall callbacks
        stallHandler.removeCallbacks(stallRunnable)
        
        val activeUrl = channel.getActiveUrl()
        tvNowPlayingTitle.text = if (channel.urls.size > 1) {
            "${channel.name} (线路 ${channel.currentUrlIndex + 1}/${channel.urls.size})"
        } else {
            channel.name
        }

        // Check if player already exists
        var player = exoPlayer
        if (player == null) {
            try {
                val sharedPrefs = getSharedPreferences("TV_LIVE_PREFS", MODE_PRIVATE)
                
                // Save last played channel name so it can be resumed on boot
                sharedPrefs.edit().putString("LAST_PLAYED_CHANNEL_NAME", channel.name).apply()

                val bufferMode = sharedPrefs.getString("PLAYBACK_BUFFER_MODE", "fast") ?: "fast"
                val isHardwarePreferred = sharedPrefs.getBoolean("DECODER_HARDWARE", true)

                // Configure dynamic LoadControl for fast channel startup ("极速秒开") or stable preloading ("稳定预加载")
                val loadControlBuilder = DefaultLoadControl.Builder()
                when (bufferMode) {
                    "fast" -> {
                        // Minimum buffer 1.2s, max buffer 2.5s, fast 400ms startup to minimize latency
                        loadControlBuilder.setBufferDurationsMs(
                            1200, // minBufferMs
                            2500, // maxBufferMs
                            400,  // bufferForPlaybackMs
                            800   // bufferForPlaybackAfterRebufferMs
                        )
                    }
                    "smart" -> {
                        // Balanced preloading buffer (Smart / Intelligent Balance)
                        loadControlBuilder.setBufferDurationsMs(
                            4000,  // minBufferMs
                            8000,  // maxBufferMs
                            1500,  // bufferForPlaybackMs
                            2000   // bufferForPlaybackAfterRebufferMs
                        )
                    }
                    "stable" -> {
                        // High buffering stability mode
                        loadControlBuilder.setBufferDurationsMs(
                            15000, // minBufferMs
                            30000, // maxBufferMs
                            4000,  // bufferForPlaybackMs
                            6000   // bufferForPlaybackAfterRebufferMs
                        )
                    }
                    else -> {
                        // Fallback to standard fast mode
                        loadControlBuilder.setBufferDurationsMs(1200, 2500, 400, 800)
                    }
                }
                val loadControl = loadControlBuilder.build()

                // Optimized HTTP connection setup with specific UA headers & timeouts (15s for high-latency streams)
                val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 VLC/3.0.18"
                val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent(userAgent)
                    .setConnectTimeoutMs(15000) // Robust connect timeout (15s)
                    .setReadTimeoutMs(15000)    // Robust read timeout (15s)
                    .setAllowCrossProtocolRedirects(true)

                // Setup MediaSource factory with our optimized HTTP transport
                val mediaSourceFactory = DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(httpDataSourceFactory)

                // Build ExoPlayer with customized components
                val playerBuilder = ExoPlayer.Builder(this)
                    .setLoadControl(loadControl)
                    .setMediaSourceFactory(mediaSourceFactory)

                player = playerBuilder.build()
                player.playWhenReady = true

                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                progressLoader.visibility = View.VISIBLE
                                stallHandler.removeCallbacks(stallRunnable)
                                // Dynamic stall check timeout from LemonTV premium configurations
                                val stallTimeoutStr = sharedPrefs.getString("STALL_SWITCH_TIMEOUT", "18s") ?: "18s"
                                val stallTimeout = when (stallTimeoutStr) {
                                    "5s" -> 5000L
                                    "10s" -> 10000L
                                    "18s" -> 18000L
                                    "30s" -> 30000L
                                    "none" -> Long.MAX_VALUE
                                    else -> 18000L
                                }
                                if (stallTimeout < Long.MAX_VALUE) {
                                    stallHandler.postDelayed(stallRunnable, stallTimeout)
                                }
                            }
                            Player.STATE_READY -> {
                                progressLoader.visibility = View.GONE
                                playerView.visibility = View.VISIBLE
                                showPlaybackInfo()
                                resetInactivityTimer()
                                stallHandler.removeCallbacks(stallRunnable)
                                
                                // Save/Archive current verified successful working line index
                                val activeChannel = currentPlayingChannel
                                if (activeChannel != null) {
                                    saveChannelsToPrefs(channelList)
                                }
                            }
                            Player.STATE_ENDED -> {
                                progressLoader.visibility = View.GONE
                                playerView.visibility = View.VISIBLE
                                stallHandler.removeCallbacks(stallRunnable)
                            }
                            Player.STATE_IDLE -> {
                                stallHandler.removeCallbacks(stallRunnable)
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val activeChannel = currentPlayingChannel ?: return
                        Log.e("MainActivity", "ExoPlayer error playing ${activeChannel.getActiveUrl()}", error)
                        progressLoader.visibility = View.GONE
                        playerView.visibility = View.VISIBLE
                        stallHandler.removeCallbacks(stallRunnable)
                        
                        val errMsg = when (error.errorCode) {
                            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "解码初始化失败"
                            PlaybackException.ERROR_CODE_DECODING_FAILED -> "视频流解码失败"
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接超时"
                            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "流媒体加载失败"
                            else -> "播放异常"
                        }
                        
                        // Switch to backup line if available
                        if (activeChannel.urls.size > 1) {
                            val nextIndex = (activeChannel.currentUrlIndex + 1) % activeChannel.urls.size
                            if (nextIndex == 0) {
                                // All lines failed for this channel! Do NOT auto switch channel! Show placeholder.
                                activeChannel.currentUrlIndex = 0 // Reset to first line for future retries
                                saveChannelsToPrefs(channelList)
                                Toast.makeText(this@MainActivity, "🚫 当前频道所有线路均无法播放，已停止切换", Toast.LENGTH_LONG).show()
                                layoutNoVideoPlaceholder.visibility = View.VISIBLE
                                return
                            }
                            activeChannel.currentUrlIndex = nextIndex
                            saveChannelsToPrefs(channelList)
                            Toast.makeText(this@MainActivity, "🚫 $errMsg，正自动切换至线路 ${nextIndex + 1}/${activeChannel.urls.size}...", Toast.LENGTH_SHORT).show()
                            playChannel(activeChannel, false)
                            return
                        }

                        // No backup URL, do NOT switch channel
                        Toast.makeText(this@MainActivity, "🚫 $errMsg 且无备用线路", Toast.LENGTH_LONG).show()
                        layoutNoVideoPlaceholder.visibility = View.VISIBLE
                    }
                })

                playerView.player = player
                exoPlayer = player
            } catch (e: Exception) {
                progressLoader.visibility = View.GONE
                playerView.visibility = View.VISIBLE
                Toast.makeText(this, "初始化解码播放出错: ${e.message}", Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            // Reuse existing player
            player.stop()
            player.clearMediaItems()
        }

        try {
            val sharedPrefs = getSharedPreferences("TV_LIVE_PREFS", MODE_PRIVATE)
            // Save last played channel name so it can be resumed on boot
            sharedPrefs.edit().putString("LAST_PLAYED_CHANNEL_NAME", channel.name).apply()

            val mediaItem = MediaItem.fromUri(Uri.parse(activeUrl))
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()

            resetInactivityTimer()
        } catch (e: Exception) {
            progressLoader.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            Toast.makeText(this, "加载播放流出错: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun switchToNextBackupUrl(channel: Channel, reason: String) {
        if (channel.urls.size <= 1) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "⚡ $reason 且无备用线路", Toast.LENGTH_LONG).show()
                layoutNoVideoPlaceholder.visibility = View.VISIBLE
            }
            return
        }
        val nextIndex = (channel.currentUrlIndex + 1) % channel.urls.size
        if (nextIndex == 0) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "⚡ 当前频道所有线路卡顿，已停止换源", Toast.LENGTH_LONG).show()
                channel.currentUrlIndex = 0 // Reset for future visits
                saveChannelsToPrefs(channelList)
                layoutNoVideoPlaceholder.visibility = View.VISIBLE
            }
            return
        }
        channel.currentUrlIndex = nextIndex
        saveChannelsToPrefs(channelList)
        
        runOnUiThread {
            Toast.makeText(this@MainActivity, "⚡ $reason，正在换源：线路 ${nextIndex + 1}/${channel.urls.size}...", Toast.LENGTH_SHORT).show()
            playChannel(channel, false)
        }
    }

    // --- Connectivity Checks ---

    private fun testChannelConnectivity(channel: Channel, callback: (ChannelStatus) -> Unit) {
        Thread {
            var status = ChannelStatus.OFFLINE
            try {
                val url = URL(channel.url)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                val responseCode = connection.responseCode
                if (responseCode in 200..399) {
                    status = ChannelStatus.ONLINE
                }
            } catch (e: Exception) {
                try {
                    val url = URL(channel.url)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    val responseCode = connection.responseCode
                    if (responseCode in 200..399) {
                        status = ChannelStatus.ONLINE
                    }
                } catch (ex: Exception) {
                    status = ChannelStatus.OFFLINE
                }
            }
            runOnUiThread {
                callback(status)
            }
        }.start()
    }

    private fun checkAllChannelsConnectivity(onStatusUpdated: () -> Unit = {}) {
        val copy = synchronized(channelList) { ArrayList(channelList) }
        for (channel in copy) {
            channel.isValidFormat = M3u8Validator.isValid(channel.url)
            testChannelConnectivity(channel) { updatedStatus ->
                channel.status = updatedStatus
                if (updatedStatus == ChannelStatus.OFFLINE || !channel.isValidFormat) {
                    synchronized(channelList) {
                        channelList.remove(channel)
                        saveChannelsToPrefs(channelList)
                    }
                    runOnUiThread {
                        channelAdapter.updateChannels(channelList)
                    }
                } else {
                    onStatusUpdated()
                }
            }
        }
    }

    // --- Settings Overlay Dialog with Live Status Visualization ---

    private fun showSettingsDialog() {
        isSettingsOpen = true
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_settings)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.window?.setBackgroundDrawable(ColorDrawable(0xFF121216.toInt()))

        // Bind Dialog Views
        val ivSettingsQr = dialog.findViewById<ImageView>(R.id.iv_settings_qr)
        val tvSettingsRemoteAddress = dialog.findViewById<TextView>(R.id.tv_settings_remote_address)
        val tvStatTotal = dialog.findViewById<TextView>(R.id.tv_stat_total)
        val tvStatValidFormat = dialog.findViewById<TextView>(R.id.tv_stat_valid_format)
        val tvStatOnline = dialog.findViewById<TextView>(R.id.tv_stat_online)
        val tvStatOffline = dialog.findViewById<TextView>(R.id.tv_stat_offline)
        val rvSettingsStatus = dialog.findViewById<RecyclerView>(R.id.rv_settings_status)
        val btnRetestAll = dialog.findViewById<Button>(R.id.btn_retest_all)
        val btnCloseSettings = dialog.findViewById<Button>(R.id.btn_close_settings)

        // Set Up Remote Address text
        val ipAddress = getLocalIpAddress()
        val remoteUrl = "http://$ipAddress:8080"
        tvSettingsRemoteAddress.text = remoteUrl

        // Load QR Code Image (Utilizing public QR API to point to local server address)
        try {
            val qrCodeApiUrl = "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=" + URLEncoder.encode(remoteUrl, "UTF-8")
            loadQrCodeImage(qrCodeApiUrl, ivSettingsQr)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Set Up Stats and Adapter
        val settingsAdapter = SettingsStatusAdapter(channelList)
        rvSettingsStatus.layoutManager = LinearLayoutManager(this)
        rvSettingsStatus.adapter = settingsAdapter

        // Update Stats & Adapter Views helper
        fun updateStatsViews() {
            val total = channelList.size
            val validFormatCount = channelList.count { it.isValidFormat }
            val onlineCount = channelList.count { it.status == ChannelStatus.ONLINE }
            val offlineCount = channelList.count { it.status == ChannelStatus.OFFLINE }

            tvStatTotal.text = total.toString()
            tvStatValidFormat.text = validFormatCount.toString()
            tvStatOnline.text = onlineCount.toString()
            tvStatOffline.text = offlineCount.toString()

            settingsAdapter.updateChannels(channelList)
        }

        updateStatsViews()

        // RETEST ALL click handler
        btnRetestAll.setOnClickListener {
            for (channel in channelList) {
                channel.status = ChannelStatus.PENDING
            }
            updateStatsViews()
            checkAllChannelsConnectivity {
                runOnUiThread {
                    updateStatsViews()
                    channelAdapter.notifyDataSetChanged()
                }
            }
        }

        onStatsUpdateNeeded = {
            updateStatsViews()
        }

        // Bind Online Subscription components
        val tvSubscriptionInfo = dialog.findViewById<TextView>(R.id.tv_subscription_info)
        val btnSyncRefresh = dialog.findViewById<Button>(R.id.btn_sync_refresh)
        val viewScrollingTerminal = dialog.findViewById<ScrollingLogCanvasView>(R.id.view_scrolling_terminal)
        activeTerminalView = viewScrollingTerminal

        dialog.setOnDismissListener {
            activeTerminalView = null
            onStatsUpdateNeeded = null
            isSettingsOpen = false
            resetInactivityTimer()
        }

        // Display current active subscription source
        val sharedPrefs = getSharedPreferences("TV_LIVE_PREFS", MODE_PRIVATE)

        // Bind Auto-start Switch
        val swAutoStart = dialog.findViewById<Switch>(R.id.sw_auto_start)
        val isAutoStartEnabled = sharedPrefs.getBoolean("AUTO_START_ENABLED", true)
        swAutoStart.isChecked = isAutoStartEnabled
        swAutoStart.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("AUTO_START_ENABLED", isChecked).apply()
            if (isChecked) {
                Toast.makeText(this, "开机自动启动已开启", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "开机自动启动已关闭", Toast.LENGTH_SHORT).show()
            }
        }

        // Bind Auto-sync Multi-source Switch
        val swAutoSyncCollect = dialog.findViewById<Switch>(R.id.sw_auto_sync_collect)
        val isAutoSyncCollectEnabled = sharedPrefs.getBoolean("AUTO_SYNC_COLLECT", true)
        swAutoSyncCollect.isChecked = isAutoSyncCollectEnabled
        swAutoSyncCollect.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("AUTO_SYNC_COLLECT", isChecked).apply()
            if (isChecked) {
                Toast.makeText(this, "启动自动同步多路精品直播源已开启", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "启动自动同步多路精品直播源已关闭", Toast.LENGTH_SHORT).show()
            }
        }

        // Bind Periodic sync GitHub Switch
        val swPeriodicSyncGithub = dialog.findViewById<Switch>(R.id.sw_periodic_sync_github)
        val isPeriodicSyncGithubEnabled = sharedPrefs.getBoolean("PERIODIC_SYNC_GITHUB_ENABLED", true)
        swPeriodicSyncGithub.isChecked = isPeriodicSyncGithubEnabled
        swPeriodicSyncGithub.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("PERIODIC_SYNC_GITHUB_ENABLED", isChecked).apply()
            if (isChecked) {
                Toast.makeText(this, "12小时定时自动更新 GitHub 电视源已开启", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "12小时定时自动更新 GitHub 电视源已关闭", Toast.LENGTH_SHORT).show()
            }
        }

        // Bind Auto-clean Expired Switch
        val swAutoCleanExpired = dialog.findViewById<Switch>(R.id.sw_auto_clean_expired)
        val isAutoCleanExpiredEnabled = sharedPrefs.getBoolean("AUTO_CLEAN_EXPIRED", true)
        swAutoCleanExpired.isChecked = isAutoCleanExpiredEnabled
        swAutoCleanExpired.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("AUTO_CLEAN_EXPIRED", isChecked).apply()
            if (isChecked) {
                Toast.makeText(this, "定时检测并自动清理失效源已开启", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "定时检测并自动清理失效源已关闭", Toast.LENGTH_SHORT).show()
            }
        }

        val currentSubUrl = sharedPrefs.getString("PLAYLIST_SUBSCRIPTION_URL", "") ?: ""
        if (currentSubUrl.isNotEmpty()) {
            tvSubscriptionInfo.text = "配置源头: $currentSubUrl"
        } else {
            tvSubscriptionInfo.text = "未配置在线订阅源"
        }

        btnSyncRefresh.setOnClickListener {
            val subUrl = sharedPrefs.getString("PLAYLIST_SUBSCRIPTION_URL", "") ?: ""
            if (subUrl.isEmpty()) {
                Toast.makeText(this, "电视端尚未配置过在线订阅！请先用手机扫码导入。", Toast.LENGTH_LONG).show()
                viewScrollingTerminal.addLog("未检测到已存储的在线订阅链接 URL！")
                return@setOnClickListener
            }
            viewScrollingTerminal.clearLogs()
            viewScrollingTerminal.addLog("触发一键同步刷新订阅源...")
            viewScrollingTerminal.addLog(subUrl)

            fetchAndSyncPlaylist(subUrl, { progressText ->
                runOnUiThread {
                    viewScrollingTerminal.addLog(progressText)
                }
            }, { totalChannelsCount ->
                runOnUiThread {
                    updateStatsViews()
                    channelAdapter.notifyDataSetChanged()
                    if (totalChannelsCount > 0) {
                        Toast.makeText(this@MainActivity, "成功刷新并同步了 $totalChannelsCount 个频道！", Toast.LENGTH_LONG).show()
                    }
                }
            })
        }

        // Bind Multi-source Synchronize Now Button
        val btnSyncCollectNow = dialog.findViewById<Button>(R.id.btn_sync_collect_now)
        btnSyncCollectNow.setOnClickListener {
            viewScrollingTerminal.clearLogs()
            viewScrollingTerminal.addLog("触发一键同步主备多线路精品直播源...")
            btnSyncCollectNow.isEnabled = false
            fetchAndSyncCollectIptv({ progressText ->
                runOnUiThread {
                    viewScrollingTerminal.addLog(progressText)
                }
            }, { totalChannelsCount ->
                runOnUiThread {
                    btnSyncCollectNow.isEnabled = true
                    updateStatsViews()
                    channelAdapter.notifyDataSetChanged()
                    if (totalChannelsCount > 0) {
                        Toast.makeText(this@MainActivity, "成功同步并检测 $totalChannelsCount 个可播放优质常规频道！", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, "同步节目源失败或未找到符合要求的频道，请稍后重试！", Toast.LENGTH_LONG).show()
                    }
                }
            })
        }

        // Close button click handler
        btnCloseSettings.setOnClickListener {
            dialog.dismiss()
        }

        // Manual channel add inputs binding and click handler
        val etInputName = dialog.findViewById<EditText>(R.id.et_input_name)
        val etInputUrl = dialog.findViewById<EditText>(R.id.et_input_url)
        val btnAddManual = dialog.findViewById<Button>(R.id.btn_add_manual)

        btnAddManual.setOnClickListener {
            val name = etInputName.text.toString().trim()
            val url = etInputUrl.text.toString().trim()

            if (name.isEmpty()) {
                Toast.makeText(this, "请输入频道名称！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (url.isEmpty()) {
                Toast.makeText(this, "请输入直播源 URL 地址！", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Enforce regex-based validation
            if (!M3u8Validator.isValid(url)) {
                Toast.makeText(this, "添加失败: URL 格式不正确 (必须是 m3u8 / mp4 等流格式)！", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // Correct format, add to channel list
            val newChannel = Channel(name, url)
            channelList.add(newChannel)
            saveChannelsToPrefs(channelList)

            // Update local adapter, update stats views in dialog
            channelAdapter.updateChannels(channelList)
            updateStatsViews()

            // Run asynchronous connectivity check for the newly added channel
            testChannelConnectivity(newChannel) { updatedStatus ->
                newChannel.status = updatedStatus
                runOnUiThread {
                    updateStatsViews()
                    channelAdapter.notifyDataSetChanged()
                }
            }

            // Clear input fields
            etInputName.setText("")
            etInputUrl.setText("")
            Toast.makeText(this, "成功手动添加频道「$name」！", Toast.LENGTH_SHORT).show()
        }

        // Bind Decoder hardware switch
        val swDecoderHardware = dialog.findViewById<Switch>(R.id.sw_decoder_hardware)
        val isDecoderHardwareEnabled = sharedPrefs.getBoolean("DECODER_HARDWARE", true)
        swDecoderHardware.isChecked = isDecoderHardwareEnabled
        swDecoderHardware.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("DECODER_HARDWARE", isChecked).apply()
            if (isChecked) {
                Toast.makeText(this, "硬解优先模式已开启 (自动适配芯片加速)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "标准软解兼容模式已启用", Toast.LENGTH_SHORT).show()
            }
            // Realtime update player settings
            if (isPlayingChannel && currentPlayingChannel != null) {
                exoPlayer?.stop()
                exoPlayer?.release()
                exoPlayer = null
                playChannel(currentPlayingChannel!!)
            }
        }

        // Bind Buffer low latency switch
        val swBufferLowLatency = dialog.findViewById<Switch>(R.id.sw_buffer_low_latency)
        val isBufferLowLatencyEnabled = sharedPrefs.getBoolean("BUFFER_LOW_LATENCY", true)
        swBufferLowLatency.isChecked = isBufferLowLatencyEnabled
        swBufferLowLatency.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("BUFFER_LOW_LATENCY", isChecked).apply()
            if (isChecked) {
                Toast.makeText(this, "极速换台缓冲开启 (500ms极速启播)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "大缓存稳定播放模式已启用", Toast.LENGTH_SHORT).show()
            }
            // Realtime update player settings
            if (isPlayingChannel && currentPlayingChannel != null) {
                exoPlayer?.stop()
                exoPlayer?.release()
                exoPlayer = null
                playChannel(currentPlayingChannel!!)
            }
        }

        // Bind LemonTV Premium configs
        val spSourceMode = dialog.findViewById<Spinner>(R.id.sp_source_mode)
        val spBufferMode = dialog.findViewById<Spinner>(R.id.sp_buffer_mode)
        val spStallTimeout = dialog.findViewById<Spinner>(R.id.sp_stall_timeout)
        val swChannelFilterMode = dialog.findViewById<Switch>(R.id.sw_channel_filter_mode)

        // Populate sp_source_mode
        val sourceModesDisplay = arrayOf(
            "融合极速全源 (4路备用 + LemonTV)",
            "LemonTV 原装精品源 (含4K)",
            "咪咕极速专线 (精品体育/卫视)",
            "APTV高速源"
        )
        val sourceModesValue = arrayOf("merge", "lemontv", "migu", "aptv")
        val currentSourceMode = sharedPrefs.getString("LEMON_SOURCE_MODE", "merge") ?: "merge"
        val sourceAdapter = DarkSpinnerAdapter(this, sourceModesDisplay)
        spSourceMode.adapter = sourceAdapter
        spSourceMode.setSelection(sourceModesValue.indexOf(currentSourceMode).let { if (it == -1) 0 else it })
        
        spSourceMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedValue = sourceModesValue[position]
                if (selectedValue != sharedPrefs.getString("LEMON_SOURCE_MODE", "merge")) {
                    sharedPrefs.edit().putString("LEMON_SOURCE_MODE", selectedValue).apply()
                    Toast.makeText(this@MainActivity, "内置直播源已切换，请立即同步或重新同步刷新生效！", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Populate sp_buffer_mode
        val bufferModesDisplay = arrayOf(
            "极速秒开模式 (缓 1.5s)",
            "智能均衡模式 (缓 4.0s)",
            "抗卡顿稳定模式 (缓 10s)"
        )
        val bufferModesValue = arrayOf("fast", "smart", "stable")
        val currentBufferMode = sharedPrefs.getString("PLAYBACK_BUFFER_MODE", "fast") ?: "fast"
        val bufferAdapter = DarkSpinnerAdapter(this, bufferModesDisplay)
        spBufferMode.adapter = bufferAdapter
        spBufferMode.setSelection(bufferModesValue.indexOf(currentBufferMode).let { if (it == -1) 0 else it })

        spBufferMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedValue = bufferModesValue[position]
                if (selectedValue != sharedPrefs.getString("PLAYBACK_BUFFER_MODE", "fast")) {
                    sharedPrefs.edit().putString("PLAYBACK_BUFFER_MODE", selectedValue).apply()
                    Toast.makeText(this@MainActivity, "播放缓冲模式已切换，下次播放生效", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Populate sp_stall_timeout
        val stallTimeoutDisplay = arrayOf(
            "5秒极速切换",
            "10秒平衡切换",
            "18秒智能切换 (默认)",
            "30秒稳定延迟",
            "不自动切换"
        )
        val stallTimeoutValue = arrayOf("5s", "10s", "18s", "30s", "none")
        val currentStallTimeout = sharedPrefs.getString("STALL_SWITCH_TIMEOUT", "18s") ?: "18s"
        val stallAdapter = DarkSpinnerAdapter(this, stallTimeoutDisplay)
        spStallTimeout.adapter = stallAdapter
        spStallTimeout.setSelection(stallTimeoutValue.indexOf(currentStallTimeout).let { if (it == -1) 2 else it })

        spStallTimeout.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedValue = stallTimeoutValue[position]
                if (selectedValue != sharedPrefs.getString("STALL_SWITCH_TIMEOUT", "18s")) {
                    sharedPrefs.edit().putString("STALL_SWITCH_TIMEOUT", selectedValue).apply()
                    Toast.makeText(this@MainActivity, "卡顿自动重连时间已更新", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Bind Smart channel filter switch
        val isChannelFilterEnabled = sharedPrefs.getBoolean("LEMON_CHANNEL_FILTER", true)
        swChannelFilterMode.isChecked = isChannelFilterEnabled
        swChannelFilterMode.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("LEMON_CHANNEL_FILTER", isChecked).apply()
            if (isChecked) {
                Toast.makeText(this, "智能画质极剪已开启 (仅保留高画质央视/重点卫视/港澳)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "原装全频道合并已开启 (将保留拉取到的全部地方小台)", Toast.LENGTH_SHORT).show()
            }
        }

        // Bind Player instant refresh button
        val btnRefreshPlayerNow = dialog.findViewById<Button>(R.id.btn_refresh_player_now)
        btnRefreshPlayerNow.setOnClickListener {
            if (isPlayingChannel && currentPlayingChannel != null) {
                Toast.makeText(this, "⚡ 正在重置解码器并重新加载播放...", Toast.LENGTH_SHORT).show()
                exoPlayer?.stop()
                exoPlayer?.release()
                exoPlayer = null
                playChannel(currentPlayingChannel!!)
            } else {
                Toast.makeText(this, "当前未播放任何频道", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun showRemoteImportDialog() {
        isRemoteImportOpen = true
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_remote_import)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.window?.setBackgroundDrawable(ColorDrawable(0xFF0A0A0C.toInt()))

        // Bind Views
        val ivRemoteQr = dialog.findViewById<ImageView>(R.id.iv_remote_qr)
        val tvRemoteAddressGiant = dialog.findViewById<TextView>(R.id.tv_remote_address_giant)
        val rvImportFeed = dialog.findViewById<RecyclerView>(R.id.rv_import_feed)
        val btnBackToPlay = dialog.findViewById<Button>(R.id.btn_back_to_play)

        // Set Up Remote Address text
        val ipAddress = getLocalIpAddress()
        val remoteUrl = "http://$ipAddress:8080"
        tvRemoteAddressGiant.text = remoteUrl

        // Load QR Code Image
        try {
            val qrCodeApiUrl = "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=" + URLEncoder.encode(remoteUrl, "UTF-8")
            loadQrCodeImage(qrCodeApiUrl, ivRemoteQr)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Set Up Recycler Adapter for real-time feed
        val feedAdapter = SettingsStatusAdapter(synchronized(channelList) { ArrayList(channelList) })
        rvImportFeed.layoutManager = LinearLayoutManager(this)
        rvImportFeed.adapter = feedAdapter
        remoteImportAdapter = feedAdapter

        dialog.setOnDismissListener {
            isRemoteImportOpen = false
            remoteImportAdapter = null
            resetInactivityTimer()
        }

        btnBackToPlay.setOnClickListener {
            dialog.dismiss()
        }

        // Make button request focus initially
        btnBackToPlay.requestFocus()

        dialog.show()
    }

    private fun loadQrCodeImage(url: String, imageView: ImageView) {
        Thread {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.doInput = true
                connection.connect()
                val input = connection.inputStream
                val bitmap = BitmapFactory.decodeStream(input)
                runOnUiThread {
                    imageView.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    // Handle remote control MENU button
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showSettingsDialog()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // --- ServerCallback Implementation ---

    override fun onChannelAdded(name: String, url: String) {
        val newChannel = Channel(name, url)
        synchronized(channelList) {
            channelList.add(newChannel)
            saveChannelsToPrefs(channelList)
            channelAdapter.updateChannels(channelList)
        }
        runOnUiThread {
            remoteImportAdapter?.updateChannels(synchronized(channelList) { ArrayList(channelList) })
        }
        Toast.makeText(this, "推送到电视: $name", Toast.LENGTH_SHORT).show()
        playChannel(newChannel)

        testChannelConnectivity(newChannel) { updatedStatus ->
            newChannel.status = updatedStatus
            runOnUiThread {
                channelAdapter.notifyDataSetChanged()
                remoteImportAdapter?.notifyDataSetChanged()
            }
        }
    }

    override fun onChannelListUpdated(channels: List<Channel>) {
        synchronized(channelList) {
            channelList.clear()
            channelList.addAll(channels)
            saveChannelsToPrefs(channelList)
            channelAdapter.updateChannels(channelList)
        }
        runOnUiThread {
            remoteImportAdapter?.updateChannels(synchronized(channelList) { ArrayList(channelList) })
        }
        Toast.makeText(this, "频道列表已同步更新！", Toast.LENGTH_SHORT).show()

        checkAllChannelsConnectivity {
            runOnUiThread {
                channelAdapter.notifyDataSetChanged()
                remoteImportAdapter?.notifyDataSetChanged()
            }
        }
    }

    override fun onPlayChannelRequested(index: Int) {
        synchronized(channelList) {
            if (index in channelList.indices) {
                val targetChannel = channelList[index]
                playChannel(targetChannel)
                Toast.makeText(this, "远程切换频道: ${targetChannel.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun getChannels(): List<Channel> {
        return synchronized(channelList) {
            ArrayList(channelList)
        }
    }

    override fun onNetworkPlaylistRequested(url: String) {
        runOnUiThread {
            Toast.makeText(this, "收到来自手机端的在线订阅链接推送！", Toast.LENGTH_LONG).show()
            activeTerminalView?.clearLogs()
            activeTerminalView?.addLog("接收到远程推送订阅源：")
            activeTerminalView?.addLog(url)
        }
    }

    override fun logToTerminal(message: String) {
        runOnUiThread {
            activeTerminalView?.addLog(message)
        }
    }

    override fun onCollectIptvRefreshRequested(onComplete: (Int) -> Unit) {
        runOnUiThread {
            activeTerminalView?.clearLogs()
            activeTerminalView?.addLog("接收到远程一键刷新多线路精品源同步指令...")
        }
        fetchAndSyncCollectIptv({ progress ->
            runOnUiThread {
                activeTerminalView?.addLog(progress)
            }
        }, { count ->
            runOnUiThread {
                activeTerminalView?.addLog("🎉 多线路一键同步、特定频道筛选与智能去重合并完成！当前共计 $count 个优质频道。")
                onComplete(count)
            }
        })
    }

    override fun getSettings(): JSONObject {
        val sharedPrefs = getSharedPreferences("TV_LIVE_PREFS", MODE_PRIVATE)
        val json = JSONObject()
        json.put("auto_start_enabled", sharedPrefs.getBoolean("AUTO_START_ENABLED", true))
        json.put("auto_sync_collect", sharedPrefs.getBoolean("AUTO_SYNC_COLLECT", true))
        json.put("auto_clean_expired", sharedPrefs.getBoolean("AUTO_CLEAN_EXPIRED", true))
        json.put("collect_iptv_url", sharedPrefs.getString("COLLECT_IPTV_URL", "https://raw.githubusercontent.com/zilong7728/Collect-IPTV/refs/heads/main/best_sorted.m3u"))
        json.put("decoder_hardware", sharedPrefs.getBoolean("DECODER_HARDWARE", true))
        json.put("buffer_low_latency", sharedPrefs.getBoolean("BUFFER_LOW_LATENCY", true))
        json.put("lemon_source_mode", sharedPrefs.getString("LEMON_SOURCE_MODE", "merge"))
        json.put("playback_buffer_mode", sharedPrefs.getString("PLAYBACK_BUFFER_MODE", "fast"))
        json.put("stall_switch_timeout", sharedPrefs.getString("STALL_SWITCH_TIMEOUT", "18s"))
        json.put("lemon_channel_filter", sharedPrefs.getBoolean("LEMON_CHANNEL_FILTER", true))
        return json
    }

    override fun saveSettings(settings: JSONObject) {
        val sharedPrefs = getSharedPreferences("TV_LIVE_PREFS", MODE_PRIVATE)
        val editor = sharedPrefs.edit()
        if (settings.has("auto_start_enabled")) {
            editor.putBoolean("AUTO_START_ENABLED", settings.getBoolean("auto_start_enabled"))
        }
        if (settings.has("auto_sync_collect")) {
            editor.putBoolean("AUTO_SYNC_COLLECT", settings.getBoolean("auto_sync_collect"))
        }
        if (settings.has("auto_clean_expired")) {
            editor.putBoolean("AUTO_CLEAN_EXPIRED", settings.getBoolean("auto_clean_expired"))
        }
        if (settings.has("collect_iptv_url")) {
            editor.putString("COLLECT_IPTV_URL", settings.getString("collect_iptv_url").trim())
        }
        if (settings.has("decoder_hardware")) {
            editor.putBoolean("DECODER_HARDWARE", settings.getBoolean("decoder_hardware"))
        }
        if (settings.has("buffer_low_latency")) {
            editor.putBoolean("BUFFER_LOW_LATENCY", settings.getBoolean("buffer_low_latency"))
        }
        if (settings.has("lemon_source_mode")) {
            editor.putString("LEMON_SOURCE_MODE", settings.getString("lemon_source_mode"))
        }
        if (settings.has("playback_buffer_mode")) {
            editor.putString("PLAYBACK_BUFFER_MODE", settings.getString("playback_buffer_mode"))
        }
        if (settings.has("stall_switch_timeout")) {
            editor.putString("STALL_SWITCH_TIMEOUT", settings.getString("stall_switch_timeout"))
        }
        if (settings.has("lemon_channel_filter")) {
            editor.putBoolean("LEMON_CHANNEL_FILTER", settings.getBoolean("lemon_channel_filter"))
        }
        editor.apply()
        
        runOnUiThread {
            Toast.makeText(this, "网页端系统设置同步保存成功！", Toast.LENGTH_SHORT).show()
            // Force re-init player if currently playing to apply decoder/buffer optimization changes immediately
            if (isPlayingChannel && currentPlayingChannel != null) {
                exoPlayer?.stop()
                exoPlayer?.release()
                exoPlayer = null
                playChannel(currentPlayingChannel!!)
            }
        }
    }

    private fun fetchAndSyncPlaylist(
        url: String,
        onProgress: (String) -> Unit,
        onComplete: (Int) -> Unit
    ) {
        // Save the original source URL
        val sharedPrefs = getSharedPreferences("TV_LIVE_PREFS", MODE_PRIVATE)
        sharedPrefs.edit().putString("PLAYLIST_SUBSCRIPTION_URL", url).apply()

        // Fetch asynchronously on a background thread
        Thread {
            val parsedChannels = OnlinePlaylistParser.fetchAndParse(url) { progress ->
                onProgress(progress)
            }

            if (parsedChannels.isNotEmpty()) {
                // Clear the original list and replace with new online channels
                runOnUiThread {
                    channelList.clear()
                    channelList.addAll(parsedChannels)
                    saveChannelsToPrefs(channelList)
                    channelAdapter.updateChannels(channelList)
                }

                onProgress("开始并发进行频道可用性检测 (并发线程数: 15)...")
                val totalCount = parsedChannels.size
                var testedCount = 0
                var onlineCount = 0
                val executor = java.util.concurrent.Executors.newFixedThreadPool(15)
                val lock = Any()

                for (channel in parsedChannels) {
                    channel.isValidFormat = M3u8Validator.isValid(channel.url)
                    executor.execute {
                        var status = ChannelStatus.OFFLINE
                        try {
                            val checkUrl = URL(channel.url)
                            val connection = checkUrl.openConnection() as HttpURLConnection
                            connection.requestMethod = "HEAD"
                            connection.connectTimeout = 5000
                            connection.readTimeout = 5000
                            val code = connection.responseCode
                            if (code in 200..399) {
                                status = ChannelStatus.ONLINE
                            }
                        } catch (e: Exception) {
                            try {
                                val checkUrl = URL(channel.url)
                                val connection = checkUrl.openConnection() as HttpURLConnection
                                connection.requestMethod = "GET"
                                connection.connectTimeout = 5000
                                connection.readTimeout = 5000
                                val code = connection.responseCode
                                if (code in 200..399) {
                                    status = ChannelStatus.ONLINE
                                }
                            } catch (ex: Exception) {
                                status = ChannelStatus.OFFLINE
                            }
                        }

                        channel.status = status

                        synchronized(lock) {
                            testedCount++
                            if (status == ChannelStatus.ONLINE) {
                                onlineCount++
                            }
                            if (testedCount % 5 == 0 || testedCount == totalCount) {
                                onProgress("已检测 $testedCount/$totalCount (在线: $onlineCount)")
                            }
                        }

                        // Batch update UI
                        if (testedCount % 3 == 0 || testedCount == totalCount) {
                            runOnUiThread {
                                channelAdapter.notifyDataSetChanged()
                            }
                        }
                    }
                }

                executor.shutdown()
                try {
                    executor.awaitTermination(35, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Final save and update, filtering out unplayable/offline ones
                runOnUiThread {
                    synchronized(channelList) {
                        channelList.removeAll { it.status == ChannelStatus.OFFLINE || !it.isValidFormat }
                        saveChannelsToPrefs(channelList)
                        channelAdapter.updateChannels(channelList)
                    }
                }
                onProgress("最终成功同步 $onlineCount 个可播放频道 (已自动删除隐藏 ${totalCount - onlineCount} 个不可播放源)")
                runOnUiThread {
                    onComplete(onlineCount)
                }
            } else {
                onProgress("未能解析到任何有效播放源频道！")
                runOnUiThread {
                    onComplete(0)
                }
            }
        }.start()
    }

    private fun deduplicateAndCleanChannels(channels: List<Channel>): List<Channel> {
        val uniqueChannels = mutableListOf<Channel>()
        val seenUrls = mutableSetOf<String>()
        val seenNames = mutableSetOf<String>()
        
        for (channel in channels) {
            val url = channel.url.trim()
            val name = channel.name.trim()
            
            if (url.isEmpty() || name.isEmpty()) {
                continue
            }
            
            // Normalize name (strip spacing and casing) to find duplicate channels
            val normalizedName = name.lowercase().replace("\\s+".toRegex(), "")
            
            if (!seenUrls.contains(url) && !seenNames.contains(normalizedName)) {
                uniqueChannels.add(channel)
                seenUrls.add(url)
                seenNames.add(normalizedName)
            }
        }
        return uniqueChannels
    }

    private fun standardizeCctvName(name: String): String? {
        val upper = name.uppercase()
            .replace("\\s+".toRegex(), "")
            .replace("-", "")
            .replace("_", "")
            .replace("HD", "")
            .replace("FHD", "")
            .replace("超清", "")
            .replace("高清", "")
            .replace("标清", "")
            .replace("CHANNEL", "")
        
        if (upper.contains("CCTV") || upper.contains("央视")) {
            return when {
                upper.contains("CCTV5+") || upper.contains("CCTV5PLUS") || (upper.contains("CCTV5") && upper.contains("+")) -> "CCTV-5+ 体育赛事"
                upper.contains("CCTV16") || upper.contains("奥林匹克") -> "CCTV-16 奥林匹克"
                upper.contains("CCTV17") || upper.contains("农业") || upper.contains("农村") -> "CCTV-17 农业农村"
                upper.contains("CCTV15") || upper.contains("音乐") -> "CCTV-15 音乐"
                upper.contains("CCTV14") || upper.contains("少儿") -> "CCTV-14 少儿"
                upper.contains("CCTV13") || upper.contains("新闻") -> "CCTV-13 新闻"
                upper.contains("CCTV12") || upper.contains("社会与法") || upper.contains("法制") -> "CCTV-12 社会与法"
                upper.contains("CCTV11") || upper.contains("戏曲") -> "CCTV-11 戏曲"
                upper.contains("CCTV10") || upper.contains("科教") -> "CCTV-10 科教"
                upper.contains("CCTV9") || upper.contains("纪录") -> "CCTV-9 纪录"
                upper.contains("CCTV8") || upper.contains("电视剧") -> "CCTV-8 电视剧"
                upper.contains("CCTV7") || upper.contains("军事") || upper.contains("国防") -> "CCTV-7 国防军事"
                upper.contains("CCTV6") || upper.contains("电影") -> "CCTV-6 电影"
                upper.contains("CCTV5") || upper.contains("体育") -> "CCTV-5 体育"
                upper.contains("CCTV4") || upper.contains("中文国际") || upper.contains("国际") -> "CCTV-4 中文国际"
                upper.contains("CCTV3") || upper.contains("综艺") -> "CCTV-3 综艺"
                upper.contains("CCTV2") || upper.contains("财经") -> "CCTV-2 财经"
                upper.contains("CCTV1") || upper.contains("综合") -> "CCTV-1 综合"
                upper.contains("风云足球") -> "CCTV 风云足球"
                upper.contains("兵器科技") -> "CCTV 兵器科技"
                upper.contains("女性时尚") -> "CCTV 女性时尚"
                upper.contains("风云音乐") -> "CCTV 风云音乐"
                upper.contains("风云剧场") -> "CCTV 风云剧场"
                upper.contains("第一剧场") -> "CCTV 第一剧场"
                upper.contains("怀旧剧场") -> "CCTV 怀旧剧场"
                upper.contains("高尔夫网球") -> "CCTV 高尔夫网球"
                upper.contains("世界地理") -> "CCTV 世界地理"
                upper.contains("文化精品") -> "CCTV 文化精品"
                upper.contains("新科动漫") -> "CCTV 新科动漫"
                upper.contains("4K") -> "CCTV 4K超高清"
                upper.contains("8K") -> "CCTV 8K超高清"
                else -> {
                    val match = Regex("CCTV(\\d+)").find(upper)
                    if (match != null) {
                        val num = match.groupValues[1].toIntOrNull()
                        if (num != null) {
                            when (num) {
                                1 -> "CCTV-1 综合"
                                2 -> "CCTV-2 财经"
                                3 -> "CCTV-3 综艺"
                                4 -> "CCTV-4 中文国际"
                                5 -> "CCTV-5 体育"
                                6 -> "CCTV-6 电影"
                                7 -> "CCTV-7 国防军事"
                                8 -> "CCTV-8 电视剧"
                                9 -> "CCTV-9 纪录"
                                10 -> "CCTV-10 科教"
                                11 -> "CCTV-11 戏曲"
                                12 -> "CCTV-12 社会与法"
                                13 -> "CCTV-13 新闻"
                                14 -> "CCTV-14 少儿"
                                15 -> "CCTV-15 音乐"
                                16 -> "CCTV-16 奥林匹克"
                                17 -> "CCTV-17 农业农村"
                                else -> "CCTV-$num"
                            }
                        } else {
                            name.replace("HD", "").replace("FHD", "").replace("高清", "").replace("超清", "").trim()
                        }
                    } else {
                        name.replace("HD", "").replace("FHD", "").replace("高清", "").replace("超清", "").trim()
                    }
                }
            }
        }
        return null
    }

    private fun standardizeOtherTargetName(name: String): String {
        val upper = name.uppercase()
            .replace("\\s+".toRegex(), "")
            .replace("-", "")
            .replace("_", "")
            .replace("HD", "")
            .replace("FHD", "")
            .replace("超清", "")
            .replace("高清", "")
            .replace("标清", "")
            .replace("CHANNEL", "")
        
        if (upper.contains("凤凰中文") || (upper.contains("凤凰") && upper.contains("中文"))) {
            return "凤凰中文台"
        }
        if (upper.contains("凤凰资讯") || (upper.contains("凤凰") && upper.contains("资讯"))) {
            return "凤凰资讯台"
        }
        if (upper.contains("凤凰香港") || (upper.contains("凤凰") && upper.contains("香港"))) {
            return "凤凰香港台"
        }
        if (upper.contains("凤凰卫视") || upper.contains("凤凰台") || upper.contains("PHOENIX")) {
            if (upper.contains("中文")) return "凤凰中文台"
            if (upper.contains("资讯")) return "凤凰资讯台"
            if (upper.contains("香港")) return "凤凰香港台"
            return "凤凰卫视"
        }
        
        if (upper.contains("广东体育") || upper.contains("广体")) {
            return "广东体育"
        }
        if (upper.contains("广东珠江") || upper.contains("珠江台") || (upper.contains("珠江") && !upper.contains("内江") && !upper.contains("金沙"))) {
            return "广东珠江"
        }
        if (upper.contains("五星体育")) {
            return "五星体育"
        }
        if (upper.contains("劲爆体育")) {
            return "劲爆体育"
        }
        if (upper.contains("北京体育") || upper.contains("BTV体育") || upper.contains("BRTV体育")) {
            return "北京体育休闲"
        }
        if (upper.contains("海南体育") || upper.contains("旅游卫视体育")) {
            return "海南竞技体育"
        }
        if (upper.contains("广东双色球") || upper.contains("广东彩民")) {
            return "广东彩民"
        }
        return name.trim()
    }

    private fun getChannelOrderKey(name: String): Long {
        val upper = name.uppercase()
        
        // Priority 1: Guangdong Sports (广东体育)
        if (upper.contains("广东体育") || upper.contains("广体") || upper.contains("GDSPORTS")) {
            return 1_000_000L
        }
        
        // Priority 2: Phoenix (凤凰)
        if (upper.contains("凤凰") || upper.contains("PHOENIX")) {
            val subOrder = when {
                upper.contains("中文") -> 1
                upper.contains("资讯") -> 2
                upper.contains("香港") -> 3
                else -> 4
            }
            return 2_000_000L + subOrder
        }
        
        // Priority 3: Guangdong Zhujiang (广东珠江)
        if (upper.contains("广东珠江") || upper.contains("珠江")) {
            return 3_000_000L
        }
        
        // Priority 4: Other Sports (CCTV-5, CCTV-5+, 五星体育, 劲爆体育, etc., or anything with "体育")
        if (upper.contains("CCTV-5") || upper.contains("CCTV5") || upper.contains("体育")) {
            val subOrder = when {
                upper.contains("CCTV-5+") || upper.contains("CCTV5+") || upper.contains("PLUS") -> 2
                upper.contains("CCTV-5") || upper.contains("CCTV5") -> 1
                upper.contains("五星") -> 3
                upper.contains("劲爆") -> 4
                else -> 5
            }
            return 4_000_000L + subOrder
        }
        
        // Priority 5: Standard CCTV channels (CCTV-1 to CCTV-17)
        if (upper.contains("CCTV") || upper.contains("央视")) {
            val match = Regex("CCTV-(\\d+)").find(name) ?: Regex("CCTV(\\d+)").find(upper)
            if (match != null) {
                val num = match.groupValues[1].toIntOrNull()
                if (num != null) {
                    return 5_000_000L + num
                }
            }
            
            val subOrder = when {
                upper.contains("4K") -> 100
                upper.contains("8K") -> 101
                upper.contains("风云足球") -> 102
                upper.contains("兵器科技") -> 103
                upper.contains("女性时尚") -> 104
                upper.contains("风云音乐") -> 105
                upper.contains("风云剧场") -> 106
                upper.contains("第一剧场") -> 107
                upper.contains("怀旧剧场") -> 108
                upper.contains("高尔夫网球") -> 109
                upper.contains("世界地理") -> 110
                upper.contains("文化精品") -> 111
                upper.contains("新科动漫") -> 112
                else -> 200
            }
            return 5_000_000L + subOrder
        }
        
        // Priority 6: Provincial Satellite TV (卫视)
        if (upper.contains("卫视")) {
            return 6_000_000L
        }
        
        // Priority 7: Other
        return 7_000_000L
    }

    private fun isConventionalOrTargetChannel(name: String): Boolean {
        val upper = name.uppercase()
        
        // 1. CCTV (keep)
        if (upper.contains("CCTV") || upper.contains("央视")) {
            return true
        }
        
        // 2. Hong Kong, Macau, Jade, TVB, Macau Asia, Phoenix, Lotus, etc. (keep)
        if (upper.contains("翡翠") || 
            upper.contains("TVB") || 
            upper.contains("香港") || 
            upper.contains("港台") || 
            upper.contains("澳亚") || 
            upper.contains("澳门") || 
            upper.contains("澳門") || 
            upper.contains("蓮花") || 
            upper.contains("莲花") || 
            upper.contains("凤凰") || 
            upper.contains("PHOENIX") || 
            upper.contains("J2") || 
            upper.contains("明珠") || 
            upper.contains("无线") || 
            upper.contains("VIU") || 
            upper.contains("港澳")) {
            return true
        }
        
        // 3. Guangdong provincial and local channels (keep, including 广东卫视, 深圳卫视, 珠江, 南方, etc.)
        if (upper.contains("广东") || 
            upper.contains("广体") || 
            upper.contains("珠江") || 
            upper.contains("南方") || 
            upper.contains("深圳") || 
            upper.contains("广州") || 
            upper.contains("大湾区")) {
            return true
        }
        
        // Exclude all other provincial satellite TVs and local channels from other provinces
        return false
    }

    private fun fetchAndSyncCollectIptv(
        onProgress: (String) -> Unit,
        onComplete: (Int) -> Unit
    ) {
        val sharedPrefs = getSharedPreferences("TV_LIVE_PREFS", MODE_PRIVATE)
        val collectIptvUrl = sharedPrefs.getString("COLLECT_IPTV_URL", "https://raw.githubusercontent.com/zilong7728/Collect-IPTV/refs/heads/main/best_sorted.m3u") ?: "https://raw.githubusercontent.com/zilong7728/Collect-IPTV/refs/heads/main/best_sorted.m3u"
        val sourceMode = sharedPrefs.getString("LEMON_SOURCE_MODE", "merge") ?: "merge"
        val isChannelFilterEnabled = sharedPrefs.getBoolean("LEMON_CHANNEL_FILTER", true)
        
        Thread {
            val primaryChannels = if (sourceMode == "merge" || sourceMode == "aptv") {
                onProgress("正在从高连通度主 IPTV 节目源自动同步抓取...")
                var res = OnlinePlaylistParser.fetchAndParse(collectIptvUrl) { progress ->
                    onProgress("[主源] $progress")
                }
                if (res.isEmpty() && collectIptvUrl.contains("refs/heads/")) {
                    val fallbackUrl = collectIptvUrl.replace("refs/heads/", "")
                    onProgress("主源请求失败，尝试从主源备用节点拉取...")
                    res = OnlinePlaylistParser.fetchAndParse(fallbackUrl) { progress ->
                        onProgress("[备用主源] $progress")
                    }
                }
                res
            } else emptyList()
            
            val lemonChannels = if (sourceMode == "merge" || sourceMode == "lemontv") {
                onProgress("正在拉取 LemonTV 极速精品源...")
                OnlinePlaylistParser.fetchAndParse("https://raw.githubusercontent.com/jia070310/lemonTV/main/iptv-fe.m3u") { progress ->
                    onProgress("[LemonTV源] $progress")
                }
            } else emptyList()

            val lemon4kChannels = if (sourceMode == "merge" || sourceMode == "lemontv") {
                onProgress("正在拉取 LemonTV 4K原画源...")
                OnlinePlaylistParser.fetchAndParse("https://raw.githubusercontent.com/jia070310/4K-IPTV-M3U/main/4K.m3u") { progress ->
                    onProgress("[LemonTV-4K] $progress")
                }
            } else emptyList()

            val backupChannels1 = if (sourceMode == "merge") {
                onProgress("正在从 big-mouth-cn/tv 自动拉取第一备用源...")
                OnlinePlaylistParser.fetchAndParse("https://raw.githubusercontent.com/big-mouth-cn/tv/main/iptv-ok.m3u") { progress ->
                    onProgress("[备用源1] $progress")
                }
            } else emptyList()

            val backupChannels2 = if (sourceMode == "merge") {
                onProgress("正在从 Guovin/iptv-api 自动拉取第二备用源...")
                OnlinePlaylistParser.fetchAndParse("https://raw.githubusercontent.com/Guovin/iptv-api/gd/output/result.m3u") { progress ->
                    onProgress("[备用源2] $progress")
                }
            } else emptyList()

            val backupChannels3 = if (sourceMode == "merge" || sourceMode == "migu") {
                onProgress("正在拉取 咪咕极速专线(含精品体育/卫视)源...")
                OnlinePlaylistParser.fetchAndParse("https://raw.githubusercontent.com/akiralereal/iptv/main/IPTV.m3u") { progress ->
                    onProgress("[咪咕极速] $progress")
                }
            } else emptyList()

            val miguGithubChannels = if (sourceMode == "merge" || sourceMode == "migu") {
                onProgress("正在拉取 咪咕原装极速 M3U 专线源...")
                OnlinePlaylistParser.fetchAndParse("https://raw.githubusercontent.com/ioptu/migu_video/main/migu.m3u") { progress ->
                    onProgress("[咪咕原装] $progress")
                }
            } else emptyList()

            val miguCctvGithubChannels = if (sourceMode == "merge" || sourceMode == "migu") {
                onProgress("正在拉取 咪咕原装央视极速专线源...")
                OnlinePlaylistParser.fetchAndParse("https://raw.githubusercontent.com/ioptu/migu_video/main/cctv.migu.m3u") { progress ->
                    onProgress("[咪咕央视] $progress")
                }
            } else emptyList()

            val mergedChannels = mutableListOf<Channel>()
            val hasChannels = primaryChannels.isNotEmpty() || backupChannels1.isNotEmpty() || 
                              backupChannels2.isNotEmpty() || backupChannels3.isNotEmpty() ||
                              lemonChannels.isNotEmpty() || lemon4kChannels.isNotEmpty() ||
                              miguGithubChannels.isNotEmpty() || miguCctvGithubChannels.isNotEmpty()

            if (hasChannels) {
                onProgress("正在对拉取到的多路直播源执行智优合并筛选...")
                
                // Record the timestamp of this successful GitHub TV source sync
                sharedPrefs.edit().putLong("LAST_GITHUB_SYNC_TIME", System.currentTimeMillis()).apply()
                
                fun getNormalizedName(name: String): String {
                    return name.uppercase()
                        .replace("\\s+".toRegex(), "")
                        .replace("HD", "")
                        .replace("FHD", "")
                        .replace("超清", "")
                        .replace("高清", "")
                        .replace("标清", "")
                        .replace("CHANNEL", "")
                        .replace("-", "")
                        .replace("_", "")
                        .trim()
                }

                val groupedUrls = LinkedHashMap<String, MutableList<String>>()
                val originalNames = LinkedHashMap<String, String>()
                
                // Helper to filter and group channels
                fun addChannelsToGroup(list: List<Channel>) {
                    for (channel in list) {
                        val url = channel.url.trim()
                        var name = channel.name.trim()
                        if (url.isEmpty() || name.isEmpty() || !M3u8Validator.isValid(url)) continue
                        
                        // Standardize names (CCTV and other target channels) to merge duplicates
                        val cctvStd = standardizeCctvName(name)
                        if (cctvStd != null) {
                            name = cctvStd
                        } else {
                            name = standardizeOtherTargetName(name)
                        }
                        
                        // Apply smart channel filter mode
                        if (isChannelFilterEnabled && !isConventionalOrTargetChannel(name)) {
                            continue
                        }
                        
                        val normName = getNormalizedName(name)
                        if (!groupedUrls.containsKey(normName)) {
                            groupedUrls[normName] = mutableListOf()
                            originalNames[normName] = name
                        }
                        val urlList = groupedUrls[normName]!!
                        if (!urlList.contains(url)) {
                            urlList.add(url)
                        }
                    }
                }

                // Process channels based on the selected mode priority
                when (sourceMode) {
                    "lemontv" -> {
                        onProgress("开始合并并极剪 LemonTV 与 4K 精品多线路源...")
                        addChannelsToGroup(lemon4kChannels)
                        addChannelsToGroup(lemonChannels)
                    }
                    "migu" -> {
                        onProgress("开始提取并极剪 咪咕体育及卫视极速专线源...")
                        addChannelsToGroup(miguCctvGithubChannels)
                        addChannelsToGroup(miguGithubChannels)
                        addChannelsToGroup(backupChannels3)
                    }
                    "aptv" -> {
                        onProgress("开始合并并极剪 APTV 高速多线路源...")
                        addChannelsToGroup(primaryChannels)
                    }
                    else -> { // merge
                        onProgress("开始合并精品多路直播源，优先导入咪咕高速专线源...")
                        // ADD MIGU FIRST TO MAKE IT THE PRIMARY URL (INDEX 0) FOR CCTV AND OTHER CHANNELS!
                        addChannelsToGroup(miguCctvGithubChannels)
                        addChannelsToGroup(miguGithubChannels)
                        // Then add other sources as backup lines!
                        addChannelsToGroup(lemon4kChannels)
                        addChannelsToGroup(lemonChannels)
                        addChannelsToGroup(primaryChannels)
                        addChannelsToGroup(backupChannels2)
                        addChannelsToGroup(backupChannels1)
                        addChannelsToGroup(backupChannels3)
                    }
                }
                
                for ((normName, urlsList) in groupedUrls) {
                    val bestName = originalNames[normName] ?: normName
                    val primaryUrl = urlsList[0]
                    mergedChannels.add(
                        Channel(
                            name = bestName,
                            url = primaryUrl,
                            urls = urlsList,
                            currentUrlIndex = 0
                        )
                    )
                }
                
                // Sort with custom priority to put Phoenix and sports channels at the very top
                val sortedChannels = mergedChannels.sortedWith(compareBy(
                    { getChannelOrderKey(it.name) },
                    { it.name }
                ))
                val uniqueChannels = sortedChannels
                onProgress("多源群组归并去重完毕！共筛选合并出 ${uniqueChannels.size} 个高品电视频道。")

                runOnUiThread {
                    synchronized(channelList) {
                        channelList.clear()
                        channelList.addAll(uniqueChannels)
                        saveChannelsToPrefs(channelList)
                        channelAdapter.updateChannels(channelList)
                    }
                    onStatsUpdateNeeded?.invoke()
                }

                val autoCleanEnabled = sharedPrefs.getBoolean("AUTO_CLEAN_EXPIRED", true)
                
                if (autoCleanEnabled) {
                    onProgress("启动并发多线程活性连通性与极速检测，滤除不响应或失效源...")
                    val totalCount = uniqueChannels.size
                    var testedCount = 0
                    var onlineCount = 0
                    val executor = java.util.concurrent.Executors.newFixedThreadPool(15)
                    val lock = Any()

                    for (channel in uniqueChannels) {
                        channel.isValidFormat = M3u8Validator.isValid(channel.url)
                        executor.execute {
                            var status = ChannelStatus.OFFLINE
                            try {
                                val checkUrl = URL(channel.url)
                                val connection = checkUrl.openConnection() as HttpURLConnection
                                connection.requestMethod = "HEAD"
                                connection.connectTimeout = 5000
                                connection.readTimeout = 5000
                                val code = connection.responseCode
                                if (code in 200..399) {
                                    status = ChannelStatus.ONLINE
                                }
                            } catch (e: Exception) {
                                try {
                                    val checkUrl = URL(channel.url)
                                    val connection = checkUrl.openConnection() as HttpURLConnection
                                    connection.requestMethod = "GET"
                                    connection.connectTimeout = 5000
                                    connection.readTimeout = 5000
                                    val code = connection.responseCode
                                    if (code in 200..399) {
                                        status = ChannelStatus.ONLINE
                                    }
                                } catch (ex: Exception) {
                                    status = ChannelStatus.OFFLINE
                                }
                            }

                            channel.status = status

                            synchronized(lock) {
                                testedCount++
                                if (status == ChannelStatus.ONLINE) {
                                    onlineCount++
                                }
                                if (testedCount % 5 == 0 || testedCount == totalCount) {
                                    onProgress("已检测活性: $testedCount/$totalCount (在线有效: $onlineCount)")
                                }
                            }

                            if (testedCount % 3 == 0 || testedCount == totalCount) {
                                runOnUiThread {
                                    channelAdapter.notifyDataSetChanged()
                                    onStatsUpdateNeeded?.invoke()
                                }
                            }
                        }
                    }

                    executor.shutdown()
                    try {
                        executor.awaitTermination(35, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // Final save and update, filtering out unplayable/offline ones
                    runOnUiThread {
                        synchronized(channelList) {
                            channelList.removeAll { it.status == ChannelStatus.OFFLINE || !it.isValidFormat }
                            saveChannelsToPrefs(channelList)
                            channelAdapter.updateChannels(channelList)
                        }
                        onStatsUpdateNeeded?.invoke()
                    }
                    onProgress("自动过滤清理失效源完毕！共上线 $onlineCount 个极速高可用频道。")
                    runOnUiThread {
                        onComplete(onlineCount)
                    }
                } else {
                    onProgress("已配置跳过连通测活。已导入 ${uniqueChannels.size} 个频道。")
                    runOnUiThread {
                        onComplete(uniqueChannels.size)
                    }
                }
            } else {
                onProgress("未能连接拉取到任何有效播放源！")
                runOnUiThread {
                    onComplete(0)
                }
            }
        }.start()
    }

    // --- Persistent Local Storage (SharedPreferences) ---

    private fun saveChannelsToPrefs(channels: List<Channel>) {
        val sharedPrefs = getSharedPreferences("TV_LIVE_PREFS", MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (channel in channels) {
            val obj = JSONObject()
            obj.put("name", channel.name)
            obj.put("url", channel.url)
            
            // Save backup URLs
            val urlsArray = JSONArray()
            for (u in channel.urls) {
                urlsArray.put(u)
            }
            obj.put("urls", urlsArray)
            obj.put("currentUrlIndex", channel.currentUrlIndex)
            
            jsonArray.put(obj)
        }
        sharedPrefs.edit().putString("SAVED_CHANNELS", jsonArray.toString()).apply()
    }

    private fun loadChannelsFromPrefs(): List<Channel> {
        val sharedPrefs = getSharedPreferences("TV_LIVE_PREFS", MODE_PRIVATE)
        val jsonStr = sharedPrefs.getString("SAVED_CHANNELS", null)
        if (jsonStr == null) {
            return getDefaultChannels()
        }
        val list = mutableListOf<Channel>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val name = obj.getString("name")
                val url = obj.getString("url")
                
                val channel = Channel(name, url)
                if (obj.has("urls")) {
                    val urlsArray = obj.getJSONArray("urls")
                    val urlsList = mutableListOf<String>()
                    for (j in 0 until urlsArray.length()) {
                        urlsList.add(urlsArray.getString(j))
                    }
                    val upperName = name.uppercase()
                    if (upperName.contains("CCTV") || upperName.contains("央视")) {
                        val miguUrls = urlsList.filter { it.lowercase().contains("migu") || it.lowercase().contains("mgsp") }
                        val otherUrls = urlsList.filter { !it.lowercase().contains("migu") && !it.lowercase().contains("mgsp") }
                        channel.urls = otherUrls + miguUrls
                    } else {
                        channel.urls = urlsList
                    }
                    if (channel.urls.isNotEmpty()) {
                        channel.url = channel.urls[0]
                    }
                } else {
                    channel.urls = listOf(url)
                }
                if (obj.has("currentUrlIndex")) {
                    channel.currentUrlIndex = obj.getInt("currentUrlIndex")
                }
                list.add(channel)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return getDefaultChannels()
        }
        return list.sortedWith(compareBy(
            { getChannelOrderKey(it.name) },
            { it.name }
        ))
    }

    private fun getDefaultChannels(): List<Channel> {
        val list = listOf(
            Channel("CCTV-1 综合", "http://39.134.115.163:8080/PLTV/88888888/224/3221225763/index.m3u8",
                listOf(
                    "http://39.134.115.163:8080/PLTV/88888888/224/3221225763/index.m3u8",
                    "http://112.50.243.8/PLTV/88888888/224/3221225763/index.m3u8"
                )
            ),
            Channel("广东卫视", "http://39.134.115.163:8080/PLTV/88888888/224/3221225821/index.m3u8",
                listOf(
                    "http://39.134.115.163:8080/PLTV/88888888/224/3221225821/index.m3u8",
                    "http://112.50.243.8/PLTV/88888888/224/3221225821/index.m3u8"
                )
            ),
            Channel("深圳卫视", "http://183.207.249.14/PLTV/88888888/224/3221225852/index.m3u8",
                listOf(
                    "http://183.207.249.14/PLTV/88888888/224/3221225852/index.m3u8",
                    "http://111.20.105.51/PLTV/88888888/224/3221225852/index.m3u8"
                )
            ),
            Channel("翡翠台", "http://39.134.115.163:8080/PLTV/88888888/224/3221225827/index.m3u8",
                listOf(
                    "http://39.134.115.163:8080/PLTV/88888888/224/3221225827/index.m3u8",
                    "http://112.50.243.8/PLTV/88888888/224/3221225827/index.m3u8"
                )
            ),
            Channel("香港卫视", "http://live.hkstv.hk.lxdns.com/live/hks/playlist.m3u8",
                listOf(
                    "http://live.hkstv.hk.lxdns.com/live/hks/playlist.m3u8",
                    "https://live.hkstv.hk.lxdns.com/live/hks/playlist.m3u8"
                )
            ),
            Channel("澳亚卫视", "http://112.50.243.8/PLTV/88888888/224/3221225816/index.m3u8",
                listOf(
                    "http://112.50.243.8/PLTV/88888888/224/3221225816/index.m3u8",
                    "http://39.134.115.163:8080/PLTV/88888888/224/3221225816/index.m3u8"
                )
            )
        )
        return list.sortedWith(compareBy(
            { getChannelOrderKey(it.name) },
            { it.name }
        ))
    }

    // --- Network Helpers ---

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1" // Fallback
    }

    private fun setFullScreen(full: Boolean) {
        isFullScreen = full
        val header = findViewById<View>(R.id.layout_header)
        val sidebar = findViewById<View>(R.id.layout_sidebar)
        val footer = findViewById<View>(R.id.layout_footer)
        val mainSplit = findViewById<LinearLayout>(R.id.layout_main_split)
        val cardView = findViewById<androidx.cardview.widget.CardView>(R.id.card_view_player)
        val container = findViewById<View>(R.id.layout_player_container)
        val layoutPlayerFocus = findViewById<FrameLayout>(R.id.layout_player_focus)

        val density = resources.displayMetrics.density

        if (full) {
            header.visibility = View.GONE
            sidebar.visibility = View.GONE
            footer.visibility = View.GONE
            mainSplit.setPadding(0, 0, 0, 0)
            cardView.radius = 0f
            
            layoutPlayerFocus?.setBackgroundResource(0)
            
            container?.layoutParams?.let { params ->
                if (params is LinearLayout.LayoutParams) {
                    params.weight = 1.0f
                    params.width = LinearLayout.LayoutParams.MATCH_PARENT
                    params.leftMargin = 0
                    container.layoutParams = params
                }
            }
            
            // Hide Status and Navigation bars for immersive TV/Cinema experience
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
            Toast.makeText(this, "已进入全屏模式，按返回键或点击退出", Toast.LENGTH_SHORT).show()
        } else {
            header.visibility = View.VISIBLE
            sidebar.visibility = View.VISIBLE
            footer.visibility = View.VISIBLE
            
            // Restore default padding (16dp horizontal, 8dp vertical) and corners (16dp)
            val padHorizontal = (16 * density).toInt()
            val padVertical = (8 * density).toInt()
            mainSplit.setPadding(padHorizontal, padVertical, padHorizontal, padVertical)
            cardView.radius = 16 * density
            
            layoutPlayerFocus?.setBackgroundResource(R.drawable.player_focus_selector)
            
            container?.layoutParams?.let { params ->
                if (params is LinearLayout.LayoutParams) {
                    params.weight = 0.72f
                    params.width = 0
                    params.leftMargin = (8 * density).toInt()
                    container.layoutParams = params
                }
            }
            
            // Show Status and Navigation bars
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            resetInactivityTimer()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        resetInactivityTimer()
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (isFullScreen) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        playPreviousChannel()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        playNextChannel()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val channel = currentPlayingChannel
                        if (channel != null && channel.urls.size > 1) {
                            val nextIndex = (channel.currentUrlIndex - 1 + channel.urls.size) % channel.urls.size
                            channel.currentUrlIndex = nextIndex
                            saveChannelsToPrefs(channelList)
                            Toast.makeText(this, "📺 线路切换: ${nextIndex + 1}/${channel.urls.size}", Toast.LENGTH_SHORT).show()
                            playChannel(channel, false)
                        } else {
                            playPreviousChannel()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val channel = currentPlayingChannel
                        if (channel != null && channel.urls.size > 1) {
                            val nextIndex = (channel.currentUrlIndex + 1) % channel.urls.size
                            channel.currentUrlIndex = nextIndex
                            saveChannelsToPrefs(channelList)
                            Toast.makeText(this, "📺 线路切换: ${nextIndex + 1}/${channel.urls.size}", Toast.LENGTH_SHORT).show()
                            playChannel(channel, false)
                        } else {
                            playNextChannel()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        setFullScreen(false)
                        rvChannels.requestFocus()
                        val currentIndex = channelList.indexOf(currentPlayingChannel)
                        if (currentIndex != -1) {
                            rvChannels.scrollToPosition(currentIndex)
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        confirmExitApp()
                        return true
                    }
                    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> {
                        // Let volume events pass through normally
                    }
                    else -> {
                        // Let other keys pass through normally, don't exit fullscreen
                        return super.dispatchKeyEvent(event)
                    }
                }
            } else {
                // Not in fullscreen (sidebar/controls are showing)
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (!isSettingsOpen && !isRemoteImportOpen) {
                        setFullScreen(true)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        resetInactivityTimer()
        if (isFullScreen) {
            if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                // Exit full screen on touch click
                setFullScreen(false)
                return true // Consume touch event
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onStop() {
        super.onStop()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        playbackMonitorHandler.removeCallbacks(playbackMonitorRunnable)
        stopPeriodicCheck()
        server?.stop()
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
    }
}

class DarkSpinnerAdapter(context: android.content.Context, items: Array<String>) : 
    android.widget.ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, items) {
    
    init {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
        val view = super.getView(position, convertView, parent)
        (view as? android.widget.TextView)?.setTextColor(android.graphics.Color.parseColor("#05D9E8"))
        (view as? android.widget.TextView)?.textSize = 13f
        return view
    }

    override fun getDropDownView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
        val view = super.getDropDownView(position, convertView, parent)
        view.setBackgroundColor(android.graphics.Color.parseColor("#121216"))
        (view as? android.widget.TextView)?.setTextColor(android.graphics.Color.WHITE)
        (view as? android.widget.TextView)?.textSize = 13f
        return view
    }
}
