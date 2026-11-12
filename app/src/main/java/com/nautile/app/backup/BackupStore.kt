package com.nautile.app.backup

import com.nautile.i18n.tr

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What a destination holds: the file's text (null when absent) and its version tag when the store has one. */
class Remote(val text: String?, val etag: String?)

/** Another device wrote in between: read again, merge, retry. */
class ConflictException : IOException("conflict")

/** One backup destination: read the file, write it (only over what was read, when the store can tell), keep older copies. */
interface BackupStore {
    fun read(): Remote

    /** Writes [bytes]; with [expected], fails with [ConflictException] if the file changed since (stores with versions only). */
    fun write(bytes: ByteArray, expected: Remote? = null)

    /** Before a backup overwrites the file: the previous ones become name.1 … name.4 (best effort). */
    fun rotate() {}
}

object BackupStores {
    private val http by lazy { OkHttpClient.Builder().callTimeout(2, TimeUnit.MINUTES).build() }

    fun of(context: Context, dest: BackupDestination, prefs: BackupPrefs): BackupStore = when (dest) {
        is BackupDestination.Document -> DocumentStore(context, Uri.parse(dest.uri))
        is BackupDestination.WebDav -> WebDavStore(dest.url, dest.username, prefs.webDavPassword().orEmpty(), http)
        is BackupDestination.S3 -> S3Store(S3Client(dest.endpoint, dest.region, dest.bucket, dest.accessKey, prefs.s3Secret().orEmpty(), http), dest.key)
        is BackupDestination.Folder -> FolderStore(context, Uri.parse(dest.treeUri), AutoBackup.FILE_NAME)
    }

    /** Checks a destination by writing a small file next to the backup, then deleting it. */
    fun probe(store: BackupStore) {
        when (store) {
            is WebDavStore -> store.probe()
            is S3Store -> store.probe()
            else -> store.read()
        }
    }
}

/** A single document picked with Android's picker (Google Drive, OneDrive…): no versions, no siblings. */
class DocumentStore(private val context: Context, private val uri: Uri) : BackupStore {
    override fun read(): Remote {
        val text = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        } catch (_: FileNotFoundException) { null }
        return Remote(text?.takeIf { it.isNotBlank() }, null)
    }

    override fun write(bytes: ByteArray, expected: Remote?) {
        // Some providers (cloud ones) do not support truncate mode "wt".
        val out = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(uri, "w")
            ?: throw IOException(tr("Le fichier de sauvegarde n'est plus accessible : choisissez-le à nouveau"))
        out.use { it.write(bytes) }
    }
}

class WebDavStore(url: String, private val username: String, private val password: String, private val http: OkHttpClient) : BackupStore {
    private val fileUrl = AutoBackup.webDavFileUrl(url)

    private fun Request.Builder.auth() = apply { if (username.isNotEmpty()) header("Authorization", Credentials.basic(username, password)) }

    override fun read(): Remote = http.newCall(Request.Builder().url(fileUrl).get().auth().build()).execute().use { r ->
        when {
            r.code == 404 -> Remote(null, null)
            r.isSuccessful -> Remote(r.body?.string()?.takeIf { it.isNotBlank() }, r.header("ETag"))
            else -> throw IOException(error(r.code))
        }
    }

    override fun write(bytes: ByteArray, expected: Remote?) = put(fileUrl, bytes, expected)

    private fun put(url: String, bytes: ByteArray, expected: Remote?) {
        val req = Request.Builder().url(url).put(bytes.toRequestBody("application/octet-stream".toMediaType())).auth()
            .apply {
                when {
                    expected?.etag != null -> header("If-Match", expected.etag)
                    expected != null && expected.text == null -> header("If-None-Match", "*")
                }
            }.build()
        http.newCall(req).execute().use { r ->
            when {
                r.code == 412 -> throw ConflictException()
                !r.isSuccessful -> throw IOException(error(r.code))
            }
        }
    }

    private fun move(from: String, to: String) = runCatching {
        http.newCall(Request.Builder().url(from).method("MOVE", null).header("Destination", to).header("Overwrite", "T").auth().build()).execute().close()
    }

    override fun rotate() {
        for (i in BackupVersions.KEEP - 2 downTo 1) move("$fileUrl.$i", "$fileUrl.${i + 1}")
        move(fileUrl, "$fileUrl.1")
    }

    fun probe() {
        val url = "$fileUrl.test"
        put(url, "nautile".toByteArray(), null)
        runCatching { http.newCall(Request.Builder().url(url).delete().auth().build()).execute().close() }
    }

    private fun error(code: Int) = when (code) {
        401, 403 -> tr("WebDAV : identifiants refusés")
        404, 409 -> tr("WebDAV : dossier introuvable")
        else -> tr("WebDAV : erreur %1\$s", code)
    }
}

/** S3: conditional writes with If-Match / If-None-Match where the service supports them, older copies by server-side copy. */
class S3Store(private val s3: S3Client, private val key: String) : BackupStore {
    override fun read(): Remote = s3.get(key)?.let { Remote(it.bytes.decodeToString().takeIf { t -> t.isNotBlank() }, it.etag) } ?: Remote(null, null)

    override fun write(bytes: ByteArray, expected: Remote?) {
        try {
            s3.put(key, bytes, ifMatch = expected?.etag, ifNoneMatch = expected != null && expected.text == null && expected.etag == null)
        } catch (_: S3Client.PreconditionFailed) {
            throw ConflictException()
        } catch (e: IOException) {
            // Services without conditional writes refuse the header: write plainly then.
            if (expected == null || e.message?.contains("NotImplemented") != true) throw e
            s3.put(key, bytes)
        }
    }

    override fun rotate() {
        runCatching {
            for (i in BackupVersions.KEEP - 2 downTo 1) s3.copy("$key.$i", "$key.${i + 1}")
            s3.copy(key, "$key.1")
        }
    }

    fun probe() {
        s3.put("$key.test", "nautile".toByteArray())
        s3.delete("$key.test")
    }
}

/** A folder (Syncthing, phone storage…): the file and its older copies side by side. */
class FolderStore(private val context: Context, private val tree: Uri, private val name: String) : BackupStore {
    private val resolver get() = context.contentResolver
    private val parent: Uri get() = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** Document of [child] in the folder, or null. */
    private fun find(child: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == child) return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
        }
        return null
    }

    private fun readBytes(child: String): ByteArray? = find(child)?.let { uri -> resolver.openInputStream(uri)?.use { it.readBytes() } }

    private fun writeBytes(child: String, bytes: ByteArray) {
        val uri = find(child) ?: DocumentsContract.createDocument(resolver, parent, "application/octet-stream", child)
            ?: throw IOException(tr("Impossible de créer %1\$s dans le dossier", child))
        val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull() ?: resolver.openOutputStream(uri, "w")
            ?: throw IOException(tr("Dossier de sauvegarde inaccessible : choisissez-le à nouveau"))
        out.use { it.write(bytes) }
    }

    override fun read(): Remote = Remote(readBytes(name)?.decodeToString()?.takeIf { it.isNotBlank() }, null)

    override fun write(bytes: ByteArray, expected: Remote?) = writeBytes(name, bytes)

    override fun rotate() {
        runCatching {
            for (i in BackupVersions.KEEP - 2 downTo 1) readBytes("$name.$i")?.let { writeBytes("$name.${i + 1}", it) }
            readBytes(name)?.let { writeBytes("$name.1", it) }
        }
    }
}
