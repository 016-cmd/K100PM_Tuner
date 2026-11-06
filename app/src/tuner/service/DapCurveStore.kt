package com.k100pm.tuner.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 实时层曲线存档（APP 内部目录，不需要 root）
 *
 * 作用：
 *   ① 「保存到文件」按钮把当前 20 段曲线写进这里
 *   ② 下次打开页面 / 开机由 BootReceiver 读出来重新写进 DSP
 *
 * 说明：持久化的第一层是 Dolby 自己的 dax_sqlite3.db（发 0x09000000 让它自己写），
 *      这个文件是第二层，用于开机兜底重放。
 */
object DapCurveStore {

    private const val FILE_NAME = "dap_geq_curve.json"
    private const val DRAFT_NAME = "dap_geq_draft.json"

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)
    private fun draftFile(ctx: Context): File = File(ctx.filesDir, DRAFT_NAME)

    private fun readCurve(f: File): IntArray? = runCatching {
        if (!f.exists()) return null
        val arr = JSONObject(f.readText()).optJSONArray("curve") ?: return null
        if (arr.length() != DapRealtime.BANDS) return null
        IntArray(DapRealtime.BANDS) { arr.getInt(it) }
    }.getOrNull()

    /**
     * 草稿：每次拖动停下就写一份，**不发 save 命令**，只用于「重进页面时 UI 显示上次调过的样子」。
     * 点「保存到文件」后草稿会被清掉（正式存档接管）。
     */
    fun saveDraft(ctx: Context, curve: IntArray) {
        runCatching {
            val json = JSONObject()
            json.put("curve", JSONArray(curve.toList()))
            json.put("savedAt", System.currentTimeMillis())
            json.put("bands", DapRealtime.BANDS)
            json.put("q4PerDb", DapRealtime.Q4_PER_DB)
            draftFile(ctx).writeText(json.toString(2))
        }
    }

    fun loadDraft(ctx: Context): IntArray? = readCurve(draftFile(ctx))

    fun clearDraft(ctx: Context) {
        runCatching { draftFile(ctx).delete() }
    }

    /** 读取存档曲线；没有存档返回 null */
    fun load(ctx: Context): IntArray? = runCatching {
        val f = file(ctx)
        if (!f.exists()) return null
        val json = JSONObject(f.readText())
        val arr = json.optJSONArray("curve") ?: return null
        if (arr.length() != DapRealtime.BANDS) return null
        IntArray(DapRealtime.BANDS) { arr.getInt(it) }
    }.getOrNull()

    /** 存档时间（毫秒），没有返回 0 */
    fun savedAt(ctx: Context): Long = runCatching {
        val f = file(ctx)
        if (!f.exists()) return 0L
        JSONObject(f.readText()).optLong("savedAt", 0L)
    }.getOrDefault(0L)

    /** 是否开启「开机写回」 */
    fun bootApplyEnabled(ctx: Context): Boolean = runCatching {
        val f = file(ctx)
        if (!f.exists()) return false
        JSONObject(f.readText()).optBoolean("bootApply", true)
    }.getOrDefault(false)

    /** 保存曲线（同时写 savedAt / bootApply 标记） */
    fun save(ctx: Context, curve: IntArray, bootApply: Boolean = true) {
        runCatching {
            val json = JSONObject()
            json.put("curve", JSONArray(curve.toList()))
            json.put("savedAt", System.currentTimeMillis())
            json.put("bootApply", bootApply)
            json.put("bands", DapRealtime.BANDS)
            json.put("q4PerDb", DapRealtime.Q4_PER_DB)
            file(ctx).writeText(json.toString(2))
        }
    }

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }
}