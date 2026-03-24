package com.acb.androidcam

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class V2MediaClient(
    private val onDebugEvent: ((String) -> Unit)? = null,
    private val onStreamStateChanged: ((StreamUiState, String) -> Unit)? = null,
) {
    companion object {
        private const val VIDEO_WS_BACKPRESSURE_BYTES = 256_000L
    }

    private fun emit(msg: String) {
        onDebugEvent?.invoke(msg)
        AppLog.i("ACB", msg)
    }

    private fun emitState(state: StreamUiState, detail: String) {
        onStreamStateChanged?.invoke(state, detail)
        AppLog.i("ACB", "stream_state=$state detail=$detail")
    }
    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()
    private val usbPacketHttp = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()
    private val scanHttp = OkHttpClient.Builder()
        .connectTimeout(220, TimeUnit.MILLISECONDS)
        .readTimeout(220, TimeUnit.MILLISECONDS)
        .writeTimeout(220, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    private val connected = AtomicBoolean(false)
    private val frameIndex = AtomicLong(0)
    private val lastConnectedAtMs = AtomicLong(0)
    @Volatile private var activeTransport = "lan"
    @Volatile private var receiverAddress = "127.0.0.1:39393"
    @Volatile private var sessionId = ""
    @Volatile private var usbNativeLinkId = ""
    private val usbSeq = AtomicLong(0)
    private val usbPacketCount = AtomicLong(0)
    private val droppedVideoPackets = AtomicLong(0)
    @Volatile private var usbAccessoryTransport: UsbAccessoryTransport? = null

    data class SessionInfo(
        val sessionId: String,
        val wsUrl: String,
        val authToken: String,
    )

    fun startSession(
        receiverAddress: String,
        transport: String,
        videoWidth: Int,
        videoHeight: Int,
        videoFps: Int,
        videoBitrate: Int,
        videoKeyInt: Int,
        audioEnabled: Boolean,
        audioSampleRate: Int,
        audioChannels: Int,
        audioBitrate: Int,
    ): SessionInfo? {
        this.activeTransport = transport
        val initial = normalizeReceiverAddress(receiverAddress)
        this.receiverAddress = initial

        // USB AOA doesn't need HTTP session handshake — media goes over bulk pipe
        if (transport == "usb-aoa") {
            sessionId = "usb-aoa-direct"
            return SessionInfo("usb-aoa-direct", "", "")
        }

        val first = startSessionOnce(
            targetAddress = initial,
            transport = transport,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            videoFps = videoFps,
            videoBitrate = videoBitrate,
            videoKeyInt = videoKeyInt,
            audioEnabled = audioEnabled,
            audioSampleRate = audioSampleRate,
            audioChannels = audioChannels,
            audioBitrate = audioBitrate,
        )
        if (first != null) return first

        if (transport != "usb-native") {
            return null
        }

        val candidates = usbNativeCandidates(initial)
        emit("usb-native scanning candidates=${candidates.size} (192.168.x.x)")
        val reachable = findReachableReceiver(candidates.filter { it != initial })
        if (reachable != null) {
            val s = startSessionOnce(
                targetAddress = reachable,
                transport = transport,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                videoFps = videoFps,
                videoBitrate = videoBitrate,
                videoKeyInt = videoKeyInt,
                audioEnabled = audioEnabled,
                audioSampleRate = audioSampleRate,
                audioChannels = audioChannels,
                audioBitrate = audioBitrate,
            )
            if (s != null) {
                this.receiverAddress = reachable
                emit("usb-native receiver auto-detected: $reachable")
                return s
            }
        }
        return null
    }

    private fun startSessionOnce(
        targetAddress: String,
        transport: String,
        videoWidth: Int,
        videoHeight: Int,
        videoFps: Int,
        videoBitrate: Int,
        videoKeyInt: Int,
        audioEnabled: Boolean,
        audioSampleRate: Int,
        audioChannels: Int,
        audioBitrate: Int,
    ): SessionInfo? {
        return try {
            val body = JSONObject()
                .put("transport", transport)
                .put("mode", "obs_direct")
                .put(
                    "video",
                    JSONObject()
                        .put("codec", "h264")
                        .put("width", videoWidth)
                        .put("height", videoHeight)
                        .put("fps", videoFps)
                        .put("bitrate", videoBitrate)
                        .put("keyint", videoKeyInt),
                )
                .put(
                    "audio",
                    JSONObject()
                        .put("codec", "aac")
                        .put("enabled", audioEnabled)
                        .put("sampleRate", audioSampleRate)
                        .put("channels", audioChannels)
                        .put("bitrate", audioBitrate),
                )
                .toString()

            val req = Request.Builder()
                .url("http://$targetAddress/api/v2/session/start")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body.string()
                if (text.isBlank()) return null
                val obj = JSONObject(text)
                sessionId = obj.optString("sessionId")
                SessionInfo(
                    sessionId = sessionId,
                    wsUrl = obj.optString("wsUrl"),
                    authToken = obj.optString("authToken"),
                )
            }
        } catch (t: Throwable) {
            AppLog.w("ACB", "v2 start session failed: ${t.message}", t)
            onDebugEvent?.invoke("v2 start session failed: ${t.message}")
            emitState(StreamUiState.ERROR, "Session start failed: ${t.message}")
            null
        }
    }

    private fun normalizeReceiverAddress(input: String): String {
        val value = input.trim()
        if (value.isBlank()) return "192.168.42.129:39393"
        return if (value.contains(":")) value else "$value:39393"
    }

    private fun usbNativeCandidates(primary: String): List<String> {
        val out = linkedSetOf<String>()
        val (host, port) = splitHostPort(primary)
        out += primary
        if (host.startsWith("192.168.")) {
            val parts = host.split('.')
            if (parts.size == 4) {
                val third = parts[2]
                for (i in 1..254) out += "192.168.$third.$i:$port"
            }
        }
        for (i in 1..254) out += "192.168.42.$i:$port"
        for (i in 1..254) out += "192.168.137.$i:$port"

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        val parts = host.split('.')
                        if (parts.size != 4) continue
                        if (parts[0] == "192" && parts[1] == "168") {
                            val third = parts[2]
                            for (i in 1..254) out += "192.168.$third.$i:$port"
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return out.toList()
    }

    private fun splitHostPort(address: String): Pair<String, String> {
        val value = address.trim()
        if (value.isBlank()) return "192.168.42.129" to "39393"
        val idx = value.lastIndexOf(':')
        if (idx <= 0 || idx == value.length - 1) return value to "39393"
        return value.substring(0, idx) to value.substring(idx + 1)
    }

    private fun probeReceiver(address: String): Boolean {
        return try {
            val req = Request.Builder()
                .url("http://$address/api/v1/devices")
                .get()
                .build()
            scanHttp.newCall(req).execute().use { resp -> resp.isSuccessful }
        } catch (_: Throwable) {
            false
        }
    }

    private fun findReachableReceiver(candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        val pool = Executors.newFixedThreadPool(24)
        val ecs = ExecutorCompletionService<String?>(pool)
        try {
            for (candidate in candidates) {
                ecs.submit(Callable<String?> {
                    if (probeReceiver(candidate)) candidate else null
                })
            }
            repeat(candidates.size) {
                val hit = ecs.take().get()
                if (!hit.isNullOrBlank()) {
                    return hit
                }
            }
            return null
        } finally {
            pool.shutdownNow()
        }
    }

    fun connect(wsUrl: String) {
        ws?.cancel()
        connected.set(false)
        usbNativeLinkId = ""
        usbSeq.set(0)
        usbPacketCount.set(0)
        droppedVideoPackets.set(0)

        if (activeTransport == "usb-native") {
            val ok = startUsbNativeLink()
            connected.set(ok)
            if (ok) {
                lastConnectedAtMs.set(System.currentTimeMillis())
                emit("usb-native link connected receiver=$receiverAddress sessionId=$sessionId linkId=$usbNativeLinkId")
                sendUsbNativeProbePacket()
            } else {
                AppLog.w("ACB", "usb-native link handshake failed")
                onDebugEvent?.invoke("usb-native link handshake failed")
                emitState(StreamUiState.ERROR, "USB native handshake failed")
            }
            return
        } else if (activeTransport == "usb-aoa") {
            // AOA transport may not be ready yet (handshake is async on Windows side).
            // Wait up to 15 seconds for the transport to become available.
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                if (usbAccessoryTransport?.isConnected() == true) {
                    connected.set(true)
                    lastConnectedAtMs.set(System.currentTimeMillis())
                    emit("USB AOA transport connected")
                    emitState(StreamUiState.STREAMING, "USB AOA connected")
                    return
                }
                try { Thread.sleep(500) } catch (_: InterruptedException) { return }
            }
            emit("USB AOA transport not connected after 15s")
            emitState(StreamUiState.ERROR, "USB AOA not connected")
            return
        }

        if (wsUrl.isBlank()) return
        val req = Request.Builder().url(wsUrl).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true)
                lastConnectedAtMs.set(System.currentTimeMillis())
                AppLog.i("ACB", "v2 websocket connected")
                emitState(StreamUiState.STREAMING, "Media websocket connected")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.set(false)
                AppLog.w("ACB", "v2 websocket failed: ${t.message}", t)
                emitState(StreamUiState.ERROR, "Media websocket failed: ${t.message ?: "unknown"}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false)
                emitState(StreamUiState.STOPPED, "Media websocket closed")
            }
        })
    }

    fun isConnected(): Boolean = connected.get()

    fun getLastConnectedAtMs(): Long = lastConnectedAtMs.get()

    fun sendVideoFrame(payload: ByteArray, isKeyframe: Boolean) {
        if (!connected.get()) return
        val sent = frameIndex.incrementAndGet()
        if (sent == 1L || sent % 120L == 0L) {
            AppLog.i(
                "ACB",
                "video packet queued count=$sent transport=$activeTransport size=${payload.size} key=$isKeyframe",
            )
        }
        val flags = if (isKeyframe) 0x01 else 0x00
        sendBinary(streamType = 1, codec = 1, flags = flags, payload = payload)
    }

    fun sendAudioFrame(payload: ByteArray, length: Int) {
        if (!connected.get() || length <= 0) return
        val data = payload.copyOf(length)
        sendBinary(streamType = 2, codec = 2, flags = 0, payload = data)
    }

    private fun sendBinary(streamType: Int, codec: Int, flags: Int, payload: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.put(1) // version
        header.put(streamType.toByte())
        header.put(codec.toByte())
        header.put(flags.toByte())
        val nowUs = System.nanoTime() / 1000L
        header.putLong(nowUs) // pts
        header.putLong(nowUs) // dts
        header.putInt(payload.size)

        val packet = ByteArray(24 + payload.size)
        System.arraycopy(header.array(), 0, packet, 0, 24)
        System.arraycopy(payload, 0, packet, 24, payload.size)
        if (activeTransport == "usb-native") {
            sendUsbNativePacket(packet)
        } else if (activeTransport == "usb-aoa") {
            usbAccessoryTransport?.sendFrame(packet)
        } else {
            val socket = ws ?: return
            if (streamType == 1 && socket.queueSize() > VIDEO_WS_BACKPRESSURE_BYTES) {
                val dropped = droppedVideoPackets.incrementAndGet()
                if (dropped == 1L || dropped % 60L == 0L) {
                    AppLog.w(
                        "ACB",
                        "dropping video due to websocket backpressure queue=${socket.queueSize()} bytes dropped=$dropped",
                    )
                }
                return
            }
            socket.send(ByteString.of(*packet))
        }
    }

    private fun startUsbNativeLink(): Boolean {
        if (sessionId.isBlank()) return false
        return try {
            val body = JSONObject()
                .put("sessionId", sessionId)
                .put("devicePath", "android-usb-native")
                .put("mtu", 65536)
                .toString()
            val req = Request.Builder()
                .url("http://$receiverAddress/api/v2/usb-native/handshake")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val text = resp.body.string()
                if (text.isBlank()) return false
                val obj = JSONObject(text)
                usbNativeLinkId = obj.optString("linkId")
                usbNativeLinkId.isNotBlank()
            }
        } catch (t: Throwable) {
            AppLog.w("ACB", "usb-native handshake failed: ${t.message}", t)
            onDebugEvent?.invoke("usb-native handshake failed: ${t.message}")
            false
        }
    }

    private fun sendUsbNativePacket(packet: ByteArray) {
        if (!connected.get() || usbNativeLinkId.isBlank()) return
        val seq = usbSeq.getAndIncrement()
        val count = usbPacketCount.incrementAndGet()
        val body = JSONObject()
            .put("linkId", usbNativeLinkId)
            .put("seq", seq)
            .put("size", packet.size)
            .put("payload", Base64.encodeToString(packet, Base64.NO_WRAP))
            .toString()
        try {
            val req = Request.Builder()
                .url("http://$receiverAddress/api/v2/usb-native/packet")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            usbPacketHttp.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val msg = resp.body.string().take(200)
                    AppLog.w("ACB", "usb-native packet failed http=${resp.code} seq=$seq size=${packet.size} body=$msg")
                    onDebugEvent?.invoke("usb-native packet failed http=${resp.code} seq=$seq size=${packet.size}")
                } else if (count % 60L == 0L) {
                    emit("usb-native packet sent count=$count seq=$seq size=${packet.size}")
                }
            }
        } catch (t: Throwable) {
            AppLog.w("ACB", "usb-native packet send failed seq=$seq size=${packet.size}: ${t.message}", t)
            onDebugEvent?.invoke("usb-native packet send failed seq=$seq size=${packet.size}: ${t.message}")
        }
    }

    private fun sendUsbNativeProbePacket() {
        if (!connected.get() || usbNativeLinkId.isBlank()) return
        val seq = usbSeq.getAndIncrement()
        val body = JSONObject()
            .put("linkId", usbNativeLinkId)
            .put("seq", seq)
            .put("size", 0)
            .toString()
        try {
            val req = Request.Builder()
                .url("http://$receiverAddress/api/v2/usb-native/packet")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            usbPacketHttp.newCall(req).execute().use { resp ->
                val text = resp.body.string()
                emit("usb-native probe http=${resp.code} resp=$text")
            }
        } catch (t: Throwable) {
            AppLog.w("ACB", "usb-native probe failed: ${t.message}", t)
            onDebugEvent?.invoke("usb-native probe failed: ${t.message}")
        }
    }

    fun close() {
        try {
            ws?.close(1000, "stop")
        } catch (_: Throwable) {
        }
        ws = null
        connected.set(false)
        emitState(StreamUiState.STOPPED, "Client closed")
    }

    fun setUsbAccessoryTransport(transport: UsbAccessoryTransport?) {
        usbAccessoryTransport = transport
        AppLog.i("ACB", "usb accessory transport attached connected=${transport?.isConnected() == true}")
    }
}
