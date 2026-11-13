package com.nautile.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.ui.common.NtTextField
import com.nautile.app.ui.common.Segmented
import com.nautile.app.ui.theme.Nt
import com.nautile.i18n.tr
import com.nautile.security.AppLock
import com.nautile.security.LockMethod
import com.nautile.security.LockSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun methodLabel(m: LockMethod) = when (m) {
    LockMethod.BIOMETRIC -> tr("Biométrie")
    LockMethod.PIN -> tr("Code PIN")
    LockMethod.PASSWORD -> tr("Mot de passe")
}

private fun methodIcon(m: LockMethod) = when (m) {
    LockMethod.BIOMETRIC -> Icons.Outlined.Fingerprint
    LockMethod.PIN -> Icons.Outlined.Dialpad
    LockMethod.PASSWORD -> Icons.Outlined.Password
}

/** What the lock card shows: kept apart from [AppLock] so the screenshots can render it. */
internal data class LockState(
    val enabled: Boolean,
    val method: LockMethod,
    val biometricAvailable: Boolean,
    val strongBiometric: Boolean,
    val shortcut: Boolean,
    val relockAfterMs: Long,
)

private fun AppLock.state() = LockState(enabled && available, method, biometricAvailable, strongBiometricAvailable, biometricShortcut, relockAfterMs)

/** Something that waits for the current code before it happens (turning off, another method, a new code). */
private sealed interface Guarded {
    data object Disable : Guarded
    data class Switch(val to: LockMethod) : Guarded
}

/** « Verrouillage » card: on/off, Biométrie / Code PIN / Mot de passe, fingerprint shortcut, delay. */
@Composable
internal fun AppLockCard(lock: AppLock) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(lock.state()) }
    var setting by remember { mutableStateOf<LockMethod?>(null) }
    var guarded by remember { mutableStateOf<Guarded?>(null) }
    fun refresh() { state = lock.state() }

    /** Applies [g] once the current code (if any) is confirmed. */
    fun run(g: Guarded) = when (g) {
        Guarded.Disable -> { lock.enabled = false; refresh() }
        is Guarded.Switch -> when (g.to) {
            LockMethod.BIOMETRIC -> { lock.useBiometric(); lock.enabled = true; refresh() }
            else -> setting = g.to
        }
    }
    fun guard(g: Guarded) { if (state.enabled && state.method != LockMethod.BIOMETRIC) guarded = g else run(g) }

    AppLockCardContent(
        state,
        onEnable = { on ->
            when {
                !on -> guard(Guarded.Disable)
                lock.available -> { lock.enabled = true; refresh() }
                lock.biometricAvailable -> { lock.useBiometric(); lock.enabled = true; refresh() }
                else -> setting = LockMethod.PIN
            }
        },
        onMethod = { m -> if (m != state.method || !state.enabled) guard(Guarded.Switch(m)) },
        onChangeCode = { guard(Guarded.Switch(state.method)) },
        onShortcut = { lock.biometricShortcut = it; refresh() },
        onDelay = { lock.relockAfterMs = it; refresh() },
    )

    setting?.let { m ->
        CodeSetupDialog(m, onDismiss = { setting = null }) { code ->
            scope.launch {
                withContext(Dispatchers.Default) { lock.setCode(m, code) }
                lock.enabled = true
                setting = null
                refresh()
            }
        }
    }
    guarded?.let { g ->
        CodeCheckDialog(state.method, check = { lock.check(it) }, onDismiss = { guarded = null }) { guarded = null; run(g) }
    }
}

@Composable
internal fun AppLockCardContent(
    state: LockState,
    onEnable: (Boolean) -> Unit,
    onMethod: (LockMethod) -> Unit,
    onChangeCode: () -> Unit,
    onShortcut: (Boolean) -> Unit,
    onDelay: (Long) -> Unit,
) {
    ToggleRow(
        tr("Verrouiller Nautile"),
        if (state.enabled) tr("%1\$s · à l'ouverture et au retour", methodLabel(state.method))
        else tr("Demander un code ou l'empreinte pour ouvrir l'app"),
        state.enabled,
    ) { onEnable(it) }
    if (!state.enabled) return
    Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LockMethod.entries.forEach { m ->
                MethodTile(
                    m, selected = m == state.method,
                    enabled = m != LockMethod.BIOMETRIC || state.biometricAvailable,
                    modifier = Modifier.weight(1f),
                ) { onMethod(m) }
            }
        }
        Text(
            when (state.method) {
                LockMethod.BIOMETRIC -> if (state.biometricAvailable) tr("Empreinte ou visage, avec le code de l'appareil en secours.")
                    else tr("Aucune empreinte ni code configuré sur l'appareil.")
                LockMethod.PIN -> tr("Un code de 4 à 8 chiffres, propre à Nautile.")
                LockMethod.PASSWORD -> tr("Un mot de passe propre à Nautile (6 caractères minimum).")
            },
            color = Nt.Muted, fontSize = 12.sp, lineHeight = 17.sp,
        )
    }
    if (state.method != LockMethod.BIOMETRIC) {
        HorizontalDivider(color = Nt.Border)
        LinkRow(
            if (state.method == LockMethod.PIN) tr("Changer le code PIN") else tr("Changer le mot de passe"),
            tr("Le code actuel vous sera demandé"), "", onChangeCode,
        )
        if (state.strongBiometric) {
            HorizontalDivider(color = Nt.Border)
            ToggleRow(tr("Empreinte en raccourci"), tr("Déverrouiller aussi par empreinte ou visage ; le code reste possible"), state.shortcut) { onShortcut(it) }
        }
    }
    HorizontalDivider(color = Nt.Border)
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Redemander après"), color = Nt.Muted, fontSize = 12.sp)
        Segmented(
            listOf(0L to tr("Immédiat"), 60_000L to tr("1 min"), 300_000L to tr("5 min"), 900_000L to tr("15 min")),
            state.relockAfterMs, onDelay,
        )
    }
}

