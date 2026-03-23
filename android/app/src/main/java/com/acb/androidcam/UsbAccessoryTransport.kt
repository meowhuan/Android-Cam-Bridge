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
        private const val WRITE_QUEUE_CAPACITY = 3
    }

    private val connected = AtomicBoolean(false)
    @Volatile private var accessory: UsbAccessory? = null
    @Volatile private var fileDescriptor: ParcelFileDescriptor? = null
    @Volatile private var outputStream: FileOutputStream? = null
    @Volatile private var inputStream: FileInputStream? = null
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

    fun open(accessory: UsbAccessory): Boolean {
        close()
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

    private fun buildEnvelope(v2Packet: ByteArray): ByteArray {
        val envelope = ByteArray(8 + v2Packet.size)
        val header = ByteBuffer.wrap(envelope).order(ByteOrder.LITTLE_ENDIAN)
        header.put(MAGIC)
        header.putInt(v2Packet.size)
        System.arraycopy(v2Packet, 0, envelope, 8, v2Packet.size)
        return envelope
    }

    private fun writeLoop() {
        Log.d(TAG, "writeLoop started")
        try {
            while (running.get()) {
                val frame = writeQueue.poll(5, TimeUnit.MILLISECONDS) ?: continue
                try {
                    outputStream?.write(frame)
                    // No flush() — USB bulk pipe commits data automatically on
                    // microframe boundaries. Explicit flush() forces a synchronous
                    // round-trip to the USB controller, adding 1-5ms per frame.
                    txFrames.incrementAndGet()
                    txBytes.addAndGet(frame.size.toLong())
                    lastSendTimeMs.set(System.currentTimeMillis())
                } catch (e: IOException) {
                    if (running.get()) {
                        emit("Write error: ${e.message}")
                    }
                    connected.set(false)
                    break
                }
            }
        } catch (_: InterruptedException) {
            Log.d(TAG, "writeLoop interrupted")
        } catch (_: Throwable) {
            // Catch any native crash fallout (e.g. closed FD)
        }
        Log.d(TAG, "writeLoop exiting")
    }

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
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (_: Throwable) {
        }
        Log.d(TAG, "keepaliveLoop exiting")
    }

    fun close() {
        if (!running.compareAndSet(true, false)) return
        connected.set(false)

        // Step 1: drain write queue so poll() exits quickly
        writeQueue.clear()

        // Step 2: close the PFD FIRST to unblock any thread stuck in write()/read().
        // This causes IOException in writeLoop, which exits the loop cleanly.
        // We must NOT join threads before closing the FD, because write() on a USB
        // bulk pipe is not interruptible — interrupt() won't unblock it, and join()
        // would time out, then closing the FD from the caller while write() is still
        // running causes a native crash (SIGPIPE/SIGSEGV).
        val pfd = fileDescriptor
        fileDescriptor = null
        try { pfd?.close() } catch (_: Throwable) {}

        // Step 3: now join threads (they should exit quickly after FD close)
        writeThread?.let { thread ->
            thread.interrupt()
            try { thread.join(3000) } catch (_: InterruptedException) {}
        }
        writeThread = null

        keepaliveThread?.let { thread ->
            thread.interrupt()
            try { thread.join(1000) } catch (_: InterruptedException) {}
        }
        keepaliveThread = null

        // Step 4: clean up remaining references
        outputStream = null
        inputStream = null
        accessory = null

        emit("USB accessory transport closed")
    }

    fun isConnected(): Boolean = connected.get()

    fun getStats(): Triple<Long, Long, Long> =
        Triple(txFrames.get(), txBytes.get(), writeQueue.size.toLong())
}
