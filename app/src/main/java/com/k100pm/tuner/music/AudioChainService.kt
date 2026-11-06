package com.k100pm.tuner.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.util.Log
import java.util.UUID

/**
 * ⚠️ 已废弃 / DISABLED — 仅供存档，不参与实际功能
 *
 * 音频链前台服务（自研实现）：
 * - 通过反射连接 DAP_offload + MiSound + DynamicsProcessing 三条音频链
 * - 通过 Foreground Service 持续维持进程存活和音频链注册
 * - DAP_offload 始终保持 Enabled 作为 HAL 锚点
 * - 不放声音也能维持音频链连接
 *
 * 说明：该能力目前由模块 + DolbyManager 链路承担，本服务启动入口已在
 * DspManager 中被禁用，不会再被拉起。保留源码仅为日后复用参考。
 */
class AudioChainService : Service() {

    companion object {
        const val TAG = "AudioChain"
        const val CHANNEL_ID = "audio_chain"
        const val NOTIFY_ID = 3001

        // MiSound UUID
        val MISOUND_UUID = UUID.fromString("5b8e36a5-144a-4c38-b1d7-0002a5d5c51b")
        // DAP_offload UUID
        val DAP_UUID = UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa")
        // DAP type UUID (用于反射调用)
        val DAP_TYPE_UUID = UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210")
        // DynamicsProcessing type UUID
        val DP_TYPE_UUID = AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING
        // DynamicsProcessing UUID (AOSP standard)
        val DP_UUID = UUID.fromString("e0e6539b-1781-7261-676f-6d7573696340")
        // Zero UUID (for type-less connection)
        val ZERO_UUID = UUID(0, 0)

        // 状态
        @Volatile var instance: AudioChainService? = null
        @Volatile var chainReady: Boolean = false
        @Volatile var chainMessage: String = "未初始化"

        // PreEQ 10段频率 (自研对齐)
        val PRE_EQ_FREQS = floatArrayOf(47f, 234f, 469f, 844f, 1313f, 2250f, 3750f, 5813f, 9000f, 13875f)
        // PostEQ 10段频率 (自研对齐)
        val POST_EQ_FREQS = floatArrayOf(141f, 328f, 656f, 1031f, 1688f, 3000f, 4688f, 7125f, 11250f, 19688f)
        // MBC 3段频率
        val MBC_FREQS = floatArrayOf(160f, 6000f, 20000f)
    }

