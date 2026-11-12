package com.nautile.app.ui.settings

import com.nautile.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.backup.AutoBackup
import com.nautile.app.backup.BackupFrequency
import com.nautile.app.backup.BackupNetwork
import com.nautile.app.backup.BackupPrefs
import com.nautile.app.ui.theme.Nt
import com.nautile.i18n.I18n
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

private enum class Pick { FREQUENCY, TIME, NETWORK }

/**
 * The automatic backup's options as a short list: each row shows its value and opens a small
 * picker. The next run sits on top, so the effect of a change is visible at once.
 */
@Composable
internal fun BackupOptions(prefs: BackupPrefs, needsNetwork: Boolean, onChange: () -> Unit) {
    val frequency by prefs.frequency.collectAsState()
    val time by prefs.time.collectAsState()
    val weekday by prefs.weekday.collectAsState()
    val network by prefs.network.collectAsState()
    val charging by prefs.charging.collectAsState()
    val last by prefs.lastExport.collectAsState()
    var pick by remember { mutableStateOf<Pick?>(null) }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Nt.Bg)) {
        Row(
            Modifier.fillMaxWidth().background(Nt.Accent.copy(alpha = 0.10f)).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Event, contentDescription = null, tint = Nt.Accent, modifier = Modifier.size(20.dp))
            Column {
                Text(tr("Prochaine sauvegarde"), color = Nt.Muted, fontSize = 12.sp)
                Text(whenText(AutoBackup.nextRun(frequency, time, weekday, last)).replaceFirstChar { it.uppercase() },
                    color = Nt.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        OptionRow(Icons.Outlined.Repeat, tr("Fréquence"), frequencyLabel(frequency).replaceFirstChar { it.uppercase() }) { pick = Pick.FREQUENCY }
        if (frequency.atTime) {
            Divider()
            OptionRow(
                Icons.Outlined.Schedule, if (frequency == BackupFrequency.WEEK) tr("Quand") else tr("Heure"),
                if (frequency == BackupFrequency.WEEK) tr("%1\$s à %2\$s", dayName(weekday), clock(time)) else clock(time),
            ) { pick = Pick.TIME }
        }
        if (needsNetwork) {
            Divider()
            OptionRow(Icons.Outlined.Wifi, tr("Réseau"), networkLabel(network)) { pick = Pick.NETWORK }
        }
        Divider()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Switch) { prefs.setCharging(!charging); onChange() }
                .padding(start = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.BatteryChargingFull, contentDescription = null, tint = Nt.TextSoft, modifier = Modifier.size(20.dp))
            Text(tr("Seulement en charge"), fontSize = 15.sp, color = Nt.Text, modifier = Modifier.weight(1f))
            Switch(charging, { prefs.setCharging(it); onChange() }, colors = SwitchDefaults.colors(checkedTrackColor = Nt.Accent, uncheckedTrackColor = Nt.Toggle))
        }
    }

    when (pick) {
        Pick.FREQUENCY -> ChoiceDialog(
            tr("Fréquence"),
            BackupFrequency.entries.map { it to frequencyLabel(it).replaceFirstChar { c -> c.uppercase() } },
            frequency, onDismiss = { pick = null },
        ) { prefs.setFrequency(it); pick = null; onChange() }
        Pick.NETWORK -> ChoiceDialog(
            tr("Réseau"),
            BackupNetwork.entries.map { it to networkLabel(it) },
            network, onDismiss = { pick = null },
            detail = { if (it == BackupNetwork.WIFI) tr("Attend un Wi-Fi : aucune donnée mobile consommée") else tr("Wi-Fi ou 4G/5G, dès qu'une connexion est disponible") },
        ) { prefs.setNetwork(it); pick = null; onChange() }
        Pick.TIME -> TimeDialog(
            initial = time, weekday = weekday.takeIf { frequency == BackupFrequency.WEEK },
            onDismiss = { pick = null },
        ) { minutes, day ->
            prefs.setTime(minutes); if (day != null) prefs.setWeekday(day)
            pick = null; onChange()
        }
        null -> {}
    }
}

/** Synchronisation: how often in the background (it also follows each change) and on which network. */
@Composable
internal fun SyncOptions(prefs: BackupPrefs, needsNetwork: Boolean, onChange: () -> Unit) {
    val network by prefs.network.collectAsState()
    val minutes by prefs.syncMinutes.collectAsState()
    var pick by remember { mutableStateOf<Pick?>(null) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Nt.Bg)) {
        OptionRow(Icons.Outlined.Repeat, tr("Fréquence"), syncLabel(minutes)) { pick = Pick.FREQUENCY }
        if (needsNetwork) {
            Divider()
            OptionRow(Icons.Outlined.Wifi, tr("Réseau"), networkLabel(network)) { pick = Pick.NETWORK }
        }
    }
    when (pick) {
        Pick.FREQUENCY -> ChoiceDialog(
            tr("Synchroniser"), BackupPrefs.SYNC_CHOICES.map { it to syncLabel(it) }, minutes, onDismiss = { pick = null },
            detail = { if (it == 15) tr("Le plus réactif, un peu plus de batterie") else null },
        ) { prefs.setSyncMinutes(it); pick = null; onChange() }
        Pick.NETWORK -> ChoiceDialog(
            tr("Réseau"), BackupNetwork.entries.map { it to networkLabel(it) }, network, onDismiss = { pick = null },
            detail = { if (it == BackupNetwork.WIFI) tr("Attend un Wi-Fi : aucune donnée mobile consommée") else tr("Wi-Fi ou 4G/5G, dès qu'une connexion est disponible") },
        ) { prefs.setNetwork(it); pick = null; onChange() }
        else -> {}
    }
}

