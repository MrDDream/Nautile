package com.nautile.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.ui.theme.Nt

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = Nt.Muted, modifier = modifier)
}

/** Rounded bordered surface used for host lists and option rows. */
@Composable
fun NtCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Nt.Surface)
            .border(BorderStroke(1.dp, Nt.Border), shape),
        content = content,
    )
}

@Composable
fun NtTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    placeholder: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    isError: Boolean = false,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, color = Nt.Placeholder) } },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine,
        isError = isError,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        textStyle = TextStyle(fontFamily = if (mono) Nt.Mono else null, fontSize = if (mono) 14.sp else 15.sp, color = Nt.Text),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = Nt.Surface,
            focusedContainerColor = Nt.Surface,
            unfocusedBorderColor = Nt.Border,
            focusedBorderColor = Nt.Accent,
            unfocusedLabelColor = Nt.TextSoft,
            focusedLabelColor = Nt.Accent,
            cursorColor = Nt.Accent,
        ),
    )
}

@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Nt.Surface)
            .border(1.dp, Nt.Border, RoundedCornerShape(14.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (on) Nt.Accent else Color.Transparent)
                    .clickable { onSelect(value) }
                    .semantics { this.selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Nt.OnAccent else Nt.TextSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Border of main buttons when the « Contour » style is chosen. */
fun Modifier.primaryBorder(shape: androidx.compose.ui.graphics.Shape): Modifier =
    Nt.PrimaryBorder?.let { this.border(2.dp, it, shape) } ?: this

/** A filter chip with its number of items; the selected one takes [color] (a group's) or the accent. */
@Composable
fun CountChip(label: String, count: Int, selected: Boolean, color: Long? = null, onClick: () -> Unit) {
    val tint = color?.let { Color(it) } ?: Nt.Accent
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) tint.copy(alpha = 0.16f) else Nt.Surface,
        border = BorderStroke(1.dp, if (selected) tint else Nt.Border),
        modifier = Modifier.heightIn(min = 40.dp).semantics { this.selected = selected },
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (color != null) Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(tint))
            Text(label, style = Centered.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = if (selected) Nt.Text else Nt.TextSoft)
            Box(
                Modifier.defaultMinSize(minWidth = 22.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (selected) tint.copy(alpha = 0.28f) else Nt.Bg).padding(horizontal = 7.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) { Text("$count", style = Centered.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = if (selected) Nt.Text else Nt.Muted) }
        }
    }
}

/** Text without Android's extra font padding, centred in its line: for badges and chips. */
val Centered = TextStyle(
    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
        alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
        trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
    ),
)
