package com.nautile.app.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test fun splitsIntoBlocks() {
        val md = """
            Le démon **nmbd** écoute sur plusieurs interfaces.
            Rien de grave.

            ### Ce que montrent les lignes
            - port `137` (NetBIOS)
            - adresses 172.21.* à 172.25.*
              - sous-point
            1. Vérifier
            2. Relancer
            ```
            sudo systemctl restart nmbd
            ```
            > Attention : coupe les partages.
        """.trimIndent()
        val b = parseMarkdown(md)
        assertEquals(MdBlock.Paragraph("Le démon **nmbd** écoute sur plusieurs interfaces. Rien de grave."), b[0])
        assertEquals(MdBlock.Heading("Ce que montrent les lignes"), b[1])
        assertEquals(MdBlock.Item("•", "port `137` (NetBIOS)", 0), b[2])
        assertEquals(MdBlock.Item("•", "sous-point", 1), b[4])
        assertEquals(MdBlock.Item("2.", "Relancer", 0), b[6])
        assertEquals(MdBlock.Code("sudo systemctl restart nmbd"), b[7])
        assertEquals(MdBlock.Quote("Attention : coupe les partages."), b[8])
    }
}
