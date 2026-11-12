package com.nautile.app.widget

import com.nautile.i18n.tr

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.nautile.app.NautileApp
import com.nautile.app.MainActivity
import com.nautile.app.R
import kotlinx.coroutines.flow.first

/** Home-screen widget: the most recently used hosts, one tap to connect. */
class HostsWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as NautileApp
        val hosts = runCatching {
            app.hostRepository.hosts.first().sortedByDescending { it.lastConnectedAt ?: 0 }.take(8)
        }.getOrDefault(emptyList())
        provideContent {
            Column(GlanceModifier.fillMaxSize().background(BG).cornerRadius(20.dp).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.padding(bottom = 6.dp)) {
                    Image(ImageProvider(R.drawable.ic_logo_mark), contentDescription = "Nautile", modifier = GlanceModifier.size(20.dp))
                    Spacer(GlanceModifier.width(8.dp))
                    Text("nautile", style = TextStyle(color = ColorProvider(TEXT), fontWeight = FontWeight.Medium, fontSize = 15.sp, fontFamily = androidx.glance.text.FontFamily.Monospace))
                    Text("_", style = TextStyle(color = ColorProvider(Color(0xFFFFB547)), fontWeight = FontWeight.Medium, fontSize = 15.sp, fontFamily = androidx.glance.text.FontFamily.Monospace))
                }
                if (hosts.isEmpty()) Text(tr("Aucun hôte"), style = TextStyle(color = ColorProvider(MUTED), fontSize = 13.sp))
                LazyColumn {
                    items(hosts, itemId = { it.id }) { h ->
                        Column(
                            GlanceModifier.fillMaxWidth().padding(vertical = 3.dp)
                                .clickable(actionStartActivity(MainActivity.connectIntent(context, h.id))),
                        ) {
                            Column(GlanceModifier.fillMaxWidth().background(SURFACE).cornerRadius(12.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(h.alias, style = TextStyle(color = ColorProvider(TEXT), fontWeight = FontWeight.Medium, fontSize = 14.sp), maxLines = 1)
                                Text(h.address, style = TextStyle(color = ColorProvider(MUTED), fontSize = 11.sp), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val BG = Color(0xFF0B0E12)
        private val SURFACE = Color(0xFF141920)
        private val TEXT = Color(0xFFE8EDF2)
        private val MUTED = Color(0xFF94A1AE)

        suspend fun refresh(context: Context) {
            if (GlanceAppWidgetManager(context).getGlanceIds(HostsWidget::class.java).isNotEmpty()) HostsWidget().updateAll(context)
        }
    }
}

class HostsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HostsWidget()
}
