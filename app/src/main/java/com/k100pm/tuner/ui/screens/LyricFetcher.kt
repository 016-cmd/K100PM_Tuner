package com.k100pm.tuner.ui.screens

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 歌词获取器 — 多平台歌词 API（网易云/QQ/酷狗）
 */
object LyricFetcher {

    data class LyricLine(val timeMs: Long, val text: String)
    data class LyricResult(val lines: List<LyricLine>, val source: String)

    /**
     * 根据歌名+歌手查询歌词,依次尝试网易云 → QQ → 酷狗
     */
    suspend fun fetch(title: String, artist: String): LyricResult? = withContext(Dispatchers.IO) {
        // 清洗:去掉括号内容如 (伴奏) / (Live)
        val cleanTitle = title.replace(Regex("""\s*[\(（].*?[\)）]\s*"""), "").trim()
        val cleanArtist = artist.replace(Regex("""\s*[\(（].*?[\)）]\s*"""), "").trim()

        // 1. 网易云
        netease(cleanTitle, cleanArtist)?.let { return@withContext it }
        // 2. QQ音乐
        qq(cleanTitle, cleanArtist)?.let { return@withContext it }
        // 3. 酷狗
        kugou(cleanTitle, cleanArtist)?.let { return@withContext it }

        null
    }

    // ── 网易云 ──
    private suspend fun netease(title: String, artist: String): LyricResult? {
        try {
            // 搜索
            val keyword = URLEncoder.encode("$title $artist", "UTF-8")
            val searchUrl = "https://music.163.com/api/search/get?s=$keyword&type=1&limit=3"
            val searchJson = httpGet(searchUrl) ?: return null
            val songs = JSONObject(searchJson).optJSONObject("result")?.optJSONArray("songs")
                ?: return null
            if (songs.length() == 0) return null

            // 遍历搜索结果,优先歌手匹配
            var songId: Long? = null
            for (i in 0 until songs.length()) {
                val s = songs.getJSONObject(i)
                val sName = s.optString("name", "")
                val arts = s.optJSONArray("artists")
                val artName = arts?.optJSONObject(0)?.optString("name", "") ?: ""

                if (sName.contains(title, ignoreCase = true) || title.contains(sName, ignoreCase = true)) {
                    songId = s.optLong("id")
                    if (artName.contains(artist, ignoreCase = true) || artist.contains(artName, ignoreCase = true)) {
                        break // 完美匹配
                    }
                }
            }
            if (songId == null) songId = songs.getJSONObject(0).optLong("id")

            // 获取歌词
            val lyricUrl = "https://music.163.com/api/song/lyric?id=$songId&lv=1"
            val lyricJson = httpGet(lyricUrl) ?: return null
            val lrcObj = JSONObject(lyricJson).optJSONObject("lrc")
            val lrcText = lrcObj?.optString("lyric", "") ?: return null
            if (lrcText.isBlank()) return null

            val lines = parseLrc(lrcText)
            if (lines.isEmpty()) return null
            return LyricResult(lines, "网易云")
        } catch (_: Exception) { return null }
    }

    // ── QQ音乐 ──
    private suspend fun qq(title: String, artist: String): LyricResult? {
        try {
            val keyword = URLEncoder.encode("$title $artist", "UTF-8")
            // 搜索
            val searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=$keyword&format=json&n=3"
            val searchJson = httpGet(searchUrl) ?: return null
            val songList = JSONObject(searchJson).optJSONObject("data")?.optJSONObject("song")
                ?.optJSONArray("list") ?: return null
            if (songList.length() == 0) return null

            var songmid: String? = null
            for (i in 0 until songList.length()) {
                val s = songList.getJSONObject(i)
                val sName = s.optString("songname", "")
                val arts = s.optJSONArray("singer")
                val artName = arts?.optJSONObject(0)?.optString("name", "") ?: ""

                songmid = s.optString("songmid", "")
                if (sName.contains(title, ignoreCase = true)) {
                    if (artName.contains(artist, ignoreCase = true)) break
                }
            }
            if (songmid.isNullOrEmpty()) return null

            // 获取歌词（base64需要解码）
            val lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$songmid&format=json&nobase64=1"
            val lyricJson = httpGet(lyricUrl, referer = "https://y.qq.com") ?: return null
            val lrcText = JSONObject(lyricJson).optString("lyric", "")
                .takeIf { it.isNotBlank() } ?: return null

            val decoded = android.util.Base64.decode(lrcText, android.util.Base64.DEFAULT)
            val lrcStr = String(decoded, Charsets.UTF_8)
            val lines = parseLrc(lrcStr)
            if (lines.isEmpty()) return null
            return LyricResult(lines, "QQ音乐")
        } catch (_: Exception) { return null }
    }

    // ── 酷狗 ──
    // 流程:搜索 → 拿 hash → 查歌词列表 → 取 id+accesskey → 下载解码 base64 LRC
    private suspend fun kugou(title: String, artist: String): LyricResult? {
        try {
            val keyword = URLEncoder.encode("$title $artist", "UTF-8")

            // 1. 搜索歌曲拿 FileHash
            val searchUrl = "https://songsearch.kugou.com/song_search_v2?keyword=$keyword&page=1&pagesize=3"
            val searchJson = httpGet(searchUrl) ?: return null
            val lists = JSONObject(searchJson).optJSONObject("data")?.optJSONArray("lists")
                ?: return null
            if (lists.length() == 0) return null

            var hash: String? = null
            for (i in 0 until lists.length()) {
                val s = lists.getJSONObject(i)
                val sName = s.optString("SongName", "")
                val sArtist = s.optString("SingerName", "")
                hash = s.optString("FileHash", "")
                if (sName.contains(title, ignoreCase = true) && sArtist.contains(artist, ignoreCase = true)) break
            }
            if (hash.isNullOrEmpty()) hash = lists.getJSONObject(0).optString("FileHash", "") ?: return null

            // 2. 查歌词候选列表
            val lyricSearchUrl = "https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=$hash"
            val lyricSearchJson = httpGet(lyricSearchUrl) ?: return null
            val candidates = JSONObject(lyricSearchJson).optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) return null

            // 取第一个（得分最高）
            val first = candidates.getJSONObject(0)
            val lyricId = first.optString("id", "")
            val accessKey = first.optString("accesskey", "")
            if (lyricId.isEmpty() || accessKey.isEmpty()) return null

            // 3. 下载歌词（返回 base64 编码的 LRC）
            val downloadUrl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=$lyricId&accesskey=$accessKey&fmt=lrc&charset=utf8"
            val downloadJson = httpGet(downloadUrl) ?: return null
            val content = JSONObject(downloadJson).optString("content", "")
                .takeIf { it.isNotBlank() } ?: return null

            // 4. Base64 解码 → LRC 文本
            val decoded = android.util.Base64.decode(content, android.util.Base64.DEFAULT)
            val lrcText = String(decoded, Charsets.UTF_8)

            val lines = parseLrc(lrcText)
            if (lines.isEmpty()) return null
            return LyricResult(lines, "酷狗音乐")
        } catch (_: Exception) { return null }
    }

    // ── LRC 解析 ──
    private fun parseLrc(lrc: String): List<LyricLine> {
        val regex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)""")
        return lrc.lines().mapNotNull { line ->
            regex.find(line)?.let { m ->
                val min = m.groupValues[1].toInt()
                val sec = m.groupValues[2].toInt()
                val ms = (m.groupValues[3].padEnd(3, '0').toInt())
                val time = (min * 60 + sec) * 1000L + ms
                val text = m.groupValues[4].trim()
                LyricLine(timeMs = time, text = text)
            }
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