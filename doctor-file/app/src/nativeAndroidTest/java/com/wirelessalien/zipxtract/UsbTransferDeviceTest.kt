package com.wirelessalien.zipxtract

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wirelessalien.zipxtract.lan.LanDevice
import com.wirelessalien.zipxtract.lan.LanReceiver
import com.wirelessalien.zipxtract.lan.LanShape
import com.wirelessalien.zipxtract.lan.UsbPipe
import com.wirelessalien.zipxtract.lan.UsbTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FilterOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class UsbTransferDeviceTest {
    @Test fun bothUsbRolesSendFilesAndEmptyDirectoriesOnTheSameLink() {
        linked { root, host, accessory, hostFolder, accessoryFolder, _, _ ->
            val source = File(root, "example.bin").apply { writeBytes(ByteArray(664_000) { (it % 251).toByte() }) }
            val empty = File(root, "empty").apply { check(mkdir()) }
            val forward = host.send(listOf(source, empty)) {}
            assertNull(forward.error)
            assertEquals(1L, forward.files)
            assertEquals(1L, forward.folders)
            assertTrue(File(accessoryFolder, empty.name).isDirectory)
            assertTrue(source.readBytes().contentEquals(File(accessoryFolder, source.name).readBytes()))

            val backward = accessory.send(listOf(File(accessoryFolder, source.name))) {}
            assertNull(backward.error)
            assertEquals(1L, backward.files)
            assertTrue(source.readBytes().contentEquals(File(hostFolder, source.name).readBytes()))
        }
    }

    @Test fun cancellingAnUnapprovedUsbOfferAllowsAnotherTransfer() {
        linked { root, host, _, _, accessoryFolder, automaticallyAccept, pending ->
            val file = File(root, "example.txt").apply { writeText("USB fixture") }
            automaticallyAccept.set(false)
            val waiting = Executors.newSingleThreadExecutor()
            try {
                val transfer = waiting.submit<com.wirelessalien.zipxtract.lan.LanBatchResult> { host.send(listOf(file)) {} }
                val offer = awaitOffer(pending)
                assertFalse(File(accessoryFolder, file.name).exists())
                host.cancelSend()
                assertEquals("发送已取消", transfer.get(5, TimeUnit.SECONDS).error)
                assertNull(offer.decision.get(5, TimeUnit.SECONDS))
                automaticallyAccept.set(true)
                val next = host.send(listOf(file)) {}
                assertNull(next.error)
                assertEquals(file.readText(), File(accessoryFolder, file.name).readText())
            } finally {
                waiting.shutdownNow()
            }
        }
    }

    @Test fun receiverCanCancelPayloadAndSendAgainOnTheSameCable() {
        val dataInFlight = CountDownLatch(1)
        val continueWriting = CountDownLatch(1)
        val holdOnce = AtomicBoolean(true)
        linked(onHostChunk = {
            if (holdOnce.compareAndSet(true, false)) {
                dataInFlight.countDown()
                check(continueWriting.await(5, TimeUnit.SECONDS))
            }
        }) { root, host, _, _, accessoryFolder, auto, pending ->
            val file = File(root, "payload.bin").apply {
                outputStream().use { sink -> repeat(32) { sink.write(ByteArray(256 * 1024)) } }
            }
            auto.set(false)
            val worker = Executors.newSingleThreadExecutor()
            try {
                val sending = worker.submit<com.wirelessalien.zipxtract.lan.LanBatchResult> { host.send(listOf(file)) {} }
                val offer = awaitOffer(pending)
                assertTrue(offer.decision.complete(accessoryFolder))
                assertTrue(dataInFlight.await(5, TimeUnit.SECONDS))
                offer.cancelReceive()
                continueWriting.countDown()
                assertEquals("接收端已取消", sending.get(8, TimeUnit.SECONDS).error)
                assertTrue(accessoryFolder.listFiles().orEmpty().isEmpty())
                auto.set(true)
                assertNull(host.send(listOf(file)) {}.error)
                assertEquals(file.length(), File(accessoryFolder, file.name).length())
            } finally {
                continueWriting.countDown()
                worker.shutdownNow()
            }
        }
    }

    private fun awaitOffer(pending: AtomicReference<LanReceiver.Offer?>): LanReceiver.Offer {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            pending.get()?.let { return it }
            Thread.sleep(15)
        }
        throw AssertionError("USB offer missing")
    }

    private fun linked(
        onHostChunk: () -> Unit = {},
        exercise: (File, UsbTransfer, UsbTransfer, File, File, AtomicBoolean, AtomicReference<LanReceiver.Offer?>) -> Unit
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(checkNotNull(context.getExternalFilesDir(null)), "usb-device-test-${System.nanoTime()}")
            .apply { check(mkdir()) }
        val hostFolder = File(root, "host").apply { check(mkdir()) }
        val accessoryFolder = File(root, "accessory").apply { check(mkdir()) }
        val tasks = Executors.newCachedThreadPool()
        val ready = CountDownLatch(2)
        val accept = AtomicBoolean(true)
        val pending = AtomicReference<LanReceiver.Offer?>()
        val loopback = InetAddress.getLoopbackAddress()
        try {
            ServerSocket(0, 1, loopback).use { server ->
                Socket(loopback, server.localPort).use { a ->
                    server.accept().use { b ->
                        fun session(socket: Socket, host: Boolean, folder: File): UsbTransfer {
                            val pipe = object : UsbPipe {
                                override val host = host
                                override val input = socket.getInputStream()
                                override val output = object : FilterOutputStream(socket.getOutputStream()) {
                                    override fun write(bytes: ByteArray, offset: Int, count: Int) {
                                        out.write(bytes, offset, count)
                                        if (host && count >= 256 * 1024) onHostChunk()
                                    }
                                }
                                override fun close() = socket.close()
                            }
                            return UsbTransfer(pipe, { action -> tasks.execute { action() } }, {
                                if (accept.get()) it.decision.complete(folder) else pending.set(it)
                            }, { _, _ -> }, { _, _ -> })
                        }
                        session(a, true, hostFolder).use { host ->
                            session(b, false, accessoryFolder).use { accessory ->
                                tasks.execute { runCatching { host.listen(HOST) { ready.countDown() } } }
                                tasks.execute { runCatching { accessory.listen(ACCESSORY) { ready.countDown() } } }
                                assertTrue("Both USB roles completed handshake", ready.await(5, TimeUnit.SECONDS))
                                exercise(root, host, accessory, hostFolder, accessoryFolder, accept, pending)
                            }
                        }
                    }
                }
            }
        } finally {
            tasks.shutdownNow()
            tasks.awaitTermination(5, TimeUnit.SECONDS)
            root.deleteRecursively()
        }
    }

    private companion object {
        val HOST = LanDevice("Test Host", "", LanShape.PHONE)
        val ACCESSORY = LanDevice("Test Accessory", "", LanShape.PHONE)
    }
}
