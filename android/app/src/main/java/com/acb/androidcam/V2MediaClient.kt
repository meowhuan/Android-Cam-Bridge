package com.acb.androidcam

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class V2MediaClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val connected = AtomicBoolean(false)
    private val frameIndex = AtomicLong(0)
    private val lastConnectedAtMs = AtomicLong(0)

    data class SessionInfo(
        val sessionId: String,
        val wsUrl: String,
        val authToken: String,
    )

    fun startSession(receiverAddress: String, transport: String): SessionInfo? {
        return try {
            val body = JSONObject()
                .put("transport", transport)
                .put("mode", "obs_direct")
                .put("video", JSONObject().put("codec", "h264"))
                .put("audio", JSONObject().put("codec", "aac").put("enabled", true))
                .toString()

            val req = Request.Builder()
                .url("http://$receiverAddress/api/v2/session/start")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string() ?: return null
                val obj = JSONObject(text)
                SessionInfo(
                    sessionId = obj.optString("sessionId"),
                    wsUrl = obj.optString("wsUrl"),
                    authToken = obj.optString("authToken"),
                )
            }
        } catch (t: Throwable) {
            Log.w("ACB", "v2 start session failed: ${t.message}")
            null
        }
    }

    fun connect(wsUrl: String) {
        if (wsUrl.isBlank()) return
        ws?.cancel()
        connected.set(false)
        val req = Request.Builder().url(wsUrl).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true)
                lastConnectedAtMs.set(System.currentTimeMillis())
                Log.i("ACB", "v2 websocket connected")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.set(false)
                Log.w("ACB", "v2 websocket failed: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false)
            }
        })
    }

    fun isConnected(): Boolean = connected.get()

    fun getLastConnectedAtMs(): Long = lastConnectedAtMs.get()

    fun sendVideoFrame(payload: ByteArray, isKeyframe: Boolean) {
        if (!connected.get()) return
        frameIndex.incrementAndGet()
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
        ws?.send(ByteString.of(*packet))
    }

    fun close() {
        try {
            ws?.close(1000, "stop")
        } catch (_: Throwable) {
        }
        connected.set(false)
    }
}
