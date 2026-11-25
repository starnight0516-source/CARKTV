package com.car.taiwanktv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.car.taiwanktv.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession

    private val songQueue = mutableListOf<SongItem>()
    private val previousStack = mutableListOf<SongItem>()
    private val playedHistory = LinkedHashSet<String>()
    private var consecutiveFailures = 0

    private lateinit var catalogAdapter: CatalogAdapter
    private lateinit var singerAdapter: SingerAdapter
    private lateinit var queueAdapter: QueueAdapter

    private var currentSong: SongItem? = null
    private var currentCategory: SongCategory = SongCategory.HOT
    private var currentAutoPlayMode: AutoPlayMode = AutoPlayMode.MIXED
    private var isAudioOnly = false
    private var isFullscreen = false
    private var remoteServer: RemoteControlServer? = null
    private val serverPort = 8899
    private var hasAutoStarted = false

    companion object {
        private const val PREFS_NAME = "taiwan_car_ktv_prefs"
        private const val KEY_AUTO_PLAY_MODE = "auto_play_mode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadUserSettings()
        setupPlayer()
        setupMediaSession()
        setupLists()
        setupControls()
        startRemoteServer()

        // 啟動後載入「新歌排行」並自動開始連續播放
        selectCategoryTab(binding.tabHot, SongCategory.HOT, autoStartPlay = true)
    }

    private fun loadUserSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedMode = prefs.getString(KEY_AUTO_PLAY_MODE, AutoPlayMode.MIXED.name)
        currentAutoPlayMode = try {
            AutoPlayMode.valueOf(savedMode ?: AutoPlayMode.MIXED.name)
        } catch (_: Exception) {
            AutoPlayMode.MIXED
        }
        updateAutoPlayModeButton()
    }

    private fun saveAutoPlayMode(mode: AutoPlayMode) {
        currentAutoPlayMode = mode
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AUTO_PLAY_MODE, mode.name)
            .apply()
        updateAutoPlayModeButton()
    }

    private fun updateAutoPlayModeButton() {
        binding.btnAutoPlayMode.text = currentAutoPlayMode.label
        val bgColor = when (currentAutoPlayMode) {
            AutoPlayMode.MIXED -> "#283593"
            AutoPlayMode.MANDARIN -> "#00695C"
            AutoPlayMode.TAIWANESE -> "#AD1457"
        }
        binding.btnAutoPlayMode.setBackgroundColor(Color.parseColor(bgColor))
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun setupPlayer() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // 強制高畫質軌道選擇器（優先最高位元率與 1080p/1440p 畫質）
        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setForceHighestSupportedBitrate(true)
                .setMaxVideoSize(2560, 1440)
                .build()
        }

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(audioAttributes, true)
            .build()

        binding.playerView.player = player
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateMediaSessionPlaybackState()
                if (playbackState == Player.STATE_ENDED) {
                    playNextOrAutoContinue()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateMediaSessionPlaybackState()
            }
        })
    }

    /**
     * 註冊系統級 MediaSession：
     * 讓車子方向盤的「上一首 / 下一首 / 播放暫停」按鍵可直接控制本 App，
     * 並將目前歌名與歌手同步顯示在車機儀表與控制列。
     */
    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "TaiwanCarKTVSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    runOnUiThread { player.play() }
                }

                override fun onPause() {
                    runOnUiThread { player.pause() }
                }

                override fun onSkipToNext() {
                    runOnUiThread { playNextOrAutoContinue() }
                }

                override fun onSkipToPrevious() {
                    runOnUiThread { playPreviousSong() }
                }

                @Suppress("DEPRECATION")
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val keyEvent = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                runOnUiThread { playNextOrAutoContinue() }
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                runOnUiThread { playPreviousSong() }
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                runOnUiThread {
                                    if (player.isPlaying) player.pause() else player.play()
                                }
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                runOnUiThread { player.play() }
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                runOnUiThread { player.pause() }
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonIntent)
                }
            })
            isActive = true
        }
        updateMediaSessionPlaybackState()
    }

    private fun updateMediaSessionPlaybackState() {
        if (!::mediaSession.isInitialized) return
        val state = when {
            player.isPlaying -> PlaybackState.STATE_PLAYING
            player.playbackState == Player.STATE_BUFFERING -> PlaybackState.STATE_BUFFERING
            player.playbackState == Player.STATE_READY -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_STOPPED
        }
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_STOP

        val playbackState = PlaybackState.Builder()
            .setActions(actions)
            .setState(state, player.currentPosition, if (player.isPlaying) 1.0f else 0.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun updateMediaSessionMetadata(song: SongItem) {
        if (!::mediaSession.isInitialized) return
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist.ifBlank { "台灣車用點歌王" })
            .build()
        mediaSession.setMetadata(metadata)
    }

    /**
     * 前景直接攔截方向盤媒體硬體按鍵（相容多數 CarPlay 安卓盒直接派發 KeyEvent）
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    playNextOrAutoContinue()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    playPreviousSong()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (player.isPlaying) player.pause() else player.play()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    player.play()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    player.pause()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP -> {
                    playNextOrAutoContinue()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    playPreviousSong()
                    return true
                }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                    toggleFullscreen()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (isFullscreen) {
                        toggleFullscreen()
                        return true
                    }
                    if (binding.singerHeaderBar.visibility == View.VISIBLE) {
                        showSingerWall()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setupLists() {
        catalogAdapter = CatalogAdapter(
            onOrderClick = { song -> enqueueOrPlayDirectly(song) },
            onCutInClick = { song -> cutInSongDirectly(song) }
        )
        binding.rvCatalog.layoutManager = LinearLayoutManager(this)
        binding.rvCatalog.adapter = catalogAdapter

        singerAdapter = SingerAdapter(CorpSongRepository.featuredSingers) { singer ->
            openSingerSongs(singer)
        }
        binding.rvSingers.layoutManager = GridLayoutManager(this, 3)
        binding.rvSingers.adapter = singerAdapter

        queueAdapter = QueueAdapter(
            items = songQueue,
            onPlayNow = { index ->
                if (index in songQueue.indices) {
                    val item = songQueue.removeAt(index)
                    queueAdapter.notifyDataSetChanged()
                    updateQueueBadge()
                    playSong(item, pushCurrentToPrev = true)
                }
            },
            onMoveTop = { index ->
                if (index > 0 && index < songQueue.size) {
                    val item = songQueue.removeAt(index)
                    songQueue.add(0, item)
                    queueAdapter.notifyDataSetChanged()
                    Toast.makeText(this, "⚡ 已移至下一首插播：${item.title}", Toast.LENGTH_SHORT).show()
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

    private fun setupControls()
        startRemoteServer() {
        binding.btnPrev.setOnClickListener { playPreviousSong() }
        binding.btnNext.setOnClickListener { playNextOrAutoContinue() }

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

        binding.btnFullscreen.setOnClickListener {
            toggleFullscreen()
        }

        // 手機掃 QR Code 遠端點歌
        binding.btnQrCode.setOnClickListener {
            showQrCodeDialog()
        }

        // 公播模式選擇：純國語 / 純台語 / 混合
        binding.btnAutoPlayMode.setOnClickListener {
            showAutoPlayModeDialog()
        }

        // 歌單自動更新設定與立即同步
        binding.btnUpdateCatalog.setOnClickListener {
            showCatalogUpdateDialog()
        }

        // 主分類分頁
        binding.tabHot.setOnClickListener { selectCategoryTab(binding.tabHot, SongCategory.HOT) }
        binding.tabTaiwanese.setOnClickListener { selectCategoryTab(binding.tabTaiwanese, SongCategory.TAIWANESE) }
        binding.tabMandarin.setOnClickListener { selectCategoryTab(binding.tabMandarin, SongCategory.MANDARIN) }
        binding.tabClassics.setOnClickListener { selectCategoryTab(binding.tabClassics, SongCategory.CLASSICS) }

        // 歌星點歌分頁
        binding.tabSingers.setOnClickListener { showSingerWall() }
        binding.btnBackToSingers.setOnClickListener { showSingerWall() }

        // 字數分類分頁
        binding.tabWordCount.setOnClickListener {
            highlightMainTab(binding.tabWordCount)
            binding.wordCountBar.visibility = View.VISIBLE
            binding.singerHeaderBar.visibility = View.GONE
            binding.rvCatalog.visibility = View.VISIBLE
            binding.rvSingers.visibility = View.GONE
            binding.rvQueue.visibility = View.GONE
            binding.listLoadingOverlay.visibility = View.GONE
            selectWordCountFilter(binding.btnWcAll, 0)
        }

        // 字數次選單按鈕
        binding.btnWcAll.setOnClickListener { selectWordCountFilter(binding.btnWcAll, 0) }
        binding.btnWc1.setOnClickListener { selectWordCountFilter(binding.btnWc1, 1) }
        binding.btnWc2.setOnClickListener { selectWordCountFilter(binding.btnWc2, 2) }
        binding.btnWc3.setOnClickListener { selectWordCountFilter(binding.btnWc3, 3) }
        binding.btnWc4.setOnClickListener { selectWordCountFilter(binding.btnWc4, 4) }
        binding.btnWc5.setOnClickListener { selectWordCountFilter(binding.btnWc5, 5) }
        binding.btnWc6Plus.setOnClickListener { selectWordCountFilter(binding.btnWc6Plus, 6) }

        // 已點歌單分頁
        binding.tabQueue.setOnClickListener {
            highlightMainTab(binding.tabQueue)
            binding.wordCountBar.visibility = View.GONE
            binding.singerHeaderBar.visibility = View.GONE
            binding.rvCatalog.visibility = View.GONE
            binding.rvSingers.visibility = View.GONE
            binding.rvQueue.visibility = View.VISIBLE
            binding.listLoadingOverlay.visibility = View.GONE
        }

        // 原生搜歌
        val doSearch = {
            val query = binding.etSearch.text.toString().trim()
            if (query.isNotEmpty()) {
                hideKeyboard()
                performNativeSearch(query)
            }
        }
        binding.btnSearch.setOnClickListener { doSearch() }
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }
    }

    private fun showAutoPlayModeDialog() {
        val modes = arrayOf(AutoPlayMode.MIXED, AutoPlayMode.MANDARIN, AutoPlayMode.TAIWANESE)
        val labels = arrayOf("混合連播（國語 + 台語全部金曲）", "純國語連播（只播國語熱門與經典）", "純台語連播（只播台語金曲與老歌）")
        val checkedIndex = modes.indexOf(currentAutoPlayMode).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("選擇自動公播語系")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val selected = modes[which]
                saveAutoPlayMode(selected)
                Toast.makeText(this, "已切換為：${selected.label}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCatalogUpdateDialog() {
        val isAuto = CorpSongRepository.isAutoUpdateEnabled(this)
        val lastTime = CorpSongRepository.getLastUpdateTime(this)
        val autoLabel = if (isAuto) "✅ 開啟 App 時自動更新最新歌單（目前：開啟）" else "⬜ 開啟 App 時自動更新最新歌單（目前：關閉）"
        val options = arrayOf(
            "🔄 立即同步最新排行榜歌單",
            autoLabel
        )

        AlertDialog.Builder(this)
            .setTitle("歌單自動更新設定（上次更新：$lastTime）")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> forceRefreshCatalog()
                    1 -> {
                        val nextState = !isAuto
                        CorpSongRepository.setAutoUpdateEnabled(this, nextState)
                        Toast.makeText(
                            this,
                            if (nextState) "已開啟「啟動自動更新最新歌單」" else "已關閉自動更新（改用本機快取）",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("關閉", null)
            .show()
    }

    private fun forceRefreshCatalog() {
        binding.listLoadingOverlay.visibility = View.VISIBLE
        binding.tvListLoadingMsg.text = "正在同步台灣點歌王最新歌單..."

        lifecycleScope.launch {
            try {
                val count = CorpSongRepository.refreshAllFromWeb(this@MainActivity)
                val updatedList = CorpSongRepository.getSongs(this@MainActivity, currentCategory, allowNetwork = false)
                catalogAdapter.submitList(updatedList)
                binding.rvCatalog.scrollToPosition(0)
                Toast.makeText(
                    this@MainActivity,
                    "✅ 歌單更新完成！同步了 $count 首最新榜單歌曲",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "更新失敗：${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.listLoadingOverlay.visibility = View.GONE
            }
        }
    }

    private fun highlightMainTab(activeTab: MaterialButton?) {
        val allTabs = listOf(
            binding.tabHot,
            binding.tabTaiwanese,
            binding.tabMandarin,
            binding.tabClassics,
            binding.tabSingers,
            binding.tabWordCount,
            binding.tabQueue
        )
        for (tab in allTabs) {
            tab.setBackgroundColor(
                if (tab == activeTab) Color.parseColor("#FF6D00") else Color.parseColor("#232833")
            )
        }
    }

    private fun selectCategoryTab(tab: MaterialButton, category: SongCategory, autoStartPlay: Boolean = false) {
        currentCategory = category
        highlightMainTab(tab)
        binding.wordCountBar.visibility = View.GONE
        binding.singerHeaderBar.visibility = View.GONE
        binding.rvCatalog.visibility = View.VISIBLE
        binding.rvSingers.visibility = View.GONE
        binding.rvQueue.visibility = View.GONE
        binding.listLoadingOverlay.visibility = View.VISIBLE
        binding.tvListLoadingMsg.text = "載入歌單中..."

        lifecycleScope.launch {
            val songs = CorpSongRepository.getSongs(this@MainActivity, category)
            catalogAdapter.submitList(songs)
            binding.rvCatalog.scrollToPosition(0)
            binding.listLoadingOverlay.visibility = View.GONE

            // 一打開 App 自動開播並連續播放
            if (autoStartPlay && !hasAutoStarted && currentSong == null) {
                hasAutoStarted = true
                playNextOrAutoContinue()
            }
        }
    }

    private fun showSingerWall() {
        highlightMainTab(binding.tabSingers)
        binding.wordCountBar.visibility = View.GONE
        binding.singerHeaderBar.visibility = View.GONE
        binding.rvCatalog.visibility = View.GONE
        binding.rvQueue.visibility = View.GONE
        binding.rvSingers.visibility = View.VISIBLE
        binding.listLoadingOverlay.visibility = View.GONE
    }

    private fun openSingerSongs(singer: SingerInfo) {
        binding.rvSingers.visibility = View.GONE
        binding.rvCatalog.visibility = View.VISIBLE
        binding.singerHeaderBar.visibility = View.VISIBLE
        binding.tvSelectedSinger.text = "🎤 ${singer.name} 精選歌單"

        val localSongs = CorpSongRepository.filterBySingerLocal(this, singer.name)
        catalogAdapter.submitList(localSongs)
        binding.rvCatalog.scrollToPosition(0)

        lifecycleScope.launch {
            if (localSongs.isEmpty()) {
                binding.listLoadingOverlay.visibility = View.VISIBLE
                binding.tvListLoadingMsg.text = "搜尋 ${singer.name} 的熱門歌曲..."
            }
            try {
                val remoteSongs = YouTubeStreamResolver.searchSongItems("${singer.name} 經典歌曲", limit = 25)
                val combined = LinkedHashMap<String, SongItem>()
                for (s in localSongs) combined[s.title] = s
                for (s in remoteSongs) {
                    if (!combined.containsKey(s.title)) combined[s.title] = s
                }
                if (binding.singerHeaderBar.visibility == View.VISIBLE &&
                    binding.tvSelectedSinger.text.contains(singer.name)
                ) {
                    catalogAdapter.submitList(combined.values.toList())
                }
            } catch (_: Exception) {
            } finally {
                binding.listLoadingOverlay.visibility = View.GONE
            }
        }
    }

    private fun selectWordCountFilter(activeBtn: MaterialButton, wordCount: Int) {
        val allBtns = listOf(
            binding.btnWcAll, binding.btnWc1, binding.btnWc2,
            binding.btnWc3, binding.btnWc4, binding.btnWc5, binding.btnWc6Plus
        )
        for (btn in allBtns) {
            btn.setBackgroundColor(
                if (btn == activeBtn) Color.parseColor("#00B0FF") else Color.parseColor("#252B37")
            )
        }
        val filtered = CorpSongRepository.filterByWordCount(this, wordCount)
        catalogAdapter.submitList(filtered)
        binding.rvCatalog.scrollToPosition(0)
        val label = when {
            wordCount == 0 -> "全部歌庫"
            wordCount >= 6 -> "6字以上"
            else -> "${wordCount}字歌"
        }
        Toast.makeText(this, "$label：共 ${filtered.size} 首", Toast.LENGTH_SHORT).show()
    }

    private fun performNativeSearch(query: String) {
        highlightMainTab(null)
        binding.wordCountBar.visibility = View.GONE
        binding.singerHeaderBar.visibility = View.GONE
        binding.rvCatalog.visibility = View.VISIBLE
        binding.rvSingers.visibility = View.GONE
        binding.rvQueue.visibility = View.GONE
        binding.listLoadingOverlay.visibility = View.VISIBLE
        binding.tvListLoadingMsg.text = "搜尋「$query」中..."

        lifecycleScope.launch {
            try {
                val results = YouTubeStreamResolver.searchSongItems(query, limit = 30)
                if (results.isEmpty()) {
                    Toast.makeText(this@MainActivity, "找不到「$query」相關歌曲", Toast.LENGTH_SHORT).show()
                } else {
                    catalogAdapter.submitList(results)
                    binding.rvCatalog.scrollToPosition(0)
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "搜尋失敗：${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.listLoadingOverlay.visibility = View.GONE
            }
        }
    }

    private fun enqueueOrPlayDirectly(song: SongItem) {
        if (currentSong == null && !player.isPlaying) {
            playSong(song, pushCurrentToPrev = false)
        } else {
            songQueue.add(song)
            queueAdapter.notifyDataSetChanged()
            updateQueueBadge()
            Toast.makeText(
                this,
                "✅ 已點（第 ${songQueue.size} 首）：${song.title}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun cutInSongDirectly(song: SongItem) {
        if (currentSong == null && !player.isPlaying) {
            playSong(song, pushCurrentToPrev = false)
        } else {
            songQueue.add(0, song)
            queueAdapter.notifyDataSetChanged()
            updateQueueBadge()
            Toast.makeText(
                this,
                "⚡ 已插播（下一首立即播放）：${song.title}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 上一首：
     * 支援 UI「上一首」按鍵與方向盤「上一首」按鍵
     */
    private fun playPreviousSong() {
        if (previousStack.isNotEmpty()) {
            val prevSong = previousStack.removeAt(previousStack.lastIndex)
            // 把目前正在播的歌放回待播清單最前面，方便再按「下一首」切回來
            currentSong?.let { curr ->
                songQueue.add(0, curr.copy())
                queueAdapter.notifyDataSetChanged()
                updateQueueBadge()
            }
            playSong(prevSong, isAutoPlay = false, pushCurrentToPrev = false)
        } else {
            // 若沒有更早的歌，則直接重頭播放當前歌曲
            player.seekTo(0)
            player.play()
            Toast.makeText(this, "已回到第一首，重新播放", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 下一首 / 自動公播連播（依據所選的 純國語 / 純台語 / 混合 模式挑選）
     */
    private fun playNextOrAutoContinue() {
        if (songQueue.isNotEmpty()) {
            val next = songQueue.removeAt(0)
            queueAdapter.notifyDataSetChanged()
            updateQueueBadge()
            playSong(next, isAutoPlay = false, pushCurrentToPrev = true)
            return
        }

        val pool = CorpSongRepository.getAutoPlayPool(this, currentAutoPlayMode)
        if (pool.isEmpty()) return

        var unplayed = pool.filter { !playedHistory.contains(it.title) }
        if (unplayed.isEmpty()) {
            playedHistory.clear()
            unplayed = pool
        }

        val randomNext = unplayed.random().copy()
        playSong(randomNext, isAutoPlay = true, pushCurrentToPrev = true)
    }

    private fun playSong(song: SongItem, isAutoPlay: Boolean = false, pushCurrentToPrev: Boolean = true) {
        if (pushCurrentToPrev) {
            currentSong?.let { prev ->
                if (previousStack.lastOrNull()?.title != prev.title) {
                    previousStack.add(prev.copy())
                    if (previousStack.size > 30) {
                        previousStack.removeAt(0)
                    }
                }
            }
        }

        currentSong = song
        playedHistory.add(song.title)
        updateMediaSessionMetadata(song)

        val prefix = if (isAutoPlay) "📻 ${currentAutoPlayMode.label}" else "🎵 正在播放"
        binding.tvNowPlaying.text = "⏳ 高畫質載入中：${song.displayName()}"
        binding.tvAudioOnlyTitle.text = song.displayName()
        binding.tvQualityBadge.visibility = View.GONE
        binding.loadingIndicator.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val resolved = resolveWithFallback(song)
                startExoPlayer(resolved)
                consecutiveFailures = 0
                binding.tvNowPlaying.text = "$prefix：${song.displayName()}"
                binding.tvQualityBadge.text = resolved.qualityLabel
                binding.tvQualityBadge.visibility = View.VISIBLE
            } catch (e: Exception) {
                consecutiveFailures++
                binding.tvNowPlaying.text = "⚠️ 載入失敗：${song.title}"
                Toast.makeText(this@MainActivity, "跳過無法載入的歌曲：${song.title}", Toast.LENGTH_SHORT).show()

                if (consecutiveFailures < 3) {
                    delay(1500)
                    playNextOrAutoContinue()
                }
            } finally {
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    private suspend fun resolveWithFallback(song: SongItem): ResolvedStream {
        if (song.videoId.isNullOrBlank() && !song.corpMvUrl.isNullOrBlank()) {
            song.videoId = YouTubeStreamResolver.extractVideoIdFromCorpPage(song.corpMvUrl)
        }

        if (!song.videoId.isNullOrBlank()) {
            try {
                return YouTubeStreamResolver.resolveStream(song.videoId!!)
            } catch (_: Exception) {
            }
        }

        if (song.candidateUrls.isEmpty()) {
            song.candidateUrls = YouTubeStreamResolver.searchCandidates(song.displayName())
            song.currentCandidateIndex = 0
        }
        if (song.candidateUrls.isEmpty()) {
            throw Exception("找不到相符的 MV")
        }
        return YouTubeStreamResolver.resolveStream(song.candidateUrls[song.currentCandidateIndex])
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
                    consecutiveFailures = 0
                    binding.tvNowPlaying.text = "🎵 正在播放：${song.displayName()}"
                    binding.tvQualityBadge.text = resolved.qualityLabel
                    binding.tvQualityBadge.visibility = View.VISIBLE
                    Toast.makeText(this@MainActivity, "已切換至第 ${song.currentCandidateIndex + 1} 個版本 (${resolved.qualityLabel})", Toast.LENGTH_SHORT).show()
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
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")

        when {
            resolved.videoUrl != null && resolved.audioUrl != null -> {
                val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(resolved.videoUrl))
                val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(resolved.audioUrl))
                val mergedSource = MergingMediaSource(videoSource, audioSource)
                player.setMediaSource(mergedSource)
            }
            resolved.hlsUrl != null -> {
                val hlsSource = HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(resolved.hlsUrl))
                player.setMediaSource(hlsSource)
            }
            resolved.muxedUrl != null -> {
                player.setMediaItem(MediaItem.fromUri(resolved.muxedUrl))
            }
            else -> throw Exception("沒有可用的影音串流")
        }

        player.prepare()
        player.play()
        updateMediaSessionPlaybackState()
    }

    private fun updateQueueBadge() {
        binding.tabQueue.text = "已點 (${songQueue.size})"
    }

    private fun startRemoteServer() {
        try {
            remoteServer?.stop()
            remoteServer = RemoteControlServer(
                context = this,
                callback = object : RemoteKtvCallback {
                    override fun getNowPlayingText(): String {
                        return binding.tvNowPlaying.text?.toString() ?: "尚未播放"
                    }

                    override fun isCurrentlyPlaying(): Boolean {
                        return player.isPlaying
                    }

                    override fun getQueueSnapshot(): List<SongItem> {
                        return synchronized(songQueue) { songQueue.toList() }
                    }

                    override fun onRemoteOrderSong(song: SongItem, cutIn: Boolean) {
                        runOnUiThread {
                            if (cutIn) {
                                cutInSongDirectly(song)
                            } else {
                                enqueueOrPlayDirectly(song)
                            }
                        }
                    }

                    override fun onRemoteControl(action: String) {
                        runOnUiThread {
                            when (action) {
                                "next" -> playNextOrAutoContinue()
                                "prev" -> playPreviousSong()
                                "replay" -> {
                                    player.seekTo(0)
                                    player.play()
                                }
                                "toggle" -> {
                                    if (player.isPlaying) player.pause() else player.play()
                                }
                            }
                        }
                    }

                    override fun onRemoteQueueAction(action: String, index: Int) {
                        runOnUiThread {
                            if (index !in songQueue.indices) return@runOnUiThread
                            when (action) {
                                "play" -> {
                                    val item = songQueue.removeAt(index)
                                    queueAdapter.notifyDataSetChanged()
                                    updateQueueBadge()
                                    playSong(item, pushCurrentToPrev = true)
                                }
                                "top" -> {
                                    if (index > 0) {
                                        val item = songQueue.removeAt(index)
                                        songQueue.add(0, item)
                                        queueAdapter.notifyDataSetChanged()
                                    }
                                }
                                "remove" -> {
                                    songQueue.removeAt(index)
                                    queueAdapter.notifyDataSetChanged()
                                    updateQueueBadge()
                                }
                            }
                        }
                    }
                },
                port = serverPort
            )
            remoteServer?.start()
        } catch (e: Exception) {
            Toast.makeText(this, "遠端點歌服務啟動失敗：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showQrCodeDialog() {
        val ip = QrCodeHelper.getLocalIpAddress()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 32, 40, 20)
            setBackgroundColor(Color.parseColor("#161922"))
        }

        if (ip == null) {
            val tvErr = TextView(this).apply {
                text = "⚠️ 偵測不到區域網路 IP\n\n請確認車機或電視已連上 Wi-Fi 或手機熱點，再按「重新偵測」。"
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
            }
            container.addView(tvErr)
        } else {
            val url = "http://$ip:$serverPort"
            val qrBitmap = QrCodeHelper.generateQrBitmap(url, 520)

            val ivQr = ImageView(this).apply {
                setImageBitmap(qrBitmap)
                layoutParams = LinearLayout.LayoutParams(520, 520)
            }
            val tvUrl = TextView(this).apply {
                text = url
                setTextColor(Color.parseColor("#40E0FF"))
                textSize = 20f
                gravity = Gravity.CENTER
                setPadding(0, 20, 0, 8)
            }
            val tvHint = TextView(this).apply {
                text = "手機連上同一個 Wi-Fi 或熱點，掃碼即可點歌、插播與切歌（免裝 App）"
                setTextColor(Color.parseColor("#CFD8DC"))
                textSize = 14f
                gravity = Gravity.CENTER
            }
            container.addView(ivQr)
            container.addView(tvUrl)
            container.addView(tvHint)
        }

        AlertDialog.Builder(this)
            .setTitle("📱 手機掃碼遠端點歌")
            .setView(container)
            .setPositiveButton("關閉", null)
            .setNeutralButton("重新偵測 IP") { _, _ ->
                startRemoteServer()
                showQrCodeDialog()
            }
            .show()
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            binding.catalogPanel.visibility = View.GONE
            binding.controlPanel.visibility = View.GONE
            Toast.makeText(this, "已進入全螢幕（按返回鍵或選單鍵退出）", Toast.LENGTH_SHORT).show()
        } else {
            binding.catalogPanel.visibility = View.VISIBLE
            binding.controlPanel.visibility = View.VISIBLE
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        binding.etSearch.clearFocus()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::mediaSession.isInitialized) {
            mediaSession.isActive = false
            mediaSession.release()
        }
        remoteServer?.stop()
        player.release()
    }
}
