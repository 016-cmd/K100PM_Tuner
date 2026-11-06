package com.k100pm.tuner.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 音乐在线搜索 + 流式播放 API
 * 流解析服务：原聚合 API（mobi-api.likegamex.top / TuneFree）已下线失效，
 *             当前 getStreamUrl 返回 null（用占位符禁用），待接入新的解析服务后恢复。
 * 搜索:网易云 / QQ / 酷我 / 酷狗官方搜索接口（仍可用）
 */
object MusicApi {

    data class Song(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val coverUrl: String,
        val durationMs: Long,
        val source: String  // "netease" / "qq" / "kuwo" / "kugou"
    )

    /**
     * 多平台搜索歌曲
     * @param keyword 搜索关键词
     * @param sources 要搜索的平台列表,默认三平台
     */
    suspend fun search(
        keyword: String,
        sources: List<String> = listOf("netease", "qq", "kuwo", "kugou")
    ): List<Song> = withContext(Dispatchers.IO) {
        val results = mutableListOf<Song>()
        for (src in sources) {
            try {
                results.addAll(searchPlatform(keyword, src))
            } catch (_: Exception) {}
        }
        // 去重:同歌名+歌手只保留一个
        results.distinctBy { "${it.title}|${it.artist}".lowercase() }
    }

    private fun searchPlatform(keyword: String, platform: String): List<Song> {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        return when (platform) {
            "netease" -> searchNetease(encoded)
            "qq" -> searchQQ(encoded)
            "kuwo" -> searchKuwo(encoded)
            "kugou" -> searchKugou(encoded)
            else -> emptyList()
        }
    }

