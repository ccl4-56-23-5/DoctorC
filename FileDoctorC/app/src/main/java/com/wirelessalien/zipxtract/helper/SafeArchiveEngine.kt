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

/** Shared by all three creation dialogs. Writes in an isolated staging directory, verifies, then publishes. */
class SafeArchiveEngine(private val cancelled: () -> Boolean = { false },
                        private val progress: (Int) -> Unit = {},
                        private val phase: (String) -> Unit = {}) {
    data class Options(val format: String, val name: String, val outputDir: File,
        val password: String? = null, val level: Int = 5, val solid: Boolean = false,
        val threads: Int = 2, val zipParameters: ZipParameters = ZipParameters(),
        val splitSize: Long = 0, val deleteSources: Boolean = false)
    data class Result(val archive: File, val retainedSources: List<String>, val deleteRequested: Boolean)
    private fun check() = ArchiveSafety.checkCancelled(cancelled)

    fun create(paths: List<String>, options: Options): Result {
        require(options.name.isNotBlank() && options.name != "." && options.name != ".." &&
            options.name.none { it == '/' || it == '\\' || it == '\u0000' }) { "压缩包名称不能含路径分隔符" }
        phase("正在检查源文件…")
        val plan = ArchiveSafety.capture(paths, ::check)
        plan.assertOutputOutsideSources(options.outputDir)
        if (!options.outputDir.isDirectory && !options.outputDir.mkdirs()) throw IOException("无法创建输出目录")
        val extension = when (options.format) {
            "7Z" -> ".7z"; "ZIP" -> ".zip"; "TAR_ONLY" -> ".tar"
            "gz" -> ".tar.gz"; "bzip2" -> ".tar.bz2"; "xz" -> ".tar.xz"
            "lzma" -> ".tar.lzma"; "zstd" -> ".tar.zst"
            else -> throw IOException("不支持的压缩格式: ${options.format}")
        }
        var stem = options.name.removeSuffix(extension)
        val originalStem = stem
        var suffix = 0
        fun collision(): Boolean = options.outputDir.listFiles()?.any {
            it.name == stem + extension || (options.splitSize > 0 && it.name.matches(Regex(Regex.escape(stem) + "\\.z[0-9]+")))
        } ?: true
        while (collision()) { check(); suffix++; stem = "$originalStem ($suffix)" }
        val stage = File(options.outputDir, ".sevenzip-${UUID.randomUUID()}")
        if (!stage.mkdir()) throw IOException("无法创建临时输出目录")
        val archive = File(stage, stem + extension)
        val published = mutableListOf<File>()
        try {
            phase("正在压缩…")
            when (options.format) {
                "7Z" -> create7z(plan, archive, options)
                "ZIP" -> createZip(plan, archive, options)
                else -> createTar(plan, archive, options)
            }
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
            val parts = (stage.listFiles() ?: throw IOException("临时压缩包丢失")).sortedBy { it == archive }
            for (part in parts) {
                check()
                val target = File(options.outputDir, part.name)
                if (!target.createNewFile()) throw IOException("输出文件冲突；源文件已保留")
                if (!part.renameTo(target)) { target.delete(); throw IOException("无法保存压缩包；源文件已保留") }
                published += target
            }
            val output = File(options.outputDir, archive.name)
            val retained = if (options.deleteSources) {
                phase("校验通过，正在删除源文件…")
                plan.deleteVerifiedSources(::check)
            } else emptyList()
            progress(100)
            return Result(output, retained, options.deleteSources)
        } finally {
            // Only this uniquely owned directory, never application filesDir or user directories.
            stage.listFiles()?.forEach { if (it.isFile) it.delete() }
            stage.delete()
        }
    }

    private fun create7z(plan: ArchiveSafety, file: File, o: Options) {
        RandomAccessFile(file, "rw").use { raf ->
            val streams = mutableSetOf<InputStream>()
            val out = SevenZip.openOutArchive7z()
            try {
                out.setHeaderEncryption(!o.password.isNullOrEmpty())
                out.setLevel(o.level.coerceIn(0, 9)); out.setSolid(o.solid)
                out.setThreadCount(if (o.threads <= 0) Runtime.getRuntime().availableProcessors().coerceAtMost(4) else o.threads.coerceIn(1, 8))
                val total = plan.entries.sumOf { it.size }.coerceAtLeast(1)
                out.createArchive(RandomAccessFileOutStream(raf), plan.entries.size,
                    object : IOutCreateCallback<IOutItem7z>, ICryptoGetTextPassword {
                        override fun cryptoGetTextPassword(): String? = o.password
                        override fun setOperationResult(ok: Boolean) { if (!ok) throw SevenZipException("压缩失败") }
                        override fun setTotal(value: Long) { check() }
                        override fun setCompleted(value: Long) { check(); progress((value * 95 / total).toInt().coerceIn(0, 95)) }
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
        val zip = ZipFile(file, o.password?.toCharArray())
        val params = ZipParameters(o.zipParameters).apply { defaultFolderPath = plan.base.path }
        zip.isRunInThread = true
        try {
            if (o.splitSize > 0) zip.createSplitZipFile(plan.entries.map { it.file }, params, true, o.splitSize)
            else zip.addFiles(plan.entries.map { it.file }, params)
            while (zip.progressMonitor.state != net.lingala.zip4j.progress.ProgressMonitor.State.READY) {
                if (cancelled()) zip.progressMonitor.isCancelAllTasks = true
                progress(zip.progressMonitor.percentDone.coerceIn(0, 95))
                Thread.sleep(50)
            }
            check()
            if (zip.progressMonitor.result != net.lingala.zip4j.progress.ProgressMonitor.Result.SUCCESS)
                throw IOException("ZIP 压缩失败", zip.progressMonitor.exception)
        } finally { zip.close() }
    }

    private fun createTar(plan: ArchiveSafety, file: File, o: Options) {
        file.outputStream().buffered().use { raw ->
            val compressed = when (o.format) {
                "TAR_ONLY" -> raw
                "zstd" -> ZstdOutputStream(raw).apply { setLevel(o.level.coerceIn(1, 22)) }
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

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(65536)
        while (true) { check(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
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
            }
        }
    }
}
