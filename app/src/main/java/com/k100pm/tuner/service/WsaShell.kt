package com.k100pm.tuner.service

/**
 * 内核音频节点执行器.
 *
 * 通过 Runtime.exec("su -c ...") 执行 root 命令.
 * 与 Operit (此 AI APP) 相同机制——用户在 Magisk 永久授权后不再弹窗.
 * APP 代码中无 Shizuku 依赖,无第三方 root 库,极简安全.
 */
object WsaShell {

    /** 执行 root 命令（公开接口,供 ModuleDetector 使用） */
    fun execSyncCmd(cmd: String): String = execSync(cmd)

    private fun execSync(cmd: String): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(); proc.destroy()
            output.trim()
        } catch (e: Exception) { "" }
    }

    fun getTinymix(id: Int): String {
        val raw = execSync("tinymix $id 2>/dev/null")
        if (raw.isBlank()) return "N/A"
        return parseTinymixValue(raw)
    }

    /**
     * 批量读取多个 tinymix 控件：**一次 su** 拿回全部值。
     *
     * 27 个控件逐个读 = 27 次 fork su 进程（每次 15~40ms），合计 0.5~1s。
     * 这里用 `echo '##id##'; tinymix id` 的标记分段，一次执行全部读完。
     */
    fun getTinymixBatch(ids: List<Int>): Map<Int, String> {
        if (ids.isEmpty()) return emptyMap()
        val cmd = ids.joinToString(" ; ") { "echo '##$it##' ; tinymix $it 2>/dev/null" }
        val raw = execSync(cmd)
        val out = HashMap<Int, String>(ids.size)
        var curId = -1
        val buf = StringBuilder()
        fun flush() {
            if (curId >= 0) out[curId] = parseTinymixValue(buf.toString())
            buf.setLength(0)
        }
        for (line in raw.lines()) {
            val t = line.trim()
            val m = if (t.startsWith("##") && t.endsWith("##"))
                t.substring(2, t.length - 2).toIntOrNull() else null
            if (m != null) { flush(); curId = m } else if (curId >= 0) { buf.append(line).append('\n') }
        }
        flush()
        for (id in ids) if (!out.containsKey(id)) out[id] = "N/A"
        return out
    }

    /** tinymix 输出 → 纯值（兼容 `id > value` / `id: value` / 裸值三种格式） */
    private fun parseTinymixValue(raw: String): String {
        if (raw.isBlank()) return "N/A"
        val arrowIdx = raw.indexOf('>')
        if (arrowIdx >= 0) return raw.substring(arrowIdx + 1).takeWhile { it != ' ' && it != '\t' && it != '\n' }
        val colonIdx = raw.indexOf(':')
        if (colonIdx >= 0) {
            val afterColon = raw.substring(colonIdx + 1).trimStart()
            return afterColon.takeWhile { it != ' ' && it != '\t' && it != '(' && it != '\n' }
        }
        return raw.trim()
    }

    fun setTinymix(id: Int, value: String): Boolean {
        val normalized = when {
            value.equals("On", ignoreCase = true) -> "1"
            value.equals("Off", ignoreCase = true) -> "0"
            else -> value
        }
        val result = execSync("tinymix $id $normalized 2>&1")
        return !result.contains("Error", true) && !result.contains("invalid", true)
    }

    /** 用户已在 Magisk 永久授权 → su 不弹窗,返回 true */
    fun hasRoot(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo OK"))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(); p.destroy()
            out.startsWith("OK")
        } catch (_: Exception) { false }
    }
}