package com.wirelessalien.zipxtract.lan

import android.os.SystemClock
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Proxy
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import javax.net.ssl.SSLHandshakeException

private const val LEGACY_MAX_FILES = 256
private const val MAX_JSON = 384L * 1024
private const val APPROVAL_SECONDS = 120L
private const val IDLE_MILLIS = 30_000L
private const val WIRED_SOCKET_BUFFER = 1024 * 1024

internal data class LanBatchResult(
    val files: Long = 0,
    val folders: Long = 0,
    val modifiedWarnings: Long = 0,
    val accessedWarnings: Long = 0,
    val error: String? = null,
    val countsConfirmed: Boolean = true
) {
    fun message() = buildString {
        if (countsConfirmed) {
            append("已完成 $files 个文件")
            if (folders > 0) append("、$folders 个文件夹")
        }
        error?.let {
            if (isNotEmpty()) append("\n")
            append(it)
        }
        if (modifiedWarnings > 0) append("\n$modifiedWarnings 个项目的修改时间未精确保留")
        if (accessedWarnings > 0) append("\n$accessedWarnings 个项目的访问时间未精确保留")
    }

    fun json() = JSONObject().put("files", files).put("folders", folders)
        .put("modifiedWarnings", modifiedWarnings).put("accessedWarnings", accessedWarnings)
        .put("error", error ?: JSONObject.NULL)
}

internal class LanCompleted {
    private var files = 0L
    private var folders = 0L
    private var modifiedWarnings = 0L
    private var accessedWarnings = 0L

    fun file(warnings: Int) {
        files++
        warn(warnings)
    }

    fun directory(warnings: Int) {
        folders++
        warn(warnings)
    }

    private fun warn(warnings: Int) {
        if (warnings and 1 != 0) modifiedWarnings++
        if (warnings and 2 != 0) accessedWarnings++
    }

    fun result(error: String? = null) = LanBatchResult(files, folders, modifiedWarnings, accessedWarnings, error)
}