private fun syncLabel(minutes: Int) = when {
    minutes < 60 -> tr("Toutes les %1\$s min", minutes)
    minutes == 60 -> tr("Toutes les heures")
    minutes < 1440 -> tr("Toutes les %1\$s h", minutes / 60)
    else -> tr("Une fois par jour")
}

@Composable
private fun OptionRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Nt.TextSoft, modifier = Modifier.size(20.dp))
        Text(title, fontSize = 15.sp, color = Nt.Text, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = Nt.Accent, fontWeight = FontWeight.Medium)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = Nt.Muted)
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = Nt.Border, modifier = Modifier.padding(start = 46.dp))

@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onDismiss: () -> Unit,
    detail: ((T) -> String?)? = null,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nt.Surface,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.RadioButton) { onPick(value) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(value == selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Nt.Accent),
                            modifier = Modifier.padding(end = 12.dp))
                        Column {
                            Text(label, fontSize = 15.sp, color = Nt.Text)
                            detail?.invoke(value)?.let { Text(it, fontSize = 12.sp, color = Nt.Muted) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Fermer"), color = Nt.TextSoft) } },
    )
}

/** Time of the backup and, for a weekly one, its day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(initial: Int, weekday: Int?, onDismiss: () -> Unit, onPick: (Int, Int?) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = !I18n.english)
    var day by remember { mutableStateOf(weekday) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nt.Surface,
        title = { Text(if (weekday != null) tr("Jour et heure") else tr("Heure de la sauvegarde")) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                day?.let { d ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        (1..7).forEach { n ->
                            val on = n == d
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).background(if (on) Nt.Accent else Color.Transparent)
                                    .border(1.dp, if (on) Nt.Accent else Nt.Border, CircleShape)
                                    .clickable(role = Role.RadioButton) { day = n },
                                contentAlignment = Alignment.Center,
                            ) { Text(dayLetter(n), color = if (on) Nt.OnAccent else Nt.TextSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
                TimePicker(
                    state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = Nt.Bg, selectorColor = Nt.Accent, clockDialSelectedContentColor = Nt.OnAccent,
                        timeSelectorSelectedContainerColor = Nt.Accent.copy(alpha = 0.18f), timeSelectorSelectedContentColor = Nt.Accent,
                        timeSelectorUnselectedContainerColor = Nt.Bg, periodSelectorSelectedContainerColor = Nt.Accent.copy(alpha = 0.18f),
                    ),
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = {
            TextButton(onClick = { onPick(state.hour * 60 + state.minute, day) }) { Text(tr("OK"), color = Nt.Accent, fontWeight = FontWeight.SemiBold) }
        },
    )
}

internal fun frequencyLabel(f: BackupFrequency) = when (f) {
    BackupFrequency.HOURS_6 -> tr("toutes les 6 heures")
    BackupFrequency.HOURS_12 -> tr("toutes les 12 heures")
    BackupFrequency.DAY -> tr("chaque jour")
    BackupFrequency.WEEK -> tr("chaque semaine")
    BackupFrequency.MONTH -> tr("chaque mois")
}

private fun networkLabel(n: BackupNetwork) = when (n) {
    BackupNetwork.WIFI -> tr("Wi-Fi seulement")
    BackupNetwork.ANY -> tr("Wi-Fi ou mobile")
}

private fun locale() = if (I18n.english) Locale.ENGLISH else Locale.FRENCH

private fun clock(minutes: Int): String =
    if (I18n.english) {
        val h = minutes / 60
        "%d:%02d %s".format(Locale.ROOT, (h + 11) % 12 + 1, minutes % 60, if (h < 12) "AM" else "PM")
    } else "%02d:%02d".format(Locale.ROOT, minutes / 60, minutes % 60)

private fun dayName(isoDay: Int): String = DayOfWeek.of(isoDay).getDisplayName(TextStyle.FULL, locale()).replaceFirstChar { it.uppercase() }

private fun dayLetter(isoDay: Int): String =
    (if (I18n.english) listOf("M", "T", "W", "T", "F", "S", "S") else listOf("L", "M", "M", "J", "V", "S", "D"))[isoDay - 1]

/** « aujourd'hui à 03:00 », « demain à 03:00 », « dim. 16 nov. à 03:00 ». */
internal fun whenText(at: Long): String {
    val zone = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(at).atZone(zone)
    val clock = clock(t.hour * 60 + t.minute)
    val days = ChronoUnit.DAYS.between(LocalDate.now(zone), t.toLocalDate())
    return when (days) {
        0L -> tr("aujourd'hui à %1\$s", clock)
        1L -> tr("demain à %1\$s", clock)
        else -> tr("%1\$s à %2\$s", DateTimeFormatter.ofPattern("EEEE d MMMM", locale()).format(t), clock)
    }
}

/** « Aujourd'hui à 03:00 », « Hier à 03:00 », « Dimanche 15 novembre à 03:00 », for a past moment. */
internal fun pastText(at: Long): String {
    val zone = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(at).atZone(zone)
    val clock = clock(t.hour * 60 + t.minute)
    val days = ChronoUnit.DAYS.between(t.toLocalDate(), LocalDate.now(zone))
    return when (days) {
        0L -> tr("Aujourd'hui à %1\$s", clock)
        1L -> tr("Hier à %1\$s", clock)
        else -> tr("%1\$s à %2\$s", DateTimeFormatter.ofPattern("EEEE d MMMM", locale()).format(t), clock).replaceFirstChar { it.uppercase() }
    }
}
