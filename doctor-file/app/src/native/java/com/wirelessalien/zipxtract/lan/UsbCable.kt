package com.wirelessalien.zipxtract.lan

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Maps Android USB roles to a single direct, non-IP FileDoctorC link. */
internal class UsbCable(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onOffer: (LanReceiver.Offer) -> Unit,
    private val onProgress: (String, LanProgress) -> Unit,
    private val onResult: (String, LanBatchResult) -> Unit,
    private val refresh: () -> Unit
) {
    data class Status(val peer: UsbPeer? = null, val hint: String? = null)

    private val usb = context.getSystemService(UsbManager::class.java)
    private val permissionAction = context.packageName + ".USB_PERMISSION"
    private val _status = MutableStateFlow(Status())
    val status = _status.asStateFlow()
    private val requested = mutableSetOf<String>()
    private val probed = mutableSetOf<String>()
    private var link: UsbTransfer? = null
    private var attachedKey: String? = null
    private var startTime = 0L
    private var retryAt = 0L
    private var generation = 0
    private var opening = false

    init {
        if (usb != null) ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { refresh() }
        }, IntentFilter(permissionAction), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun reconcile(device: LanDevice?, visible: Boolean, active: Boolean) {
        if (usb == null) return
        if (device == null || (!visible && !active)) {
            reset()
            return
        }
        val accessory = usb.accessoryList.orEmpty().firstOrNull {
            it.manufacturer == MANUFACTURER && it.model == MODEL
        }
        val devices = usb.deviceList.values
        val host = devices.firstOrNull(::isAccessoryMode) ?: devices.firstOrNull(::isAndroidUsbCandidate)
        val key = accessory?.let { "accessory:$MANUFACTURER:$MODEL" } ?: host?.deviceName
        requested.retainAll(devices.mapTo(mutableSetOf()) { it.deviceName }.apply {
            if (accessory != null && key != null) add(key)
        })
        probed.retainAll(devices.map { it.deviceName }.toSet())
        if (key != attachedKey) reset(key)
        if (key == null) return
        if (opening || link != null) {
            if (_status.value.peer == null && SystemClock.elapsedRealtime() - startTime > 25_000) {
                reset(key)
                retryAt = SystemClock.elapsedRealtime() + 1500
                _status.value = Status(hint = "等待另一台设备开启 FileDoctorC 并完成 USB 授权")
            }
            return
        }
        if (!visible || SystemClock.elapsedRealtime() < retryAt) return
        if (accessory != null) {
            if (usb.hasPermission(accessory)) open(device) { accessoryPipe(accessory) }
            else request(key) { usb.requestPermission(accessory, permissionIntent()) }
        } else if (host != null) {
            if (!usb.hasPermission(host)) {
                request(key) { usb.requestPermission(host, permissionIntent()) }
            } else if (isAccessoryMode(host)) {
                open(device) { hostPipe(host) }
            } else if (probed.add(key)) {
                val round = generation
                _status.value = Status(hint = "正在协商 USB 直连，请在另一台设备确认配件授权")
                scope.launch(Dispatchers.IO) {
                    val supported = runCatching { startAccessoryMode(host) }.getOrDefault(false)
                    scope.launch(Dispatchers.Main.immediate) {
                        if (round == generation && !supported) {
                            _status.value = Status(hint = "对端未开放 USB 配件协议，请保持文件传输模式并重新插线")
                        }
                    }
                }
            }
        }
    }

    private fun request(key: String, run: () -> Unit) {
        _status.value = Status(hint = "请完成系统 USB 授权，并保持两端 FileDoctorC 打开")
        if (requested.add(key) && runCatching(run).isFailure) {
            _status.value = Status(hint = "USB 授权未能打开，请重新插线")
        }
    }

    private fun permissionIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 0, Intent(permissionAction).setPackage(context.packageName), flags)
    }

    private fun open(local: LanDevice, makePipe: () -> UsbPipe?) {
        val round = ++generation
        opening = true
        startTime = SystemClock.elapsedRealtime()
        _status.value = Status(hint = "正在连接 USB 对端…")
        scope.launch(Dispatchers.IO) {
            val pipe = runCatching(makePipe).getOrNull()
            scope.launch(Dispatchers.Main.immediate) opening@ {
                if (round != generation || pipe == null) {
                    if (pipe != null) scope.launch(Dispatchers.IO) { pipe.close() }
                    if (round == generation) failed()
                    return@opening
                }
                opening = false
                val session = UsbTransfer(pipe, { action -> scope.launch(Dispatchers.IO) { action() } },
                    onOffer, onProgress, onResult)
                link = session
                scope.launch(Dispatchers.IO) {
                    try {
                        session.listen(local) { peer ->
                            scope.launch(Dispatchers.Main.immediate) {
                                if (round == generation) _status.value = Status(peer)
                            }
                        }
                    } catch (_: Exception) {
                        session.close()
                    } finally {
                        scope.launch(Dispatchers.Main.immediate) {
                            if (round == generation) failed()
                        }
                    }
                }
            }
        }
    }

    private fun failed() {
        opening = false
        link = null
        retryAt = SystemClock.elapsedRealtime() + 1500
        _status.value = Status(hint = "等待另一台设备开启 FileDoctorC 并完成 USB 授权")
        refresh()
    }

    private fun reset(key: String? = null) {
        if (attachedKey == key && link == null && !opening) return
        val previous = link
        generation++
        attachedKey = key
        link = null
        opening = false
        retryAt = 0
        _status.value = Status()
        if (previous != null) scope.launch(Dispatchers.IO) { previous.close() }
    }

    private fun startAccessoryMode(device: UsbDevice): Boolean {
        val connection = usb.openDevice(device) ?: return false
        try {
            val protocol = ByteArray(2)
            val vendorIn = UsbConstants.USB_TYPE_VENDOR or UsbConstants.USB_DIR_IN
            val vendorOut = UsbConstants.USB_TYPE_VENDOR or UsbConstants.USB_DIR_OUT
            if (connection.controlTransfer(vendorIn, 51, 0, 0, protocol, 2, 1800) != 2) return false
            val version = (protocol[0].toInt() and 0xff) or ((protocol[1].toInt() and 0xff) shl 8)
            if (version !in 1..2) return false
            listOf(MANUFACTURER, MODEL, "FileDoctorC USB Transfer", "1").forEachIndexed { index, value ->
                val bytes = (value + '\u0000').toByteArray(Charsets.UTF_8)
                if (connection.controlTransfer(vendorOut, 52, 0, index, bytes, bytes.size, 1800) != bytes.size) {
                    return false
                }
            }
            return connection.controlTransfer(vendorOut, 53, 0, 0, null, 0, 1800) == 0
        } finally {
            connection.close()
        }
    }

    private fun hostPipe(device: UsbDevice): UsbPipe? {
        if (device.interfaceCount == 0) return null
        val iface = device.getInterface(0)
        val endpoints = (0 until iface.endpointCount).map(iface::getEndpoint)
            .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        val receive = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: return null
        val send = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: return null
        val connection = usb.openDevice(device) ?: return null
        if (!connection.claimInterface(iface, false)) {
            connection.close()
            return null
        }
        return HostPipe(connection, receive, send)
    }

    private fun accessoryPipe(accessory: UsbAccessory): UsbPipe? =
        usb.openAccessory(accessory)?.let(::AccessoryPipe)

    private fun isAccessoryMode(device: UsbDevice) =
        device.vendorId == 0x18d1 && device.productId in 0x2d00..0x2d01

    private fun isAndroidUsbCandidate(device: UsbDevice) = (0 until device.interfaceCount).any { index ->
        val iface = device.getInterface(index)
        val mtp = iface.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE &&
            iface.interfaceSubclass == 1 && iface.interfaceProtocol == 1 &&
            iface.name?.contains("PTP", ignoreCase = true) != true
        val adb = iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC &&
            iface.interfaceSubclass == 0x42 && iface.interfaceProtocol == 1
        mtp || adb
    }

    private companion object {
        const val MANUFACTURER = "DoctorC"
        const val MODEL = "FileDoctorC"
        // Accessory reads consume a USB request at once. Use full 16 KiB blocks even for control frames.
        const val USB_BLOCK = 16 * 1024
        const val HOST_MAX = 256 * 1024
    }

    private abstract class BulkInput(blockSize: Int = USB_BLOCK) : InputStream() {
        private val block = ByteArray(blockSize)
        private var at = 0
        private var end = 0
        abstract fun readBlock(destination: ByteArray): Int

        private fun ready(): Boolean {
            while (at == end) {
                val length = readBlock(block)
                if (length < 0) return false
                end = length
                at = 0
            }
            return true
        }

        override fun read(): Int = if (ready()) block[at++].toInt() and 0xff else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (!ready()) return -1
            val count = minOf(length, end - at)
            block.copyInto(buffer, offset, at, at + count)
            at += count
            return count
        }
    }

    private class HostPipe(
        private val connection: UsbDeviceConnection,
        private val receive: UsbEndpoint,
        private val send: UsbEndpoint
    ) : UsbPipe {
        private val closed = AtomicBoolean()
        override val host = true
        private val asyncRead = if (Build.VERSION.SDK_INT >= 28) UsbRequest().let { request ->
            if (request.initialize(connection, receive)) request else { request.close(); null }
        } else null
        private val directRead = if (asyncRead == null) null else ByteBuffer.allocateDirect(HOST_MAX)
        private var useDirectRead = directRead != null

        override val input: InputStream = object : BulkInput(if (directRead == null) USB_BLOCK else HOST_MAX) {
            override fun readBlock(destination: ByteArray): Int {
                if (Build.VERSION.SDK_INT >= 28 && useDirectRead && directRead != null && asyncRead != null) {
                    if (closed.get()) return -1
                    directRead.clear()
                    if (runCatching { asyncRead.queue(directRead) }.getOrDefault(false)) {
                        while (!closed.get()) {
                            try {
                                if (connection.requestWait(1000) !== asyncRead) return -1
                                val count = directRead.position()
                                directRead.flip()
                                directRead.get(destination, 0, count)
                                return count
                            } catch (_: TimeoutException) {
                                // Keep the same request queued without discarding bytes already in flight.
                            }
                        }
                        return -1
                    }
                    useDirectRead = false
                }
                while (!closed.get()) {
                    val count = connection.bulkTransfer(receive, destination, USB_BLOCK, 1000)
                    if (count > 0) return count
                }
                return -1
            }
        }
        override val output: OutputStream = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                var sent = 0
                while (sent < length) {
                    if (closed.get()) throw IOException("USB 连接已断开")
                    val limit = if (Build.VERSION.SDK_INT >= 28) HOST_MAX else USB_BLOCK
                    val available = minOf(limit, length - sent)
                    // AOA gadget reads finish on 16 KiB or a short packet. A final
                    // packet-aligned fragment would otherwise require a USB ZLP.
                    val packet = send.maxPacketSize
                    val amount = if (packet > 1 && available % USB_BLOCK != 0 && available % packet == 0)
                        available - 1 else available
                    val count = connection.bulkTransfer(send, buffer, offset + sent, amount, 8000)
                    if (count <= 0) throw IOException("USB 写入超时或已断开")
                    sent += count
                }
            }
        }
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { asyncRead?.cancel() }
            connection.close()
            runCatching { asyncRead?.close() }
        }
    }

    private class AccessoryPipe(private val descriptor: ParcelFileDescriptor) : UsbPipe {
        private val closed = AtomicBoolean()
        private val reader = FileInputStream(descriptor.fileDescriptor)
        private val writer = FileOutputStream(descriptor.fileDescriptor)
        override val host = false
        override val input: InputStream = object : BulkInput() {
            override fun readBlock(destination: ByteArray): Int =
                if (closed.get()) -1 else reader.read(destination)
        }
        override val output: OutputStream = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (closed.get()) throw IOException("USB 连接已断开")
                // The gadget driver splits a large write across its own TX requests.
                writer.write(buffer, offset, length)
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) descriptor.close()
        }
    }
}
