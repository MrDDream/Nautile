package com.nautile.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class I18nTest {
    @After fun reset() { I18n.english = false }

    @Test fun frenchIsTheSource() = assertEquals("Hôtes", tr("Hôtes"))

    @Test fun englishLookupAndFormat() {
        I18n.english = true
        assertEquals("Hosts", tr("Hôtes"))
        assertEquals("Key “id” generated", tr("Clé « %1\$s » générée", "id"))
        assertEquals("unknown stays", tr("unknown stays"))
        assertEquals("No hosts yet.\nAdd one or import your ~/.ssh/config.", tr("Aucun hôte pour l'instant.\nAjoutez-en un ou importez votre ~/.ssh/config."))
    }

    @Test fun everyTranslationKeepsItsPlaceholders() {
        val ph = Regex("%\\d\\\$s")
        EnStrings.map.forEach { (fr, en) ->
            val a = ph.findAll(fr).map { it.value }.toSet()
            val b = ph.findAll(en).map { it.value }.toSet()
            // English may drop a French-only plural suffix argument, never invent one.
            assert(b.all { it in a }) { "Placeholders differ: $fr -> $en" }
        }
    }
}
