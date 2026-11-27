package com.wirelessalien.zipxtract.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Locale
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

internal const val LAN_SERVICE = "_zipdoctorc2._tcp."
internal const val LAN_API = "/api/zipdoctorc/v2"

internal data class LanEndpoint(val address: Inet4Address, val port: Int) {
    override fun toString() = "${address.hostAddress}:$port"
}

internal enum class LanLink { WIFI, WIRED }

internal data class LanSubnet(
    val address: Inet4Address,
    val prefix: Int,
    val network: Network?,
    val link: LanLink,
    val interfaceName: String?
) {
    fun contains(peer: Inet4Address): Boolean {
        if (prefix !in 1..32 || peer.isAnyLocalAddress || peer.isLoopbackAddress || peer.isMulticastAddress) return false
        val diff = address.address.zip(peer.address).fold(0) { value, (a, b) ->
            (value shl 8) or ((a.toInt() xor b.toInt()) and 255)
        }
        return diff ushr (32 - prefix) == 0
    }
}

internal fun ipv4(value: String): Inet4Address? {
    val octets = value.split('.')
    if (octets.size != 4) return null
    val bytes = octets.map { octet ->
        if (octet.length !in 1..3 || octet.any { it !in '0'..'9' }) return null
        (octet.toIntOrNull()?.takeIf { it in 0..255 } ?: return null).toByte()
    }
    return InetAddress.getByAddress(bytes.toByteArray()) as Inet4Address
}

@Suppress("DEPRECATION")
internal fun lanSubnets(context: Context): List<LanSubnet> {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val networks = listOfNotNull(manager.activeNetwork) + manager.allNetworks
    val knownInterfaces = mutableSetOf<String>()
    val connected = networks.distinct().flatMap { network ->
        val caps = manager.getNetworkCapabilities(network) ?: return@flatMap emptyList()
        val properties = manager.getLinkProperties(network) ?: return@flatMap emptyList()
        properties.interfaceName?.let(knownInterfaces::add)
        val link = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                (Build.VERSION.SDK_INT >= 31 && caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)) -> LanLink.WIRED
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LanLink.WIFI
            else -> return@flatMap emptyList()
        }
        properties.linkAddresses.mapNotNull { local ->
            (local.address as? Inet4Address)?.let {
                LanSubnet(it, local.prefixLength, network, link, properties.interfaceName)
            }
        }
    }
    // A USB tethering host can have a downstream interface without a ConnectivityManager Network.
    val downstream = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { nic ->
            if (nic.name in knownInterfaces || !usbInterface.matches(nic.name) || !nic.isUp) return@flatMap emptyList()
            nic.interfaceAddresses.mapNotNull { local ->
                (local.address as? Inet4Address)?.let {
                    LanSubnet(it, local.networkPrefixLength.toInt(), null, LanLink.WIRED, nic.name)
                }
            }
        }
    }.getOrDefault(emptyList())
    return (connected + downstream).distinct()
}

private val usbInterface = Regex("(?:rndis|usb|ncm)[0-9]+")

internal fun matchingLanRoutes(
    subnets: List<LanSubnet>,
    address: Inet4Address,
    discoveredOn: Network?,
    advertisedLink: LanLink?
): List<LanSubnet> {
    val candidates = subnets.filter { it.address != address && it.contains(address) }
    if (discoveredOn != null) return candidates.filter { it.network == discoveredOn }
    val matching = candidates.filter { it.link == advertisedLink }
    // Older NSD versions omit the observed network. In an overlapping subnet the
    // advertised link disambiguates; a legacy service keeps the existing Wi-Fi preference.
    return when {
        matching.isNotEmpty() -> matching
        else -> candidates.sortedBy { it.link }.take(1)
    }

}

