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
        val arrowIdx = raw.indexOf('>')
        if (arrowIdx >= 0) return raw.substring(arrowIdx + 1).takeWhile { it != ' ' && it != '\t' && it != '\n' }
        val colonIdx = raw.indexOf(':')
        if (colonIdx >= 0) {
            val afterColon = raw.substring(colonIdx + 1).trimStart()
            return afterColon.takeWhile { it != ' ' && it != '\t' && it != '(' && it != '\n' }
        }
        return raw
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