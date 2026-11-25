package com.car.taiwanktv

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

interface RemoteKtvCallback {
    fun getNowPlayingText(): String
    fun isCurrentlyPlaying(): Boolean
    fun getQueueSnapshot(): List<SongItem>
    fun onRemoteOrderSong(song: SongItem, cutIn: Boolean)
    fun onRemoteControl(action: String)
    fun onRemoteQueueAction(action: String, index: Int)
}

class RemoteControlServer(
    private val context: Context,
    private val callback: RemoteKtvCallback,
    port: Int = 8899
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val params = session.parameters

        return try {
            when {
                uri == "/" || uri == "/index.html" -> {
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", buildMobileHtml())
                }
                uri == "/api/status" -> {
                    val obj = JSONObject()
                    obj.put("nowPlaying", callback.getNowPlayingText())
                    obj.put("isPlaying", callback.isCurrentlyPlaying())
                    val qArr = JSONArray()
                    for (s in callback.getQueueSnapshot()) {
                        val sObj = JSONObject()
                        sObj.put("title", s.title)
                        sObj.put("artist", s.artist)
                        qArr.put(sObj)
                    }
                    obj.put("queue", qArr)
                    jsonResponse(obj.toString())
                }
                uri == "/api/catalog" -> {
                    val catStr = params["cat"]?.firstOrNull() ?: "HOT"
                    val cat = try {
                        SongCategory.valueOf(catStr)
                    } catch (_: Exception) {
                        SongCategory.HOT
                    }
                    val songs = runBlocking { CorpSongRepository.getSongs(context, cat, allowNetwork = false) }
                    jsonResponse(songsToJson(songs))
                }
                uri == "/api/search" -> {
                    val q = params["q"]?.firstOrNull()?.trim() ?: ""
                    if (q.isBlank()) {
                        jsonResponse("[]")
                    } else {
                        val results = runBlocking { YouTubeStreamResolver.searchSongItems(q, limit = 20) }
                        jsonResponse(songsToJson(results))
                    }
                }
                uri == "/api/order" -> {
                    val title = params["title"]?.firstOrNull()?.trim() ?: ""
                    val artist = params["artist"]?.firstOrNull()?.trim() ?: ""
                    val videoId = params["videoId"]?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
                    val corpMvUrl = params["corpMvUrl"]?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
                    val cutIn = params["cutIn"]?.firstOrNull() == "1"
                    if (title.isNotBlank()) {
                        callback.onRemoteOrderSong(
                            SongItem(title = title, artist = artist, videoId = videoId, corpMvUrl = corpMvUrl),
                            cutIn
                        )
                        jsonResponse("""{"ok":true}""")
                    } else {
                        jsonResponse("""{"ok":false,"error":"empty title"}""")
                    }
                }
                uri == "/api/control" -> {
                    val action = params["action"]?.firstOrNull() ?: ""
                    callback.onRemoteControl(action)
                    jsonResponse("""{"ok":true}""")
                }
                uri == "/api/queue/action" -> {
                    val action = params["action"]?.firstOrNull() ?: ""
                    val index = params["index"]?.firstOrNull()?.toIntOrNull() ?: -1
                    callback.onRemoteQueueAction(action, index)
                    jsonResponse("""{"ok":true}""")
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            jsonResponse("""{"ok":false,"error":"${e.message?.replace("\"", "'") ?: "error"}"}""")
        }
    }

    private fun songsToJson(songs: List<SongItem>): String {
        val arr = JSONArray()
        for (s in songs) {
            val obj = JSONObject()
            obj.put("title", s.title)
            obj.put("artist", s.artist)
            obj.put("videoId", s.videoId ?: "")
            obj.put("corpMvUrl", s.corpMvUrl ?: "")
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun jsonResponse(json: String): Response {
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
    }

    private fun buildMobileHtml(): String {
        return """
<!DOCTYPE html>
<html lang="zh-TW">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>台灣點歌王 - 手機遙控點歌</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Noto Sans TC", sans-serif; }
  body { background: #0F1115; color: #FFFFFF; padding-bottom: 40px; }
  .header { position: sticky; top: 0; z-index: 100; background: #161922; padding: 12px; border-bottom: 1px solid #262C38; }
  .now-playing { font-size: 15px; font-weight: bold; color: #FFD54F; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-bottom: 10px; }
  .ctrl-row { display: flex; gap: 6px; margin-bottom: 10px; }
  .ctrl-btn { flex: 1; height: 42px; border: none; border-radius: 8px; font-size: 14px; font-weight: bold; color: #FFF; background: #262D3B; cursor: pointer; }
  .ctrl-btn.primary { background: #FF6D00; }
  .search-row { display: flex; gap: 8px; }
  .search-input { flex: 1; height: 44px; border-radius: 8px; border: 1px solid #323A4A; background: #1E232D; color: #FFF; padding: 0 12px; font-size: 15px; }
  .search-btn { width: 72px; height: 44px; border: none; border-radius: 8px; background: #00B0FF; color: #FFF; font-size: 15px; font-weight: bold; }
  .tabs { display: flex; gap: 6px; overflow-x: auto; padding: 10px 12px; background: #12151C; }
  .tab { flex: 0 0 auto; padding: 8px 14px; border-radius: 20px; border: none; background: #232833; color: #CFD8DC; font-size: 14px; font-weight: bold; }
  .tab.active { background: #FF6D00; color: #FFF; }
  .list { padding: 10px 12px; }
  .card { display: flex; align-items: center; background: #1A1E26; border-radius: 10px; padding: 12px; margin-bottom: 8px; }
  .info { flex: 1; min-width: 0; padding-right: 8px; }
  .title { font-size: 16px; font-weight: bold; color: #FFF; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .artist { font-size: 13px; color: #9AA4B2; margin-top: 3px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .btn-sm { height: 38px; padding: 0 12px; border: none; border-radius: 8px; font-size: 13px; font-weight: bold; color: #FFF; margin-left: 6px; }
  .btn-cut { background: #00B0FF; }
  .btn-add { background: #FF6D00; }
  .btn-del { background: #D32F2F; }
  .toast { position: fixed; bottom: 24px; left: 50%; transform: translateX(-50%); background: rgba(0,0,0,0.88); color: #00E676; padding: 10px 18px; border-radius: 24px; font-size: 14px; font-weight: bold; display: none; z-index: 999; border: 1px solid #00E676; white-space: nowrap; }
  .empty { text-align: center; color: #8892A0; padding: 36px 12px; font-size: 15px; }
</style>
</head>
<body>
  <div class="header">
    <div id="nowPlaying" class="now-playing">🎵 連線中...</div>
    <div class="ctrl-row">
      <button class="ctrl-btn" onclick="sendCtrl('prev')">⏮ 上首</button>
      <button class="ctrl-btn" onclick="sendCtrl('toggle')">⏯ 播放/暫停</button>
      <button class="ctrl-btn primary" onclick="sendCtrl('next')">下首 ⏭</button>
      <button class="ctrl-btn" onclick="sendCtrl('replay')">重唱</button>
    </div>
    <div class="search-row">
      <input id="qInput" class="search-input" type="text" placeholder="輸入歌名或歌手搜歌..." onkeydown="if(event.key==='Enter') doSearch()">
      <button class="search-btn" onclick="doSearch()">搜歌</button>
    </div>
  </div>

  <div class="tabs">
    <button id="t_HOT" class="tab active" onclick="loadCat('HOT')">新歌排行</button>
    <button id="t_TAIWANESE" class="tab" onclick="loadCat('TAIWANESE')">台語金曲</button>
    <button id="t_MANDARIN" class="tab" onclick="loadCat('MANDARIN')">國語熱門</button>
    <button id="t_CLASSICS" class="tab" onclick="loadCat('CLASSICS')">經典對唱</button>
    <button id="t_QUEUE" class="tab" onclick="showQueue()">已點 (<span id="qCount">0</span>)</button>
  </div>

  <div id="songList" class="list"></div>
  <div id="toast" class="toast"></div>

<script>
  let currentMode = 'HOT';
  let latestQueue = [];

  function showToast(msg) {
    const t = document.getElementById('toast');
    t.innerText = msg;
    t.style.display = 'block';
    clearTimeout(window.__tTimer);
    window.__tTimer = setTimeout(() => t.style.display = 'none', 2000);
  }

  function setActiveTab(id) {
    document.querySelectorAll('.tab').forEach(el => el.classList.remove('active'));
    const target = document.getElementById('t_' + id);
    if (target) target.classList.add('active');
  }

  async function refreshStatus() {
    try {
      const res = await fetch('/api/status');
      const data = await res.json();
      document.getElementById('nowPlaying').innerText = data.nowPlaying || '🎵 等待點歌中...';
      latestQueue = data.queue || [];
      document.getElementById('qCount').innerText = latestQueue.length;
      if (currentMode === 'QUEUE') renderQueue();
    } catch (e) {}
  }

  async function loadCat(cat) {
    currentMode = cat;
    setActiveTab(cat);
    document.getElementById('songList').innerHTML = '<div class="empty">載入歌單中...</div>';
    try {
      const res = await fetch('/api/catalog?cat=' + encodeURIComponent(cat));
      const songs = await res.json();
      renderCatalog(songs);
    } catch (e) {
      document.getElementById('songList').innerHTML = '<div class="empty">載入失敗，請確認連線</div>';
    }
  }

  async function doSearch() {
    const q = document.getElementById('qInput').value.trim();
    if (!q) return;
    currentMode = 'SEARCH';
    setActiveTab('');
    document.getElementById('songList').innerHTML = '<div class="empty">正在搜尋「' + escapeHtml(q) + '」...</div>';
    try {
      const res = await fetch('/api/search?q=' + encodeURIComponent(q));
      const songs = await res.json();
      renderCatalog(songs);
    } catch (e) {
      document.getElementById('songList').innerHTML = '<div class="empty">搜尋失敗</div>';
    }
  }

  function showQueue() {
    currentMode = 'QUEUE';
    setActiveTab('QUEUE');
    renderQueue();
    refreshStatus();
  }

  function renderCatalog(songs) {
    const el = document.getElementById('songList');
    if (!songs || songs.length === 0) {
      el.innerHTML = '<div class="empty">找不到相關歌曲</div>';
      return;
    }
    el.innerHTML = songs.map((s, idx) => `
      <div class="card">
        <div class="info">
          <div class="title">${idx + 1}. ${escapeHtml(s.title)}</div>
          <div class="artist">${escapeHtml(s.artist || '精選 MV')}</div>
        </div>
        <button class="btn-sm btn-cut" onclick='orderSong(${JSON.stringify(s)}, 1)'>插播</button>
        <button class="btn-sm btn-add" onclick='orderSong(${JSON.stringify(s)}, 0)'>+ 點歌</button>
      </div>
    `).join('');
  }

  function renderQueue() {
    const el = document.getElementById('songList');
    if (!latestQueue || latestQueue.length === 0) {
      el.innerHTML = '<div class="empty">目前沒有待播歌曲，快去點歌吧！</div>';
      return;
    }
    el.innerHTML = latestQueue.map((s, idx) => `
      <div class="card">
        <div class="info">
          <div class="title">${idx + 1}. ${escapeHtml(s.title)}</div>
          <div class="artist">${escapeHtml(s.artist || '待播清單')}</div>
        </div>
        <button class="btn-sm btn-add" onclick="queueAct('play', ${idx})">立刻播</button>
        <button class="btn-sm btn-cut" onclick="queueAct('top', ${idx})">插播</button>
        <button class="btn-sm btn-del" onclick="queueAct('remove', ${idx})">刪</button>
      </div>
    `).join('');
  }

  async function orderSong(song, cutIn) {
    const qs = new URLSearchParams({
      title: song.title || '',
      artist: song.artist || '',
      videoId: song.videoId || '',
      corpMvUrl: song.corpMvUrl || '',
      cutIn: String(cutIn)
    });
    await fetch('/api/order?' + qs.toString());
    showToast(cutIn ? '⚡ 已插播：' + song.title : '✅ 已點歌：' + song.title);
    setTimeout(refreshStatus, 300);
  }

  async function sendCtrl(action) {
    await fetch('/api/control?action=' + action);
    setTimeout(refreshStatus, 400);
  }

  async function queueAct(action, index) {
    await fetch('/api/queue/action?action=' + action + '&index=' + index);
    setTimeout(refreshStatus, 300);
  }

  function escapeHtml(str) {
    return String(str).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  }

  loadCat('HOT');
  refreshStatus();
  setInterval(refreshStatus, 3000);
</script>
</body>
</html>
        """.trimIndent()
    }
}