internal class LanAdvertisement(private val nsd: NsdManager, endpoint: LanEndpoint,
                                subnet: LanSubnet, device: LanDevice, fingerprint: String) : AutoCloseable {
    private val listener = object : NsdManager.RegistrationListener {
        override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        override fun onServiceRegistered(info: NsdServiceInfo) = Unit
        override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
    }

    init {
        nsd.registerService(NsdServiceInfo().apply {
            val wirelessName = "FileDoctorC-${fingerprint.take(10)}"
            serviceName = if (subnet.link == LanLink.WIFI) wirelessName
                else "$wirelessName-E-${endpoint.address.hostAddress}"
            if (Build.VERSION.SDK_INT >= 33) setNetwork(subnet.network)
            serviceType = LAN_SERVICE
            port = endpoint.port
            setAttribute("id", fingerprint)
            setAttribute("addr", endpoint.address.hostAddress.orEmpty())
            setAttribute("model", device.model.take(48))
            setAttribute("brand", device.brand.take(24))
            setAttribute("shape", device.shape.name)
            setAttribute("tree", "1")
            setAttribute("link", if (subnet.link == LanLink.WIRED) "wired" else "wifi")
        }, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    override fun close() { runCatching { nsd.unregisterService(listener) } }
}

@Suppress("DEPRECATION")
internal class LanDiscovery(private val context: Context, private val ownFingerprint: String?,
                            private val changed: (List<LanPeer>) -> Unit) : AutoCloseable {
    private data class ServiceKey(val name: String, val network: Network?)
    private data class Service(val endpoint: LanEndpoint, val fingerprint: String, val device: LanDevice,
                               val supportsTree: Boolean, val link: LanLink?, val network: Network?)

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val offered = linkedMapOf<ServiceKey, NsdServiceInfo>()
    private val resolved = linkedMapOf<ServiceKey, Service>()
    private val waiting = ArrayDeque<ServiceKey>()
    private var lastPeers = emptyList<LanPeer>()
    private val refresh = object : Runnable {
        override fun run() {
            synchronized(lock) {
                if (closed) return
                publish()
                main.postDelayed(this, 1500)
            }
        }
    }
    private var resolving = false
    private var closed = false
    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = Unit
        override fun onDiscoveryStopped(type: String) = Unit
        override fun onStartDiscoveryFailed(type: String, code: Int) { close() }
        override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        override fun onServiceFound(info: NsdServiceInfo) {
            synchronized(lock) {
                val key = info.key()
                if (closed || offered.size >= 64 || offered.putIfAbsent(key, info) != null) return
                waiting.add(key)
                resolveNext()
            }
        }
        override fun onServiceLost(info: NsdServiceInfo) {
            synchronized(lock) {
                val key = info.key()
                offered.remove(key)
                waiting.remove(key)
                if (resolved.remove(key) != null) publish()
            }
        }
    }

    init {
        nsd.discoverServices(LAN_SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
        main.post(refresh)
    }

    private fun NsdServiceInfo.networkOrNull() = if (Build.VERSION.SDK_INT >= 33) network else null

    private fun NsdServiceInfo.key() = ServiceKey(serviceName, networkOrNull())

    private fun publish() {
        val subnets = lanSubnets(context)
        val peers = resolved.values.flatMap { service ->
            matchingLanRoutes(subnets, service.endpoint.address, service.network, service.link).map { route ->
                LanPeer(service.endpoint, service.fingerprint, service.device, service.supportsTree, route) to service.link
            }
        }.sortedByDescending { (peer, remoteLink) -> peer.route.link == remoteLink }
            .distinctBy { (peer, _) -> peer.fingerprint to peer.route.link }
            .map { it.first }
            .sortedWith(compareBy({ it.device.model }, { it.fingerprint }, { it.route.link }))
        if (peers == lastPeers) return
        lastPeers = peers
        // Serialize callbacks with the refresh and UI lifecycle, including NSD Binder callbacks.
        main.post { synchronized(lock) { if (!closed && peers == lastPeers) changed(peers) } }
    }

    private fun resolveNext() {
        if (closed || resolving) return
        val key = waiting.removeFirstOrNull() ?: return
        val info = offered[key] ?: return resolveNext()
        resolving = true
        fun complete(service: NsdServiceInfo?) {
            synchronized(lock) {
                resolving = false
                if (closed) return
                if (offered[key] === info && service != null) {
                    readService(service, key.network)?.takeIf { it.fingerprint != ownFingerprint }?.let {
                        resolved[key] = it
                        publish()
                    }
                }
                resolveNext()
            }
        }
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(service: NsdServiceInfo, code: Int) = complete(null)
                override fun onServiceResolved(service: NsdServiceInfo) = complete(service)
            })
        } catch (_: RuntimeException) {
            complete(null)
        }
    }

    private fun readService(info: NsdServiceInfo, observedNetwork: Network?): Service? {
        val address = info.attributes["addr"]?.toString(Charsets.UTF_8)?.let(::ipv4)
            ?: if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.filterIsInstance<Inet4Address>().firstOrNull()
                else info.host as? Inet4Address
        val id = info.attributes["id"]?.toString(Charsets.UTF_8) ?: return null
        if (address == null || info.port !in 1..65535 || !LanTls.validFingerprint(id)) return null
        fun value(key: String) = info.attributes[key]?.toString(Charsets.UTF_8).orEmpty()
            .filterNot { it.isISOControl() || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' }.trim().take(60)
        val shape = LanShape.entries.firstOrNull { it.name == value("shape") } ?: LanShape.PHONE
        val link = when (value("link")) {
            "wired" -> LanLink.WIRED
            "wifi" -> LanLink.WIFI
            else -> null
        }
        return Service(LanEndpoint(address, info.port), id,
            LanDevice(value("model").ifBlank { "附近设备" }, value("brand"), shape), value("tree") == "1",
            link, info.networkOrNull() ?: observedNetwork)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            waiting.clear()
            main.removeCallbacks(refresh)
        }
        runCatching { nsd.stopServiceDiscovery(listener) }
    }
}

