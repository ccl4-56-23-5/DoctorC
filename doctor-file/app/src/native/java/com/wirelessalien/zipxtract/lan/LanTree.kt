package com.wirelessalien.zipxtract.lan

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val FILE = 1
private const val DIRECTORY = 2
private const val END_DIRECTORY = 3
internal const val LAN_PREVIEW_SIZE = 8

internal data class LanPreview(val name: String, val size: Long?) {
    val directory get() = size == null

    fun json() = JSONObject().put("name", name).put("size", size ?: JSONObject.NULL)
}

internal data class LanTreeOffer(
    val fileCount: Long,
    val folderCount: Long,
    val rootCount: Long,
    val contentBytes: Long,
    val wireBytes: Long,
    val preview: List<LanPreview>
) {
    fun json() = JSONObject()
        .put("fileCount", fileCount).put("folderCount", folderCount).put("rootCount", rootCount)
        .put("contentBytes", contentBytes).put("wireBytes", wireBytes)
        .put("preview", JSONArray().apply { preview.forEach { put(it.json()) } })

    companion object {
        fun parse(value: JSONObject): LanTreeOffer {
            val files = value.getLong("fileCount")
            val folders = value.getLong("folderCount")
            val roots = value.getLong("rootCount")
            val content = value.getLong("contentBytes")
            val wire = value.getLong("wireBytes")
            val preview = value.getJSONArray("preview")
            require(files >= 0 && folders in 0..Long.MAX_VALUE - files && roots in 1..files + folders)
            require(content >= 0 && wire > content && wire >= files + folders)
            require(preview.length().toLong() == minOf(roots, LAN_PREVIEW_SIZE.toLong()))
            return LanTreeOffer(files, folders, roots, content, wire, List(preview.length()) { index ->
                val item = preview.getJSONObject(index)
                val name = item.getString("name")
                val size = if (item.isNull("size")) null else item.getLong("size")
                require(LanFile.validName(name) && (size == null || size in 0..content))
                LanPreview(name, size)
            })
        }
    }
}

internal class LanTreePlan private constructor(val offer: LanTreeOffer, private val records: List<Record>) {
    private data class Record(val source: LanSource, val header: ByteArray, val type: Int)

    fun write(output: OutputStream, check: () -> Unit, progress: (LanProgress) -> Unit) {
        val batch = LanBatchProgress(offer.fileCount + offer.folderCount, offer.contentBytes, progress)
        for ((source, header, type) in records) {
            check()
            source.check()
            output.write(header)
            if (type == FILE) {
                val file = source.meta
                source.writePayload(output, check, batch.payload(file.name))
                batch.complete(file.name, file.size)
            }
            if (type == END_DIRECTORY) batch.complete(source.meta.name)
        }
    }

    companion object {
        private data class Pending(val file: File, val end: LanSource? = null)

        fun scan(roots: List<File>, check: () -> Unit): LanTreePlan {
            require(roots.isNotEmpty()) { "请选择要发送的文件或文件夹" }
            val preview = mutableListOf<LanPreview>()
            val records = mutableListOf<Record>()
            var fileCount = 0L
            var folderCount = 0L
            var contentBytes = 0L
            var wireBytes = 0L
            fun record(source: LanSource, type: Int) {
                val header = encodeHeader(source, type)
                wireBytes = Math.addExact(wireBytes, header.size.toLong())
                if (type == FILE) wireBytes = Math.addExact(wireBytes, Math.addExact(source.meta.size, LAN_DIGEST_BYTES.toLong()))
                records += Record(source, header, type)
            }
            for (root in roots) {
                val pending = ArrayDeque<Pending>().apply { addLast(Pending(root)) }
                while (pending.isNotEmpty()) {
                    check()
                    val (file, end) = pending.removeLast()
                    if (end != null) {
                        end.check()
                        record(end, END_DIRECTORY)
                        continue
                    }
                    val source = LanSource.inspect(file, allowDirectory = true)
                    val meta = source.meta
                    if (file == root && preview.size < LAN_PREVIEW_SIZE) {
                        preview += LanPreview(meta.name, meta.size.takeUnless { source.directory })
                    }
                    if (source.directory) {
                        folderCount++
                        record(source, DIRECTORY)
                        val children = file.listFiles() ?: throw IOException("无法读取文件夹：" + file.name)
                        pending.addLast(Pending(file, source))
                        for (index in children.indices.reversed()) pending.addLast(Pending(children[index]))
                    } else {
                        fileCount++
                        contentBytes = Math.addExact(contentBytes, meta.size)
                        record(source, FILE)
                    }
                }
            }
            val offer = LanTreeOffer(fileCount, folderCount, roots.size.toLong(), contentBytes, wireBytes, preview)
            return LanTreePlan(offer, records)
        }

        private fun encodeHeader(source: LanSource, type: Int): ByteArray {
            if (type == END_DIRECTORY) return byteArrayOf(END_DIRECTORY.toByte())
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use {
                it.writeByte(type)
                it.writeUTF(source.meta.name)
                it.writeUTF(source.meta.times.modified)
                it.writeUTF(source.meta.times.accessed.orEmpty())
                if (type == FILE) it.writeLong(source.meta.size)
            }
            return bytes.toByteArray()
        }
    }
}

