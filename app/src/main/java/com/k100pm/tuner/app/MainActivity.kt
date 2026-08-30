package com.k100pm.tuner.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.util.DebugLogger
import com.k100pm.tuner.ui.AppContextHolder
import com.k100pm.tuner.ui.screens.MainApp
import com.k100pm.tuner.ui.theme.K100TunerTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val MEDIA_CHANNEL_ID = "k100pm_media_playback"
    }

    // 通知权限请求
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 用户响应,不做特殊处理 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContextHolder.ctx = applicationContext
        enableEdgeToEdge()

        // 创建媒体播放通知渠道
        createMediaNotificationChannel()

        // 请求通知权限（Android 13+）
        requestNotificationPermission()

        // 初始化 Coil 图片加载器（让 AsyncImage 能工作）
        SingletonImageLoader.setSafe { context: PlatformContext ->
            ImageLoader.Builder(context)
                .crossfade(true)
                .build()
        }

        setContent {
            K100TunerTheme {
                MainApp(activity = this@MainActivity)
            }
        }
    }

    private fun createMediaNotificationChannel() {
        val channel = NotificationChannel(
            MEDIA_CHANNEL_ID,
            "音乐播放",
            NotificationManager.IMPORTANCE_LOW  // LOW 不弹出打扰,只在通知栏显示
        ).apply {
            description = "控制音乐播放的媒体通知"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}