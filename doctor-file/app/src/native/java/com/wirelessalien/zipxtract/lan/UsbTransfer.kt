package com.wirelessalien.zipxtract.lan

import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal interface UsbPipe : AutoCloseable {
    val host: Boolean
    val input: InputStream
    val output: OutputStream
}

internal data class UsbPeer(val device: LanDevice, val session: UsbTransfer)

/** One negotiated USB link. Control messages are bounded and one batch owns the data stream. */
internal class UsbTransfer(
    private val pipe: UsbPipe,
    private val dispatch: (() -> Unit) -> Unit,
    private val onOffer: (LanReceiver.Offer) -> Unit,
    private val onProgress: (String, LanProgress) -> Unit,
    private val onResult: (String, LanBatchResult) -> Unit
) : AutoCloseable {
    private class Sending {
        val id = UUID.randomUUID().toString()
        val cancelled = AtomicBoolean()
        val approval = CompletableFuture<Boolean>()
        val result = CompletableFuture<LanBatchResult>()
    }

    private class Receiving(val offer: LanReceiver.Offer, val tree: LanTreeOffer) {
        var folder: File? = null
    }

    private val closed = AtomicBoolean()
    private val state = Any()
    private val writer = Any()
    private val input = DataInputStream(pipe.input)
    private val output = DataOutputStream(BufferedOutputStream(pipe.output, 16 * 1024))
    private var sending: Sending? = null
    private var receiving: Receiving? = null
    private lateinit var remote: LanDevice

    fun listen(local: LanDevice, onReady: (UsbPeer) -> Unit) {
        try {
            if (pipe.host) writeHello(local)
            val hello = readControl()
            require(hello.getString("type") == MAGIC)
            val shape = LanShape.entries.firstOrNull { it.name == hello.optString("shape") } ?: LanShape.PHONE
            remote = LanDevice(hello.optString("model").cleanName().ifBlank { "USB 设备" },
                hello.optString("brand").cleanName(), shape)
            if (!pipe.host) writeHello(local)
            onReady(UsbPeer(remote, this))
            while (!closed.get()) {
                val frame = readControl()
                val id = frame.getString("id")
                require(LanFile.identifier.matches(id)) { "无效的 USB 传输编号" }
                when (frame.getString("type")) {
                    "offer" -> offer(id, LanTreeOffer.parse(frame.getJSONObject("tree")))
                    "decision" -> synchronized(state) {
                        sending?.takeIf { it.id == id }?.approval?.complete(frame.getBoolean("accepted"))
                    }
                    "data" -> receive(id)
                    "cancel" -> reject(id)
                    "result" -> synchronized(state) {
                        sending?.takeIf { it.id == id }?.result?.complete(readResult(frame))
                    }
                    else -> throw IOException("USB 传输协议版本不兼容")
                }
            }
        } finally {
            close()
        }
    }

    fun send(files: List<File>, progress: (LanProgress) -> Unit): LanBatchResult {
        val tx = synchronized(state) {
            if (closed.get()) return LanBatchResult(error = "USB 连接已断开")
            if (sending != null || receiving != null) return LanBatchResult(error = "USB 连接正在处理另一批文件")
            Sending().also { sending = it }
        }
        var offered = false
        var started = false
        return try {
            progress(LanProgress("正在扫描所选项目…"))
            val plan = LanTreePlan.scan(files) { checkSend(tx) }
            checkSend(tx)
            progress(LanProgress("等待 USB 接收端确认…"))
            sendControl(frame("offer", tx.id).put("tree", plan.offer.json()))
            offered = true
            val approved = tx.approval.get(135, TimeUnit.SECONDS)
            checkSend(tx)
            if (!approved) return LanBatchResult(error = "接收端已拒绝或请求已过期")
            synchronized(writer) {
                checkSend(tx)
                writeControl(frame("data", tx.id))
                started = true
                val pipeOutput = PayloadOutput(output)
                try {
                    plan.write(pipeOutput, { checkSend(tx) }, progress)
                    pipeOutput.finish(0)
                } catch (e: Exception) {
                    if (!closed.get()) runCatching { pipeOutput.finish(-1) }.onFailure { close() }
                    throw e
                }
            }
            progress(LanProgress("等待接收端校验…", 99))
            val result = tx.result.get(35, TimeUnit.SECONDS)
            require(result.files in 0..plan.offer.fileCount && result.folders in 0..plan.offer.folderCount)
            if (result.error == null) {
                require(result.files == plan.offer.fileCount && result.folders == plan.offer.folderCount)
            }
            result
        } catch (e: Exception) {
            if (offered && !started && !closed.get()) {
                runCatching { sendControl(frame("cancel", tx.id)) }.onFailure { close() }
            }
            if (e is TimeoutException && started) close()
            val error = when {
                tx.cancelled.get() -> "发送已取消"
                closed.get() -> "USB 连接已断开"
                e is TimeoutException -> "USB 传输等待超时"
                else -> e.message ?: "USB 发送失败"
            }
            LanBatchResult(error = error, countsConfirmed = !started)
        } finally {
            synchronized(state) { if (sending === tx) sending = null }
        }
    }

    fun cancelSend() = synchronized(state) {
        sending?.let { tx ->
            tx.cancelled.set(true)
            tx.approval.complete(false)
        }
    }

    private fun offer(id: String, tree: LanTreeOffer) {
        val receive = synchronized(state) {
            if (sending != null || receiving != null) null else Receiving(
                LanReceiver.Offer(id, remote.model, "USB 有线", tree.fileCount, tree.folderCount,
                    tree.rootCount, tree.preview, CompletableFuture()), tree).also { receiving = it }
        }
        if (receive == null) {
            sendControl(frame("decision", id).put("accepted", false))
            return
        }
        onOffer(receive.offer)
        dispatch {
            try {
                val folder = try { receive.offer.decision.get(120, TimeUnit.SECONDS) }
                catch (_: TimeoutException) { null }
                val allowed = folder != null && folder.isDirectory && folder.canWrite() &&
                    receive.tree.contentBytes <= folder.usableSpace
                synchronized(writer) {
                    synchronized(state) {
                        if (receiving !== receive || closed.get()) return@dispatch
                        if (allowed) receive.folder = folder else receiving = null
                    }
                    writeControl(frame("decision", id).put("accepted", allowed))
                }
                receive.offer.decision.complete(null)
                if (!allowed && folder != null) onResult(id, LanBatchResult(error = "目标目录不可写或空间不足"))
            } catch (_: Exception) {
                close()
            }
        }
    }

    private fun receive(id: String) {
        val (active, folder) = synchronized(state) {
            val pending = receiving?.takeIf { it.offer.id == id } ?: throw IOException("未确认的 USB 文件")
            pending to (pending.folder ?: throw IOException("未确认 USB 接收目录"))
        }
        val body = PayloadInput(input)
        val completed = LanCompleted()
        val progress = LanBatchProgress(active.tree.fileCount + active.tree.folderCount, active.tree.contentBytes) {
            onProgress(id, it)
        }
        val result = try {
            LanTreeDestination.receive(folder, active.tree, body, completed,
                { if (closed.get()) throw IOException("USB 连接已断开") }, progress)
            require(body.read() == -1) { "USB 文件内容超出清单长度" }
            completed.result()
        } catch (_: PayloadAborted) {
            completed.result("发送端已取消")
        } catch (e: Exception) {
            clearReceive(active, completed.result(e.message ?: "USB 接收失败"))
            throw e
        }
        synchronized(writer) {
            clearReceive(active, result)
            writeControl(result.json().put("type", "result").put("id", id))
        }
    }

    private fun reject(id: String) {
        val active = synchronized(state) {
            receiving?.takeIf { it.offer.id == id }?.also { receiving = null }
        } ?: return
        active.offer.decision.complete(null)
        onResult(id, LanBatchResult(error = "发送端已取消", countsConfirmed = false))
    }

    private fun clearReceive(active: Receiving, result: LanBatchResult) {
        val cleared = synchronized(state) {
            if (receiving !== active) false else { receiving = null; true }
        }
        if (cleared) onResult(active.offer.id, result)
    }

    private fun checkSend(tx: Sending) {
        if (tx.cancelled.get()) throw IOException("发送已取消")
        if (closed.get()) throw IOException("USB 连接已断开")
    }

    private fun writeHello(local: LanDevice) = sendControl(JSONObject().put("type", MAGIC)
        .put("model", local.model.cleanName()).put("brand", local.brand.cleanName()).put("shape", local.shape.name))

    private fun readControl(): JSONObject {
        val length = input.readInt()
        require(length in 1..MAX_CONTROL) { "USB 控制信息超长" }
        return JSONObject(ByteArray(length).also(input::readFully).toString(Charsets.UTF_8))
    }

    private fun sendControl(frame: JSONObject) = synchronized(writer) { writeControl(frame) }

    private fun writeControl(frame: JSONObject) {
        if (closed.get()) throw IOException("USB 连接已断开")
        val bytes = frame.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_CONTROL)
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val active = synchronized(state) {
            sending?.approval?.complete(false)
            sending?.result?.complete(LanBatchResult(error = "USB 连接已断开", countsConfirmed = false))
            receiving.also { receiving = null }
        }
        active?.let {
            it.offer.decision.complete(null)
            onResult(it.offer.id, LanBatchResult(error = "USB 连接已断开", countsConfirmed = false))
        }
        runCatching { pipe.close() }
    }

    private companion object {
        const val MAGIC = "FileDoctorC.USB.1"
        const val MAX_CONTROL = 384 * 1024
        const val MAX_PACKET = 256 * 1024
        fun frame(type: String, id: String) = JSONObject().put("type", type).put("id", id)
        fun String.cleanName() = filterNot { it.isISOControl() || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' }
            .trim().take(60)
        fun readResult(data: JSONObject) = LanBatchResult(data.getLong("files"), data.getLong("folders"),
            data.getLong("modifiedWarnings"), data.getLong("accessedWarnings"),
            if (data.isNull("error")) null else data.getString("error").cleanName())
    }

    private class PayloadOutput(private val stream: DataOutputStream) : OutputStream() {
        override fun write(value: Int) {
            stream.writeInt(1)
            stream.write(value)
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            var sent = 0
            while (sent < length) {
                val count = minOf(MAX_PACKET, length - sent)
                stream.writeInt(count)
                stream.write(buffer, offset + sent, count)
                sent += count
            }
        }
        fun finish(marker: Int) {
            stream.writeInt(marker)
            stream.flush()
        }
    }

    private class PayloadAborted : IOException("发送端已取消")

    private class PayloadInput(private val stream: DataInputStream) : InputStream() {
        private var remaining = 0
        private var ended = false
        private fun availableChunk(): Boolean {
            if (ended) return false
            if (remaining > 0) return true
            remaining = stream.readInt()
            if (remaining == -1) throw PayloadAborted()
            require(remaining in 0..MAX_PACKET) { "无效的 USB 数据块长度" }
            ended = remaining == 0
            return !ended
        }
        override fun read(): Int {
            if (!availableChunk()) return -1
            remaining--
            return stream.readUnsignedByte()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (!availableChunk()) return -1
            val count = minOf(length, remaining)
            stream.readFully(buffer, offset, count)
            remaining -= count
            return count
        }
    }
}