internal class LanReceiver(
    private val subnet: LanSubnet,
    identity: LanTls.Identity,
    private val onOffer: (Offer) -> Unit,
    private val onProgress: (String, LanProgress) -> Unit,
    private val onResult: (String, LanBatchResult) -> Unit
) : NanoHTTPD(subnet.address.hostAddress, 0), AutoCloseable {
    data class Offer(
        val id: String,
        val alias: String,
        val peer: String,
        val fileCount: Long,
        val folderCount: Long,
        val rootCount: Long,
        val preview: List<LanPreview>,
        val decision: CompletableFuture<File?>,
        val cancelReceive: () -> Unit
    ) {
        val description get() = "$fileCount 个文件" + if (folderCount > 0) "、$folderCount 个文件夹" else ""
    }

    private class Session(val peer: String, val files: Map<String, LanFile>, val tree: LanTreeOffer?, bytes: Long,
                          onProgress: (String, LanProgress) -> Unit) {
        val id = UUID.randomUUID().toString()
        val tokens = files.mapValues { UUID.randomUUID().toString() }
        val decision = CompletableFuture<File?>()
        val completed = LanCompleted()
        val progress = LanBatchProgress(tree?.let { it.fileCount + it.folderCount } ?: files.size.toLong(), bytes) {
            onProgress(id, it)
        }
        val completedIds = mutableSetOf<String>()
        var deadline = SystemClock.elapsedRealtime() + APPROVAL_SECONDS * 1000
        var destination: File? = null
        var uploading = false
        var uploadInput: InputStream? = null
        var abort: String? = null
    }

    private val closed = AtomicBoolean()
    private val workers = BoundedClients()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val lock = Any()
    private var active: Session? = null
    private var receiverCancelled: Pair<String, String>? = null
    val endpoint get() = LanEndpoint(subnet.address, listeningPort)

    init {
        val protocols = identity.context.supportedSSLParameters.protocols
            .filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        makeSecure(identity.context.serverSocketFactory, protocols)
        if (subnet.link == LanLink.WIRED) {
            val secure = serverSocketFactory
            setServerSocketFactory { secure.create().apply { receiveBufferSize = WIRED_SOCKET_BUFFER } }
        }
        setAsyncRunner(workers)
        // Uploads are streamed from the socket. Disable the HTTP library's body spool.
        setTempFileManagerFactory {
            object : TempFileManager {
                override fun clear() = Unit
                override fun createTempFile(filenameHint: String?): TempFile =
                    throw IOException("Disk buffering is disabled")
            }
        }
        timer.scheduleAtFixedRate(::expire, 2, 2, TimeUnit.SECONDS)
    }

    override fun serve(request: IHTTPSession): Response {
        val peer = request.remoteIpAddress
        if (closed.get() || ipv4(peer)?.let(subnet::contains) != true) return response(Status.FORBIDDEN)
        if (request.method != Method.POST) return response(Status.METHOD_NOT_ALLOWED)
        if (request.headers.containsKey("transfer-encoding")) return response(Status.BAD_REQUEST)
        return try {
            when (request.uri) {
                "$LAN_API/prepare-upload" -> prepare(request, peer)
                "$LAN_API/upload" -> upload(request, peer)
                "$LAN_API/upload-tree" -> uploadTree(request, peer)
                "$LAN_API/cancel" -> cancel(request, peer)
                else -> response(Status.NOT_FOUND)
            }
        } catch (_: IllegalArgumentException) {
            response(Status.BAD_REQUEST)
        } catch (_: JSONException) {
            response(Status.BAD_REQUEST)
        } catch (_: IOException) {
            response(Status.INTERNAL_ERROR)
        }
    }

    private fun prepare(request: IHTTPSession, peer: String): Response {
        val offer = JSONObject(readJson(request.inputStream, bodyLength(request)))
        val tree = if (offer.has("tree")) LanTreeOffer.parse(offer.getJSONObject("tree")) else null
        val files = if (tree != null) emptyMap() else {
            val raw = offer.getJSONObject("files")
            require(raw.length() in 1..LEGACY_MAX_FILES)
            raw.keys().asSequence().associateWith { LanFile.fromJson(it, raw.getJSONObject(it)) }
        }
        val alias = offer.getJSONObject("info").getString("alias").take(80).filterNot { it.isISOControl() }
        var bytes = tree?.contentBytes ?: 0L
        for (file in files.values) {
            require(file.size <= Long.MAX_VALUE - bytes)
            bytes += file.size
        }
        val session = Session(peer, files, tree, bytes, onProgress)
        synchronized(lock) {
            if (closed.get() || active != null) return response(Status.CONFLICT)
            active = session
        }
        val preview = tree?.preview ?: files.values.take(LAN_PREVIEW_SIZE).map { LanPreview(it.name, it.size) }
        onOffer(Offer(session.id, alias.ifBlank { "附近设备" }, peer, tree?.fileCount ?: files.size.toLong(),
            tree?.folderCount ?: 0, tree?.rootCount ?: files.size.toLong(), preview, session.decision) { cancelReceive(session) })
        val folder = try { session.decision.get(APPROVAL_SECONDS, TimeUnit.SECONDS) }
        catch (_: TimeoutException) { null }
        catch (_: InterruptedException) { null }
        val writable = folder != null && folder.isDirectory && folder.canWrite() && bytes <= folder.usableSpace
        val accepted = synchronized(lock) {
            if (active !== session) false
            else if (!writable || closed.get()) { active = null; false }
            else {
                session.destination = folder
                session.deadline = SystemClock.elapsedRealtime() + IDLE_MILLIS
                true
            }
        }
        if (!accepted) {
            session.decision.complete(null)
            if (folder != null && !writable) onResult(session.id, LanBatchResult(error = "目标目录不可写或空间不足"))
            return response(if (folder != null && !writable) Status.SERVICE_UNAVAILABLE else Status.FORBIDDEN)
        }
        onProgress(session.id, LanProgress("已接受，等待文件…", 0))
        val result = JSONObject().put("sessionId", session.id)
        if (tree == null) result.put("files", JSONObject(session.tokens))
        return response(Status.OK, result)
    }

    private fun upload(request: IHTTPSession, peer: String): Response {
        val id = request.parameter("fileId")
        val token = request.parameter("token")
        val (session, file, folder) = synchronized(lock) {
            val state = active?.takeIf { it.peer == peer && it.id == request.parameter("sessionId") }
                ?: return response(Status.FORBIDDEN)
            val target = state.destination ?: return response(Status.FORBIDDEN)
            val item = state.files[id] ?: return response(Status.FORBIDDEN)
            if (token != state.tokens[id]) return response(Status.FORBIDDEN)
            if (state.uploading || state.abort != null || id in state.completedIds) return response(Status.CONFLICT)
            if (bodyLength(request) != item.size + LAN_DIGEST_BYTES) return response(Status.BAD_REQUEST)
            state.uploading = true
            state.uploadInput = request.inputStream
            state.deadline = SystemClock.elapsedRealtime() + IDLE_MILLIS
            Triple(state, item, target)
        }
        try {
            val update = session.progress.payload(file.name)
            val receipt = LanDestination.save(folder, file, request.inputStream, { check(session) }, update)
            synchronized(lock) {
                session.completed.file(receipt.timeWarnings)
                session.progress.complete(receipt.name, file.size)
                session.uploading = false
                session.uploadInput = null
                if (active === session) {
                    session.completedIds += id
                    if (session.abort != null || session.completedIds.size == session.files.size) finish(session, session.abort)
                }
            }
            return response(Status.OK, JSONObject().put("fileName", receipt.name).put("timeWarnings", receipt.timeWarnings))
        } catch (e: Exception) {
            synchronized(lock) { if (active === session) finish(session, session.abort ?: e.message ?: "接收失败") }
            return response(if (e is HashMismatch) HASH_MISMATCH else Status.INTERNAL_ERROR)
        }
    }

    private fun uploadTree(request: IHTTPSession, peer: String): Response {
        val (session, tree, folder) = synchronized(lock) {
            val state = active?.takeIf { it.peer == peer && it.id == request.parameter("sessionId") }
                ?: return response(Status.FORBIDDEN)
            val target = state.destination ?: return response(Status.FORBIDDEN)
            val offer = state.tree ?: return response(Status.FORBIDDEN)
            if (state.uploading || state.abort != null) return response(Status.CONFLICT)
            if (bodyLength(request) != offer.wireBytes) return response(Status.BAD_REQUEST)
            state.uploading = true
            state.uploadInput = request.inputStream
            Triple(state, offer, target)
        }
        val error = try {
            LanTreeDestination.receive(folder, tree, request.inputStream, session.completed, { check(session) }, session.progress)
            null
        } catch (e: Exception) {
            e.message ?: "接收失败"
        }
        val result = synchronized(lock) {
            val failure = session.abort ?: error ?: if (closed.get()) "接收已关闭" else null
            if (active === session) finish(session, failure)
            session.completed.result(failure)
        }
        return response(Status.OK, result.json())
    }

    private fun check(session: Session) = synchronized(lock) {
        if (active !== session || closed.get() || session.abort != null) throw IOException("传输已取消")
        session.deadline = SystemClock.elapsedRealtime() + IDLE_MILLIS
    }

    private fun cancelReceive(session: Session) {
        val stream = synchronized(lock) {
            if (active !== session) return
            receiverCancelled = session.peer to session.id
            abort(session, "接收已取消")
            session.uploadInput
        }
        // Wake a TLS upload blocked while reading; the receiving worker cleans its staging file.
        runCatching { stream?.close() }
    }

    private fun cancel(request: IHTTPSession, peer: String): Response = synchronized(lock) {
        val id = request.parameter("sessionId")
        if (receiverCancelled == (peer to id)) {
            return response(Status.OK, JSONObject().put("receiverCancelled", true))
        }
        val session = active?.takeIf { it.peer == peer && it.id == id } ?: return response(Status.FORBIDDEN)
        abort(session, "发送端已取消")
        response(Status.OK)
    }

    private fun expire() = synchronized(lock) {
        val session = active ?: return@synchronized
        if (SystemClock.elapsedRealtime() >= session.deadline) abort(session, "传输等待超时")
    }

    private fun abort(session: Session, error: String) {
        if (session.uploading) session.abort = session.abort ?: error else finish(session, error)
    }

    private fun finish(session: Session, error: String?) {
        active = null
        session.decision.complete(null)
        if (!closed.get()) onResult(session.id, session.completed.result(error))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) { active?.let { finish(it, "接收已关闭") } }
        timer.shutdownNow()
        stop()
        workers.shutdown()
    }

    private class BoundedClients : AsyncRunner {
        private val clients = ConcurrentHashMap.newKeySet<ClientHandler>()
        private val threads = ThreadPoolExecutor(0, 6, 20L, TimeUnit.SECONDS, SynchronousQueue())
        override fun exec(client: ClientHandler) {
            clients.add(client)
            try { threads.execute(client) }
            catch (_: RejectedExecutionException) { clients.remove(client); client.close() }
        }
        override fun closed(client: ClientHandler) { clients.remove(client) }
        override fun closeAll() { clients.toList().forEach(ClientHandler::close) }
        fun shutdown() { threads.shutdownNow() }
    }

    companion object {
        private val HASH_MISMATCH = object : Response.IStatus {
            override fun getRequestStatus() = 422
            override fun getDescription() = "422 Unprocessable Content"
        }

        private fun response(status: Response.IStatus, data: JSONObject? = null): Response =
            newFixedLengthResponse(status, "application/json", data?.toString().orEmpty()).apply { closeConnection(true) }

        private fun IHTTPSession.parameter(name: String) = parameters[name]?.singleOrNull().orEmpty()
        private fun bodyLength(request: IHTTPSession) =
            request.headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 }
                ?: throw IllegalArgumentException("Content-Length required")
    }
}

