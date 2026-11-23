/* Copyright (C) 2026. GPL-3.0-or-later; see License. */
package com.wirelessalien.zipxtract.helper

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.sf.sevenzipjbinding.*
import net.sf.sevenzipjbinding.impl.*
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.compressors.CompressorStreamFactory
import com.github.luben.zstd.ZstdOutputStream
import java.io.*
import java.security.MessageDigest
import java.util.Date
import java.util.UUID
import android.os.Build
import android.system.Os

/** Shared by all three creation dialogs. Writes in an isolated staging directory, verifies, then publishes. */
class SafeArchiveEngine(private val cancelled: () -> Boolean = { false },
                        private val progress: (Int) -> Unit = {},
                        private val phase: (String) -> Unit = {}) {
    data class Options(val format: String, val name: String, val outputDir: File,
        val password: String? = null,
        val splitSize: Long = 0, val deleteSources: Boolean = false)
    data class Result(val archive: File, val retainedSources: List<String>, val deleteRequested: Boolean)
    private fun check() = ArchiveSafety.checkCancelled(cancelled)

    fun create(paths: List<String>, options: Options): Result {
        require(options.name.isNotBlank() && options.name != "." && options.name != ".." &&
            options.name.none { it == '/' || it == '\\' || it == '\u0000' }) { "压缩包名称不能含路径分隔符" }
        require(options.splitSize == 0L || (options.format == "ZIP" && options.splitSize >= 65536L)) { "仅 ZIP 支持分卷，分卷大小至少为 64 KiB" }
        require(options.password.isNullOrEmpty() || options.format in setOf("ZIP", "7Z")) { "当前格式不支持密码，不能忽略加密要求" }
        val extension = when (options.format) {
            "7Z" -> ".7z"; "ZIP" -> ".zip"; "TAR_ONLY" -> ".tar"
            "gz" -> ".tar.gz"; "bzip2" -> ".tar.bz2"; "xz" -> ".tar.xz"
            "lzma" -> ".tar.lzma"; "zstd" -> ".tar.zst"
            else -> throw IOException("不支持的压缩格式: ${options.format}")
        }
        var stem = options.name.removeSuffix(extension)
        require(stem.isNotBlank() && stem != "." && stem != "..") { "请输入压缩包名称，不能只填写扩展名" }
        val outputDir = options.outputDir.canonicalFile
        phase("正在检查源文件…")
        val plan = ArchiveSafety.capture(paths, ::check)
        plan.assertOutputOutsideSources(outputDir)
        if (!outputDir.isDirectory && !outputDir.mkdirs()) throw IOException("无法创建输出目录")
        val originalStem = stem
        var suffix = 0
        val existing = outputDir.listFiles() ?: throw IOException("输出目录不可读取")
        fun collision(): Boolean = existing.any {
            it.name == stem + extension || (options.splitSize > 0 && it.name.matches(Regex(Regex.escape(stem) + "\\.z[0-9]+")))
        }
        while (collision()) { check(); suffix++; stem = "$originalStem ($suffix)" }
        val stage = File(outputDir, ".sevenzip-${UUID.randomUUID()}")
        if (!stage.mkdir()) throw IOException("无法创建临时输出目录")
        val archive = File(stage, stem + extension)
        val published = mutableListOf<File>()
        val identities = mutableMapOf<String, Pair<Long, Long>>()
        var committed = false
        fun remember(file: File) { val stat = Os.lstat(file.path); identities[file.path] = stat.st_dev to stat.st_ino }
        fun owned(file: File): Boolean = runCatching {
            val stat = Os.lstat(file.path)
            identities[file.path] == (stat.st_dev to stat.st_ino) && !ArchiveSafety.isLink(file)
        }.getOrDefault(false)
        try {
            phase("正在压缩…")
            when (options.format) {
                "7Z" -> create7z(plan, archive, options)
                "ZIP" -> createZip(plan, archive, options)
                else -> createTar(plan, archive, options)
            }
            stage.listFiles()?.filter { it.isFile && !ArchiveSafety.isLink(it) &&
                (it.name == archive.name || (options.format == "ZIP" && options.splitSize > 0 &&
                    it.name.matches(Regex(Regex.escape(stem) + "\\.z[0-9]+")))) }?.forEach(::remember)
            check()
            phase("正在逐项校验压缩包…")
            when (options.format) {
                "7Z" -> verify7z(plan, archive, options.password)
                "ZIP" -> verifyZip(plan, archive, options.password)
                else -> verifyTar(plan, archive, options.format)
            }
            plan.assertUnchanged(::check)
            check()
            // Publish the index (.zip) last; never overwrite an existing archive/volume.
            val parts = if (options.format == "ZIP") ZipFile(archive, options.password?.toCharArray()).use { it.splitZipFiles }
                else listOf(archive)
            if (stage.listFiles()?.toSet() != parts.toSet() || parts.any { !it.isFile || ArchiveSafety.isLink(it) })
                throw IOException("临时压缩包出现非预期文件；源文件已保留")
            val hashes = parts.associate { it.name to it.inputStream().use { input -> ArchiveSafety.digest(input, ::check) } }
            for (part in parts) remember(part)
            for (part in parts.sortedBy { it == archive }) {
                check()
                if (!owned(part)) throw IOException("临时压缩包已被替换；源文件已保留")
                val target = File(outputDir, part.name)
                if (Build.VERSION.SDK_INT >= 26) {
                    java.nio.file.Files.move(part.toPath(), target.toPath()) // No REPLACE_EXISTING or ATOMIC_MOVE.
                    published += target; identities[target.path] = identities.getValue(part.path)
                } else {
                    val fd = Os.open(target.path, android.system.OsConstants.O_WRONLY or android.system.OsConstants.O_CREAT or
                        android.system.OsConstants.O_EXCL or android.system.OsConstants.O_NOFOLLOW, 0x180)
                    val stat = Os.fstat(fd); identities[target.path] = stat.st_dev to stat.st_ino; published += target
                    FileOutputStream(fd).use { output -> part.inputStream().use { copy(it, output) }; output.fd.sync() }
                }
                if (!owned(target) || target.inputStream().use { ArchiveSafety.digest(it, ::check) } != hashes[part.name])
                    throw IOException("发布后的压缩包校验失败；源文件已保留")
            }
            committed = true
            val output = File(outputDir, archive.name)
            val retained = if (options.deleteSources) {
                phase("校验通过，正在删除源文件…")
                for (part in published) {
                    if (!owned(part) || part.inputStream().use { ArchiveSafety.digest(it) {} } != hashes[part.name])
                        throw IOException("最终压缩包已变化，停止删除源文件")
                }
                plan.deleteVerifiedSources(::check, retainingOnInterruption = true)
            } else emptyList()
            runCatching { progress(100) } // Observers cannot turn an already committed deletion into failure.
            return Result(output, retained, options.deleteSources)
        } finally {
            if (!committed) published.asReversed().forEach { if (owned(it)) it.delete() }
            // Codec-owned names only; do not recursively delete an unknown file/tree injected here.
            stage.listFiles()?.forEach { file ->
                val codecName = file.name == archive.name || (options.format == "ZIP" && options.splitSize > 0 &&
                    file.name.matches(Regex(Regex.escape(stem) + "\\.z[0-9]+")))
                if (codecName && file.isFile && !ArchiveSafety.isLink(file) && (identities[file.path] == null || owned(file))) file.delete()
            }
            stage.delete()
        }
    }

    private fun create7z(plan: ArchiveSafety, file: File, o: Options) {
        RandomAccessFile(file, "rw").use { raf ->
            val streams = mutableSetOf<InputStream>()
            val out = SevenZip.openOutArchive7z()
            try {
                out.setHeaderEncryption(!o.password.isNullOrEmpty())
                out.setLevel(5); out.setSolid(true)
                out.setThreadCount(2)
                val total = plan.entries.sumOf { it.size }.coerceAtLeast(1)
                out.createArchive(RandomAccessFileOutStream(raf), plan.entries.size,
                    object : IOutCreateCallback<IOutItem7z>, ICryptoGetTextPassword {
                        override fun cryptoGetTextPassword(): String? = o.password
                        override fun setOperationResult(ok: Boolean) { if (!ok) throw SevenZipException("压缩失败") }
                        override fun setTotal(value: Long) { check() }
                        override fun setCompleted(value: Long) { check(); progress((value.toDouble() * 95 / total).toInt().coerceIn(0, 95)) }
                        override fun getItemInformation(index: Int, factory: OutItemFactory<IOutItem7z>): IOutItem7z {
                            check(); val e = plan.entries[index]
                            return factory.createOutItem().apply {
                                dataSize = e.size; propertyPath = e.name; propertyIsDir = e.directory
                                propertyLastModificationTime = Date(e.modified)
                            }
                        }
                        override fun getStream(index: Int): ISequentialInStream? {
                            check(); val e = plan.entries[index]
                            if (e.directory) return null
                            val stream = e.file.inputStream(); streams += stream
                            return object : ISequentialInStream {
                                override fun read(buffer: ByteArray): Int {
                                    check()
                                    val count = stream.read(buffer)
                                    if (count < 0) { close(); return 0 }
                                    return count
                                }
                                override fun close() { stream.close(); streams.remove(stream) }
                            }
                        }
                    })
            } finally { try { out.close() } finally { streams.forEach { it.close() } } }
        }
    }

    private fun createZip(plan: ArchiveSafety, file: File, o: Options) {
        // Stream the frozen inventory exactly once on this worker. No background Zip4j writer
        // may outlive cancellation, a progress exception or closing/removing our stage.
        net.lingala.zip4j.io.outputstream.SplitOutputStream(file, if (o.splitSize > 0) o.splitSize else -1L).use { raw ->
            net.lingala.zip4j.io.outputstream.ZipOutputStream(raw, o.password?.toCharArray()).use { zip ->
                val total = plan.entries.sumOf { it.size.toDouble() }.coerceAtLeast(1.0)
                var completed = 0.0
                plan.entries.forEachIndexed { index, entry ->
                    check()
                    val params = ZipParameters().apply {
                        fileNameInZip = entry.name + if (entry.directory) "/" else ""
                        lastModifiedFileTime = entry.modified; entrySize = entry.size
                        compressionMethod = net.lingala.zip4j.model.enums.CompressionMethod.DEFLATE
                        compressionLevel = net.lingala.zip4j.model.enums.CompressionLevel.NORMAL
                        if (!entry.directory && !o.password.isNullOrEmpty()) {
                            isEncryptFiles = true; encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES
                            aesKeyStrength = net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256
                        }
                    }
                    zip.putNextEntry(params)
                    if (!entry.directory) entry.file.inputStream().use { input -> copy(input, zip) { count ->
                        completed += count; progress((completed * 95 / total).toInt().coerceIn(0, 95))
                    } }
                    zip.closeEntry(); progress((index + 1) * 95 / plan.entries.size)
                }
            }
        }
    }

    private fun createTar(plan: ArchiveSafety, file: File, o: Options) {
        file.outputStream().buffered().use { raw ->
            val compressed = when (o.format) {
                "TAR_ONLY" -> raw
                "zstd" -> ZstdOutputStream(raw).apply { setLevel(3) }
                else -> CompressorStreamFactory().createCompressorOutputStream(o.format, raw)
            }
            TarArchiveOutputStream(compressed, "UTF-8").use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                plan.entries.forEachIndexed { i, e ->
                    check()
                    val item = TarArchiveEntry(e.name + if (e.directory) "/" else "").apply {
                        size = e.size; modTime = Date(e.modified)
                    }
                    tar.putArchiveEntry(item)
                    if (!e.directory) e.file.inputStream().use { input -> copy(input, tar) }
                    tar.closeArchiveEntry(); progress((i + 1) * 95 / plan.entries.size)
                }
                tar.finish()
            }
        }
    }

    private fun copy(input: InputStream, output: OutputStream, copied: (Int) -> Unit = {}) {
        val buffer = ByteArray(65536)
        while (true) { check(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n); copied(n) }
    }
    private fun normalized(name: String): String {
        val value = name.replace('\\', '/').trimEnd('/')
        if (value.startsWith('/') || value.split('/').any { it == ".." || it == "." || it.isEmpty() }) throw IOException("压缩包路径异常")
        return value
    }
    private fun verifyEntry(plan: ArchiveSafety, seen: MutableSet<String>, name: String, directory: Boolean, input: InputStream?) {
        check(); val path = normalized(name)
        if (!seen.add(path)) throw IOException("压缩包中有重复条目")
        val expected = plan.entriesByName[path] ?: throw IOException("压缩包含非预期条目: $path")
        if (expected.directory != directory) throw IOException("压缩包类型不匹配: $path")
        if (!directory && (input == null || ArchiveSafety.digest(input, ::check) != expected.hash)) throw IOException("压缩包内容校验失败: $path")
    }
    private fun finishVerify(plan: ArchiveSafety, seen: Set<String>) {
        if (seen != plan.entries.map { it.name }.toSet()) throw IOException("压缩包缺少源文件；源文件已保留")
    }
    private fun verifyZip(plan: ArchiveSafety, file: File, password: String?) {
        ZipFile(file, password?.toCharArray()).use { zip ->
            val seen = mutableSetOf<String>()
            zip.fileHeaders.forEach { header ->
                if (header.isDirectory) verifyEntry(plan, seen, header.fileName, true, null)
                else zip.getInputStream(header).use { verifyEntry(plan, seen, header.fileName, false, it) }
            }
            finishVerify(plan, seen)
        }
    }
    private fun verify7z(plan: ArchiveSafety, file: File, password: String?) {
        RandomAccessFile(file, "r").use { raf ->
            val input = SevenZip.openInArchive(null, RandomAccessFileInStream(raf), password)
            try {
                val seen = mutableSetOf<String>()
                // One pass is essential for solid archives: extractSlow per item repeatedly decodes the same block.
                input.extract(null, false, object : IArchiveExtractCallback, ICryptoGetTextPassword {
                    var expected: ArchiveSafety.Entry? = null
                    var hash = MessageDigest.getInstance("SHA-256")
                    override fun cryptoGetTextPassword(): String? = password
                    override fun getStream(index: Int, mode: ExtractAskMode): ISequentialOutStream? {
                        check(); expected = null
                        if (mode != ExtractAskMode.EXTRACT) return null
                        val name = normalized(input.getStringProperty(index, PropID.PATH))
                        val directory = input.getProperty(index, PropID.IS_FOLDER) == true
                        if (directory) { verifyEntry(plan, seen, name, true, null); return null }
                        val entry = plan.entriesByName[name]
                        if (!seen.add(name) || entry == null || entry.directory) throw IOException("7z 条目异常: $name")
                        expected = entry; hash = MessageDigest.getInstance("SHA-256")
                        return ISequentialOutStream { bytes -> check(); hash.update(bytes); bytes.size }
                    }
                    override fun prepareOperation(mode: ExtractAskMode) { check() }
                    override fun setOperationResult(result: ExtractOperationResult) {
                        check()
                        if (result != ExtractOperationResult.OK) throw IOException("7z 解压校验失败: $result")
                        val entry = expected
                        if (entry != null && hash.digest().joinToString("") { "%02x".format(it) } != entry.hash)
                            throw IOException("7z 内容校验失败: ${entry.name}")
                    }
                    override fun setTotal(total: Long) { check() }
                    override fun setCompleted(complete: Long) { check() }
                })
                finishVerify(plan, seen)
            } finally { input.close() }
        }
    }
    private fun verifyTar(plan: ArchiveSafety, file: File, format: String) {
        file.inputStream().buffered().use { raw ->
            val decoded = if (format == "TAR_ONLY") raw else CompressorStreamFactory().createCompressorInputStream(format, raw)
            TarArchiveInputStream(decoded, "UTF-8").use { tar ->
                val seen = mutableSetOf<String>()
                while (true) {
                    val e = tar.nextTarEntry ?: break
                    if (e.isSymbolicLink || e.isLink) throw IOException("压缩包包含链接")
                    verifyEntry(plan, seen, e.name, e.isDirectory, if (e.isDirectory) null else tar)
                }
                finishVerify(plan, seen)
                // TAR's end-of-archive can precede the compressor trailer/checksum.
                val remainder = ByteArray(65536)
                while (true) { check(); if (decoded.read(remainder) < 0) break }
            }
        }
    }
}
