package com.example.clmplayer

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal interface ClmSource : Closeable {
    val size: Long
    fun readAt(position: Long, target: ByteArray, offset: Int, count: Int): Int
}
internal data class ClmEntry(val offset: Long, val length: Long, val noncePrefix: ByteArray, val ordinal: Int, val mime: String)

internal class ClmContainer(private val source: ClmSource, password: String) : Closeable {
    companion object {
        val MAGIC = byteArrayOf(67, 76, 77, 50, 69, 78, 67, 0)
        const val CHUNK_SIZE = 65536
        const val ITERATIONS = 210000
        fun storedLength(length: Long): Long {
            require(length > 0 && length <= 256L * 1024 * 1024 * 1024) { "条目长度超限" }
            return length + (length + CHUNK_SIZE - 1) / CHUNK_SIZE * 16
        }
    }
    private val key: ByteArray
    val manifest: String
    val payloadStart: Long
    val id: String
    private var closed = false
    init {
        try {
            val header = readExact(0, 64)
            require(header.copyOfRange(0, 8).contentEquals(MAGIC)) { "请使用电脑端重新封装为加密 CLM 2 格式" }
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(b.getInt(8) == 2 && b.getInt(12) == CHUNK_SIZE && b.getInt(16) == ITERATIONS) { "不支持的 CLM 协议参数" }
            require(header.copyOfRange(52, 64).all { it == 0.toByte() }) { "CLM 文件头无效" }
            val length = b.getInt(20)
            require(length in 1..4 * 1024 * 1024 && source.size >= 64L + length + 16) { "CLM 目录长度无效" }
            val salt = header.copyOfRange(24, 40)
            val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
            key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, header.copyOfRange(40, 52)))
            cipher.updateAAD(header)
            manifest = cipher.doFinal(readExact(64, length + 16)).toString(Charsets.UTF_8)
            payloadStart = 64L + length + 16
            id = salt.joinToString("") { "%02x".format(it.toInt() and 255) }
        } catch (e: Exception) { source.close(); throw IOException("CLM 无法打开：格式错误、密钥不匹配或文件损坏", e) }
    }
    @Synchronized private fun readExact(position: Long, count: Int): ByteArray {
        if (closed) throw IOException("CLM 已关闭")
        if (position < 0 || count < 0 || position > source.size - count) throw IOException("CLM 数据越界")
        val data = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = source.readAt(position + read, data, read, count - read)
            if (n <= 0) throw IOException("CLM 文件不完整")
            read += n
        }
        return data
    }
    fun validate(entries: List<ClmEntry>) {
        require(entries.size in 2..100001) { "目录数量无效" }
        var expectedOffset = 0L
        val nonces = mutableSetOf(readExact(40, 8).joinToString())
        for ((i, e) in entries.withIndex()) {
            require(e.ordinal == i && e.offset == expectedOffset && e.noncePrefix.size == 8) { "CLM 条目目录无效" }
            require(nonces.add(e.noncePrefix.joinToString())) { "CLM 加密参数重复" }
            expectedOffset = Math.addExact(expectedOffset, storedLength(e.length))
            require(payloadStart <= source.size && expectedOffset <= source.size - payloadStart) { "CLM 条目超出文件" }
        }
        require(payloadStart + expectedOffset == source.size) { "CLM 尾部长度不匹配" }
    }
    fun reader(entry: ClmEntry) = EntryReader(entry)
    inner class EntryReader(private val entry: ClmEntry) : Closeable {
        private var blockIndex = -1L
        private var block = ByteArray(0)
        private var ended = false
        val size get() = entry.length
        @Synchronized fun readAt(position: Long, target: ByteArray, offset: Int, count: Int): Int {
            if (ended || closed) throw IOException("音轨已关闭")
            require(position >= 0 && offset >= 0 && count >= 0 && offset <= target.size - count)
            if (count == 0) return 0
            if (position >= entry.length) return -1
            var pos = position
            val needed = minOf(count.toLong(), entry.length - pos).toInt()
            var written = 0
            while (written < needed) {
                val index = pos / CHUNK_SIZE
                if (index != blockIndex) {
                    val plainLength = minOf(CHUNK_SIZE.toLong(), entry.length - index * CHUNK_SIZE).toInt()
                    val encrypted = readExact(payloadStart + entry.offset + index * (CHUNK_SIZE + 16L), plainLength + 16)
                    val nonce = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).put(entry.noncePrefix).putInt(index.toInt()).array()
                    val aad = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).put(MAGIC).putInt(entry.ordinal).putInt(index.toInt()).putInt(plainLength).array()
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                    cipher.updateAAD(aad)
                    block.fill(0)
                    block = try { cipher.doFinal(encrypted) } catch (e: Exception) { throw IOException("音轨数据校验失败", e) }
                    blockIndex = index
                }
                val start = (pos % CHUNK_SIZE).toInt()
                val n = minOf(block.size - start, needed - written)
                block.copyInto(target, offset + written, start, start + n)
                pos += n; written += n
            }
            return written
        }
        override fun close() { ended = true; block.fill(0); block = ByteArray(0) }
    }
    override fun close() { closed = true; key.fill(0); source.close() }
}