internal object LanTls {
    private const val KEY_ALIAS = "zipdoctorc.lan.v1"
    private val fingerprintPattern = Regex("[0-9A-F]{64}")
    data class Identity(val context: SSLContext, val fingerprint: String)

    @Synchronized fun receiver(): Identity {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .build())
                generateKeyPair()
            }
        }
        val certificate = store.getCertificate(KEY_ALIAS) as X509Certificate
        val key = store.getKey(KEY_ALIAS, null) as PrivateKey
        val manager = object : X509ExtendedKeyManager() {
            override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = null
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getServerAliases(type: String?, issuers: Array<out Principal>?) =
                if (type == "EC") arrayOf(KEY_ALIAS) else null
            override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) =
                if (type == "EC") KEY_ALIAS else null
            override fun getCertificateChain(alias: String?) = if (alias == KEY_ALIAS) arrayOf(certificate) else null
            override fun getPrivateKey(alias: String?) = if (alias == KEY_ALIAS) key else null
        }
        val context = SSLContext.getInstance("TLS").apply { init(arrayOf(manager), null, null) }
        return Identity(context, fingerprint(certificate))
    }

    fun validFingerprint(fingerprint: String) = fingerprintPattern.matches(fingerprint)
    fun matches(expected: String, certificate: X509Certificate) = fingerprint(certificate) == expected

    data class Client(val context: SSLContext, val trustManager: X509TrustManager)

    fun sender(expected: String): Client {
        require(validFingerprint(expected))
        val pinning = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, type: String?) {
                throw CertificateException("Unexpected client certificate")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, type: String?) {
                val certificate = chain?.firstOrNull() ?: throw CertificateException("Missing peer certificate")
                if (!matches(expected, certificate)) throw CertificateException("接收设备证书不匹配")
            }
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(pinning), null) }
        return Client(context, pinning)
    }

    private fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02X".format(Locale.ROOT, it) }
}