@Composable
private fun MethodTile(m: LockMethod, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val tint = when {
        !enabled -> Nt.Muted.copy(alpha = 0.5f)
        selected -> Nt.Accent
        else -> Nt.TextSoft
    }
    Column(
        modifier.heightIn(min = 76.dp)
            .background(if (selected) Nt.Accent.copy(alpha = 0.12f) else Nt.Bg, shape)
            .border(BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Nt.Accent else Nt.Border), shape)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(methodIcon(m), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Text(
            methodLabel(m), fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 1,
            color = if (enabled) (if (selected) Nt.Text else Nt.TextSoft) else Nt.Muted.copy(alpha = 0.6f),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Choose a PIN or a password: typed twice. */
@Composable
internal fun CodeSetupDialog(method: LockMethod, onDismiss: () -> Unit, onSet: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val pin = method == LockMethod.PIN
    val valid = if (pin) LockSecret.validPin(code) else LockSecret.validPassword(code)
    val ok = valid && code == again && !saving
    val type = if (pin) KeyboardType.NumberPassword else KeyboardType.Password
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Nt.Surface,
        title = { Text(if (pin) tr("Choisir un code PIN") else tr("Choisir un mot de passe")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (pin) tr("De 4 à 8 chiffres. Il sera demandé à chaque ouverture de Nautile.")
                    else tr("6 caractères minimum. Il sera demandé à chaque ouverture de Nautile."),
                    color = Nt.TextSoft, fontSize = 13.sp, lineHeight = 18.sp,
                )
                NtTextField(
                    code, { v -> code = if (pin) v.filter { it.isDigit() }.take(LockSecret.PIN_MAX) else v },
                    if (pin) tr("Code PIN") else tr("Mot de passe"),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = type),
                    isError = code.isNotEmpty() && !valid && again.isNotEmpty(),
                )
                NtTextField(
                    again, { v -> again = if (pin) v.filter { it.isDigit() }.take(LockSecret.PIN_MAX) else v },
                    tr("Confirmer"),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = type),
                    isError = again.isNotEmpty() && again != code,
                )
                Text(
                    tr("Oublié ? Il faudra effacer les données de l'app (vos sauvegardes restent utilisables)."),
                    color = Nt.Muted, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = {
            Button(
                onClick = { saving = true; onSet(code) }, enabled = ok,
                colors = ButtonDefaults.buttonColors(containerColor = Nt.Accent, contentColor = Nt.OnAccent),
            ) { Text(tr("Enregistrer"), fontWeight = FontWeight.SemiBold) }
        },
    )
}

/** The current PIN / password, before turning the lock off or changing it. */
@Composable
private fun CodeCheckDialog(method: LockMethod, check: (String) -> AppLock.Check, onDismiss: () -> Unit, onOk: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val pin = method == LockMethod.PIN
    fun submit() {
        busy = true
        scope.launch {
            when (val r = withContext(Dispatchers.Default) { check(code) }) {
                AppLock.Check.Ok -> onOk()
                is AppLock.Check.Wrong -> { error = tr("Code incorrect"); code = "" }
                is AppLock.Check.Wait -> { error = tr("Trop d'essais : réessayez dans %1\$s s", ((r.untilMs - System.currentTimeMillis()) / 1000).coerceAtLeast(1)); code = "" }
            }
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Nt.Surface,
        title = { Text(if (pin) tr("Code PIN actuel") else tr("Mot de passe actuel")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NtTextField(
                    code, { v -> code = if (pin) v.filter { it.isDigit() } else v; error = null },
                    if (pin) tr("Code PIN") else tr("Mot de passe"),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (pin) KeyboardType.NumberPassword else KeyboardType.Password),
                    isError = error != null,
                )
                error?.let { Text(it, color = Nt.Pink, fontSize = 12.sp) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Annuler"), color = Nt.TextSoft) } },
        confirmButton = {
            Button(
                onClick = ::submit, enabled = code.isNotEmpty() && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Nt.Accent, contentColor = Nt.OnAccent),
            ) { Text(tr("Valider"), fontWeight = FontWeight.SemiBold) }
        },
    )
}
