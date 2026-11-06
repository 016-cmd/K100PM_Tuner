package com.k100pm.tuner.ui.screens

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import coil3.compose.AsyncImage
import com.k100pm.tuner.app.MainActivity
import com.k100pm.tuner.music.FavoriteSong
import com.k100pm.tuner.music.MusicApi
import com.k100pm.tuner.music.MusicDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 本地搜索播放模式 — 完全独立的 Composable
 * 搜索 → 点击播放 → ExoPlayer 流式播放 → 自控歌词进度
 *
 * v2.0 新增:搜索结果收藏按钮、播放列表（收藏歌单）、上下首、MediaSession 媒体通知
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalModeScreen(
    activity: Activity,
    searchQuery: String, onSearchQueryChange: (String) -> Unit,
    searchResults: List<MusicApi.Song>, onSearchResultsChange: (List<MusicApi.Song>) -> Unit,
    currentSong: MusicApi.Song?, onCurrentSongChange: (MusicApi.Song?) -> Unit,
    exoPlayer: ExoPlayer?, onExoPlayerChange: (ExoPlayer?) -> Unit,
    favoriteSongs: List<FavoriteSong>, onFavoriteSongsChange: (List<FavoriteSong>) -> Unit
) {
    val ctx = LocalContext.current

    var searchLoading by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var durationMs by remember { mutableStateOf(0L) }
    var errorMsg by remember { mutableStateOf("") }

    var lyricLines by remember { mutableStateOf<List<LyricFetcher.LyricLine>>(emptyList()) }
    var lyricSource by remember { mutableStateOf("") }
    var lyricLoading by remember { mutableStateOf(false) }

    var playlistExpanded by remember { mutableStateOf(false) }
    var favCache by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    val db = remember { MusicDatabase.getInstance(ctx) }
    val dao = remember { db.favoriteSongDao() }
    var mediaSession by remember { mutableStateOf<MediaSession?>(null) }

    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.red * 0.299f + colors.background.green * 0.587f + colors.background.blue * 0.114f < 0.5f
    val cardBg = if (isDark) Color.Black.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.40f)
    val cardShape = RoundedCornerShape(24.dp)
    val scope = rememberCoroutineScope()

    // ── 播放队列索引 ──
    val currentFavIndex = remember(currentSong, favoriteSongs) {
        if (currentSong != null) {
            favoriteSongs.indexOfFirst { it.songId == currentSong.id && it.source == currentSong.source }
        } else -1
    }
    val hasPrev = currentFavIndex > 0
    val hasNext = currentFavIndex in 0 until favoriteSongs.size - 1

    // ── 播放锁（只有一个播放器存在）──
    // true = 正在创建播放器中,不允许再触发
    var isPlayingLock by remember { mutableStateOf(false) }

    // ── 播放器启动 ──（必须先定义,playFav 会调用它）
    fun startPlayback(song: MusicApi.Song) {
        if (isPlayingLock) {
            // 已经有播放器在创建中 → 释放旧的,重新来
            exoPlayer?.release()
            mediaSession?.release()
            onExoPlayerChange(null)
            mediaSession = null
        }
        isPlayingLock = true
        scope.launch(Dispatchers.IO) {
            try {
                // ═══ 第一步:获取封面（仅内存,不存DB）═══
                android.util.Log.d("K100PM_COVER", "尝试获取封面: song=${song.title} source=${song.source} coverUrl=${song.coverUrl}")
                var coverUrl = MusicApi.fetchCoverAnyPlatform(song)
                android.util.Log.d("K100PM_COVER", "fetchCoverAnyPlatform 结果: $coverUrl")
                // 如果当前平台拿不到封面,用歌名搜QQ和酷狗
                if (coverUrl.isNullOrEmpty()) {
                    android.util.Log.d("K100PM_COVER", "fetchCoverAnyPlatform 没拿到,开始兜底搜索QQ/酷狗")
                    try {
                        val fallbackResults = MusicApi.search(song.title, listOf("qq", "kugou"))
                        android.util.Log.d("K100PM_COVER", "兜底搜索返回 ${fallbackResults.size} 条")
                        coverUrl = fallbackResults.firstOrNull {
                            it.title.equals(song.title, ignoreCase = true)
                        }?.coverUrl?.takeIf { it.isNotEmpty() }
                        android.util.Log.d("K100PM_COVER", "兜底搜索封面结果: $coverUrl")
                    } catch (_: Exception) { }
                }
                val finalCoverUrl = coverUrl ?: song.coverUrl
                val songWithCover = song.copy(coverUrl = finalCoverUrl)
                android.util.Log.d("K100PM_COVER", "最终封面URL: $finalCoverUrl (原始song.coverUrl=${song.coverUrl})")
                if (songWithCover.coverUrl != song.coverUrl) {
                    withContext(Dispatchers.Main) { onCurrentSongChange(songWithCover) }
                }

                // ═══ 第二步:获取流播放地址 ═══
                // 酷狗不支持播放,fallback 到用歌名搜索其他平台
                val playSrc = if (song.source == "kugou") {
                    val altResults = MusicApi.search(song.title, listOf("netease", "qq", "kuwo"))
                    altResults.firstOrNull { it.title.equals(song.title, ignoreCase = true) }
                        ?: altResults.firstOrNull()
                } else null

                val actualSong = playSrc ?: songWithCover
                // ⚠️ 音频流解析服务已下线（原聚合 API 域名 mobi-api.likegamex.top 失效，HTTP 530/连接失败）。
                // 原逻辑直接请求该失效域名拿流地址，现用占位返回 null，配合其它入口给出明确失败提示，
                // 待接入新的可用流解析服务后再恢复真实解析。
                val streamUrl: String? = null
                /*
                val apiUrl = "https://mobi-api.likegamex.top/tunefree/stream?platform=${actualSong.source}&id=${actualSong.id}"
                val streamUrl = try {
                    val conn = URL(apiUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000; conn.readTimeout = 5000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    conn.instanceFollowRedirects = false; conn.connect()
                    val loc = conn.getHeaderField("Location"); conn.disconnect()
                    loc ?: apiUrl
                } catch (e: Exception) { apiUrl }
                */

                withContext(Dispatchers.Main) {
                    try {
                        exoPlayer?.release(); mediaSession?.release()
                        durationMs = 0L; positionMs = 0L; errorMsg = ""

                        // 流解析服务不可用（占位），给出明确提示并终止播放
                        if (streamUrl.isNullOrEmpty()) {
                            errorMsg = "播放暂不可用：音频解析服务已下线，待接入新的解析服务后恢复"
                            isPlayingLock = false
                            return@withContext
                        }

                        val notificationManager = ctx.getSystemService(NotificationManager::class.java)
                        val player = ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx)).build().also { p ->
                            p.addListener(object : Player.Listener {
                                override fun onPlaybackStateChanged(state: Int) {
                                    buildAndNotifyMediaNotification(ctx, notificationManager, p, song, mediaSession)
                                    if (state == Player.STATE_ENDED) {
                                        Log.d("K100PM_PLAY", "播放结束 STATE_ENDED, songId=${song.id} source=${song.source}")
                                        scope.launch(Dispatchers.IO) {
                                            // 从数据库重新查询最新顺序的歌单（↑↓调序后列表已变）
                                            val favs = dao.getAll()
                                            Log.d("K100PM_PLAY", "STATE_ENDED 重新查库 favSize=${favs.size}")
                                            val idx = favs.indexOfFirst { it.songId == song.id && it.source == song.source }
                                            Log.d("K100PM_PLAY", "STATE_ENDED 计算idx=$idx")
                                            if (idx in 0 until favs.size - 1) {
                                                val next = favs[idx + 1]
                                                Log.d("K100PM_PLAY", "自动切到下一首: ${next.title} (${next.source}|${next.songId})")
                                                val s = MusicApi.Song(
                                                    id = next.songId, title = next.title, artist = next.artist,
                                                    album = next.album, coverUrl = next.coverUrl,
                                                    durationMs = next.durationMs, source = next.source
                                                )
                                                onCurrentSongChange(s)
                                                delay(200) // 等 currentSong 状态刷新
                                                startPlayback(s)
                                            } else {
                                                Log.d("K100PM_PLAY", "不满足切歌条件 idx=$idx, favs.size=${favs.size}")
                                            }
                                        }
                                    }
                                }
                                override fun onIsPlayingChanged(isPlaying: Boolean) {
                                    buildAndNotifyMediaNotification(ctx, notificationManager, p, song, mediaSession)
                                }
                                override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                                    errorMsg = "播放失败: ${e.localizedMessage}"
                                }
                            })
                            val mediaItem = MediaItem.Builder()
                                .setUri(streamUrl!!)
                                .setMediaMetadata(
                                    MediaMetadata.Builder()
                                        .setTitle(actualSong.title).setArtist(actualSong.artist)
                                        .setAlbumTitle(actualSong.album).build()
                                )
                                .build()
                            p.setMediaItem(mediaItem)
                            p.prepare(); p.playWhenReady = true
                        }

                        // MediaSession → 澎湃OS系统媒体通知
                        val session = MediaSession.Builder(ctx, player)
                            .setSessionActivity(PendingIntent.getActivity(
                                ctx, 0, activity.intent, PendingIntent.FLAG_IMMUTABLE
                            ))
                            .build()
                        mediaSession = session
                        // MediaSession 就绪后立即发布一条通知
                        buildAndNotifyMediaNotification(ctx, notificationManager, player, song, session)
                        // 异步下载封面Bitmap,更新到通知显示专辑封面
                        if (songWithCover.coverUrl.isNotEmpty()) {
                            val nm = ctx.getSystemService(NotificationManager::class.java)
                            val pi = PendingIntent.getActivity(ctx, 0, activity.intent, PendingIntent.FLAG_IMMUTABLE)
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val conn = URL(songWithCover.coverUrl).openConnection() as HttpURLConnection
                                    conn.connectTimeout = 3000; conn.readTimeout = 3000
                                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                                    conn.connect()
                                    if (conn.responseCode == 200) {
                                        val `is`: java.io.InputStream = conn.inputStream
                                        val bitmap = android.graphics.BitmapFactory.decodeStream(`is`)
                                        conn.disconnect()
                                        if (bitmap != null) {
                                            withContext(Dispatchers.Main) {
                                                // 更新通知封面（setLargeIcon显示在通知中）
                                                val updatedNotification = NotificationCompat.Builder(ctx, MainActivity.MEDIA_CHANNEL_ID)
                                                    .setContentTitle(songWithCover.title)
                                                    .setContentText(songWithCover.artist)
                                                    .setSubText(songWithCover.album)
                                                    .setSmallIcon(android.R.drawable.ic_media_play)
                                                    .setLargeIcon(bitmap)
                                                    .setStyle(MediaStyle()
                                                        .setMediaSession(session.sessionCompatToken)
                                                        .setShowActionsInCompactView(0, 1, 2))
                                                    .setOngoing(player.isPlaying)
                                                    .setShowWhen(false)
                                                    .setContentIntent(pi)
                                                    .addAction(android.R.drawable.ic_media_previous, "上一首", pi)
                                                    .addAction(
                                                        if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                                                        if (player.isPlaying) "暂停" else "播放", pi)
                                                    .addAction(android.R.drawable.ic_media_next, "下一首", pi)
                                                    .build()
                                                nm.notify(1001, updatedNotification)
                                            }
                                        }
                                    } else { conn.disconnect() }
                                } catch (_: Exception) { }
                            }
                        }
                        onExoPlayerChange(player)
                    } catch (e: Exception) { errorMsg = "创建播放器失败: ${e.message}" } finally { isPlayingLock = false }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { errorMsg = "播放启动失败: ${e.message}" }
            } finally { isPlayingLock = false }
        }
    }

    fun playFav(fav: FavoriteSong) {
        val song = MusicApi.Song(
            id = fav.songId, title = fav.title, artist = fav.artist,
            album = fav.album, coverUrl = fav.coverUrl,
            durationMs = fav.durationMs, source = fav.source
        )
        onCurrentSongChange(song)
        startPlayback(song)
    }

    // ── 按钮冷却（防止快速点击切歌）──
    var cooldown by remember { mutableStateOf(false) }
    fun withCooldown(action: () -> Unit) {
        if (!cooldown) {
            cooldown = true
            action()
            scope.launch {
                delay(1000) // 1秒冷却
                cooldown = false
            }
        }
    }

    fun playPrev() { withCooldown { if (hasPrev) playFav(favoriteSongs[currentFavIndex - 1]) } }
    fun playNext() { withCooldown { if (hasNext) playFav(favoriteSongs[currentFavIndex + 1]) } }

    fun toggleFavorite(song: MusicApi.Song) {
        scope.launch(Dispatchers.IO) {
            val key = "${song.source}|${song.id}"
            val isFav = dao.isFavorite(song.id, song.source)
            if (isFav) {
                dao.deleteBySongId(song.id, song.source)
                favCache = favCache + (key to false)
            } else {
                val maxOrder = dao.getMaxSortOrder()
                dao.insert(FavoriteSong(
                    songId = song.id, source = song.source,
                    title = song.title, artist = song.artist,
                    album = song.album, coverUrl = song.coverUrl,
                    durationMs = song.durationMs, sortOrder = maxOrder + 1
                ))
                favCache = favCache + (key to true)
            }
            onFavoriteSongsChange(dao.getAll())
        }
    }

    LaunchedEffect(searchResults) {
        val map = mutableMapOf<String, Boolean>()
        for (s in searchResults) { map["${s.source}|${s.id}"] = dao.isFavorite(s.id, s.source) }
        favCache = map
    }

    // currentSong 变化时同步更新 favCache
    LaunchedEffect(currentSong) {
        val s = currentSong ?: return@LaunchedEffect
        val key = "${s.source}|${s.id}"
        favCache = favCache + (key to dao.isFavorite(s.id, s.source))
    }

    // 进度轮询
    LaunchedEffect(exoPlayer) {
        val p = exoPlayer ?: return@LaunchedEffect
        while (p == exoPlayer) {
            positionMs = maxOf(0, p.currentPosition)
            durationMs = maxOf(0, p.duration).takeIf { it > 0 } ?: durationMs
            isPlaying = p.isPlaying; delay(150)
        }
    }

    // 歌词拉取
    LaunchedEffect(currentSong) {
        val song = currentSong ?: run { lyricLines = emptyList(); lyricSource = ""; return@LaunchedEffect }
        lyricLines = emptyList(); lyricSource = ""; lyricLoading = true
        var fetched = false
        val lrc = MusicApi.getLyric(song.source, song.id)
        if (!lrc.isNullOrBlank()) { lyricLines = parseLrc(lrc); lyricSource = sourceLabel(song.source); fetched = true }
        if (!fetched) { val r = LyricFetcher.fetch(song.title, song.artist); if (r != null) { lyricLines = r.lines; lyricSource = r.source } }
        lyricLoading = false
    }

    // ═══════════════════════════════════════════
    //  UI — LazyColumn 整体可滚动
    // ═══════════════════════════════════════════

    LazyColumn(Modifier.fillMaxSize()) {
        // ── 搜索框 ──
        item {
            OutlinedTextField(
                value = searchQuery, onValueChange = onSearchQueryChange,
                placeholder = { Text("搜索歌曲...", color = colors.onSurfaceVariant.copy(0.5f)) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = colors.onSurfaceVariant.copy(0.5f)) },
                trailingIcon = {
                    if (searchLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else TextButton(onClick = {
                        if (searchQuery.isNotBlank()) {
                            searchLoading = true
                            // 不中断当前播放,只清空旧搜索结果展示新的
                            lyricLines = emptyList()
                            onSearchResultsChange(emptyList())
                            scope.launch(Dispatchers.IO) { onSearchResultsChange(MusicApi.search(searchQuery)); searchLoading = false }
                        }
                    }) { Text("搜索") }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.primary.copy(0.3f),
                    unfocusedBorderColor = colors.onSurfaceVariant.copy(0.15f)
                )
            )
            Spacer(Modifier.height(8.dp))
        }

        // ── 搜索结果（每项带收藏小爱心） ──
        if (searchResults.isNotEmpty()) {
            itemsIndexed(searchResults, key = { i, _ -> i }) { _, song ->
                val key = "${song.source}|${song.id}"
                val isFav = favCache[key] ?: false
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
                    .background(cardBg, RoundedCornerShape(12.dp)),
                    verticalAlignment = Alignment.CenterVertically) {
                    // 收藏爱心
                    IconButton(onClick = { toggleFavorite(song) }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            null, Modifier.size(18.dp),
                            tint = if (isFav) Color(0xFFE53935) else colors.onSurfaceVariant.copy(0.5f)
                        )
                    }
                    // 歌名 + 歌手
                    Column(Modifier.weight(1f).clickable {
                        onCurrentSongChange(song); onSearchResultsChange(emptyList()); startPlayback(song)
                    }.padding(horizontal = 4.dp)) {
                        Text(song.title, style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium, color = colors.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${song.artist}  ·  ${song.album}".trim('.', ' '),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant.copy(0.6f), maxLines = 1)
                    }
                    // 平台标签
                    val sc = when (song.source) {
                        "netease" -> Color(0xFFE72D2D); "qq" -> Color(0xFF31C27C)
                        "kuwo" -> Color(0xFFF5A623); "kugou" -> Color(0xFF2196F3); else -> colors.primary
                    }
                    Surface(shape = RoundedCornerShape(4.dp), color = sc.copy(0.15f)) {
                        Text(sourceLabel(song.source), style = MaterialTheme.typography.labelSmall,
                            color = sc, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
        }

        // ── 信息卡片（封面图+文字信息靠左对齐）──
        item {
            Row(
                modifier = Modifier.fillMaxWidth().clip(cardShape).background(cardBg, cardShape).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧封面图（圆角,60dp,无封面显示APP图标）
                Box(
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(colors.onSurfaceVariant.copy(0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (currentSong != null && currentSong!!.coverUrl.isNotEmpty()) {
                        // 带 Referer 下载封面（网易云图床需要）
                        val coverUrl = currentSong!!.coverUrl
                        var coverBitmap by remember(coverUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
                        LaunchedEffect(coverUrl) {
                            withContext(Dispatchers.IO) {
                            android.util.Log.d("K100PM_COVER", "信息卡片开始下载: $coverUrl")
                            try {
                                val conn = URL(coverUrl).openConnection() as HttpURLConnection
                                conn.connectTimeout = 5000; conn.readTimeout = 5000
                                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                                if (coverUrl.contains("music.126.net")) {
                                    conn.setRequestProperty("Referer", "https://music.163.com/")
                                }
                                conn.connect()
                                android.util.Log.d("K100PM_COVER", "下载响应码: ${conn.responseCode}")
                                if (conn.responseCode == 200) {
                                    val bitmap = android.graphics.BitmapFactory.decodeStream(conn.inputStream)
                                    val bitmapInfo = if (bitmap != null) "${bitmap.width}x${bitmap.height}" else "null"
                                    android.util.Log.d("K100PM_COVER", "Bitmap解码结果: $bitmapInfo")
                                    if (bitmap != null) coverBitmap = bitmap
                                }
                                conn.disconnect()
                            } catch (e: Exception) {
                                android.util.Log.e("K100PM_COVER", "下载封面异常: ${e.message} type=${e.javaClass.simpleName}")
                                android.util.Log.e("K100PM_COVER", android.util.Log.getStackTraceString(e))
                            }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.onSurfaceVariant.copy(0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (coverBitmap != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = coverBitmap!!.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(Icons.Rounded.MusicNote, null, Modifier.size(32.dp),
                                    tint = colors.onSurfaceVariant.copy(0.4f))
                            }
                        }
                    } else {
                        Icon(
                            if (currentSong != null) Icons.Rounded.MusicNote else Icons.Rounded.AudioFile,
                            null, Modifier.size(32.dp),
                            tint = colors.onSurfaceVariant.copy(0.4f)
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                // 右侧文字信息（靠左对齐）
                Column(Modifier.weight(1f)) {
                    // 平台标签
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val srcLabel = if (currentSong != null) sourceLabel(currentSong!!.source) else "未播放"
                        val srcColor = when (currentSong?.source) {
                            "netease" -> Color(0xFFE72D2D); "qq" -> Color(0xFF31C27C)
                            "kuwo" -> Color(0xFFF5A623); "kugou" -> Color(0xFF2196F3); else -> colors.onSurfaceVariant.copy(0.5f)
                        }
                        Surface(shape = RoundedCornerShape(4.dp), color = srcColor.copy(0.15f)) {
                            Text(srcLabel, style = MaterialTheme.typography.labelSmall,
                                color = srcColor, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    // 歌名
                    Text(currentSong?.title ?: "未知歌曲",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    // 歌手
                    Text(currentSong?.artist ?: "未知歌手",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // 专辑
                    currentSong?.album?.takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant.copy(0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // 分隔
            Spacer(Modifier.height(6.dp))
            // 控制按钮行（上一首/暂停/下一首）
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { playPrev() }, enabled = hasPrev && !cooldown, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, null, Modifier.size(28.dp),
                        tint = if (hasPrev && !cooldown) colors.onSurface else colors.onSurfaceVariant.copy(0.3f))
                }
                IconButton(onClick = { exoPlayer?.playWhenReady = !(exoPlayer?.playWhenReady ?: false) },
                    Modifier.size(58.dp).clip(CircleShape).background(colors.primary)) {
                    Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        null, Modifier.size(32.dp), tint = colors.onPrimary)
                }
                IconButton(onClick = { playNext() }, enabled = hasNext && !cooldown, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.SkipNext, null, Modifier.size(28.dp),
                        tint = if (hasNext && !cooldown) colors.onSurface else colors.onSurfaceVariant.copy(0.3f))
                }
            }
        }

        // ── 错误提示 ──
        if (errorMsg.isNotEmpty()) {
            item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Color(0xFFFF5252).copy(0.15f)) {
                    Text(errorMsg, modifier = Modifier.padding(12.dp),
                        color = Color(0xFFFF5252), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ── 进度条 ──
        if (currentSong != null) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Spacer(Modifier.height(6.dp))
                    Slider(
                        value = positionMs.toFloat().coerceIn(0f, maxOf(durationMs, 1L).toFloat()),
                        onValueChange = { exoPlayer?.seekTo(it.toLong()) },
                        valueRange = 0f..maxOf(durationMs, 1L).toFloat(),
                        modifier = Modifier.fillMaxWidth(),
                        colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary,
                            inactiveTrackColor = colors.onSurfaceVariant.copy(0.2f))
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(positionMs), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(0.5f))
                        Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(0.5f))
                    }
                }
            }
        }

        // ── 歌词区域 ──
        item {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(cardShape).background(cardBg, cardShape)) {
                when {
                    lyricLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp, color = colors.primary)
                    }
                    lyricLines.isNotEmpty() -> LyricViewLocal(lines = lyricLines, source = lyricSource, positionMs = positionMs, modifier = Modifier.fillMaxSize())
                    currentSong != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无歌词", color = colors.onSurfaceVariant.copy(0.4f)) }
                    searchResults.isEmpty() && searchQuery.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.MusicNote, null, Modifier.size(48.dp), tint = colors.onSurfaceVariant.copy(0.3f))
                            Spacer(Modifier.height(8.dp))
                            Text("在线搜索并播放你喜欢的歌曲", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant.copy(0.4f))
                        }
                    }
                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("输入歌名开始搜索", color = colors.onSurfaceVariant.copy(0.4f)) }
                }
            }
        }

        // ── 我的歌单（收藏列表）展开卡片 ──
        if (favoriteSongs.isNotEmpty()) {
            item {
                Spacer(Modifier.height(6.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().clip(cardShape),
                    shape = cardShape,
                    colors = CardDefaults.cardColors(containerColor = cardBg)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { playlistExpanded = !playlistExpanded }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Favorite, null, Modifier.size(18.dp), tint = Color(0xFFE53935))
                            Spacer(Modifier.width(8.dp))
                            Text("我的歌单 (${favoriteSongs.size})", style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                            Spacer(Modifier.weight(1f))
                            Icon(if (playlistExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                        }
                        if (playlistExpanded) {
                            HorizontalDivider(color = colors.outlineVariant.copy(0.3f))
                            Column(Modifier.fillMaxWidth()) {
                                favoriteSongs.forEachIndexed { index, fav ->
                                    val isCurrent = currentSong?.id == fav.songId && currentSong?.source == fav.source
                                    val fKey = "${fav.source}|${fav.songId}"
                                    val isFav = favCache[fKey] ?: true
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 序号
                                        Text("${index + 1}", style = MaterialTheme.typography.bodySmall,
                                            color = if (isCurrent) colors.primary else colors.onSurfaceVariant.copy(0.5f),
                                            modifier = Modifier.width(16.dp))
                                        // 上移按钮
                                        IconButton(
                                            onClick = {
                                                if (index > 0) {
                                                    scope.launch(Dispatchers.IO) {
                                                        val list = favoriteSongs.toMutableList()
                                                        val item = list.removeAt(index)
                                                        list.add(index - 1, item)
                                                        list.forEachIndexed { idx, f -> dao.updateSortOrder(f.id, idx) }
                                                        onFavoriteSongsChange(dao.getAll())
                                                    }
                                                }
                                            },
                                            enabled = index > 0,
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Rounded.KeyboardArrowUp, null, Modifier.size(16.dp),
                                                tint = if (index > 0) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(0.2f))
                                        }
                                        // 下移按钮
                                        IconButton(
                                            onClick = {
                                                if (index < favoriteSongs.size - 1) {
                                                    scope.launch(Dispatchers.IO) {
                                                        val list = favoriteSongs.toMutableList()
                                                        val item = list.removeAt(index)
                                                        list.add(index + 1, item)
                                                        list.forEachIndexed { idx, f -> dao.updateSortOrder(f.id, idx) }
                                                        onFavoriteSongsChange(dao.getAll())
                                                    }
                                                }
                                            },
                                            enabled = index < favoriteSongs.size - 1,
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(16.dp),
                                                tint = if (index < favoriteSongs.size - 1) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(0.2f))
                                        }
                                        // 歌名 + 歌手
                                        Column(Modifier.weight(1f).clickable(enabled = !isCurrent) { playFav(fav) }) {
                                            Text(fav.title, style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isCurrent) colors.primary else colors.onSurface,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(fav.artist, style = MaterialTheme.typography.bodySmall,
                                                color = colors.onSurfaceVariant.copy(0.6f), maxLines = 1)
                                        }
                                        // 平台标签
                                        val sc = when (fav.source) {
                                            "netease" -> Color(0xFFE72D2D); "qq" -> Color(0xFF31C27C)
                                            "kuwo" -> Color(0xFFF5A623); "kugou" -> Color(0xFF2196F3); else -> colors.primary
                                        }
                                        Surface(shape = RoundedCornerShape(4.dp), color = sc.copy(0.15f),
                                            modifier = Modifier.padding(end = 4.dp)) {
                                            Text(sourceLabel(fav.source), style = MaterialTheme.typography.labelSmall,
                                                color = sc, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                        }
                                        // 收藏爱心
                                        IconButton(onClick = {
                                            val song = MusicApi.Song(id = fav.songId, source = fav.source,
                                                title = fav.title, artist = fav.artist, album = fav.album,
                                                coverUrl = fav.coverUrl, durationMs = fav.durationMs)
                                            toggleFavorite(song)
                                            favCache = favCache + (fKey to !isFav)
                                        }, modifier = Modifier.size(24.dp)) {
                                            Icon(
                                                if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                                null, Modifier.size(14.dp),
                                                tint = if (isFav) Color(0xFFE53935) else colors.onSurfaceVariant.copy(0.5f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } // LazyColumn 结束

    DisposableEffect(Unit) { onDispose { mediaSession?.release() } }
}

// ── 媒体通知构建 ──
private fun buildAndNotifyMediaNotification(
    ctx: android.content.Context,
    nm: NotificationManager,
    player: ExoPlayer,
    song: MusicApi.Song,
    session: MediaSession?
) {
    if (session == null) return

    val pendingIntent = PendingIntent.getActivity(
        ctx, 0,
        ctx.packageManager.getLaunchIntentForPackage(ctx.packageName),
        PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(ctx, MainActivity.MEDIA_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle(song.title)
        .setContentText(song.artist)
        .setSubText(song.album)
        .setStyle(MediaStyle()
            .setMediaSession(session.sessionCompatToken)
            .setShowActionsInCompactView(0, 1, 2)
        )
        .addAction(android.R.drawable.ic_media_previous, "上一首", pendingIntent)
        .addAction(
            if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (player.isPlaying) "暂停" else "播放",
            pendingIntent
        )
        .addAction(android.R.drawable.ic_media_next, "下一首", pendingIntent)
        .setContentIntent(pendingIntent)
        .setOngoing(player.isPlaying)
        .setShowWhen(false)
        .build()

    nm.notify(1001, notification)
}

private fun sourceLabel(source: String) = when (source) {
    "netease" -> "网易云"; "qq" -> "QQ音乐"; "kuwo" -> "酷我"; "kugou" -> "酷狗"; else -> source
}

private fun parseLrc(lrc: String): List<LyricFetcher.LyricLine> {
    val re = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)""")
    return lrc.lines().mapNotNull { line ->
        re.find(line)?.let {
            LyricFetcher.LyricLine(
                timeMs = (it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt()) * 1000L + it.groupValues[3].padEnd(3, '0').toInt(),
                text = it.groupValues[4].trim()
            )
        }
    }
}

@Composable
private fun LyricViewLocal(lines: List<LyricFetcher.LyricLine>, source: String, positionMs: Long, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val idx = lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)
    LaunchedEffect(idx) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(idx.coerceAtMost(lines.size - 1))
        }
    }
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text("歌词来源:$source  |  ${formatTime(positionMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant.copy(0.4f),
                modifier = Modifier.padding(bottom = 12.dp))
        }
        itemsIndexed(lines, key = { i, _ -> i }) { i, line ->
            val cur = i == idx
            Text(line.text,
                style = if (cur) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal,
                color = if (cur) colors.primary else colors.onSurface.copy(0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        }
    }
}

private fun formatTime(ms: Long): String { val s = ms / 1000; return "${s / 60}:%02d".format(s % 60) }