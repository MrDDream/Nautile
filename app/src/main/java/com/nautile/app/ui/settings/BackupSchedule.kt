package com.nautile.app.ui.settings

import com.nautile.i18n.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.nautile.app.ui.common.Segmented
import com.nautile.app.ui.theme.Nt
import com.nautile.i18n.I18n
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * When the automatic backup runs (recurrence, time, weekday) and on what conditions (network,
 * charging, battery). [scheduleShown] is false with synchronisation on: it runs every hour then,
 * but the conditions still apply.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun BackupSchedule(prefs: BackupPrefs, scheduleShown: Boolean, needsNetwork: Boolean, onChange: () -> Unit) {
    val frequency by prefs.frequency.collectAsState()
    val time by prefs.time.collectAsState()
    val weekday by prefs.weekday.collectAsState()
    val network by prefs.network.collectAsState()
    val charging by prefs.charging.collectAsState()
    val batteryOk by prefs.batteryOk.collectAsState()
    val last by prefs.lastExport.collectAsState()
    var pickTime by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Nt.Bg).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (scheduleShown) {
            Label(tr("Récurrence"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    BackupFrequency.HOURS_6 to tr("6 h"), BackupFrequency.HOURS_12 to tr("12 h"), BackupFrequency.DAY to tr("Jour"),
                    BackupFrequency.WEEK to tr("Semaine"), BackupFrequency.MONTH to tr("Mois"),
                ).forEach { (f, label) -> Pill(label, f == frequency) { prefs.setFrequency(f); onChange() } }
            }
            if (frequency.atTime) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Label(tr("Heure"), Modifier.weight(1f))
                    Row(
                        Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, Nt.Accent, RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { pickTime = true }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = Nt.Accent, modifier = Modifier.size(18.dp))
                        Text(clock(time), color = Nt.Accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = Nt.Mono)
                    }
                }
            }
            if (frequency == BackupFrequency.WEEK) {
                Label(tr("Jour"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    (1..7).forEach { d ->
                        val on = d == weekday
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).background(if (on) Nt.Accent else Color.Transparent)
                                .border(1.dp, if (on) Nt.Accent else Nt.Border, CircleShape)
                                .clickable(role = Role.RadioButton) { prefs.setWeekday(d); onChange() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(dayLetter(d), color = if (on) Nt.OnAccent else Nt.TextSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            Text(
                tr("Prochaine sauvegarde : %1\$s", whenText(AutoBackup.nextRun(frequency, time, weekday, last))),
                color = Nt.TextSoft, fontSize = 13.sp,
            )
        }

        Label(tr("Conditions"))
        if (needsNetwork) Segmented(
            listOf(BackupNetwork.WIFI to tr("Wi-Fi seulement"), BackupNetwork.ANY to tr("Wi-Fi ou mobile")),
            network, { prefs.setNetwork(it); onChange() },
        ) else Text(tr("Fichier sur le téléphone : aucun réseau nécessaire."), color = Nt.Muted, fontSize = 12.sp)
        Condition(Icons.Outlined.BatteryChargingFull, tr("Seulement en charge"), charging) { prefs.setCharging(it); onChange() }
        Condition(Icons.Outlined.BatteryStd, tr("Pas si la batterie est faible"), batteryOk) { prefs.setBatteryOk(it); onChange() }
        Text(
            if (scheduleShown) tr("Si une condition manque à l'heure prévue, Android lance la sauvegarde dès qu'elle est remplie.")
            else tr("La synchronisation passe toutes les heures, quand ces conditions sont remplies."),
            color = Nt.Muted, fontSize = 12.sp, lineHeight = 16.sp,
        )
    }

    if (pickTime) {
        val state = rememberTimePickerState(initialHour = time / 60, initialMinute = time % 60, is24Hour = !I18n.english)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            containerColor = Nt.Surface,
            title = { Text(tr("Heure de la sauvegarde")) },
            text = {
                TimePicker(
                    state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = Nt.Bg, selectorColor = Nt.Accent, clockDialSelectedContentColor = Nt.OnAccent,
                        timeSelectorSelectedContainerColor = Nt.Accent.copy(alpha = 0.18f), timeSelectorSelectedContentColor = Nt.Accent,
                        timeSelectorUnselectedContainerColor = Nt.Bg, periodSelectorSelectedContainerColor = Nt.Accent.copy(alpha = 0.18f),
                    ),
                )
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(tr("Annuler"), color = Nt.TextSoft) } },
            confirmButton = {
                TextButton(onClick = { prefs.setTime(state.hour * 60 + state.minute); pickTime = false; onChange() }) {
                    Text(tr("OK"), color = Nt.Accent, fontWeight = FontWeight.SemiBold)
                }
            },
        )
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) =
    Text(text, color = Nt.TextSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)

@Composable
private fun Pill(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp))
            .background(if (on) Nt.Accent else Color.Transparent)
            .border(1.dp, if (on) Nt.Accent else Nt.Border, RoundedCornerShape(18.dp))
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (on) Nt.OnAccent else Nt.TextSoft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Condition(icon: ImageVector, title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Nt.TextSoft, modifier = Modifier.size(20.dp))
        Text(title, fontSize = 14.sp, color = Nt.Text, modifier = Modifier.weight(1f))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Nt.Accent, uncheckedTrackColor = Nt.Toggle))
    }
}

private fun clock(minutes: Int): String =
    if (I18n.english) {
        val h = minutes / 60
        "%d:%02d %s".format(Locale.ROOT, (h + 11) % 12 + 1, minutes % 60, if (h < 12) "AM" else "PM")
    } else "%02d:%02d".format(Locale.ROOT, minutes / 60, minutes % 60)

private fun dayLetter(isoDay: Int): String =
    (if (I18n.english) listOf("M", "T", "W", "T", "F", "S", "S") else listOf("L", "M", "M", "J", "V", "S", "D"))[isoDay - 1]

/** « aujourd'hui à 03:00 », « demain à 03:00 », « dim. 16 nov. à 03:00 ». */
internal fun whenText(at: Long): String {
    val zone = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(at).atZone(zone)
    val clock = clock(t.hour * 60 + t.minute)
    val days = ChronoUnit.DAYS.between(LocalDate.now(zone), t.toLocalDate())
    val locale = if (I18n.english) Locale.ENGLISH else Locale.FRENCH
    return when (days) {
        0L -> tr("aujourd'hui à %1\$s", clock)
        1L -> tr("demain à %1\$s", clock)
        else -> tr("%1\$s à %2\$s", DateTimeFormatter.ofPattern("EEE d MMM", locale).format(t), clock)
    }
}
