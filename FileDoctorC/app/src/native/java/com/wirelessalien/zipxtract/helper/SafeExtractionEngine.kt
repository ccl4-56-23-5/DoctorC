package com.wirelessalien.zipxtract.helper

import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import net.lingala.zip4j.ZipFile
import net.sf.sevenzipjbinding.*
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.*
import java.nio.ByteBuffer
import java.util.Date
import java.util.concurrent.CancellationException

/** Existing codecs, one bounded output pipeline. Failure/cancel never publishes a success directory. */
class SafeExtractionEngine(private val cancelled: () -> Boolean = { false }, private val progress: (Int) -> Unit = {}) {
    data class Item(val name: String, val directory: Boolean, val size: Long)
    private fun check() { if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("解压已取消，原压缩包保留") }
    private class Unsupported(message: String) : IOException(message)
    private val filters = setOf("tar", "gz", "gzip", "tgz", "bz2", "bzip2", "tbz", "tbz2", "xz", "txz", "lzma", "zst", "zstd", "lz4", "lz", "z")
    /** Preview uses the same container layer as extraction, notably compressed TAR streams. */
    fun list(source: File, password: String? = null): List<Item> {
        val file = firstVolume(source)
        if (file.extension.equals("zip", true)) return ZipFile(file, password?.toCharArray()).use { zip ->
            if (zip.isEncrypted && password == null) throw IOException("请输入压缩包密码")
            zip.fileHeaders.map { check(); Item(ExtractionOutput.normalize(it.fileName, it.isDirectory), it.isDirectory, it.uncompressedSize) }
        }
        if (file.extension.lowercase(java.util.Locale.ROOT) in filters) {
            val found = mutableListOf<Item>()
            FileInputStream(file).use { input -> android.os.ParcelFileDescriptor.dup(input.fd).use { descriptor ->
                val archive = Archive.readNew()
                try {
                    Archive.readSupportFilterAll(archive); Archive.readSupportFormatAll(archive)
                    if (!file.extension.equals("tar", true)) Archive.readSupportFormatRaw(archive)
                    Archive.readOpenFd(archive, descriptor.fd, 65536)
                    while (true) {
                        check(); val entry = Archive.readNextHeader(archive); if (entry == 0L) break
                        val raw = Archive.format(archive) == Archive.FORMAT_RAW
                        if (raw && Archive.filterCode(archive, 0) == 0) throw IOException("文件不是受支持的压缩流")
                        val name = if (raw) outputStem(file.name) else ArchiveEntry.pathnameUtf8(entry)
                            ?: ArchiveEntry.pathname(entry)?.toString(Charsets.UTF_8) ?: throw IOException("条目缺少名称")
                        val directory = ArchiveEntry.filetype(entry) == 0x4000
                        val normalized = ExtractionOutput.normalize(name, directory)
                        if (normalized.isNotEmpty()) found += Item(normalized, directory, if (ArchiveEntry.sizeIsSet(entry)) ArchiveEntry.size(entry) else -1)
                        Archive.readDataSkip(archive)
                    }
                } finally { Archive.free(archive) }
            } }
            return found
        }
        val volumes = Volumes(file, password)
        try {
            val archive = SevenZip.openInArchive(volumes.formatHint, volumes.initial(), volumes)
            try {
                return (0 until archive.numberOfItems).map { index ->
                    check()
                    if (password == null && archive.getProperty(index, PropID.ENCRYPTED) == true) throw IOException("请输入压缩包密码")
                    val directory = archive.getProperty(index, PropID.IS_FOLDER) == true
                    val name = archive.getStringProperty(index, PropID.PATH)?.takeIf { it.isNotEmpty() } ?: outputStem(file.name)
                    Item(ExtractionOutput.normalize(name, directory), directory, (archive.getProperty(index, PropID.SIZE) as? Number)?.toLong() ?: -1)
                }
            } finally { archive.close() }
        } finally { volumes.close() }
    }
    fun extract(source: File, parent: File, password: String? = null, selected: List<String>? = null): File {
        check()
        val file = firstVolume(source)
        if (!file.isFile || !file.canRead() || ArchiveSafety.isLink(file)) throw IOException("压缩包不可读取或为链接")
        val stem = outputStem(file.name)
        fun attempt(decoder: (ExtractionOutput) -> Unit): File = ExtractionOutput(parent, stem, selected, ::check).use { out ->
            decoder(out); check(); out.finish()
        }
        return when {
            file.extension.equals("zip", true) -> attempt { zip(file, password, it) }
            file.extension.lowercase(java.util.Locale.ROOT) in filters -> attempt { libarchive(file, password, it) }
            else -> {
                try { attempt { sevenZip(file, password, it, selected) } }
                catch (e: Exception) {
                    // Policy and CRC/data errors must not be relabelled as successful by a lax fallback.
                    if (causes(e).any { it is CancellationException || it is ExtractionPolicyException }) throw e
                    if (e !is Unsupported) throw e
                    attempt { libarchive(file, password, it) }
                }
            }
        }
    }
    private fun zip(file: File, password: String?, out: ExtractionOutput) {
        ZipFile(file, password?.toCharArray()).use { zip ->
            if (!zip.isValidZipFile) throw IOException("ZIP 文件损坏或分卷不完整")
            val headers = zip.fileHeaders
            val entries = headers.map { header ->
                val attr = header.externalFileAttributes
                val mode = if (attr != null && attr.size >= 4) ((attr[3].toInt() and 255) shl 8) or (attr[2].toInt() and 255) else 0
                val kind = mode and 0xf000
                out.entry(header.fileName, header.isDirectory, kind != 0 && kind != 0x4000 && kind != 0x8000)
            }
            headers.forEachIndexed { i, header ->
                check(); val entry = entries[i]
                if (entry.included) {
                    if (entry.directory) out.directory(entry, header.lastModifiedTimeEpoch)
                    else zip.getInputStream(header).use { input -> out.file(entry, header.lastModifiedTimeEpoch, header.uncompressedSize).use { copy(input, it) } }
                }
                progress(((i + 1L) * 99 / headers.size.coerceAtLeast(1)).toInt())
            }
        }
    }
    private fun sevenZip(file: File, password: String?, out: ExtractionOutput, selected: List<String>?) {
        val volumes = Volumes(file, password)
        try {
            val stream = volumes.initial()
            val archive = try { SevenZip.openInArchive(volumes.formatHint, stream, volumes) }
                catch (e: SevenZipException) {
                    if (file.extension.equals("7z", true) || file.extension.equals("rar", true) || file.name.matches(Regex("(?i).*\\.(7z|zip)\\.[0-9]+$")))
                        throw IOException("压缩包损坏、分卷缺失或密码不正确：${e.message}", e)
                    throw Unsupported("无法识别压缩包或密码不正确：${e.message}")
                }
            try {
                val entries = (0 until archive.numberOfItems).map { i ->
                    val name = archive.getStringProperty(i, PropID.PATH)?.takeIf { it.isNotEmpty() } ?: outputStem(file.name)
                    val directory = archive.getProperty(i, PropID.IS_FOLDER) == true
                    val link = !archive.getStringProperty(i, PropID.SYM_LINK).isNullOrEmpty() || !archive.getStringProperty(i, PropID.HARD_LINK).isNullOrEmpty()
                    val mode = (archive.getProperty(i, PropID.POSIX_ATTRIB) as? Number)?.toInt()?.and(0xf000) ?: 0
                    out.entry(name, directory, link || (mode != 0 && mode != 0x4000 && mode != 0x8000))
                }
                val indices = entries.indices.filter { entries[it].included }.toIntArray()
                var output: OutputStream? = null
                try {
                    archive.extract(indices, false, object : IArchiveExtractCallback, ICryptoGetTextPassword {
                        override fun cryptoGetTextPassword(): String = password ?: ""
                        override fun getStream(index: Int, mode: ExtractAskMode): ISequentialOutStream? {
                            check(); output?.close(); output = null
                            if (mode != ExtractAskMode.EXTRACT) return null
                            val entry = entries[index]; val time = (archive.getProperty(index, PropID.LAST_MODIFICATION_TIME) as? Date)?.time ?: 0
                            if (entry.directory) { out.directory(entry, time); return null }
                            output = out.file(entry, time, (archive.getProperty(index, PropID.SIZE) as? Number)?.toLong() ?: -1)
                            return ISequentialOutStream { bytes -> check(); output!!.write(bytes); bytes.size }
                        }
                        override fun prepareOperation(mode: ExtractAskMode) { check() }
                        override fun setOperationResult(result: ExtractOperationResult) {
                            if (result == ExtractOperationResult.OK) output?.close() else runCatching { output?.close() }
                            output = null; check()
                            when (result) {
                                ExtractOperationResult.OK -> Unit
                                ExtractOperationResult.UNSUPPORTEDMETHOD -> throw Unsupported("该压缩算法需要备用解码器")
                                ExtractOperationResult.WRONG_PASSWORD -> throw IOException("密码不正确")
                                else -> throw IOException("压缩包损坏或校验失败：$result")
                            }
                        }
                        override fun setTotal(total: Long) { check() }
                        override fun setCompleted(completed: Long) { check() }
                    })
                    if (output != null) throw IOException("解码器未确认条目完成")
                } catch (e: SevenZipException) {
                    causes(e).filterIsInstance<Unsupported>().firstOrNull()?.let { throw it }
                    throw e
                } finally { runCatching { output?.close() } }
            } finally { archive.close() }
        } finally { volumes.close() }
    }
    private fun libarchive(file: File, password: String?, out: ExtractionOutput) {
        FileInputStream(file).use { input -> android.os.ParcelFileDescriptor.dup(input.fd).use { descriptor ->
            val archive = Archive.readNew()
            try {
                Archive.readSupportFilterAll(archive); Archive.readSupportFormatAll(archive)
                if (file.extension.lowercase(java.util.Locale.ROOT) in filters && !file.extension.equals("tar", true)) Archive.readSupportFormatRaw(archive)
                if (password != null) Archive.readAddPassphrase(archive, password.toByteArray(Charsets.UTF_8))
                Archive.readOpenFd(archive, descriptor.fd, 65536)
                val buffer = ByteBuffer.allocateDirect(65536); val bytes = ByteArray(65536)
                while (true) {
                    check(); val header = Archive.readNextHeader(archive); if (header == 0L) break
                    val raw = Archive.format(archive) == Archive.FORMAT_RAW
                    if (raw && Archive.filterCode(archive, 0) == 0) throw IOException("文件不是受支持的压缩流")
                    val name = if (raw) outputStem(file.name) else ArchiveEntry.pathnameUtf8(header)
                        ?: ArchiveEntry.pathname(header)?.toString(Charsets.UTF_8) ?: throw IOException("压缩条目缺少名称")
                    val kind = ArchiveEntry.filetype(header)
                    val entry = out.entry(name, kind == 0x4000,
                        ArchiveEntry.hardlinkIsSet(header) || ArchiveEntry.symlink(header) != null || (kind != 0 && kind != 0x4000 && kind != 0x8000))
                    val time = if (ArchiveEntry.mtimeIsSet(header)) ArchiveEntry.mtime(header) * 1000 else 0
                    if (!entry.included) { Archive.readDataSkip(archive); continue }
                    if (entry.directory) out.directory(entry, time)
                    else out.file(entry, time, if (ArchiveEntry.sizeIsSet(header)) ArchiveEntry.size(header) else -1).use { output ->
                        while (true) {
                            check(); buffer.clear(); Archive.readData(archive, buffer)
                            val count = buffer.position(); if (count == 0) break
                            buffer.flip(); buffer.get(bytes, 0, count); output.write(bytes, 0, count)
                        }
                    }
                }
            } finally { Archive.free(archive) }
        } }
    }
    private fun copy(input: InputStream, output: OutputStream) {
        val bytes = ByteArray(65536)
        while (true) { check(); val count = input.read(bytes); if (count < 0) break; output.write(bytes, 0, count) }
    }
    private fun causes(error: Throwable): List<Throwable> = generateSequence(error) { it.cause }.take(20).toList()
    companion object {
        fun outputStem(name: String): String = name.replace(Regex("(?i)\\.(tar\\.(gz|bz2|xz|zst|zstd|lzma|lz4)|7z\\.\\d+|zip\\.\\d+|part\\d+\\.rar|[a-z0-9]+)$"), "").ifBlank { "解压文件" }
        fun firstVolume(file: File): File {
            val name = file.name
            val master = when {
                Regex("(?i).*\\.z\\d+$").matches(name) -> name.replace(Regex("(?i)\\.z\\d+$"), ".zip")
                Regex("(?i).*\\.(zip|7z)\\.\\d+$").matches(name) -> name.replace(Regex("\\.\\d+$"), ".001")
                Regex("(?i).*\\.part\\d+\\.rar$").matches(name) -> {
                    val stem = name.replace(Regex("(?i)\\.part\\d+\\.rar$"), "")
                    file.parentFile?.listFiles()?.firstOrNull { it.name.matches(Regex(Regex.escape(stem) + "\\.part0*1\\.rar", RegexOption.IGNORE_CASE)) }?.name ?: name
                }
                Regex("(?i).*\\.r\\d+$").matches(name) -> name.replace(Regex("(?i)\\.r\\d+$"), ".rar")
                else -> name
            }
            val direct = File(file.parentFile, master)
            return if (direct.exists()) direct else file.parentFile?.listFiles()?.filter { it.name.equals(master, true) }?.singleOrNull() ?: direct
        }
    }
    private inner class Volumes(private val first: File, private val password: String?) : IArchiveOpenCallback, IArchiveOpenVolumeCallback, ICryptoGetTextPassword, AutoCloseable {
        val formatHint: ArchiveFormat? = when {
            first.name.matches(Regex("(?i).*\\.7z\\.001$")) -> ArchiveFormat.SEVEN_ZIP
            first.name.matches(Regex("(?i).*\\.zip\\.001$")) -> ArchiveFormat.ZIP
            else -> null
        }
        private val streams = mutableListOf<IInStream>()
        private val parent = first.parentFile!!.canonicalFile
        fun initial(): IInStream {
            if (first.name.matches(Regex("(?i).*\\.(7z|zip)\\.001$"))) {
                val prefix = first.name.dropLast(3)
                val parts = parent.listFiles()?.filter { it.name.startsWith(prefix) && it.name.removePrefix(prefix).matches(Regex("[0-9]+")) }
                    ?.sortedBy { it.name.removePrefix(prefix).toIntOrNull() ?: Int.MAX_VALUE }.orEmpty()
                parts.forEachIndexed { i, part ->
                    if (part.name.removePrefix(prefix).toIntOrNull() != i + 1 || ArchiveSafety.isLink(part)) throw IOException("分卷缺失或不安全")
                }
                return Joined(parts).also { streams += it }
            }
            return open(first)
        }
        private fun open(file: File): IInStream {
            check()
            if (file.canonicalFile.parentFile != parent || ArchiveSafety.isLink(file) || !file.isFile) throw IOException("分卷不存在或路径不安全：${file.name}")
            return RandomAccessFileInStream(RandomAccessFile(file, "r")).also { streams += it }
        }
        override fun getStream(name: String): IInStream? {
            check()
            if (name != File(name).name || '/' in name || '\\' in name) throw ExtractionPolicyException("压缩包请求了外部路径分卷")
            val stem = first.name.replace(Regex("(?i)(\\.part\\d+)?\\.rar$"), "")
            if (!(name.equals(first.name, true) || name.matches(Regex(Regex.escape(stem) + "(\\.part[0-9]+\\.rar|\\.r[0-9]+|\\.rar)", RegexOption.IGNORE_CASE))))
                throw ExtractionPolicyException("压缩包请求了非关联文件：$name")
            val file = File(parent, name); return if (file.exists()) open(file) else null
        }
        override fun getProperty(id: PropID): Any? = if (id == PropID.NAME) {
            if (formatHint != null) first.name.dropLast(4) else first.name
        } else null
        override fun cryptoGetTextPassword(): String = password ?: ""
        override fun setTotal(files: Long?, bytes: Long?) { check() }
        override fun setCompleted(files: Long?, bytes: Long?) { check() }
        override fun close() { streams.asReversed().forEach { runCatching { it.close() } }; streams.clear() }
    }
    private inner class Joined(parts: List<File>) : IInStream {
        private val files = mutableListOf<RandomAccessFile>()
        init {
            try { parts.forEach { files += RandomAccessFile(it, "r") } }
            catch (e: Exception) { files.forEach { runCatching { it.close() } }; throw e }
        }
        private val ends = files.runningFold(0L) { sum, file -> Math.addExact(sum, file.length()) }
        private var position = 0L
        override fun seek(offset: Long, origin: Int): Long {
            position = Math.addExact(when (origin) { 0 -> 0; 1 -> position; 2 -> ends.last(); else -> throw IOException("无效 seek") }, offset)
            if (position < 0) throw IOException("无效分卷偏移"); return position
        }
        override fun read(bytes: ByteArray): Int {
            check(); if (position >= ends.last()) return 0
            val i = (0 until files.size).first { position < ends[it + 1] }
            files[i].seek(position - ends[i]); val count = files[i].read(bytes, 0, minOf(bytes.size.toLong(), ends[i + 1] - position).toInt())
            if (count <= 0) throw EOFException("分卷读取提前结束")
            position += count; return count
        }
        override fun close() { files.forEach { it.close() } }
    }
}
