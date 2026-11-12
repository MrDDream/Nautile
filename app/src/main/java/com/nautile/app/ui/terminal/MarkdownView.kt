package com.nautile.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nautile.app.ui.theme.Nt

/** A piece of an answer: what a chat model writes, cut into blocks a phone can lay out. */
internal sealed interface MdBlock {
    data class Heading(val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Item(val marker: String, val text: String, val depth: Int) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
}

internal fun parseMarkdown(text: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim()); para.clear() }
    val lines = text.lines()
    var i = 0
    val bullet = Regex("^(\\s*)([-*•+])\\s+(.*)$")
    val number = Regex("^(\\s*)(\\d+[.)])\\s+(.*)$")
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()
        when {
            t.startsWith("```") -> {
                flush()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { code.appendLine(lines[i]); i++ }
                out += MdBlock.Code(code.toString().trimEnd())
            }
            t.isEmpty() -> flush()
            t.startsWith("#") -> { flush(); out += MdBlock.Heading(t.trimStart('#').trim()) }
            t.startsWith(">") -> { flush(); out += MdBlock.Quote(t.trimStart('>').trim()) }
            bullet.matches(line) -> { flush(); bullet.find(line)!!.let { out += MdBlock.Item("•", it.groupValues[3], it.groupValues[1].length / 2) } }
            number.matches(line) -> { flush(); number.find(line)!!.let { out += MdBlock.Item(it.groupValues[2], it.groupValues[3], it.groupValues[1].length / 2) } }
            t.matches(Regex("^[-*_]{3,}$")) -> flush()
            else -> { if (para.isNotEmpty()) para.append(' '); para.append(t) }
        }
        i++
    }
    flush()
    return out
}

/** An answer's explanation, block by block: headings, spaced paragraphs, lists, code and notes. */
@Composable
internal fun MarkdownView(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { i, b ->
            when (b) {
                is MdBlock.Heading -> Text(
                    markdownLite(b.text), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Nt.Text,
                    modifier = Modifier.padding(top = if (i > 0) 6.dp else 0.dp),
                )
                is MdBlock.Paragraph -> Text(markdownLite(b.text), fontSize = 14.sp, lineHeight = 21.sp, color = Nt.TextSoft)
                is MdBlock.Item -> Row(Modifier.padding(start = (b.depth * 16).dp)) {
                    Text(
                        b.marker, fontSize = 14.sp, lineHeight = 21.sp, color = Nt.Accent, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.widthIn(min = 18.dp),
                    )
                    Box(Modifier.width(6.dp))
                    Text(markdownLite(b.text), fontSize = 14.sp, lineHeight = 21.sp, color = Nt.TextSoft)
                }
                is MdBlock.Code -> Text(
                    b.text, fontFamily = Nt.Mono, fontSize = 12.5.sp, lineHeight = 18.sp, color = Nt.Text, softWrap = false,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Nt.Bg)
                        .horizontalScroll(rememberScrollState()).padding(12.dp),
                )
                is MdBlock.Quote -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Nt.Accent.copy(alpha = 0.08f))) {
                    Box(Modifier.width(3.dp).background(Nt.Accent))
                    Text(markdownLite(b.text), fontSize = 14.sp, lineHeight = 21.sp, color = Nt.Text, modifier = Modifier.padding(10.dp))
                }
            }
        }
    }
}