internal class LanSender(private val endpoint: LanEndpoint, private val fingerprint: String, private val route: LanSubnet,
                         private val supportsTree: Boolean) {
    private val tls = LanTls.sender(fingerprint)
    private val client = OkHttpClient.Builder()
        .socketFactory(LanSocketFactory(route))
        .sslSocketFactory(tls.context.socketFactory, tls.trustManager)
        .hostnameVerifier { host, session ->
            val certificate = session.peerCertificates.firstOrNull() as? X509Certificate
            host == endpoint.address.hostAddress && certificate != null && LanTls.matches(fingerprint, certificate)
        }
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(7, TimeUnit.SECONDS)
        .writeTimeout(35, TimeUnit.SECONDS)
        .build()
    private val cancelled = AtomicBoolean()
    private val connection = AtomicReference<Call?>()

    fun cancel() {
        cancelled.set(true)
        connection.get()?.cancel()
    }

    fun send(files: List<File>, alias: String, progress: (LanProgress) -> Unit): LanBatchResult {
        val completed = LanCompleted()
        var session: String? = null
        val info = JSONObject().put("alias", alias).put("version", "2.2").put("deviceType", "mobile")
            .put("deviceModel", alias).put("port", endpoint.port).put("protocol", "https")
        fun prepare(offer: JSONObject): JSONObject {
            val payload = offer.put("info", info).toString().toByteArray(Charsets.UTF_8)
            require(payload.size <= MAX_JSON) { "发送清单过长" }
            progress(LanProgress("等待接收端确认…"))
            return post("/prepare-upload", payload.size.toLong(), 130_000) { it.write(payload) }.also {
                session = it.getString("sessionId").also { id -> require(LanFile.identifier.matches(id)) }
            }
        }
        try {
            require(files.isNotEmpty()) { "请选择文件或文件夹" }
            progress(LanProgress("正在扫描所选项目…"))
            if (supportsTree) {
                val plan = LanTreePlan.scan(files, ::check)
                prepare(JSONObject().put("tree", plan.offer.json()))
                progress(LanProgress("开始发送…", 0))
                val reply = post("/upload-tree?sessionId=$session", plan.offer.wireBytes, 35_000) { output ->
                    plan.write(output, ::check, progress)
                    progress(LanProgress("等待接收端校验…", 99))
                }
                val result = LanBatchResult(reply.getLong("files"), reply.getLong("folders"),
                    reply.getLong("modifiedWarnings"), reply.getLong("accessedWarnings"),
                    if (reply.isNull("error")) null else reply.getString("error"))
                require(result.files in 0..plan.offer.fileCount && result.folders in 0..plan.offer.folderCount)
                if (result.error == null) {
                    require(result.files == plan.offer.fileCount && result.folders == plan.offer.folderCount)
                }
                return result
            }
            require(files.size <= LEGACY_MAX_FILES && files.all { it.isFile }) {
                "接收设备版本较旧，请更新至 FileDoctorC 后传输文件夹或超过 $LEGACY_MAX_FILES 个文件"
            }
            val manifest = JSONObject()
            val sources = files.map { file ->
                check()
                LanSource.inspect(file).also { manifest.put(it.meta.id, it.meta.json()) }
            }
            val answer = prepare(JSONObject().put("files", manifest))
            val sessionId = checkNotNull(session)
            val tokens = answer.getJSONObject("files")
            val bytes = sources.fold(0L) { sum, source -> Math.addExact(sum, source.meta.size) }
            val batch = LanBatchProgress(sources.size.toLong(), bytes, progress)
            for (source in sources) {
                check()
                source.check()
                val file = source.meta
                val token = tokens.getString(file.id).also { require(LanFile.identifier.matches(it)) }
                val update = batch.payload(file.name)
                val reply = post("/upload?sessionId=$sessionId&fileId=${file.id}&token=$token", file.size + LAN_DIGEST_BYTES, 35_000) { output ->
                    source.writePayload(output, ::check, update)
                }
                completed.file(reply.optInt("timeWarnings"))
                batch.complete(file.name, file.size)
            }
            return completed.result()
        } catch (e: Exception) {
            val rejected = session?.let { id ->
                runCatching { post("/cancel?sessionId=$id", 0, 1500, cleanup = true) {}
                    .optBoolean("receiverCancelled") }.getOrDefault(false)
            } == true
            val error = when {
                cancelled.get() -> "发送已取消"
                rejected -> "接收端已取消"
                e is SSLHandshakeException -> "设备身份已变化，请重新选择设备"
                else -> e.message ?: "发送失败"
            }
            if (supportsTree && session != null) {
                return LanBatchResult(error = "$error\n请在接收设备上确认已收到的项目", countsConfirmed = false)
            }
            return completed.result(error)
        }
    }

    private fun check() { if (cancelled.get()) throw IOException("发送已取消") }

    private fun post(route: String, bytes: Long, timeout: Int, cleanup: Boolean = false,
                     write: (OutputStream) -> Unit): JSONObject {
        val mediaType = (if (route == "/prepare-upload") "application/json" else "application/octet-stream").toMediaType()
        val body = object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = bytes
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = write(sink.outputStream())
        }
        val request = Request.Builder().url("https://$endpoint$LAN_API$route")
            .header("Connection", "close").post(body).build()
        val http = client.newBuilder().readTimeout(timeout.toLong(), TimeUnit.MILLISECONDS).apply {
            if (cleanup) {
                connectTimeout(1500, TimeUnit.MILLISECONDS)
                callTimeout(2, TimeUnit.SECONDS)
            }
        }.build().newCall(request)
        if (!cleanup) connection.set(http)
        try {
            if (!cleanup) check()
            return http.execute().use { result ->
                if (result.code != 200) throw IOException(when (result.code) {
                    403 -> "接收端已拒绝或请求已过期"
                    409 -> "接收端正在处理其他传输"
                    422 -> "接收端校验失败"
                    503 -> "接收端存储空间不足"
                    else -> "接收端返回错误 ${result.code}"
                })
                val content = result.body?.byteStream()?.let { readJson(it, null) }.orEmpty()
                if (content.isEmpty()) JSONObject() else JSONObject(content)
            }
        } finally {
            connection.compareAndSet(http, null)
        }
    }
}

