package com.k100pm.tuner.service

import android.media.audiofx.AudioEffect
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Dolby DAP 实时层（GEQ 参数 110）
 *
 * 只做一件事：把 20 段 GEQ 曲线写进「运行中的」杜比 DAP，不重启音频服务。
 * 协议细节全部来自对 DapTune（Apache-2.0）接口事实的核对，实现为本项目自写。
 *
 * 关键协议（不要改，改了就写不进去/读不出来）：
 *   查询  key = command + 5
 *   读曲线 key = 0x01000000 + 5 + (110 shl 16) + (profile shl 8)
 *   写     setParameter(5, [0x01000000, 21, profile, 110, gain×20])
 *   保存   setParameter(5, [0x09000000])
 *
 * 安全红线：
 *   ① offload 型读回全是 0，禁止「回读比对 → 回滚」，否则把曲线清零
 *   ② 上限不卡 +10 dB（本模块 dax 里有 +11.56 dB 的段），这里放宽到 ±24 dB
 *   ③ 每次用完必须 release()，否则控制权被自己占住
 */
object DapRealtime {

    const val BANDS = 20
    const val Q4_PER_DB = 16
    const val MAX_BOOST_Q4 = 384      // +24.0 dB（放宽到二改版同款，DSP 侧若截断不报错）
    const val MIN_GAIN_Q4 = -384      // -24.0 dB

    val FREQS = intArrayOf(
        47, 141, 234, 328, 469, 656, 844, 1031, 1313, 1688,
        2250, 3000, 3750, 4688, 5813, 7125, 9000, 11250, 13875, 19688
    )

    // ── 协议常量 ──
    private const val CMD_PARAM = 5
    private const val COMMAND_SET_PROFILE = 0x01000000
    private const val COMMAND_GET_PROFILE_COUNT = 0x03000000
    private const val COMMAND_SAVE = 0x09000000
    private const val COMMAND_GET_CURRENT = 0x0a000000
    private const val COMMAND_GET_DAP_ON = 0x00000000
    private const val PARAM_GEQ_BAND_GAINS = 110

    private val TYPE_UUID = UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210")
    private val IMPL_UUID = UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa")
    private val PROXY_TYPE_UUID = UUID.fromString("fa81dbde-588b-11ed-9b6a-0242ac120002")
    private val OFFLOAD_TYPE_UUID = UUID.fromString("46d279d9-9be7-453d-9d7c-ef937f675587")

    data class Info(
        val available: Boolean,
        val detail: String,
        val readback: Boolean = false,
        val profileCount: Int = 0,
        val currentProfile: Int = -1,
        val dapOn: Boolean = false
    )

    // ────────────────────────────────────────────────────────────
    //  反射桥（4 参构造 + setParameter/getParameter）
    // ────────────────────────────────────────────────────────────

    private var bypassReady = false

    /**
     * 隐藏 API 豁免：必须用 "L" 全量豁免。
     * 只写类名前缀在 Android 17 上匹配不到 -> getParameter 查找失败 ->
     * NoSuchMethodException（现象：能写不能读）。
     */
    private fun ensureBypass() {
        if (bypassReady) return
        val ok = runCatching {
            org.lsposed.hiddenapibypass.HiddenApiBypass
                .addHiddenApiExemptions("L")
            true
        }.getOrDefault(false)
        if (ok) bypassReady = true
    }

    /** 先 getMethod，失败再用 getDeclaredMethod 并解除访问限制 */
    private fun findEffectMethod(name: String, vararg types: Class<*>): Method {
        val c = AudioEffect::class.java
        val m = runCatching { c.getMethod(name, *types) }.getOrNull()
            ?: runCatching { c.getDeclaredMethod(name, *types) }.getOrNull()
            ?: throw NoSuchMethodException("android.media.audiofx.AudioEffect.$name")
        runCatching { m.isAccessible = true }
        return m
    }

