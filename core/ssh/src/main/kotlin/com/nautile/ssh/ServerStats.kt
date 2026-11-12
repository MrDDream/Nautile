package com.nautile.ssh

/** A snapshot of a Linux server's health, read over SSH from /proc and a few tools. */
data class ServerStats(
    val hostname: String,
    val kernel: String,
    val cores: Int,
    /** 0..100 over the sampling second, null if unavailable. */
    val cpuPercent: Double?,
    val load1: Double, val load5: Double, val load15: Double,
    val memTotalKb: Long, val memAvailableKb: Long,
    val swapTotalKb: Long, val swapFreeKb: Long,
    val diskTotalKb: Long, val diskUsedKb: Long, val diskMount: String,
    val uptimeSeconds: Long,
    val topProcesses: List<Proc>,
) {
    data class Proc(val pid: Int, val command: String, val cpu: Double, val mem: Double)

    val memUsedPercent: Double get() = if (memTotalKb > 0) 100.0 * (memTotalKb - memAvailableKb) / memTotalKb else 0.0
    val diskUsedPercent: Double get() = if (diskTotalKb > 0) 100.0 * diskUsedKb / diskTotalKb else 0.0

    companion object {
        /** One round trip; the CPU figure comes from two /proc/stat samples one second apart. */
        const val COMMAND = "LC_ALL=C; hostname; echo @@; uname -sr; echo @@; nproc 2>/dev/null || grep -c ^processor /proc/cpuinfo; echo @@; " +
            "head -1 /proc/stat; sleep 1; head -1 /proc/stat; echo @@; cat /proc/loadavg; echo @@; cat /proc/meminfo; echo @@; " +
            "df -Pk / | tail -1; echo @@; cat /proc/uptime; echo @@; ps -eo pid,pcpu,pmem,comm --sort=-pcpu 2>/dev/null | head -6"

        fun parse(output: String): ServerStats {
            val parts = output.split("@@").map { it.trim() }
            fun part(i: Int) = parts.getOrElse(i) { "" }
            val cpuLines = part(3).lines().filter { it.startsWith("cpu ") }
            val cpu = if (cpuLines.size == 2) cpuPercent(cpuLines[0], cpuLines[1]) else null
            val load = part(4).split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
            val mem = part(5).lines().associate { l ->
                val k = l.substringBefore(':').trim()
                k to (l.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L)
            }
            val df = part(6).split(Regex("\\s+"))
            val procs = part(8).lines().drop(1).mapNotNull { l ->
                val f = l.trim().split(Regex("\\s+"), limit = 4)
                if (f.size < 4) null else ServerStats.Proc(f[0].toIntOrNull() ?: return@mapNotNull null, f[3], f[1].toDoubleOrNull() ?: 0.0, f[2].toDoubleOrNull() ?: 0.0)
            }
            return ServerStats(
                hostname = part(0), kernel = part(1), cores = part(2).toIntOrNull() ?: 1,
                cpuPercent = cpu,
                load1 = load.getOrElse(0) { 0.0 }, load5 = load.getOrElse(1) { 0.0 }, load15 = load.getOrElse(2) { 0.0 },
                memTotalKb = mem["MemTotal"] ?: 0, memAvailableKb = mem["MemAvailable"] ?: mem["MemFree"] ?: 0,
                swapTotalKb = mem["SwapTotal"] ?: 0, swapFreeKb = mem["SwapFree"] ?: 0,
                diskTotalKb = df.getOrNull(1)?.toLongOrNull() ?: 0, diskUsedKb = df.getOrNull(2)?.toLongOrNull() ?: 0,
                diskMount = df.getOrNull(5) ?: "/",
                uptimeSeconds = part(7).substringBefore(' ').toDoubleOrNull()?.toLong() ?: 0,
                topProcesses = procs,
            )
        }

        internal fun cpuPercent(a: String, b: String): Double? {
            fun nums(l: String) = l.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            val x = nums(a); val y = nums(b)
            if (x.size < 4 || y.size < 4) return null
            val idle = { v: List<Long> -> v[3] + v.getOrElse(4) { 0 } }
            val total = (y.sum() - x.sum()).toDouble()
            if (total <= 0) return 0.0
            return (100.0 * (total - (idle(y) - idle(x))) / total).coerceIn(0.0, 100.0)
        }
    }
}
