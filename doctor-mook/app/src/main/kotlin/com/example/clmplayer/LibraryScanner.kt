package com.example.clmplayer

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract

data class LibraryItem @JvmOverloads constructor(
    val uri: Uri,
    val name: String,
    val folder: String,
    val bytes: Long,
    val modified: Long = 0,
    val folderId: String = folder
)
internal object LibraryScanner {
    private val digits = Regex("[0-9]+")

    fun sourceId(uri: Uri): String = runCatching {
        val document = DocumentsContract.getDocumentId(uri)
        uri.authority + ":" + document
    }.getOrDefault(uri.toString())

    fun scan(context: Context, tree: Uri, cancellation: CancellationSignal,
             accept: (String) -> Boolean = { it.endsWith(".clm", ignoreCase = true) }): List<LibraryItem> {
        val result = mutableListOf<LibraryItem>()
        val visited = mutableSetOf<String>()
        fun visit(id: String, path: String, depth: Int) {
            cancellation.throwIfCanceled()
            require(depth <= 128 && visited.size < 50000) { "目录层级过深或数量过多" }
            if (!visited.add(id)) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            val cursor = context.contentResolver.query(children, columns, null, null, null, cancellation)
                ?: error("无法扫描目录，请重新选择文件夹")
            cursor.use { c ->
                val modifiedColumn = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (c.moveToNext()) {
                    cancellation.throwIfCanceled()
                    val childId = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        visit(childId, if (path.isEmpty()) name else "$path/$name", depth + 1)
                    } else if (accept(name)) {
                        val modified = if (modifiedColumn < 0 || c.isNull(modifiedColumn)) 0 else c.getLong(modifiedColumn)
                        result += LibraryItem(
                            DocumentsContract.buildDocumentUriUsingTree(tree, childId),
                            name, path.ifEmpty { "根目录" }, if (c.isNull(3)) 0 else c.getLong(3),
                            modified, id
                        )
                    }
                }
            }
        }
        visit(DocumentsContract.getTreeDocumentId(tree), "", 0)
        cancellation.throwIfCanceled()
        return result.sortedWith(compareBy<LibraryItem> { naturalKey(it.folder) }
            .thenBy { it.folder }
            .thenBy { it.folderId }
            .thenBy { naturalKey(it.name) }
            .thenBy { it.name }
            .thenBy { sourceId(it.uri) })
    }

    private fun naturalKey(name: String) = digits.replace(name.lowercase()) { match ->
        val number = match.value.trimStart('0').ifEmpty { "0" }
        number.length.toString().padStart(10, '0') + number
    }

}