    // ── 网易云搜索 ──
    private fun searchNetease(keyword: String): List<Song> {
        val url = "https://music.163.com/api/search/get/web?s=$keyword&type=1&offset=0&limit=20"
        val json = httpGet(url, referer = "https://music.163.com/") ?: return emptyList()
        val songs = JSONObject(json).optJSONObject("result")?.optJSONArray("songs")
            ?: return emptyList()
        val list = mutableListOf<Song>()
        for (i in 0 until songs.length()) {
            val s = songs.getJSONObject(i)
            val artists = s.optJSONArray("artists")
            val artist = artists?.let { a ->
                (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name", "") }
            } ?: ""
            val album = s.optJSONObject("album")?.optString("name", "") ?: ""
            list.add(Song(
                id = s.optString("id"),
                title = s.optString("name"),
                artist = artist,
                album = album,
                coverUrl = albumUrl(s.optJSONObject("album")),
                durationMs = s.optLong("duration"),
                source = "netease"
            ))
        }
        return list
    }

    private fun albumUrl(album: JSONObject?): String {
        // 优先 picUrl（直接返回的URL）
        val directUrl = album?.optString("picUrl", "") ?: ""
        if (directUrl.isNotEmpty()) return directUrl
        // 其次通过 picId 拼出 300x300 封面
        val picId = album?.optLong("picId", 0L) ?: 0L
        if (picId > 0) return "https://p2.music.126.net/$picId/${picId}.jpg"
        return ""
    }

    // ── QQ 音乐搜索 ──
    private fun searchQQ(keyword: String): List<Song> {
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=$keyword&format=json&n=20"
        val json = httpGet(url, referer = "https://y.qq.com") ?: return emptyList()
        val songList = JSONObject(json).optJSONObject("data")?.optJSONObject("song")
            ?.optJSONArray("list") ?: return emptyList()
        val list = mutableListOf<Song>()
        for (i in 0 until songList.length()) {
            val s = songList.getJSONObject(i)
            val singers = s.optJSONArray("singer")
            val artist = singers?.let { a ->
                (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name", "") }
            } ?: ""
            val songmid = s.optString("songmid")
            val albummid = s.optString("albummid")
            list.add(Song(
                id = songmid,
                title = s.optString("songname"),
                artist = artist,
                album = s.optString("albumname"),
                coverUrl = "https://y.gtimg.cn/music/photo_new/T002R300x300M000${albummid}.jpg",
                durationMs = s.optLong("interval") * 1000,
                source = "qq"
            ))
        }
        return list
    }

    // ── 酷我搜索 ──
    private fun searchKuwo(keyword: String): List<Song> {
        val url = "https://search.kuwo.cn/r.s?all={$keyword}&ft=music&pn=0&rn=20&rformat=json&encoding=utf8"
            .replace("{$keyword}", keyword) // kuwo uses different encoding
        val json = httpGet(url) ?: return emptyList()
        val absList = JSONObject(json).optJSONArray("abslist") ?: return emptyList()
        val list = mutableListOf<Song>()
        for (i in 0 until absList.length()) {
            val s = absList.getJSONObject(i)
            list.add(Song(
                id = s.optString("MUSICRID", "").replace("MUSIC_", ""),
                title = s.optString("NAME"),
                artist = s.optString("ARTIST"),
                album = s.optString("ALBUM"),
                coverUrl = "",
                durationMs = s.optLong("DURATION") * 1000,
                source = "kuwo"
            ))
        }
        return list
    }

    // ── 酷狗搜索 ──
    private fun searchKugou(keyword: String): List<Song> {
        val url = "https://songsearch.kugou.com/song_search_v2?keyword=$keyword&page=1&pagesize=20"
        val json = httpGet(url) ?: return emptyList()
        val lists = JSONObject(json).optJSONObject("data")?.optJSONArray("lists")
            ?: return emptyList()
        val list = mutableListOf<Song>()
        for (i in 0 until lists.length()) {
            val s = lists.getJSONObject(i)
            val fileHash = s.optString("FileHash", "")
            val albumId = s.optString("AlbumID", "")
            list.add(Song(
                id = fileHash,
                title = s.optString("FileName", "").replace(Regex("""\.(mp3|flac|ape)$"""), ""),
                artist = s.optString("SingerName"),
                album = s.optString("AlbumName", ""),
                coverUrl = if (albumId.isNotEmpty()) "https://imge.kugou.com/stdmusic/${albumId}.jpg" else "",
                durationMs = s.optLong("Duration", 0L) * 1000,
                source = "kugou"
            ))
        }
        return list
    }

    // ── 多平台搜索封面（并发搜索各平台,谁先拿到用谁,仅内存）──
    suspend fun fetchCoverAnyPlatform(song: Song): String? = withContext(Dispatchers.IO) {
        // 如果已有封面URL,先验证是否有效
        if (song.coverUrl.isNotEmpty()) {
            try {
                val conn = URL(song.coverUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 2000; conn.readTimeout = 2000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                // 网易云图片需要Referer
                if (song.coverUrl.contains("music.126.net")) {
                    conn.setRequestProperty("Referer", "https://music.163.com/")
                }
                conn.connect()
                val valid = conn.responseCode == 200
                conn.disconnect()
                if (valid) return@withContext song.coverUrl
            } catch (_: Exception) { }
        }

        // 并发搜索各平台拿封面
        val platforms = listOf("netease", "qq", "kugou")
        val deferreds = platforms.map { platform ->
            async {
                try {
                    when (platform) {
                        "netease" -> {
                            val url = "https://music.163.com/api/song/detail?id=${song.id}&ids=%5B${song.id}%5D"
                            val json = httpGet(url, referer = "https://music.163.com/") ?: return@async null
                            JSONObject(json).optJSONArray("songs")?.optJSONObject(0)
                                ?.optJSONObject("album")?.optString("picUrl", "")?.takeIf { it.isNotEmpty() }
                                ?.let { picUrl ->
                                    // 验证这个URL是否有效
                                    try {
                                        val c = URL(picUrl).openConnection() as HttpURLConnection
                                        c.connectTimeout = 2000; c.readTimeout = 2000
                                        c.setRequestProperty("User-Agent", "Mozilla/5.0")
                                        c.setRequestProperty("Referer", "https://music.163.com/")
                                        c.connect()
                                        val ok = c.responseCode == 200
                                        c.disconnect()
                                        if (ok) picUrl else null
                                    } catch (_: Exception) { null }
                                }
                        }
                        "qq" -> {
                            val results = searchQQ(URLEncoder.encode(song.title, "UTF-8"))
                            results.firstOrNull { it.title.equals(song.title, ignoreCase = true) }
                                ?.coverUrl?.takeIf { it.isNotEmpty() }
                        }
                        "kugou" -> {
                            val results = searchKugou(URLEncoder.encode(song.title, "UTF-8"))
                            results.firstOrNull { it.title.equals(song.title, ignoreCase = true) }
                                ?.coverUrl?.takeIf { it.isNotEmpty() }
                        }
                        else -> null
                    }
                } catch (_: Exception) { null }
            }
        }
        // 谁先返回非空用谁
        deferreds.firstNotNullOfOrNull { it.await() }
    }
    suspend fun getStreamUrl(platform: String, songId: String): String? = withContext(Dispatchers.IO) {
        // ⚠️ 音频流解析服务已下线（原聚合 API 域名失效，HTTP 530 / 连接失败）。
        // 原有实现见下方【DEPRECATED】区块，已用占位符替代以停用该失效域名。
        // 需接入新的可用的音乐流解析服务后，再恢复 getStreamUrl 的真实实现。
        return@withContext null

        /* 【DEPRECATED — 原聚合解析服务（mobi-api.likegamex.top 已下线，勿恢复）
        try {
            val url = "https://mobi-api.likegamex.top/tunefree/stream?platform=$platform&id=$songId"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connect()
            // stream 端点返回 302 或直接 mp3 流,取 Location
            if (conn.responseCode == 302 || conn.responseCode == 307) {
                conn.getHeaderField("Location")
            } else {
                // 如果直接返回流,返回原始 URL 供 ExoPlayer 用
                url
            }
        } catch (_: Exception) { null }
        */
    }

    // ── 获取歌词（复用 LyricFetcher 的逻辑,但接受 songId）──
    suspend fun getLyric(platform: String, songId: String): String? = withContext(Dispatchers.IO) {
        when (platform) {
            "netease" -> {
                val json = httpGet(
                    "https://music.163.com/api/song/lyric?id=$songId&lv=1",
                    referer = "https://music.163.com/"
                ) ?: return@withContext null
                JSONObject(json).optJSONObject("lrc")?.optString("lyric")
            }
            "qq" -> {
                val json = httpGet(
                    "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songId&format=json&nobase64=1",
                    referer = "https://y.qq.com"
                ) ?: return@withContext null
                val base64 = JSONObject(json).optString("lyric") ?: return@withContext null
                String(android.util.Base64.decode(base64, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }
            else -> null
        }
    }

    // ── HTTP GET ──
    private fun httpGet(urlStr: String, referer: String? = null): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            if (referer != null) conn.setRequestProperty("Referer", referer)
            conn.connect()
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) { null }
    }
}