package com.k100pm.tuner.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 无歌词时的后备卡片 —— 轮播心情语录/名诗名句
 */
@Composable
fun QuoteCard(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.red * 0.299f + colors.background.green * 0.587f + colors.background.blue * 0.114f < 0.5f
    val fillColor = if (isDark) Color.Black.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.40f)
    val shape = RoundedCornerShape(24.dp)

    val quotes = listOf(
        "音乐是灵魂的避难所." to "— 毛姆",
        "没有音乐,生活将是一种错误." to "— 尼采",
        "音乐应当使人类的精神爆发出火花." to "— 贝多芬",
        "语言的尽头,是音乐开始的地方." to "— 海涅",
        "音乐是唯一可以纵情而不会损害道德和宗教观念的享受." to "— 爱迪生",
        "音乐是思维着的声音." to "— 雨果",
        "此曲只应天上有,人间能得几回闻." to "— 杜甫",
        "大弦嘈嘈如急雨,小弦切切如私语." to "— 白居易",
        "音乐,是人生最大的快乐." to "— 冼星海",
        "凡是出于音乐的,都必归于音乐." to "— 村上春树",
        "音乐是空气的诗歌." to "— 保罗",
        "听,那是花开的声音." to "",
        "纵使黑夜吞噬了一切,太阳还可以重新回来." to "— 汪国真",
        "心之所向,素履以往." to "— 《易经》",
        "生活不止眼前的苟且,还有诗和远方的田野." to "— 许巍",
        "愿你被这个世界温柔以待." to "",
        "每一个不曾起舞的日子,都是对生命的辜负." to "— 尼采",
        "星光不负赶路人." to "",
        "岁月极美,在于它必然的流逝." to "— 三毛",
        "春风十里,不如你." to "— 冯唐",
        "凡是过往,皆为序章." to "— 莎士比亚",
        "生如夏花之绚烂,死如秋叶之静美." to "— 泰戈尔"
    )

    var index by remember { mutableStateOf(0) }
    var visible by remember { mutableStateOf(true) }

    // 每 8 秒切换一次,带淡入淡出效果
    LaunchedEffect(Unit) {
        while (true) {
            delay(8000)
            visible = false
            delay(400)
            index = (index + 1) % quotes.size
            visible = true
        }
    }

    val alpha = if (visible) 1f else 0f

    Box(
        modifier = modifier
            .clip(shape)
            .background(fillColor, shape),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "🎵",
                style = MaterialTheme.typography.displaySmall
            )
            Spacer(Modifier.height(16.dp))
            Text(
                quotes[index].first,
                style = MaterialTheme.typography.titleMedium,
                fontStyle = FontStyle.Italic,
                color = colors.onSurface.copy(alpha = 0.85f * alpha),
                textAlign = TextAlign.Center
            )
            if (quotes[index].second.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    quotes[index].second,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurface.copy(alpha = 0.45f * alpha),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}