    // 三条音频链
    private var dapEffect: AudioEffect? = null     // DAP_offload
    private var miSoundEffect: AudioEffect? = null  // MiSound
    private var dp: DynamicsProcessing? = null      // DynamicsProcessing

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFY_ID, buildNotification("音频链初始化中…"))
        Log.i(TAG, "AudioChainService onCreate — 延迟2秒后初始化音频链")
        // 延迟初始化调度（自研策略）
        Handler(mainLooper).postDelayed({
            Thread { initAudioChain() }.start()
        }, 2000L)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.k100pm.tuner.action.ENABLE_DP") {
            enableDp(intent.getBooleanExtra("enabled", true))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "AudioChainService onDestroy — 释放音频链")
        instance = null
        chainReady = false
        releaseChain()
        super.onDestroy()
    }

    // ── 音频链初始化 ──

    private fun initAudioChain() {
        val msgs = mutableListOf<String>()
        try {
            // 第1步:反射连接 MiSound
            try {
                msgs.add(connectMiSound())
            } catch (e: Exception) {
                msgs.add("MiSound连接失败: ${summarize(e)}")
                Log.w(TAG, "MiSound failed", e)
            }

            // 第2步:反射连接 DAP_offload
            try {
                msgs.add(connectDap())
            } catch (e: Exception) {
                msgs.add("DAP连接失败: ${summarize(e)}")
                Log.w(TAG, "DAP failed", e)
            }

            // 第3步:创建 DynamicsProcessing
            try {
                msgs.add(createDp())
            } catch (e: Exception) {
                msgs.add("DynamicsProcessing创建失败: ${summarize(e)}")
                Log.w(TAG, "DP failed", e)
            }

            // DAP_offload 保持 Enabled 作为 HAL 锚点
            dapEffect?.let {
                try {
                    if (!it.enabled) it.setEnabled(true)
                } catch (e: Exception) { /* ignore */ }
            }

            chainReady = dp != null
            chainMessage = msgs.joinToString(" · ")
            Log.i(TAG, "AudioChain 初始化完成: $chainMessage ready=$chainReady")
            updateNotification(buildNotification(chainMessage))

        } catch (e: Exception) {
            chainMessage = "初始化异常: ${summarize(e)}"
            Log.e(TAG, "initAudioChain failed", e)
            updateNotification(buildNotification(chainMessage))
        }
    }

    private fun connectMiSound(): String {
        val ctor = AudioEffect::class.java.getConstructor(
            UUID::class.java, UUID::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!
        )
        val ae = ctor.newInstance(ZERO_UUID, MISOUND_UUID, 0, 0) as AudioEffect
        ae.id // 触发内部初始化
        miSoundEffect = ae
        // 初始Disabled
        ae.setEnabled(false)
        Log.i(TAG, "MiSound连接成功, id=${ae.id}")
        return "MiSound已连接"
    }

    private fun connectDap(): String {
        val ctor = AudioEffect::class.java.getConstructor(
            UUID::class.java, UUID::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!
        )
        val ae = ctor.newInstance(ZERO_UUID, DAP_UUID, 0, 0) as AudioEffect
        ae.id
        dapEffect = ae
        // DAP_offload 保持 Enabled (HAL锚点)
        ae.setEnabled(true)
        Log.i(TAG, "DAP_offload连接成功, id=${ae.id}")
        return "DAP已连接"
    }

    private fun createDp(): String {
        val config = DynamicsProcessing.Config.Builder(
            0,           // variant: 0
            2,           // channel count
            true,        // preEq enabled
            10,          // preEq bands
            true,        // mbc enabled
            3,           // mbc bands
            true,        // postEq enabled
            10,          // postEq bands
            false        // limiter enabled
        )
            .setPreferredFrameDuration(10.0f)
            .setInputGainAllChannelsTo(0.0f)
            .setPreEqAllChannelsTo(buildEq(10, PRE_EQ_FREQS))
            .setMbcAllChannelsTo(buildMbc())
            .setPostEqAllChannelsTo(buildEq(10, POST_EQ_FREQS))
            .setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(false, false, 0, 1f, 60f, 10f, 0f, 0f)
            )
            .build()

        val dynamicsProcessing = DynamicsProcessing(0, 0, config)
        dynamicsProcessing.id // 确保初始化
        dynamicsProcessing.setEnabled(false)
        dp = dynamicsProcessing
        Log.i(TAG, "DynamicsProcessing创建成功, id=${dynamicsProcessing.id}")
        return "DynamicsProcessing已连接"
    }

    private fun buildEq(bands: Int, freqs: FloatArray): DynamicsProcessing.Eq {
        val eq = DynamicsProcessing.Eq(true, true, bands)
        for (i in 0 until bands) {
            val freq = if (i < freqs.size) freqs[i]
                       else Math.min(20000f, ((i - freqs.size + 1) * 500f) + freqs.last())
            eq.setBand(i, DynamicsProcessing.EqBand(true, freq, 0f))
        }
        return eq
    }

    private fun buildMbc(): DynamicsProcessing.Mbc {
        val mbc = DynamicsProcessing.Mbc(true, false, 3)
        for (i in 0..2) {
            mbc.setBand(i, DynamicsProcessing.MbcBand(
                true, MBC_FREQS[i], 10f, 80f, 1f, 0f, 0f, -90f, 1f, 0f, 0f
            ))
        }
        return mbc
    }

    // ── 动态控制 ──

    fun enableDp(enabled: Boolean) {
        dp?.let {
            try {
                it.setEnabled(enabled)
                Log.i(TAG, "DP setEnabled($enabled) OK")
            } catch (e: Exception) {
                Log.e(TAG, "DP setEnabled($enabled) failed", e)
            }
        }
    }

    fun setPreEqBand(band: Int, gainDb: Float) {
        dp?.let {
            val freq = if (band < PRE_EQ_FREQS.size) PRE_EQ_FREQS[band]
                       else Math.min(20000f, ((band - PRE_EQ_FREQS.size + 1) * 500f) + PRE_EQ_FREQS.last())
            try {
                it.setPreEqBandAllChannelsTo(band, DynamicsProcessing.EqBand(true, freq, gainDb))
            } catch (e: Exception) {
                Log.e(TAG, "setPreEqBand($band, $gainDb) failed", e)
            }
        }
    }

    fun setPostEqBand(band: Int, gainDb: Float) {
        dp?.let {
            val freq = if (band < POST_EQ_FREQS.size) POST_EQ_FREQS[band]
                       else Math.min(20000f, ((band - POST_EQ_FREQS.size + 1) * 500f) + POST_EQ_FREQS.last())
            try {
                it.setPostEqBandAllChannelsTo(band, DynamicsProcessing.EqBand(true, freq, gainDb))
            } catch (e: Exception) {
                Log.e(TAG, "setPostEqBand($band, $gainDb) failed", e)
            }
        }
    }

    fun setInputGain(gainDb: Float) {
        dp?.let {
            try {
                it.setInputGainAllChannelsTo(gainDb)
            } catch (e: Exception) {
                Log.e(TAG, "setInputGain($gainDb) failed", e)
            }
        }
    }

    // ── 释放 ──

    private fun releaseChain() {
        dp?.release()
        dp = null
        miSoundEffect?.release()
        miSoundEffect = null
        dapEffect?.release()
        dapEffect = null
        chainReady = false
    }

    // ── 通知 ──

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "音效接管", NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        // 使用 Notification.Builder 并传入 PendingIntent
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, Class.forName("com.k100pm.tuner.app.MainActivity"))
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("K100PM Tuner 音效")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(notification: Notification) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFY_ID, notification)
    }

    private fun summarize(e: Throwable): String {
        var t: Throwable = e
        while (t.cause != null && t.cause !== t) t = t.cause!!
        val msg = t.message
        return if (msg.isNullOrBlank()) t.javaClass.simpleName
               else "${t.javaClass.simpleName}: $msg"
    }
}