package com.wirelessalien.zipxtract.lan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.window.layout.WindowMetricsCalculator
import com.wirelessalien.zipxtract.helper.AppEvent
import com.wirelessalien.zipxtract.helper.EventBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

internal enum class LanShape(val label: String) {
    PHONE("手机"), TABLET("平板"), PC("PC"), FOLDABLE("折叠屏")
}

internal data class LanDevice(val model: String, val brand: String, val shape: LanShape) {
    companion object {
        fun current(activity: Activity, hasFold: Boolean = false): LanDevice {
            val metrics = WindowMetricsCalculator.getOrCreate().computeMaximumWindowMetrics(activity).bounds
            val shortDp = minOf(metrics.width(), metrics.height()) / activity.resources.displayMetrics.density
            val hinge = activity.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_HINGE_ANGLE) != null
            val shape = when {
                activity.packageManager.hasSystemFeature("android.hardware.type.pc") -> LanShape.PC
                hasFold || hinge -> LanShape.FOLDABLE
                shortDp >= 600f -> LanShape.TABLET
                else -> LanShape.PHONE
            }
            return LanDevice(Build.MODEL.ifBlank { "FileDoctorC" },
                Build.BRAND.takeUnless { it.isBlank() || it.equals("unknown", true) } ?: Build.MANUFACTURER, shape)
        }
    }
}

internal data class LanPeer(val endpoint: LanEndpoint, val fingerprint: String, val device: LanDevice,
                            val supportsTree: Boolean, val route: LanSubnet)

