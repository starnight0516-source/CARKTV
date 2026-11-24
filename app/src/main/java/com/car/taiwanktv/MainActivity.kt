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
import androidx.recyclerview.widget.LinearLayoutManager
import com.car.taiwanktv.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var player: ExoPlayer

    private val songQueue = mutableListOf<SongItem>()
    private lateinit var catalogAdapter: CatalogAdapter
    private lateinit var queueAdapter: QueueAdapter

    private var currentSong: SongItem? = null
    private var isAudioOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupPlayer()
        setupLists()
        setupControls()

        // 啟動預設載入「新歌排行」
        selectCategoryTab(binding.tabHot, SongCategory.HOT)
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

    private fun setupLists() {
        // 點歌清單：點一下直接進歌單（若沒在播就直接播），絕無多餘彈窗或跳頁
        catalogAdapter = CatalogAdapter { song ->
            enqueueOrPlayDirectly(song)
        }
        binding.rvCatalog.layoutManager = LinearLayoutManager(this)
        binding.rvCatalog.adapter = catalogAdapter

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

        // 分類分頁按鈕
        binding.tabHot.setOnClickListener { selectCategoryTab(binding.tabHot, SongCategory.HOT) }
        binding.tabTaiwanese.setOnClickListener { selectCategoryTab(binding.tabTaiwanese, SongCategory.TAIWANESE) }
        binding.tabMandarin.setOnClickListener { selectCategoryTab(binding.tabMandarin, SongCategory.MANDARIN) }
        binding.tabClassics.setOnClickListener { selectCategoryTab(binding.tabClassics, SongCategory.CLASSICS) }

        binding.tabQueue.setOnClickListener {
            highlightTab(binding.tabQueue)
            binding.rvCatalog.visibility = View.GONE
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

    private fun highlightTab(activeTab: MaterialButton?) {
        val allTabs = listOf(binding.tabHot, binding.tabTaiwanese, binding.tabMandarin, binding.tabClassics, binding.tabQueue)
        for (tab in allTabs) {
            tab.setBackgroundColor(
                if (tab == activeTab) Color.parseColor("#FF6D00") else Color.parseColor("#232833")
            )
        }
    }

    private fun selectCategoryTab(tab: MaterialButton, category: SongCategory) {
        highlightTab(tab)
        binding.rvCatalog.visibility = View.VISIBLE
        binding.rvQueue.visibility = View.GONE
        binding.listLoadingOverlay.visibility = View.VISIBLE
        binding.tvListLoadingMsg.text = "載入歌單中..."

        lifecycleScope.launch {
            val songs = CorpSongRepository.getSongs(category)
            catalogAdapter.submitList(songs)
            binding.rvCatalog.scrollToPosition(0)
            binding.listLoadingOverlay.visibility = View.GONE
        }
    }

    private fun performNativeSearch(query: String) {
        highlightTab(null)
        binding.rvCatalog.visibility = View.VISIBLE
        binding.rvQueue.visibility = View.GONE
        binding.listLoadingOverlay.visibility = View.VISIBLE
        binding.tvListLoadingMsg.text = "搜尋「$query」中..."

        lifecycleScope.launch {
            try {
                val results = YouTubeStreamResolver.searchSongItems(query)
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
     * 純粹點歌邏輯：
     * - 沒在播歌：點一下直接送上方立刻播放
     * - 正在播歌：點一下直接排入已點清單，無任何多餘彈窗或跳轉
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

    private fun playNextInQueue() {
        if (songQueue.isNotEmpty()) {
            val next = songQueue.removeAt(0)
            queueAdapter.notifyDataSetChanged()
            updateQueueBadge()
            playSong(next)
        } else {
            currentSong = null
            player.stop()
            binding.tvNowPlaying.text = "🎵 歌單已播完，請在下方點選新歌"
            Toast.makeText(this, "已點清單已播完", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playSong(song: SongItem) {
        currentSong = song
        binding.tvNowPlaying.text = "🎵 載入中：${song.displayName()}"
        binding.tvAudioOnlyTitle.text = song.displayName()
        binding.loadingIndicator.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val resolved = resolveWithFallback(song)
                startExoPlayer(resolved)
                binding.tvNowPlaying.text = "🎵 正在播放：${song.displayName()}"
            } catch (e: Exception) {
                binding.tvNowPlaying.text = "⚠️ 載入失敗：${song.title}（請按換版本）"
                Toast.makeText(this@MainActivity, "無法載入 MV：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.loadingIndicator.visibility = View.GONE
            }
        }
    }

    private suspend fun resolveWithFallback(song: SongItem): ResolvedStream {
        // 1. 若有來自台灣點歌王的連結且尚未取得 videoId，先靜默取一次 videoId
        if (song.videoId.isNullOrBlank() && !song.corpMvUrl.isNullOrBlank()) {
            song.videoId = YouTubeStreamResolver.extractVideoIdFromCorpPage(song.corpMvUrl)
        }

        // 2. 有 videoId 直接解析播放
        if (!song.videoId.isNullOrBlank()) {
            try {
                return YouTubeStreamResolver.resolveStream(song.videoId!!)
            } catch (_: Exception) {
                // 若原影片下架或受限，自動退回用歌名+歌手搜尋官方 MV
            }
        }

        // 3. 以歌名+歌手搜尋官方 MV
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
