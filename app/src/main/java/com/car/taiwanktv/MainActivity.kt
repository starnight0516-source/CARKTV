package com.car.taiwanktv

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.car.taiwanktv.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var player: ExoPlayer

    private val songQueue = mutableListOf<SongItem>()
    private val playedHistory = LinkedHashSet<String>()
    private var consecutiveFailures = 0

    private lateinit var catalogAdapter: CatalogAdapter
    private lateinit var singerAdapter: SingerAdapter
    private lateinit var queueAdapter: QueueAdapter

    private var currentSong: SongItem? = null
    private var isAudioOnly = false
    private var hasAutoStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPlayer()
        setupLists()
        setupControls()

        // 啟動後載入「新歌排行」並自動開始連續播放
        selectCategoryTab(binding.tabHot, SongCategory.HOT, autoStartPlay = true)
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
                    playNextOrAutoContinue()
                }
            }
        })
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
                    playSong(item)
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

    private fun setupControls() {
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
        highlightMainTab(tab)
        binding.wordCountBar.visibility = View.GONE
        binding.singerHeaderBar.visibility = View.GONE
        binding.rvCatalog.visibility = View.VISIBLE
        binding.rvSingers.visibility = View.GONE
        binding.rvQueue.visibility = View.GONE
        binding.listLoadingOverlay.visibility = View.VISIBLE
        binding.tvListLoadingMsg.text = "載入歌單中..."

        lifecycleScope.launch {
            val songs = CorpSongRepository.getSongs(category)
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

        // 1. 先瞬間顯示本地大歌庫裡該歌星的歌曲
        val localSongs = CorpSongRepository.filterBySingerLocal(singer.name)
        catalogAdapter.submitList(localSongs)
        binding.rvCatalog.scrollToPosition(0)

        // 2. 背景同步從 YouTube 擴充更多該歌星的熱門 MV
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
                // 離線或限流時仍保有本地歌單
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
        val filtered = CorpSongRepository.filterByWordCount(wordCount)
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

    /**
     * 一般點歌（排到已點清單最後；若沒在播則直接開播）
     */
    private fun enqueueOrPlayDirectly(song: SongItem) {
        if (currentSong == null && !player.isPlaying) {
            playSong(song)
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

    /**
     * 優先插播（直接排到已點清單第 1 位，這首播完馬上播它；若沒在播則直接開播）
     */
    private fun cutInSongDirectly(song: SongItem) {
        if (currentSong == null && !player.isPlaying) {
            playSong(song)
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
     * 連續播放核心：
     * 1. 若「已點歌單」有歌 → 播下一首已點歌曲
     * 2. 若「已點歌單」播完 → 自動從完整大歌庫隨機挑一首未重複歌曲無縫連播（公播模式）
     */
    private fun playNextOrAutoContinue() {
        if (songQueue.isNotEmpty()) {
            val next = songQueue.removeAt(0)
            queueAdapter.notifyDataSetChanged()
            updateQueueBadge()
            playSong(next, isAutoPlay = false)
            return
        }

        val pool = CorpSongRepository.getAllBuiltInAndCachedSongs()
        if (pool.isEmpty()) return

        var unplayed = pool.filter { !playedHistory.contains(it.title) }
        if (unplayed.isEmpty()) {
            playedHistory.clear()
            unplayed = pool
        }

        val randomNext = unplayed.random().copy()
        playSong(randomNext, isAutoPlay = true)
    }

    private fun playSong(song: SongItem, isAutoPlay: Boolean = false) {
        currentSong = song
        playedHistory.add(song.title)
        val prefix = if (isAutoPlay) "📻 自動連播" else "🎵 正在播放"
        binding.tvNowPlaying.text = "⏳ 載入中：${song.displayName()}"
        binding.tvAudioOnlyTitle.text = song.displayName()
        binding.loadingIndicator.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val resolved = resolveWithFallback(song)
                startExoPlayer(resolved)
                consecutiveFailures = 0
                binding.tvNowPlaying.text = "$prefix：${song.displayName()}"
            } catch (e: Exception) {
                consecutiveFailures++
                binding.tvNowPlaying.text = "⚠️ 載入失敗：${song.title}"
                Toast.makeText(this@MainActivity, "跳過無法載入的歌曲：${song.title}", Toast.LENGTH_SHORT).show()

                // 若為自動連播或連續失敗小於 3 次，自動跳下一首避免車上冷場
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
            resolved.muxedUrl != null -> {
                player.setMediaItem(MediaItem.fromUri(resolved.muxedUrl))
            }
            resolved.hlsUrl != null -> {
                val hlsSource = HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(resolved.hlsUrl))
                player.setMediaSource(hlsSource)
            }
            else -> throw Exception("沒有可用的影音串流")
        }

        player.prepare()
        player.play()
    }

    private fun updateQueueBadge() {
        binding.tabQueue.text = "已點 (${songQueue.size})"
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        binding.etSearch.clearFocus()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }
}
