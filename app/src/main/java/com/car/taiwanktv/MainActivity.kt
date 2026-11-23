package com.car.taiwanktv

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.recyclerview.widget.LinearLayoutManager
import com.car.taiwanktv.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var player: ExoPlayer

    private val songQueue = mutableListOf<SongItem>()
    private lateinit var queueAdapter: QueueAdapter
    private var currentSong: SongItem? = null
    private var isAudioOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPlayer()
        setupQueueList()
        setupWebView()
        setupControls()
    }

    private fun setupPlayer() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .build()

        binding.playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    playNextInQueue()
                }
            }
        })
    }

    private fun setupQueueList() {
        queueAdapter = QueueAdapter(
            items = songQueue,
            onMoveTop = { index ->
                if (index > 0 && index < songQueue.size) {
                    val item = songQueue.removeAt(index)
                    songQueue.add(0, item)
                    queueAdapter.notifyDataSetChanged()
                    Toast.makeText(this, "已優先插播：${item.title}", Toast.LENGTH_SHORT).show()
                }
            },
            onRemove = { index ->
                if (index in songQueue.indices) {
                    val removed = songQueue.removeAt(index)
                    queueAdapter.notifyDataSetChanged()
                    updateQueueBadge()
                    Toast.makeText(this, "已移除：${removed.title}", Toast.LENGTH_SHORT).show()
                }
            }
        )
        binding.rvQueue.layoutManager = LinearLayoutManager(this)
        binding.rvQueue.adapter = queueAdapter
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        binding.webView.setBackgroundColor(Color.parseColor("#0F1115"))
        binding.webView.addJavascriptInterface(WebAppInterface(), "AndroidBridge")

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.contains("youtube.com/watch") || url.contains("youtu.be/")) {
                    val videoId = extractYouTubeId(url)
                    if (videoId != null) {
                        promptAddSong(SongItem(title = "YouTube MV", videoId = videoId))
                        return true
                    }
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectCarDarkThemeAndInterceptor(view)
            }
        }

        binding.webView.loadUrl("https://song.corp.com.tw/")
    }

    private fun injectCarDarkThemeAndInterceptor(webView: WebView?) {
        val js = """
            (function() {
                if (!document.getElementById('car-ktv-style')) {
                    var style = document.createElement('style');
                    style.id = 'car-ktv-style';
                    style.innerHTML = `
                        body, html, .container, .content, div, section {
                            background-color: #0F1115 !important;
                            color: #E8ECF2 !important;
                        }
                        a { color: #40C4FF !important; font-size: 16px !important; }
                        ins.adsbygoogle, .adsbygoogle, iframe[src*="google"], .ad, .banner {
                            display: none !important;
                        }
                        input, button, select {
                            background-color: #1E232D !important;
                            color: #FFFFFF !important;
                            border: 1px solid #3A4252 !important;
                            min-height: 40px !important;
                            font-size: 16px !important;
                        }
                    `;
                    document.head.appendChild(style);
                }

                var iframes = document.querySelectorAll('iframe[src*="youtube.com/embed"]');
                if (iframes.length > 0 && !document.getElementById('car-play-yt-btn')) {
                    var src = iframes[0].src;
                    var match = src.match(/embed\/([a-zA-Z0-9_-]{11})/);
                    if (match && match[1]) {
                        var vid = match[1];
                        var pageTitle = document.title.replace(/- 台灣點歌王.*/, '').trim();
                        var btn = document.createElement('button');
                        btn.id = 'car-play-yt-btn';
                        btn.innerText = '▶ 點播此首 MV 到上方播放器';
                        btn.style.cssText = 'position:fixed;bottom:16px;right:16px;z-index:99999;background:#FF6D00;color:#fff;padding:14px 20px;border-radius:12px;font-size:18px;font-weight:bold;border:none;box-shadow:0 4px 12px rgba(0,0,0,0.6);';
                        btn.onclick = function() {
                            AndroidBridge.onSongSelected(pageTitle, '', vid);
                        };
                        document.body.appendChild(btn);
                    }
                }
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js, null)
    }

    inner class WebAppInterface {
        @JavascriptInterface
        fun onSongSelected(title: String, artist: String, videoId: String) {
            runOnUiThread {
                val song = SongItem(
                    title = title.ifBlank { "精選歌曲" },
                    artist = artist,
                    videoId = videoId.ifBlank { null }
                )
                promptAddSong(song)
            }
        }
    }

    private fun setupControls() {
        binding.btnNext.setOnClickListener { playNextInQueue() }

        binding.btnReplay.setOnClickListener {
            player.seekTo(0)
            player.play()
        }

        binding.btnSwitchSource.setOnClickListener {
            val song = currentSong
            if (song == null) {
                Toast.makeText(this, "目前沒有正在播放的歌曲", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            switchNextCandidateVersion(song)
        }

        binding.btnAudioOnly.setOnClickListener {
            isAudioOnly = !isAudioOnly
            binding.audioOnlyOverlay.visibility = if (isAudioOnly) View.VISIBLE else View.GONE
            binding.btnAudioOnly.text = if (isAudioOnly) "顯示MV" else "純聽歌"
            binding.tvAudioOnlyTitle.text = currentSong?.displayName() ?: "尚未播放歌曲"
        }

        binding.tabCatalog.setOnClickListener {
            binding.webView.visibility = View.VISIBLE
            binding.rvQueue.visibility = View.GONE
            binding.tabCatalog.setBackgroundColor(Color.parseColor("#FF6D00"))
            binding.tabQueue.setBackgroundColor(Color.parseColor("#252A34"))
        }

        binding.tabQueue.setOnClickListener {
            binding.webView.visibility = View.GONE
            binding.rvQueue.visibility = View.VISIBLE
            binding.tabQueue.setBackgroundColor(Color.parseColor("#FF6D00"))
            binding.tabCatalog.setBackgroundColor(Color.parseColor("#252A34"))
        }

        val triggerQuickAdd = {
            val query = binding.etQuickSearch.text.toString().trim()
            if (query.isNotEmpty()) {
                binding.etQuickSearch.setText("")
                promptAddSong(SongItem(title = query))
            }
        }

        binding.btnQuickAdd.setOnClickListener { triggerQuickAdd() }
        binding.etQuickSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                triggerQuickAdd()
                true
            } else false
        }
    }

    private fun promptAddSong(song: SongItem) {
        if (currentSong == null && !player.isPlaying) {
            playSong(song)
            return
        }

        val options = arrayOf("加入已點清單 (排最後)", "優先插播 (下一首)", "立即切歌播放")
        AlertDialog.Builder(this)
            .setTitle("點歌：${song.displayName()}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        songQueue.add(song)
                        queueAdapter.notifyDataSetChanged()
                        updateQueueBadge()
                        Toast.makeText(this, "已加入清單：${song.title}", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        songQueue.add(0, song)
                        queueAdapter.notifyDataSetChanged()
                        updateQueueBadge()
                        Toast.makeText(this, "已優先插播：${song.title}", Toast.LENGTH_SHORT).show()
                    }
                    2 -> playSong(song)
                }
            }
            .show()
    }

    private fun playNextInQueue() {
        if (songQueue.isNotEmpty()) {
            val next = songQueue.removeAt(0)
            queueAdapter.notifyDataSetChanged()
            updateQueueBadge()
            playSong(next)
        } else {
            Toast.makeText(this, "已點清單已播完，請點選新歌", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playSong(song: SongItem) {
        currentSong = song
        binding.tvNowPlaying.text = "🎵 載入中：${song.displayName()}"
        binding.tvAudioOnlyTitle.text = song.displayName()
        binding.loadingIndicator.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val targetUrl = if (!song.videoId.isNullOrBlank()) {
                    song.videoId!!
                } else {
                    if (song.candidateUrls.isEmpty()) {
                        song.candidateUrls = YouTubeStreamResolver.searchCandidates(song.displayName())
                        song.currentCandidateIndex = 0
                    }
                    if (song.candidateUrls.isEmpty()) {
                        throw Exception("找不到相符的 MV")
                    }
                    song.candidateUrls[song.currentCandidateIndex]
                }

                val resolved = YouTubeStreamResolver.resolveStream(targetUrl)
                startExoPlayer(resolved)
                binding.tvNowPlaying.text = "🎵 正在播放：${song.displayName()}"
            } catch (e: Exception) {
                binding.tvNowPlaying.text = "⚠️ 載入失敗：${song.title}"
                Toast.makeText(this@MainActivity, "無法載入 MV：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun switchNextCandidateVersion(song: SongItem) {
        lifecycleScope.launch {
            binding.loadingIndicator.visibility = View.VISIBLE
            try {
                if (song.candidateUrls.isEmpty()) {
                    song.candidateUrls = YouTubeStreamResolver.searchCandidates(song.displayName())
                    song.currentCandidateIndex = 0
                } else {
                    song.currentCandidateIndex = (song.currentCandidateIndex + 1) % song.candidateUrls.size
                }
                song.videoId = null
                if (song.candidateUrls.isNotEmpty()) {
                    val resolved = YouTubeStreamResolver.resolveStream(song.candidateUrls[song.currentCandidateIndex])
                    startExoPlayer(resolved)
                    Toast.makeText(this@MainActivity, "已切換至第 ${song.currentCandidateIndex + 1} 個版本", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "切換版本失敗：${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun startExoPlayer(resolved: ResolvedStream) {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")

        if (resolved.videoUrl != null && resolved.audioUrl != null) {
            val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(resolved.videoUrl))
            val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(resolved.audioUrl))
            val mergedSource = MergingMediaSource(videoSource, audioSource)
            player.setMediaSource(mergedSource)
        } else if (resolved.muxedUrl != null) {
            player.setMediaItem(MediaItem.fromUri(resolved.muxedUrl))
        } else {
            throw Exception("沒有可用的影音串流")
        }

        player.prepare()
        player.play()
    }

    private fun updateQueueBadge() {
        binding.tabQueue.text = "已點 (${songQueue.size})"
    }

    private fun extractYouTubeId(url: String): String? {
        val regex = Regex("(?:v=|youtu\\.be/|embed/)([a-zA-Z0-9_-]{11})")
        return regex.find(url)?.groupValues?.getOrNull(1)
    }

    override fun onBackPressed() {
        if (binding.webView.visibility == View.VISIBLE && binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }
}