/** LAN tasks and the receiving socket belong to the app process, independently of page lifetimes. */
internal class LanForeground(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _pending = MutableStateFlow<LanReceiver.Offer?>(null)
    val pending = _pending.asStateFlow()
    private val _sending = MutableStateFlow<LanTask?>(null)
    val sending = _sending.asStateFlow()
    private val _receiving = MutableStateFlow<LanTask?>(null)
    val receiving = _receiving.asStateFlow()
    private var sender: LanSender? = null
    private var visible = 0
    private var device: LanDevice? = null
    private var networkWatched = false
    private class Listener(val receiver: LanReceiver, val fingerprint: String) {
        var advertisement: LanAdvertisement? = null
        var advertisedDevice: LanDevice? = null
    }

    private val listeners = linkedMapOf<LanSubnet, Listener>()
    private var pendingRoute: LanSubnet? = null
    private var receivingRoute: LanSubnet? = null
    private val refreshPoll = Runnable { schedule() }
    private val refresh = Runnable { scope.launch {
        reconcile()
        main.removeCallbacks(refreshPoll)
        if (needsListener()) main.postDelayed(refreshPoll, 1800)
    } }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = schedule()
        override fun onLost(network: Network) = schedule()
        override fun onLinkPropertiesChanged(network: Network, properties: android.net.LinkProperties) = schedule()
    }

    fun enter(hostDevice: LanDevice) {
        visible++
        update(hostDevice)
        watchNetwork()
    }

    fun leave() {
        visible = (visible - 1).coerceAtLeast(0)
        watchNetwork()
        schedule()
    }

    private fun needsListener() = visible > 0 || _receiving.value?.active == true

    private fun watchNetwork() {
        if (needsListener() && !networkWatched) {
            networkWatched = runCatching {
                connectivity.registerNetworkCallback(NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).apply {
                        if (Build.VERSION.SDK_INT >= 31) addTransportType(NetworkCapabilities.TRANSPORT_USB)
                    }.build(), networkCallback)
            }.isSuccess
        } else if (!needsListener() && networkWatched) {
            runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
            networkWatched = false
        }
    }

    fun update(hostDevice: LanDevice) {
        device = hostDevice
        schedule()
    }

    suspend fun ownFingerprint(): String = withContext(Dispatchers.IO) { LanTls.receiver().fingerprint }

    fun send(files: List<File>, peer: LanPeer, onRelease: (() -> Unit)? = null): Boolean {
        if (_sending.value?.active == true) return false
        val task = LanTask(UUID.randomUUID().toString(), "发送到 ${peer.device.model}", LanProgress("正在连接…"))
        _sending.value = task
        scope.launch {
            val result = try {
                val client = withContext(Dispatchers.IO) {
                    if (peer.route !in lanSubnets(context)) throw IOException("所选连接已断开，请重新选择设备")
                    LanSender(peer.endpoint, peer.fingerprint, peer.route, peer.supportsTree)
                }
                if (_sending.value?.cancelling == true) LanBatchResult(error = "发送已取消")
                else {
                    sender = client
                    withContext(Dispatchers.IO) {
                        client.send(files, Build.MODEL) { updateProgress(_sending, task.id, it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LanBatchResult(error = if (_sending.value?.cancelling == true) "发送已取消" else e.message ?: "发送失败")
            } finally {
                sender = null
                release(onRelease)
            }
            _sending.update { if (it?.id == task.id) it.finish(result) else it }
        }
        return true
    }

    fun release(cleanup: (() -> Unit)?) {
        if (cleanup == null) return
        scope.launch(Dispatchers.IO) { cleanup() }
    }

    fun cancelSend(id: String) {
        val task = _sending.value?.takeIf { it.id == id && it.active && !it.cancelling } ?: return
        _sending.update { if (it?.id == task.id) it.copy(cancelling = true, progress = LanProgress("正在取消…")) else it }
        sender?.let { client -> scope.launch(Dispatchers.IO) { client.cancel() } }
    }

    fun accept(offer: LanReceiver.Offer, folder: File): Boolean {
        if (_pending.value !== offer || offer.decision.isDone) return false
        val previous = _receiving.value
        _receiving.value = LanTask(offer.id, "接收自 ${offer.alias}", LanProgress("正在准备接收…"))
        receivingRoute = pendingRoute
        if (offer.decision.complete(folder)) return true
        receivingRoute = null
        _receiving.value = previous
        return false
    }

    fun dismiss(id: String) {
        _sending.update { if (it?.id == id && !it.active) null else it }
        _receiving.update { if (it?.id == id && !it.active) null else it }
    }

    private fun updateProgress(flow: MutableStateFlow<LanTask?>, id: String, progress: LanProgress) {
        flow.update { if (it?.id == id && it.active && !it.cancelling) it.copy(progress = progress) else it }
    }

    private fun schedule() {
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, 350)
    }

    private fun storageAllowed(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private suspend fun reconcile() = mutex.withLock {
        val details = device
        val targets = if (needsListener() && storageAllowed() && details != null) lanSubnets(context) else emptyList()
        val removed = listeners.keys.filter { it !in targets }
        val stopped = removed.mapNotNull { route ->
            if (pendingRoute == route) _pending.value?.decision?.complete(null)
            if (receivingRoute == route) {
                receivingRoute = null
                _receiving.update { if (it?.active == true) {
                    it.finish(LanBatchResult(error = "接收连接已断开或存储权限已关闭", countsConfirmed = false))
                } else it }
            }
            listeners.remove(route)?.also { it.advertisement?.close() }?.receiver
        }
        if (stopped.isNotEmpty()) withContext(Dispatchers.IO) { stopped.forEach(LanReceiver::close) }
        watchNetwork()
        if (details == null || !needsListener()) return@withLock
        for (route in targets) {
            val listener = listeners[route] ?: startListener(route)?.also { listeners[route] = it } ?: continue
            if (visible == 0) {
                listener.advertisement?.close()
                listener.advertisement = null
            } else if (listener.advertisement == null || listener.advertisedDevice != details) {
                listener.advertisement?.close()
                listener.advertisement = runCatching {
                    LanAdvertisement(context.getSystemService(NsdManager::class.java), listener.receiver.endpoint,
                        route, details, listener.fingerprint)
                }.getOrNull()
                listener.advertisedDevice = details
            }
        }
    }

    private suspend fun startListener(route: LanSubnet): Listener? = withContext(Dispatchers.IO) {
        runCatching {
            val identity = LanTls.receiver()
            val receiver = LanReceiver(route, identity,
                onOffer = { offer -> scope.launch {
                    if (route !in listeners || visible == 0 || offer.decision.isDone ||
                        _pending.value?.decision?.isDone == false || _receiving.value?.active == true) {
                        offer.decision.complete(null)
                    } else {
                        pendingRoute = route
                        _pending.value = offer
                        offer.decision.whenComplete { _, _ -> scope.launch {
                            if (_pending.value === offer) {
                                _pending.value = null
                                pendingRoute = null
                            }
                        } }
                    }
                } },
                onProgress = { id, progress -> updateProgress(_receiving, id, progress) },
                onResult = { id, result -> scope.launch {
                    if (result.files > 0 || result.folders > 0) EventBus.emit(AppEvent.LanFilesReceived)
                    if (_receiving.value?.id == id) {
                        receivingRoute = null
                        _receiving.update { if (it?.id == id && it.active) it.finish(result) else it }
                        watchNetwork()
                        schedule()
                    }
                } }
            )
            try {
                receiver.start(20_000)
                Listener(receiver, identity.fingerprint)
            } catch (e: Exception) {
                receiver.close()
                throw e
            }
        }.getOrNull()
    }
}
