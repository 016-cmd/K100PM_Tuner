package com.k100pm.tuner.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.ExoPlayer
import com.k100pm.tuner.music.FavoriteSong
import com.k100pm.tuner.music.MusicApi

/**
 * 播放器页面路由 — 接收 MainApp 持有的状态,透传给子 Composable
 */
@Composable
fun PlayerScreen(
    activity: Activity,
    exoPlayer: ExoPlayer?, onExoPlayerChange: (ExoPlayer?) -> Unit,
    currentSong: MusicApi.Song?, onCurrentSongChange: (MusicApi.Song?) -> Unit,
    searchQuery: String, onSearchQueryChange: (String) -> Unit,
    searchResults: List<MusicApi.Song>, onSearchResultsChange: (List<MusicApi.Song>) -> Unit,
    favoriteSongs: List<FavoriteSong>, onFavoriteSongsChange: (List<FavoriteSong>) -> Unit
) {
    var mode by remember { mutableIntStateOf(0) } // 0=外挂, 1=搜索
    val colors = MaterialTheme.colorScheme

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 60.dp, bottom = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 模式切换
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { mode = 0 }) {
                Text("外挂检测", fontWeight = if (mode == 0) FontWeight.Bold else FontWeight.Normal,
                    color = if (mode == 0) colors.primary else colors.onSurfaceVariant)
            }
            TextButton(onClick = { mode = 1 }, enabled = false,
                modifier = Modifier.alpha(0.35f)) {
                Text("在线搜索", fontWeight = if (mode == 1) FontWeight.Bold else FontWeight.Normal,
                    color = colors.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))

        when (mode) {
            0 -> ExtModeScreen(activity)
            1 -> LocalModeScreen(
                activity = activity,
                searchQuery = searchQuery, onSearchQueryChange = onSearchQueryChange,
                searchResults = searchResults, onSearchResultsChange = onSearchResultsChange,
                currentSong = currentSong, onCurrentSongChange = onCurrentSongChange,
                exoPlayer = exoPlayer, onExoPlayerChange = onExoPlayerChange,
                favoriteSongs = favoriteSongs, onFavoriteSongsChange = onFavoriteSongsChange
            )
        }
    }
}