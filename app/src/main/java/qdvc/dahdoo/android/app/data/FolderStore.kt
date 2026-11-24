package qdvc.dahdoo.android.app.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.IOException

/** One row from a SAF directory listing. Cheap fields only; never the body. */
data class DocInfo(
    val docId: String,
    val name: String,
    val mime: String,
    val lastModified: Long,
    val size: Long,
) {
    val isDir: Boolean get() = mime == Document.MIME_TYPE_DIR
}

/**
 * All Storage Access Framework access for one user-granted folder (a tree URI).
 *
 * Rules carried over from the QDVC Android spec (B3) and Cursed Knowledge:
 * - Everything goes through [ContentResolver]; never `java.io.File`.
 * - Stay inside the granted tree: address children by document id within it via
 *   `buildChildDocumentsUriUsingTree`, never a fabricated subfolder tree URI.
 * - Every call here is IPC to the storage provider. Call only from
 *   `Dispatchers.IO`, and avoid listing on hot paths.
 * - No `DocumentFile`: its single-URI form silently returns null from
 *   `findFile`/`createFile`, and it hides which calls are expensive.
 */
class FolderStore(private val resolver: ContentResolver, val treeUri: Uri) {

    val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    /** Throws if the folder cannot be listed (permission revoked, provider gone). */
    fun list(parentDocId: String): List<DocInfo> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val out = ArrayList<DocInfo>()
        val cursor = resolver.query(uri, COLUMNS, null, null, null)
            ?: throw IOException("The folder could not be listed")
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                out += DocInfo(
                    docId = id,
                    name = c.getString(1) ?: "",
                    mime = c.getString(2) ?: "",
                    lastModified = if (c.isNull(3)) 0L else c.getLong(3),
                    size = if (c.isNull(4)) 0L else c.getLong(4),
                )
            }
        }
        return out
    }

    /** Metadata for one document, or null if it has gone. */
    fun info(docId: String): DocInfo? = try {
        resolver.query(docUri(docId), COLUMNS, null, null, null)?.use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                DocInfo(
                    docId = c.getString(0) ?: docId,
                    name = c.getString(1) ?: "",
                    mime = c.getString(2) ?: "",
                    lastModified = if (c.isNull(3)) 0L else c.getLong(3),
                    size = if (c.isNull(4)) 0L else c.getLong(4),
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    fun find(parentDocId: String, name: String): DocInfo? =
        list(parentDocId).firstOrNull { it.name == name }

    /** The child folder [name], created if missing. */
    fun ensureDir(parentDocId: String, name: String): String {
        find(parentDocId, name)?.let { existing ->
            if (existing.isDir) return existing.docId
            throw IOException("\"$name\" exists in the folder but is not a folder")
        }
        val created = DocumentsContract.createDocument(
            resolver, docUri(parentDocId), Document.MIME_TYPE_DIR, name
        ) ?: throw IOException("Could not create the \"$name\" folder")
        return DocumentsContract.getDocumentId(created)
    }

    fun readText(docId: String): String =
        resolver.openInputStream(docUri(docId))?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("Could not open a file for reading")

    /**
     * Replaces the whole contents of an existing document. Mode "wt", not "w":
     * some providers (notably ExternalStorageProvider on Android 10) do not
     * truncate on "w", so a shorter write leaves the old file's tail behind.
     */
    fun overwriteText(docId: String, text: String) {
        val out = resolver.openOutputStream(docUri(docId), "wt")
            ?: throw IOException("Could not open a file for writing")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** Creates [name] in [parentDocId] holding [text]; returns its document id. */
    fun createText(parentDocId: String, name: String, text: String): String {
        val id = createExactly(parentDocId, name)
        try {
            overwriteText(id, text)
        } catch (e: Exception) {
            runCatching { delete(id) }
            throw e
        }
        return id
    }

    /**
     * Copies a local file into the folder as [name] and returns its document id.
     * If a document of that name already exists with the same size (a previous
     * copy that finished but whose bookkeeping did not), it is reused; any other
     * existing document of that name is an incomplete copy and is replaced.
     */
    fun copyIn(parentDocId: String, name: String, source: File): String {
        val expected = source.length()
        find(parentDocId, name)?.let { existing ->
            if (!existing.isDir && existing.size == expected && expected > 0) return existing.docId
            delete(existing.docId)
        }
        val id = createExactly(parentDocId, name)
        try {
            val out = resolver.openOutputStream(docUri(id), "wt")
                ?: throw IOException("Could not open the new file for writing")
            val copied = out.use { o -> source.inputStream().use { it.copyTo(o) } }
            if (copied != expected) throw IOException("Copied $copied of $expected bytes")
        } catch (e: Exception) {
            runCatching { delete(id) }
            throw e
        }
        return id
    }

    fun delete(docId: String) {
        if (!DocumentsContract.deleteDocument(resolver, docUri(docId))) {
            throw IOException("The file could not be deleted")
        }
    }

    /** The folder's own display name, for Settings. */
    fun displayName(): String? = info(rootDocId)?.name?.takeIf { it.isNotBlank() }

    /**
     * Creates a document whose name is exactly [name], or throws.
     *
     * The MIME type passed is application/octet-stream on purpose. Providers
     * reconcile a display name's extension with the MIME type, and where the two
     * disagree they append the MIME type's own extension -- older Android maps
     * "m4a" to audio/mpeg, so asking for audio/mp4 could produce "x.m4a.mp4".
     * octet-stream has no extension, so the name is left alone. Providers may
     * also de-duplicate with " (1)"; the name is checked afterwards so a
     * mismatched file never silently becomes a recording's audio or sidecar.
     */
    private fun createExactly(parentDocId: String, name: String): String {
        val uri = DocumentsContract.createDocument(resolver, docUri(parentDocId), OCTET_STREAM, name)
            ?: throw IOException("Could not create \"$name\"")
        val id = DocumentsContract.getDocumentId(uri)
        val actual = info(id)?.name
        if (actual != null && actual != name) {
            runCatching { delete(id) }
            throw IOException("The folder renamed \"$name\" to \"$actual\"")
        }
        return id
    }

    private companion object {
        const val OCTET_STREAM = "application/octet-stream"
        val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_SIZE,
        )
    }
}
