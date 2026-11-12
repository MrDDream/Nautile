package com.nautile.app.ui.common

import com.nautile.i18n.tr

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.R
import com.nautile.app.ui.theme.Nt

/** The « nautile_ » wordmark: spiral mark, name in JetBrains Mono, amber cursor. */
@Composable
fun NautileWordmark(modifier: Modifier = Modifier, fontSize: TextUnit = 26.sp) {
    Row(
        modifier.clearAndSetSemantics { contentDescription = tr("Nautile") },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy((fontSize.value * 0.4f).dp),
    ) {
        Image(painterResource(R.drawable.ic_logo_mark), contentDescription = null, modifier = Modifier.height((fontSize.value * 1.25f).dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("nautile", fontFamily = Nt.Mono, fontWeight = FontWeight.Medium, fontSize = fontSize, color = Nt.Text)
            Text("_", fontFamily = Nt.Mono, fontWeight = FontWeight.Medium, fontSize = fontSize, color = Nt.Accent)
        }
    }
}
