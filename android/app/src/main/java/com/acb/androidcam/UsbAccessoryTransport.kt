package com.acb.androidcam

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class UsbAccessoryTransport(
    private val context: Context,
    private val onDebugLog: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "UsbAoaTransport"
        private val MAGIC = byteArrayOf(0x41, 0x43, 0x42, 0x01)
        private const val MAX_FRAME_SIZE = 2 * 1024 * 1024
        private const val KEEPALIVE_INTERVAL_MS = 500L
        private const val WRITE_QUEUE_CAPACITY = 256
    }

    private val connected = AtomicBoolean(false)
    private var accessory: UsbAccessory? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var outputStream: FileOutputStream? = null
    private var inputStream: FileInputStream? = null
    private var writeThread: Thread? = null
    private var keepaliveThread: Thread? = null
    private val writeQueue = LinkedBlockingQueue<ByteArray>(WRITE_QUEUE_CAPACITY)
    private val txFrames = AtomicLong(0)
    private val txBytes = AtomicLong(0)
    private val lastSendTimeMs = AtomicLong(0)
    private val running = AtomicBoolean(false)

    private fun emit(msg: String) {
        onDebugLog?.invoke(msg)
        Log.i(TAG, msg)
    }

    /**
     * Open the USB accessory and start the write + keepalive threads.
     * Returns true on success, false if the accessory could not be opened.
     */
    fun open(accessory: UsbAccessory): Boolean {
        closeOnce.set(false)
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val pfd: ParcelFileDescriptor? = try {
            usbManager.openAccessory(accessory)
        } catch (e: Exception) {
            Log.e(TAG, "openAccessory failed: ${e.message}")
            emit("openAccessory failed: ${e.message}")
            null
        }
        if (pfd == null) {
            Log.e(TAG, "openAccessory returned null")
            emit("openAccessory returned null")
            return false
        }

        this.accessory = accessory
        this.fileDescriptor = pfd
        this.outputStream = FileOutputStream(pfd.fileDescriptor)
        this.inputStream = FileInputStream(pfd.fileDescriptor)

        running.set(true)
        connected.set(true)
        txFrames.set(0)
        txBytes.set(0)
        lastSendTimeMs.set(System.currentTimeMillis())
        writeQueue.clear()

        writeThread = Thread({ writeLoop() }, "UsbAoaWriter").also { it.start() }
        keepaliveThread = Thread({ keepaliveLoop() }, "UsbAoaKeepalive").also { it.start() }

        emit("USB accessory opened: manufacturer=${accessory.manufacturer} model=${accessory.model}")
        return true
    }

    /**
     * Enqueue a v2 media packet for transmission over USB.
     * The packet is wrapped in an 8-byte framing envelope before being written.
     *
     * @param v2Packet the complete v2 packet (24-byte header + payload)
     */
    fun sendFrame(v2Packet: ByteArray) {
        if (!connected.get()) return
        if (v2Packet.size > MAX_FRAME_SIZE) {
            Log.w(TAG, "sendFrame: packet too large (${v2Packet.size} > $MAX_FRAME_SIZE), dropping")
            return
        }

        val frame = buildEnvelope(v2Packet)
        if (!writeQueue.offer(frame)) {
            Log.w(TAG, "sendFrame: write queue full, dropping frame (size=${v2Packet.size})")
        }
    }

    /**
     * Build the 8-byte framing envelope around a v2 packet.
     *
     * Layout:
     *   [4 bytes: magic 0x41 0x43 0x42 0x01]
     *   [4 bytes: frame_length LE uint32 = v2Packet.size]
     *   [v2Packet bytes]
     */
    private fun buildEnvelope(v2Packet: ByteArray): ByteArray {
        val envelope = ByteArray(8 + v2Packet.size)
        val header = ByteBuffer.wrap(envelope).order(ByteOrder.LITTLE_ENDIAN)
        header.put(MAGIC)
        header.putInt(v2Packet.size)
        System.arraycopy(v2Packet, 0, envelope, 8, v2Packet.size)
        return envelope
    }

    /**
     * Write thread: drains the write queue and writes frames to the USB output stream.
     */
    private fun writeLoop() {
        Log.d(TAG, "writeLoop started")
        try {
            while (running.get()) {
                val frame = writeQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                try {
                    outputStream?.write(frame)
                    outputStream?.flush()
                    txFrames.incrementAndGet()
                    txBytes.addAndGet(frame.size.toLong())
                    lastSendTimeMs.set(System.currentTimeMillis())
                } catch (e: IOException) {
                    if (running.get()) {
                        emit("Write error: ${e.message}")
                    }
                    connected.set(false)
                    running.set(false)
                    break
                }
            }
        } catch (e: InterruptedException) {
            Log.d(TAG, "writeLoop interrupted")
        } finally {
            Log.d(TAG, "writeLoop exiting")
            closeResources()
        }
    }

    /**
     * Keepalive thread: sends periodic keepalive frames when the link is idle.
     * A keepalive is a v2 header-only packet with streamType=3 (meta), codec=0,
     * flags=0, and payloadSize=0.
     */
    private fun keepaliveLoop() {
        Log.d(TAG, "keepaliveLoop started")
        try {
            while (running.get() && connected.get()) {
                Thread.sleep(KEEPALIVE_INTERVAL_MS)
                if (!connected.get()) break

                val elapsed = System.currentTimeMillis() - lastSendTimeMs.get()
                if (elapsed >= KEEPALIVE_INTERVAL_MS) {
                    val nowUs = System.nanoTime() / 1000L
                    val v2Header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                    v2Header.put(1)                  // version
                    v2Header.put(3.toByte())         // streamType = meta
                    v2Header.put(0.toByte())         // codec = 0
                    v2Header.put(0.toByte())         // flags = 0
                    v2Header.putLong(nowUs)           // ptsUs
                    v2Header.putLong(nowUs)           // dtsUs
                    v2Header.putInt(0)                // payloadSize = 0

                    val keepalivePacket = v2Header.array()
                    val frame = buildEnvelope(keepalivePacket)
                    if (writeQueue.offer(frame)) {
                        lastSendTimeMs.set(System.currentTimeMillis())
                    } else {
                        Log.w(TAG, "keepaliveLoop: queue full, skipping keepalive")
                    }
                }
            }
        } catch (_: InterruptedException) {
            Log.d(TAG, "keepaliveLoop interrupted")
        }
        Log.d(TAG, "keepaliveLoop exiting")
    }

    /**
     * Close the transport, stopping threads and releasing all resources.
     */
    fun close() {
        running.set(false)
        connected.set(false)

        writeThread?.let { thread ->
            if (thread.isAlive) {
                thread.interrupt()
                try {
                    thread.join(2000)
                } catch (_: InterruptedException) {
                }
            }
        }
        writeThread = null

        keepaliveThread?.let { thread ->
            if (thread.isAlive) {
                thread.interrupt()
                try {
                    thread.join(2000)
                } catch (_: InterruptedException) {
                }
            }
        }
        keepaliveThread = null

        closeResources()
        emit("USB accessory transport closed")
    }

    private val closeOnce = AtomicBoolean(false)

    /**
     * Release underlying I/O resources (streams + file descriptor).
     */
    private fun closeResources() {
        if (!closeOnce.compareAndSet(false, true)) return
        // Only close ParcelFileDescriptor — it owns the FD shared by both streams
        try { fileDescriptor?.close() } catch (_: IOException) {}
        outputStream = null
        inputStream = null
        fileDescriptor = null
        accessory = null
    }

    fun isConnected(): Boolean = connected.get()

    /**
     * Returns transport statistics as (txFrames, txBytes, queuedFrames).
     */
    fun getStats(): Triple<Long, Long, Long> =
        Triple(txFrames.get(), txBytes.get(), writeQueue.size.toLong())
}
