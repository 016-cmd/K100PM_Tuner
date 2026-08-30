package com.k100pm.tuner.ui.screens

import android.app.Activity
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 外挂检测模式 — 完全独立的 Composable
 * 仅检测外部播放器（酷狗等）的歌曲信息,不拉歌词,不播放
 */
@Composable
fun ExtModeScreen(activity: Activity) {
    val ctx = LocalContext.current
    val helper = remember { ExtMediaSessionHelper(ctx) }

    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf(false) }

    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.red * 0.299f + colors.background.green * 0.587f + colors.background.blue * 0.114f < 0.5f
    val cardBg = if (isDark) Color.Black.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.40f)
    val cardShape = RoundedCornerShape(24.dp)

    LaunchedEffect(Unit) {
        while (true) {
            val info = withContext(Dispatchers.IO) {
                withTimeoutOrNull(2000) { helper.getSongInfo() }
            }
            if (info != null) {
                title = info.title; artist = info.artist; album = info.album
                pkg = info.packageName; playing = info.isPlaying
                available = info.packageName.isNotEmpty()
            }
            delay(2000)
        }
    }

    // 信息卡片
    Box(
        Modifier.fillMaxWidth().clip(cardShape).background(cardBg, cardShape).padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (available) pkg else "未检测到播放",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant.copy(0.6f))
            Spacer(Modifier.height(8.dp))
            Text(title.ifEmpty { "未知歌曲" },
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = colors.onSurface, textAlign = TextAlign.Center,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp))
            Spacer(Modifier.height(4.dp))
            Text(artist.ifEmpty { "未知歌手" },
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (album.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(album, style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant.copy(0.6f), maxLines = 1)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { helper.execAsync("input keyevent 88") }, Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, null, Modifier.size(28.dp), tint = colors.onSurface)
                }
                IconButton(onClick = { helper.execAsync("input keyevent 85") },
                    Modifier.size(58.dp).clip(CircleShape).background(colors.primary)) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        null, Modifier.size(32.dp), tint = colors.onPrimary)
                }
                IconButton(onClick = { helper.execAsync("input keyevent 87") }, Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.SkipNext, null, Modifier.size(28.dp), tint = colors.onSurface)
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    // 语录
    Box(Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(cardShape).background(cardBg, cardShape)) {
        QuoteCard(modifier = Modifier.fillMaxSize())
    }
}

private class ExtMediaSessionHelper(private val ctx: Context) {
    data class SongInfo(
        val title: String = "未知歌曲", val artist: String = "未知歌手",
        val album: String = "", val packageName: String = "",
        val isPlaying: Boolean = false, val positionMs: Long = 0
    )
    private val m = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private var ctrl: MediaController? = null
    private var reg = false
    @Volatile var liveTitle = ""; @Volatile var liveArtist = ""; @Volatile var liveAlbum = ""; @Volatile var livePkg = ""
    @Volatile var livePlaying = false; @Volatile var livePos = 0L

    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(s: PlaybackState?) {
            s ?: return; livePos = s.position; livePlaying = s.state == PlaybackState.STATE_PLAYING
        }
        override fun onMetadataChanged(m: MediaMetadata?) {
            m ?: return
            liveTitle = m.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
            liveArtist = m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            liveAlbum = m.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        }
    }
    private fun attach(): SongInfo? {
        try {
            val ss = try { m.getActiveSessions(null) } catch (_: SecurityException) { null }
            if (!ss.isNullOrEmpty()) {
                val c = ss.firstOrNull { it.playbackState != null } ?: ss.first()
                val p = c.packageName ?: ""
                if (ctrl?.packageName != p) { ctrl?.unregisterCallback(cb); reg = false }
                if (!reg) { c.registerCallback(cb); ctrl = c; reg = true; livePkg = p; liveTitle = c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""; liveArtist = c.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""; liveAlbum = c.metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""; livePos = c.playbackState?.position ?: 0; livePlaying = c.playbackState?.state == PlaybackState.STATE_PLAYING }
                return SongInfo(liveTitle.ifEmpty { "未知歌曲" }, liveArtist.ifEmpty { "未知歌手" }, liveAlbum, livePkg, livePlaying, livePos)
            }
        } catch (_: Exception) {}
        return null
    }
    suspend fun getSongInfo() = withContext(Dispatchers.IO) {
        attach()?.let { return@withContext it }
        try {
            val o = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys media_session")).inputStream.bufferedReader().use { it.readText() }
            var p = ""; var t = ""; var a = ""; var al = ""; var ip = false; var pos = 0L; var ins = false
            for (l in o.lines()) {
                val tr = l.trim()
                if (tr.startsWith("Sessions Stack") || tr.startsWith("Active Sessions") || tr.startsWith("Active sessions")) { ins = true; continue }
                if (!ins) continue
                if (tr.startsWith("Audio playback") || tr.startsWith("Media session config")) break
                when {
                    tr.startsWith("package=") -> p = tr.substringAfter("package=").trim()
                    tr.startsWith("state=PlaybackState") -> { val mx = Regex("state=(\\w+)\\((\\d+)\\)").find(tr); if (mx != null) ip = mx.groupValues[2] == "3"; val px = Regex("position=(\\d+)").find(tr); if (px != null) pos = px.groupValues[1].toLongOrNull() ?: 0 }
                    tr.startsWith("metadata:") -> { val d = tr.substringAfter("description=").trim(); if (d.isNotEmpty() && d != "null") { val ps = d.split(", "); t = ps.getOrNull(0) ?: ""; a = ps.getOrNull(1) ?: ""; al = ps.getOrNull(2) ?: "" } }
                }
            }
            return@withContext SongInfo(t.ifEmpty { "未知歌曲" }, a.ifEmpty { "未知歌手" }, al, p, ip, pos)
        } catch (_: Exception) {}
        SongInfo()
    }
    fun execAsync(cmd: String) { Thread { try { Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() } catch (_: Exception) {} }.start() }
}