    private val ctor: Constructor<*> by lazy {
        AudioEffect::class.java.getDeclaredConstructor(
            UUID::class.java,
            UUID::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
    }

    private val setParamMethod: Method by lazy {
        findEffectMethod("setParameter", Int::class.javaPrimitiveType!!, ByteArray::class.java)
    }

    private val getParamMethod: Method by lazy {
        findEffectMethod("getParameter", Int::class.javaPrimitiveType!!, ByteArray::class.java)
    }

    private fun findDescriptor(): AudioEffect.Descriptor? =
        AudioEffect.queryEffects()?.firstOrNull { it.uuid == IMPL_UUID }

    /**
     * 打开 effect。
     *
     * 真机取证：本机 `dap_hw` 注册行是
     *   <effect name="dap_hw" library="dap_hw" uuid="9d4921da-…"/>
     * 全盘没有任何 `ec7178ec-…`（那是其它小米机型上的 DAP type）。
     * 所以这里不能用硬编码 type，顺序是：
     *   ① 系统上报的 descriptor.type（最准）
     *   ② impl uuid 自身（MIUI 的 DolbyAudioEffect 内部就只带一个 uuid）
     *   ③ 旧机型的 ec7178ec（兜底）
     */
    private fun openEffect(): AudioEffect {
        ensureBypass()
        val candidates = ArrayList<UUID>(3)
        findDescriptor()?.type?.let { candidates.add(it) }
        candidates.add(IMPL_UUID)
        candidates.add(TYPE_UUID)

        var last: Throwable? = null
        for (type in candidates.distinct()) {
            try {
                return ctor.newInstance(type, IMPL_UUID, 0, 0) as AudioEffect
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IllegalStateException("未找到可用的 DAP effect type")
    }

    private fun AudioEffect.setParam(key: Int, payload: ByteArray): Int =
        setParamMethod.invoke(this, key, payload) as Int

    private fun AudioEffect.getParam(key: Int, buffer: ByteArray): Int =
        getParamMethod.invoke(this, key, buffer) as Int

    private fun statusText(status: Int, what: String): String = when {
        status >= 0 -> ""
        status == AudioEffect.ERROR_BAD_VALUE -> "$what：参数无效"
        status == AudioEffect.ERROR_INVALID_OPERATION -> "$what：操作无效"
        status == AudioEffect.ERROR_NO_INIT -> "$what：音效未初始化"
        status == AudioEffect.ERROR_DEAD_OBJECT -> "$what：音频服务已重启"
        else -> "$what：错误 $status"
    }

    /** 反射会把真实异常包一层，这里剥出根因，方便排查 */
    private fun rootCause(t: Throwable): String {
        var cur: Throwable = t
        var guard = 0
        while (cur.cause != null && cur.cause !== cur && guard < 8) {
            cur = cur.cause!!
            guard++
        }
        val name = cur.javaClass.simpleName
        val msg = cur.message?.takeIf { it.isNotBlank() } ?: ""
        return if (msg.isEmpty()) name else "$name: $msg"
    }

    // ────────────────────────────────────────────────────────────
    //  协议组包
    // ────────────────────────────────────────────────────────────

    private fun queryKey(command: Int): Int = command + CMD_PARAM

    private fun curveReadKey(profile: Int): Int =
        COMMAND_SET_PROFILE + CMD_PARAM +
            (PARAM_GEQ_BAND_GAINS shl 16) + (profile shl 8)

    private fun buildCurvePayload(profile: Int, gains: IntArray): ByteArray {
        val buf = ByteBuffer.allocate((BANDS + 4) * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(COMMAND_SET_PROFILE)
        buf.putInt(BANDS + 1)
        buf.putInt(profile)
        buf.putInt(PARAM_GEQ_BAND_GAINS)
        gains.forEach { buf.putInt(it) }
        return buf.array()
    }

    private fun buildSavePayload(): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(COMMAND_SAVE).array()

    private fun buildQueryBuffer(): ByteArray = ByteArray(12)

    private fun buildCurveBuffer(): ByteArray = ByteArray((BANDS + 2) * 4)

    private fun readFirstInt(bytes: ByteArray): Int =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int

    private fun readCurveInts(bytes: ByteArray): IntArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(BANDS) { buf.int }
    }

    // ────────────────────────────────────────────────────────────
    //  对外接口
    // ────────────────────────────────────────────────────────────

    /** 探测能力（会不会写入，取决于 readback / profileCount） */
    fun inspect(): Info {
        var effect: AudioEffect? = null
        return try {
            val desc = findDescriptor()
                ?: return Info(false, "未找到 Dolby DAP（本机可能不支持实时层）")
            val readback = desc.type != OFFLOAD_TYPE_UUID &&
                desc.name?.equals("DAP_offload", ignoreCase = true) != true
            effect = openEffect()
            // getParameter 被隐藏 API 屏蔽时读不到，但写入仍然可用：降级为“仅写入”
            val dapOn = runCatching { queryInt(effect, COMMAND_GET_DAP_ON) }.getOrDefault(0) > 0
            val count = runCatching { queryInt(effect, COMMAND_GET_PROFILE_COUNT) }.getOrDefault(0)
            val current = runCatching { queryInt(effect, COMMAND_GET_CURRENT) }.getOrDefault(-1)
            val canRead = count in 1..32 || current >= 0 || dapOn
            Info(
                available = true,
                detail = buildString {
                    append(if (readback && canRead) "可用（支持曲线回读）" else "可用（仅写入，不支持回读）")
                    append(" · type=").append(desc.type.toString().take(8))
                },
                readback = readback && canRead,
                profileCount = count,
                currentProfile = current,
                dapOn = dapOn
            )
        } catch (t: Throwable) {
            Info(false, "打开实时层失败：${rootCause(t)}")
        } finally {
            runCatching { effect?.release() }
        }
    }

    /**
     * 读回当前生效的 GEQ 曲线（优先复用常驻会话）。
     *
     * 顺序：当前 profile → 其余 profile（0..count-1）。
     * 只要有一次 getParam 返回成功就采用（哪怕全是 0，也代表 DSP 确实是平直）。
     *
     * 如果失败原因是「句柄已失效」（音频服务重启过），会自动换一个新句柄再读一次。
     */
    @Synchronized
    fun readLive(): IntArray? {
        val (curve, err) = readLiveOnce()
        if (curve != null) return curve
        // 只有"死对象"才重开；读回不支持之类的错误不要乱重开（重开会触发 Dolby 下发旧值）
        if (err != null && isDeadError(err) && ensureAlive()) {
            return readLiveOnce().first
        }
        return null
    }

    /** 读一次，返回 (曲线, 错误描述) */
    private fun readLiveOnce(): Pair<IntArray?, String?> {
        var lastErr: String? = null
        return try {
            val e = session ?: openEffect().also { session = it }
            val count = runCatching { queryInt(e, COMMAND_GET_PROFILE_COUNT) }.getOrDefault(0)
            val cur = runCatching { queryInt(e, COMMAND_GET_CURRENT) }.getOrDefault(-1)
            val order = LinkedHashSet<Int>()
            if (cur in 0..31) order.add(cur)
            if (count in 1..32) for (p in 0 until count) order.add(p) else order.add(0)
            for (p in order) {
                val buf = buildCurveBuffer()
                val st = e.getParam(curveReadKey(p), buf)
                if (st >= 0) return readCurveInts(buf) to null
                lastErr = statusText(st, "读取曲线")
            }
            null to lastErr
        } catch (t: Throwable) {
            null to rootCause(t)
        }
    }

    /** 诊断用：把「读曲线」每一步的状态码拼成一行文字 */
    @Synchronized
    fun probeRead(): String {
        var effect: AudioEffect? = null
        return try {
            effect = session ?: openEffect().also { session = it }
            val count = runCatching { queryInt(effect, COMMAND_GET_PROFILE_COUNT) }.getOrDefault(-999)
            val cur = runCatching { queryInt(effect, COMMAND_GET_CURRENT) }.getOrDefault(-999)
            val sb = StringBuilder("cur=$cur count=$count")
            val n = if (count in 1..32) count else 1
            for (p in 0 until n) {
                val buf = buildCurveBuffer()
                val st = effect.getParam(curveReadKey(p), buf)
                val sum = if (st >= 0) readCurveInts(buf).sumOf { kotlin.math.abs(it) } else -1
                sb.append(" | p$p st=$st sum=$sum")
            }
            sb.toString()
        } catch (t: Throwable) {
            "probe 失败：${rootCause(t)}"
        } finally {
            if (session == null) runCatching { effect?.release() }
        }
    }

    /** 读回指定 profile 的 GEQ 曲线；不支持回读时返回 null */
    fun readCurve(profile: Int = -1): IntArray? {
        var effect: AudioEffect? = null
        return try {
            effect = openEffect()
            val target = if (profile >= 0) profile else queryInt(effect, COMMAND_GET_CURRENT)
            if (target < 0) return null
            val buf = buildCurveBuffer()
            val st = effect.getParam(curveReadKey(target), buf)
            if (st < 0) return null
            readCurveInts(buf)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { effect?.release() }
        }
    }

    /**
     * 写入曲线（一次性：打开 → 写 → 释放）。开机写回、探针用。
     * @param onlyProfile null = 写全部
     */
    fun writeCurve(curve: IntArray, onlyProfile: Int? = null): String? {
        if (curve.size != BANDS) return "曲线长度不正确（需要 $BANDS 段）"
        var effect: AudioEffect? = null
        return try {
            effect = openEffect()
            writeOn(effect, curve, onlyProfile)
        } catch (t: Throwable) {
            "写入失败：${rootCause(t)}"
        } finally {
            runCatching { effect?.release() }
        }
    }

    /** 让 Dolby 把当前参数落进自己的数据库（重启后由它自己读回） */
    fun save(): String? {
        var effect: AudioEffect? = null
        return try {
            effect = openEffect()
            saveOn(effect)
        } catch (t: Throwable) {
            "保存失败：${rootCause(t)}"
        } finally {
            runCatching { effect?.release() }
        }
    }

    // ────────────────────────────────────────────────────────────
    //  会话：拖动时复用同一个 effect 实例
    //
    //  真机现象：拖动没声音，点「保存到文件」才生效。
    //  原因：每次写都新建 + release，release 后全局 effect 实例被回收，
    //        刚写进内存的参数随之丢失；而 save 会把值提交到 Dolby 自己的
    //        存储并触发重放，所以只有那一下能听到。
    //  解决：页面内保持一个常开实例，拖动期间只写不释放。
    // ────────────────────────────────────────────────────────────

    private var session: AudioEffect? = null

    /** 打开常驻会话（进入页面时调一次） */
    @Synchronized
    fun openSession(): String? {
        if (session != null) return null
        return try {
            session = openEffect()
            null
        } catch (t: Throwable) {
            session = null
            rootCause(t)
        }
    }

    /** 关闭常驻会话（离开页面时调） */
    @Synchronized
    fun closeSession() {
        runCatching { session?.release() }
        session = null
    }

    // ────────────────────────────────────────────────────────────
    //  自愈：音频服务重启后，旧的 AudioEffect 句柄会变成"死对象"
    //  （读写都返回 ERROR_DEAD_OBJECT），必须丢掉重开一个才能继续用。
    //  重开这一瞬间 Dolby 会把"它自己存的值"下发上来，
    //  所以调用方在重开之后要立刻把我们的曲线写回去（refreshFromDevice 已在做）。
    // ────────────────────────────────────────────────────────────

    /** 这个错误是不是"句柄已失效"这一类的 */
    private fun isDeadError(msg: String): Boolean =
        msg.contains("音频服务已重启") ||
            msg.contains("控制权被") ||
            msg.contains("DEAD_OBJECT", true)

    /**
     * 探活：确认当前会话还能用；不能用就丢掉重开。
     * @return true = 现在有一个可用会话
     */
    @Synchronized
    fun ensureAlive(): Boolean {
        session?.let { e ->
            val ok = runCatching { queryInt(e, COMMAND_GET_DAP_ON) }.isSuccess
            if (ok) return true
            runCatching { e.release() }
            session = null
        }
        return try {
            val ne = openEffect()
            // 新开也要能用才算成功
            queryInt(ne, COMMAND_GET_DAP_ON)
            session = ne
            true
        } catch (t: Throwable) {
            runCatching { session?.release() }
            session = null
            false
        }
    }

    /** 用会话写一次（不含自愈） */
    private fun writeWithSession(curve: IntArray): String? = try {
        val e = session ?: openEffect().also { session = it }
        writeOn(e, curve, null)
    } catch (t: Throwable) {
        "写入失败：${rootCause(t)}"
    }

    /** 拖动时用：写全部 profile，不释放实例；句柄失效时自动重开并重试一次 */
    @Synchronized
    fun writeLive(curve: IntArray): String? {
        if (curve.size != BANDS) return "曲线长度不正确（需要 $BANDS 段）"
        val first = writeWithSession(curve)
        if (first == null) return null
        if (!isDeadError(first)) return first
        // 句柄失效（音频服务重启过）→ 换新句柄再写一次
        if (!ensureAlive()) return first
        return writeWithSession(curve)
    }

    /** 保存时用：写全部 + 提交落库，不释放实例；同样带自愈 */
    @Synchronized
    fun commit(curve: IntArray): String? {
        if (curve.size != BANDS) return "曲线长度不正确（需要 $BANDS 段）"
        val first = try {
            val e = session ?: openEffect().also { session = it }
            writeOn(e, curve, null) ?: saveOn(e)
        } catch (t: Throwable) {
            "保存失败：${rootCause(t)}"
        }
        if (first == null) return null
        if (!isDeadError(first)) return first
        if (!ensureAlive()) return first
        return try {
            val e = session!!
            writeOn(e, curve, null) ?: saveOn(e)
        } catch (t: Throwable) {
            "保存失败：${rootCause(t)}"
        }
    }

    /** 实际写入逻辑（一次性与会话共用） */
    private fun writeOn(effect: AudioEffect, curve: IntArray, onlyProfile: Int?): String? {
        if (!effect.hasControl()) return "音效控制权被其他应用占用"
        if (!effect.enabled) return "杜比音效未启用（请先在系统里打开杜比全景声）"
        if (queryInt(effect, COMMAND_GET_DAP_ON) <= 0) return "杜比全景声已关闭"

        val count = queryInt(effect, COMMAND_GET_PROFILE_COUNT)
        if (count !in 1..32) return "无法取得 profile 数量（返回 $count）"

        val targets: List<Int> = when {
            onlyProfile == null -> (0 until count).toList()
            onlyProfile in 0 until count -> listOf(onlyProfile)
            else -> (0 until count).toList()
        }
        for (p in targets) {
            val st = effect.setParam(CMD_PARAM, buildCurvePayload(p, curve))
            if (st < 0) return statusText(st, "写入 profile $p")
        }
        return null
    }

    private fun saveOn(effect: AudioEffect): String? {
        val st = effect.setParam(CMD_PARAM, buildSavePayload())
        return if (st < 0) statusText(st, "保存") else null
    }

    // ── 变更通知 ──
    // 预设加载 / 重启音频服务写回 之后，DSP 里的曲线变了，但实时层 UI 是本地状态，
    // 不会自己知道。这里放一个计数器，任何外部改动都 +1，UI 订阅它刷新。
    val reloadTick = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** 通知界面：实时层曲线已被外部改动（预设加载、重启后写回等） */
    fun notifyChanged() {
        reloadTick.value = reloadTick.value + 1
    }

    // ── 内部查询 ──
    private fun queryInt(effect: AudioEffect, command: Int): Int {
        val buf = buildQueryBuffer()
        val st = effect.getParam(queryKey(command), buf)
        if (st < 0) throw IllegalStateException(statusText(st, "查询 0x${command.toString(16)}"))
        return readFirstInt(buf)
    }

    // ── Q4 ↔ dB 换算 ──
    fun q4ToDb(q4: Int): Float = q4 / Q4_PER_DB.toFloat()
    fun dbToQ4(db: Float): Int = Math.round(db * Q4_PER_DB)
}