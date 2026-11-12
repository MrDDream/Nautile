package com.nautile.app.ui.monitor

import com.nautile.i18n.tr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.NautileApp
import com.nautile.app.session.ChannelBackend
import com.nautile.app.ui.common.NtCard
import com.nautile.app.ui.common.SectionLabel
import com.nautile.app.ui.theme.Nt
import com.nautile.ssh.ServerStats
import kotlinx.coroutines.delay

/** Live CPU / memory / disk / load of a host, polled every few seconds over SSH. */
@Composable
fun MonitorScreen(hostId: Long, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as NautileApp
    var alias by remember { mutableStateOf("") }
    var stats by remember { mutableStateOf<ServerStats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val cpuHistory = remember { mutableStateListOf<Float>() }

    LaunchedEffect(hostId) {
        alias = app.hostRepository.get(hostId)?.alias.orEmpty()
        app.sessions.sftp.acquire(hostId) // keep the connection for the whole screen
        try {
            while (true) {
                runCatching { ServerStats.parse(app.sessions.sftp.exec(hostId, ServerStats.COMMAND)) }
                    .onSuccess { s ->
                        stats = s; error = null
                        s.cpuPercent?.let { cpuHistory += it.toFloat(); if (cpuHistory.size > 40) cpuHistory.removeAt(0) }
                    }
                    .onFailure { error = ChannelBackend.describe(it) }
                delay(4_000)
            }
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { app.sessions.sftp.release(hostId) }
        }
    }

    Column(Modifier.fillMaxSize().background(Nt.Bg)) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Retour")) }
            Column {
                Text("Moniteur · $alias", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                stats?.let { Text("${it.hostname} · ${it.kernel} · ${it.cores} cœurs", fontFamily = Nt.Mono, fontSize = 11.sp, color = Nt.Muted) }
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { Text(it, color = Nt.Pink) }
            val s = stats
            if (s == null && error == null) Text(tr("Connexion…"), color = Nt.Muted)
            if (s != null) {
                NtCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row { Text("CPU", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(s.cpuPercent?.let { "%.0f %%".format(it) } ?: "—", fontFamily = Nt.Mono) }
                        Sparkline(cpuHistory)
                        Text("Charge : %.2f · %.2f · %.2f".format(s.load1, s.load5, s.load15), color = Nt.Muted, fontSize = 12.sp, fontFamily = Nt.Mono)
                    }
                }
                Gauge(tr("Mémoire"), s.memUsedPercent, "${gb(s.memTotalKb - s.memAvailableKb)} / ${gb(s.memTotalKb)}")
                if (s.swapTotalKb > 0) Gauge("Swap", 100.0 * (s.swapTotalKb - s.swapFreeKb) / s.swapTotalKb, "${gb(s.swapTotalKb - s.swapFreeKb)} / ${gb(s.swapTotalKb)}")
                Gauge("Disque ${s.diskMount}", s.diskUsedPercent, "${gb(s.diskUsedKb)} / ${gb(s.diskTotalKb)}")
                NtCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row { Text(tr("En marche depuis"), modifier = Modifier.weight(1f)); Text(uptime(s.uptimeSeconds), fontFamily = Nt.Mono) }
                    }
                }
                if (s.topProcesses.isNotEmpty()) {
                    SectionLabel(tr("Processus les plus gourmands"))
                    NtCard {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            s.topProcesses.forEach { p ->
                                Row {
                                    Text(p.command, modifier = Modifier.weight(1f), fontFamily = Nt.Mono, fontSize = 13.sp, maxLines = 1)
                                    Text("%5.1f%% cpu  %4.1f%% mem".format(p.cpu, p.mem), fontFamily = Nt.Mono, fontSize = 12.sp, color = Nt.Muted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Gauge(label: String, percent: Double, detail: String) {
    val color = when { percent >= 90 -> Nt.Pink; percent >= 75 -> Nt.Accent; else -> Nt.Green }
    NtCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row { Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text("%.0f %%".format(percent), fontFamily = Nt.Mono) }
            LinearProgressIndicator(progress = { (percent / 100).toFloat().coerceIn(0f, 1f) }, color = color, trackColor = Nt.Border, modifier = Modifier.fillMaxWidth())
            Text(detail, color = Nt.Muted, fontSize = 12.sp, fontFamily = Nt.Mono)
        }
    }
}

@Composable
private fun Sparkline(values: List<Float>) {
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        if (values.size < 2) return@Canvas
        val stepX = size.width / (40 - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width - (values.size - 1 - i) * stepX
            val y = size.height * (1f - v / 100f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawLine(Nt.Border, Offset(0f, size.height), Offset(size.width, size.height))
        drawPath(path, Nt.Accent, style = Stroke(width = 2.dp.toPx()))
    }
}

private fun gb(kb: Long) = if (kb >= 1024 * 1024) "%.1f Go".format(kb / 1024.0 / 1024) else "%.0f Mo".format(kb / 1024.0)

private fun uptime(sec: Long): String {
    val d = sec / 86_400; val h = sec % 86_400 / 3_600; val m = sec % 3_600 / 60
    return listOfNotNull(d.takeIf { it > 0 }?.let { "$it j" }, "$h h", "$m min").joinToString(" ")
}