internal object LanTreeDestination {
    private data class OpenDirectory(val folder: File, val times: FileTimes)

    fun receive(
        destination: File,
        offer: LanTreeOffer,
        input: InputStream,
        completed: LanCompleted,
        check: () -> Unit,
        progress: LanBatchProgress
    ) {
        val bounded = BoundedInput(input, offer.wireBytes)
        val stream = DataInputStream(bounded)
        val stack = ArrayDeque<OpenDirectory>()
        var files = 0L
        var folders = 0L
        var roots = 0L
        var bytes = 0L
        try {
            while (bounded.remaining > 0) {
                check()
                val type = stream.readUnsignedByte()
                if (type == END_DIRECTORY) {
                    val directory = stack.lastOrNull() ?: throw IOException("文件夹结束标记无效")
                    LanDestination.requireDirectory(directory.folder)
                    val warnings = directory.times.restore(directory.folder)
                    stack.removeLast()
                    completed.directory(warnings)
                    progress.complete(directory.folder.name)
                    continue
                }
                require(type == FILE || type == DIRECTORY) { "传输项目类型无效" }
                val name = stream.readUTF()
                val modified = stream.readUTF()
                val accessed = stream.readUTF().ifEmpty { null }
                require(LanFile.validName(name) && FileTimes.valid(modified) &&
                    (accessed == null || FileTimes.valid(accessed))) { "文件或文件夹信息无效" }
                val times = FileTimes(modified, accessed)
                val size = if (type == FILE) stream.readLong() else null
                val parent = stack.lastOrNull()?.folder ?: destination
                val atRoot = stack.isEmpty()
                if (atRoot) {
                    require(roots < offer.rootCount) { "根项目多于已确认数量" }
                    if (roots < offer.preview.size) {
                        require(offer.preview[roots.toInt()] == LanPreview(name, size)) { "根项目与已确认清单不符" }
                    }
                    roots++
                }
                if (size == null) {
                    require(folders < offer.folderCount) { "文件夹多于已确认数量" }
                    folders++
                    val folder = LanDestination.createDirectory(parent, name, renameOnConflict = atRoot)
                    stack.addLast(OpenDirectory(folder, times))
                } else {
                    require(files < offer.fileCount && size in 0..offer.contentBytes - bytes &&
                        bounded.remaining >= LAN_DIGEST_BYTES && size <= bounded.remaining - LAN_DIGEST_BYTES) {
                        "文件数量或大小与已确认清单不符"
                    }
                    files++
                    bytes += size
                    val file = LanFile("", name, size, times, "application/octet-stream")
                    val update = progress.payload(name)
                    val receipt = LanDestination.save(parent, file, stream, check, update, renameOnConflict = atRoot)
                    completed.file(receipt.timeWarnings)
                    progress.complete(receipt.name, size)
                }
            }
            require(stack.isEmpty() && files == offer.fileCount && folders == offer.folderCount &&
                roots == offer.rootCount && bytes == offer.contentBytes) { "传输提前结束" }
        } finally {
            // Keep published files and completed empty folders. Remove incomplete empty directories.
            while (stack.isNotEmpty()) {
                val folder = stack.removeLast().folder
                runCatching {
                    LanDestination.requireDirectory(folder)
                    folder.delete()
                }
            }
        }
    }

    private class BoundedInput(private val source: InputStream, length: Long) : InputStream() {
        var remaining = length
            private set

        override fun read(): Int {
            if (remaining == 0L) return -1
            val byte = source.read()
            if (byte < 0) throw EOFException("连接中断，文件未传完")
            remaining--
            return byte
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return -1
            val count = source.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("连接中断，文件未传完")
            remaining -= count
            return count
        }
    }
}
