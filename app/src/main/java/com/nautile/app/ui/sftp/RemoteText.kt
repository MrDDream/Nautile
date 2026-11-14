package com.nautile.app.ui.sftp

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * The text of a remote file for the editor, in an encoding that gives back the same bytes when
 * saved unchanged: UTF-8 when the file is valid UTF-8, otherwise ISO-8859-1 (every byte is one
 * character), so an old Latin-1 config file is not rewritten with replacement characters.
 */
data class RemoteText(val text: String, val charset: Charset) {
    companion object {
        fun decode(bytes: ByteArray): RemoteText = try {
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            RemoteText(text, Charsets.UTF_8)
        } catch (_: CharacterCodingException) {
            RemoteText(String(bytes, Charsets.ISO_8859_1), Charsets.ISO_8859_1)
        }

        /** [text] in [charset]; null when a character cannot be written in it (no silent « ? »). */
        fun encode(text: String, charset: Charset): ByteArray? = try {
            val buf = charset.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text))
            ByteArray(buf.remaining()).also(buf::get)
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
