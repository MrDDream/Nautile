package com.nautile.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.ui.common.NtTextField
import com.nautile.app.ui.theme.Nt
import com.nautile.i18n.tr
import com.nautile.security.AppLock
import com.nautile.security.LockMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What covers the app while it is locked.
 * [check] verifies a typed code (run off the main thread); [onBiometric] opens the fingerprint prompt.
 */
@Composable
fun LockedScreen(
    method: LockMethod,
    error: String?,
    canBiometric: Boolean,
    onBiometric: () -> Unit,
    check: (String) -> AppLock.Check = { AppLock.Check.Ok },
    pinLength: Int = 0,
    initialWaitUntil: Long = 0L,
    onUnlocked: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var waitUntil by remember { mutableLongStateOf(initialWaitUntil) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val waitS = ((waitUntil - now + 999) / 1000).coerceAtLeast(0)
    LaunchedEffect(waitUntil) { while (System.currentTimeMillis() < waitUntil) { now = System.currentTimeMillis(); delay(250) }; now = System.currentTimeMillis() }

    fun submit(value: String) {
        if (checking || value.isEmpty() || waitS > 0) return
        checking = true
        scope.launch {
            when (val r = withContext(Dispatchers.Default) { check(value) }) {
                AppLock.Check.Ok -> { wrong = null; onUnlocked() }
                is AppLock.Check.Wrong -> {
                    wrong = if (r.triesLeft <= 2) tr("Code incorrect · %1\$s essai(s) avant une attente", r.triesLeft) else tr("Code incorrect")
                    code = ""
                }
                is AppLock.Check.Wait -> { wrong = null; code = ""; waitUntil = r.untilMs; now = System.currentTimeMillis() }
            }
            checking = false
        }
    }

    val message = when {
        waitS > 0 -> tr("Trop d'essais : réessayez dans %1\$s s", waitS)
        wrong != null -> wrong
        !error.isNullOrBlank() -> error
        else -> null
    }

    // Opaque: it may cover the app when it relocks.
    Box(Modifier.fillMaxSize().background(Nt.Bg).systemBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 360.dp).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(64.dp).background(Nt.Accent.copy(alpha = 0.14f), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = Nt.Accent, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(18.dp))
            Text(tr("Nautile est verrouillée"), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                when (method) {
                    LockMethod.PIN -> tr("Saisissez votre code PIN")
                    LockMethod.PASSWORD -> tr("Saisissez votre mot de passe")
                    LockMethod.BIOMETRIC -> tr("Empreinte, visage ou code de l'appareil")
                },
                color = Nt.Muted, fontSize = 14.sp,
            )

            when (method) {
                LockMethod.PIN -> {
                    Spacer(Modifier.height(28.dp))
                    PinDots(code.length, pinLength, error = wrong != null)
                    Message(message)
                    PinPad(
                        enabled = waitS == 0L && !checking,
                        canBiometric = canBiometric,
                        onDigit = { d ->
                            if (code.length < com.nautile.security.LockSecret.PIN_MAX) {
                                code += d; wrong = null
                                if (pinLength > 0 && code.length == pinLength) submit(code)
                            }
                        },
                        onBack = { code = code.dropLast(1) },
                        onBiometric = onBiometric,
                        onOk = if (pinLength == 0) ({ submit(code) }) else null,
                    )
                }
                LockMethod.PASSWORD -> {
                    Spacer(Modifier.height(24.dp))
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    NtTextField(
                        code, { code = it; wrong = null }, tr("Mot de passe"),
                        modifier = Modifier.focusRequester(focus),
                        visualTransformation = PasswordVisualTransformation(),
                        isError = wrong != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit(code) }),
                    )
                    Message(message)
                    Button(
                        onClick = { submit(code) },
                        enabled = code.isNotEmpty() && waitS == 0L && !checking,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Nt.Accent, contentColor = Nt.OnAccent),
                    ) { Text(tr("Déverrouiller"), fontWeight = FontWeight.SemiBold) }
                    if (canBiometric) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onBiometric, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Outlined.Fingerprint, contentDescription = null, tint = Nt.Accent, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(tr("Utiliser l'empreinte"), color = Nt.Text)
                        }
                    }
                }
                LockMethod.BIOMETRIC -> {
                    Message(message)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onBiometric,
                        modifier = Modifier.height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Nt.Accent, contentColor = Nt.OnAccent),
                    ) {
                        Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(tr("Déverrouiller"), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String?) {
    Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
        if (text != null) Text(text, color = Nt.Pink, fontSize = 13.sp, maxLines = 2)
    }
}

/** One dot per digit; the expected length, when known, as empty rings. */
@Composable
private fun PinDots(typed: Int, length: Int, error: Boolean) {
    val total = maxOf(length, typed, 4)
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.height(16.dp).semantics { contentDescription = tr("%1\$s chiffre(s) saisi(s)", typed) },
    ) {
        repeat(total) { i ->
            val filled = i < typed
            val color = if (error) Nt.Pink else Nt.Accent
            Box(
                Modifier.size(14.dp).then(
                    if (filled) Modifier.background(color, CircleShape) else Modifier.border(1.5.dp, Nt.Border, CircleShape),
                ),
            )
        }
    }
}

@Composable
private fun PinPad(
    enabled: Boolean,
    canBiometric: Boolean,
    onDigit: (Char) -> Unit,
    onBack: () -> Unit,
    onBiometric: () -> Unit,
    onOk: (() -> Unit)?,
) {
    val rows = listOf("123", "456", "789")
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) { r.forEach { d -> DigitKey(d, enabled) { onDigit(d) } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            if (canBiometric) IconKey(Icons.Outlined.Fingerprint, tr("Utiliser l'empreinte"), true, Nt.Accent, onBiometric)
            else Spacer(Modifier.size(KEY))
            DigitKey('0', enabled) { onDigit('0') }
            IconKey(Icons.AutoMirrored.Outlined.Backspace, tr("Effacer"), enabled, Nt.TextSoft, onBack)
        }
        if (onOk != null) Button(
            onClick = onOk, enabled = enabled,
            modifier = Modifier.padding(top = 4.dp).height(48.dp).widthIn(min = 200.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Nt.Accent, contentColor = Nt.OnAccent),
        ) { Text(tr("Déverrouiller"), fontWeight = FontWeight.SemiBold) }
    }
}

private val KEY = 72.dp

@Composable
private fun DigitKey(d: Char, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(KEY).background(Nt.Surface, CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(d.toString(), fontSize = 28.sp, color = if (enabled) Nt.Text else Nt.Muted, style = com.nautile.app.ui.common.Centered) }
}

@Composable
private fun IconKey(icon: ImageVector, label: String, enabled: Boolean, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier.size(KEY).background(androidx.compose.ui.graphics.Color.Transparent, CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(26.dp)) }
}