private class LanSocketFactory(private val route: LanSubnet) : SocketFactory() {
    override fun createSocket(): Socket {
        val socket = route.network?.socketFactory?.createSocket() ?: Socket()
        try {
            socket.bind(InetSocketAddress(route.address, 0))
            if (route.link == LanLink.WIRED) socket.sendBufferSize = WIRED_SOCKET_BUFFER
            return socket
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }

    // OkHttp uses the unconnected socket above so its connect timeout and cancellation apply.
    override fun createSocket(host: String, port: Int): Socket = connect(InetSocketAddress(host, port))
    override fun createSocket(host: InetAddress, port: Int): Socket = connect(InetSocketAddress(host, port))
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
        connect(InetSocketAddress(host, port), local, localPort)
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
        connect(InetSocketAddress(host, port), local, localPort)

    private fun connect(remote: InetSocketAddress, local: InetAddress = route.address, localPort: Int = 0): Socket {
        require(local == route.address && localPort == 0)
        val socket = createSocket()
        try {
            socket.connect(remote)
            return socket
        } catch (e: IOException) {
            socket.close()
            throw e
        }
    }
}

private fun readJson(input: InputStream, length: Long?): String {
    if (length != null) require(length in 1..MAX_JSON)
    val output = ByteArrayOutputStream()
    if (length != null) streamBytes(input, output, length, {}, {})
    else {
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (output.size() + n > MAX_JSON) throw IOException("应答过大")
            output.write(buffer, 0, n)
        }
    }
    return output.toString(Charsets.UTF_8.name())
}
