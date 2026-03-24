package com.acb.androidcam

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class UsbAccessoryTransport(
    private val context: Context,
    private val onDebugLog: ((String) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "UsbAoaTransport"
        private val MAGIC = byteArrayOf(0x41, 0x43, 0x42, 0x01)
        private const val MAX_FRAME_SIZE = 2 * 1024 * 1024
        private const val KEEPALIVE_INTERVAL_MS = 500L
        private const val STREAM_VIDEO = 1
        private const val STREAM_AUDIO = 2
        private const val STREAM_META = 3
        private const val AUDIO_QUEUE_CAPACITY = 12
    }

    private data class PendingFrame(
        val streamType: Int,
        val bytes: ByteArray,
    )

    private val connected = AtomicBoolean(false)
    @Volatile private var accessory: UsbAccessory? = null
    @Volatile private var fileDescriptor: ParcelFileDescriptor? = null
    @Volatile private var outputStream: FileOutputStream? = null
    @Volatile private var inputStream: FileInputStream? = null
    private var writeThread: Thread? = null
    private var keepaliveThread: Thread? = null
    private val queueLock = Object()
    private val audioQueue = ArrayDeque<PendingFrame>(AUDIO_QUEUE_CAPACITY)
    private var latestVideoFrame: PendingFrame? = null
    private var latestMetaFrame: PendingFrame? = null
    private val txFrames = AtomicLong(0)
    private val txBytes = AtomicLong(0)
    private val droppedVideo = AtomicLong(0)
    private val droppedAudio = AtomicLong(0)
    private val lastSendTimeMs = AtomicLong(0)
    private val running = AtomicBoolean(false)

    private fun emit(msg: String) {
        onDebugLog?.invoke(msg)
        AppLog.i(TAG, msg)
    }

    fun open(accessory: UsbAccessory): Boolean {
        close()
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val pfd: ParcelFileDescriptor? = try {
            usbManager.openAccessory(accessory)
        } catch (e: Exception) {
            AppLog.e(TAG, "openAccessory failed: ${e.message}", e)
            emit("openAccessory failed: ${e.message}")
            null
        }
        if (pfd == null) {
            AppLog.e(TAG, "openAccessory returned null")
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
        droppedVideo.set(0)
        droppedAudio.set(0)
        lastSendTimeMs.set(System.currentTimeMillis())
        synchronized(queueLock) {
            audioQueue.clear()
            latestVideoFrame = null
            latestMetaFrame = null
        }

        writeThread = Thread({ writeLoop() }, "UsbAoaWriter").also { it.start() }
        keepaliveThread = Thread({ keepaliveLoop() }, "UsbAoaKeepalive").also { it.start() }

        emit("USB accessory opened: manufacturer=${accessory.manufacturer} model=${accessory.model}")
        return true
    }

    fun sendFrame(v2Packet: ByteArray) {
        if (!connected.get()) return
        if (v2Packet.size > MAX_FRAME_SIZE) {
            AppLog.w(TAG, "sendFrame: packet too large (${v2Packet.size} > $MAX_FRAME_SIZE), dropping")
            return
        }

        val streamType = if (v2Packet.size >= 2) v2Packet[1].toInt() and 0xFF else 0
        val frame = PendingFrame(streamType = streamType, bytes = buildEnvelope(v2Packet))
        synchronized(queueLock) {
            when (streamType) {
                STREAM_VIDEO -> {
                    if (latestVideoFrame != null) {
                        val dropped = droppedVideo.incrementAndGet()
                        if (dropped == 1L || dropped % 60L == 0L) {
                            AppLog.w(TAG, "dropping stale video before send dropped=$dropped")
                        }
                    }
                    latestVideoFrame = frame
                }
                STREAM_AUDIO -> {
                    if (audioQueue.size >= AUDIO_QUEUE_CAPACITY) {
                        if (audioQueue.isNotEmpty()) {
                            audioQueue.removeFirst()
                        }
                        val dropped = droppedAudio.incrementAndGet()
                        if (dropped == 1L || dropped % 60L == 0L) {
                            AppLog.w(TAG, "dropping oldest audio due to queue pressure dropped=$dropped")
                        }
                    }
                    audioQueue.addLast(frame)
                }
                else -> {
                    latestMetaFrame = frame
                }
            }
            queueLock.notifyAll()
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
        AppLog.d(TAG, "writeLoop started")
        try {
            while (running.get()) {
                val frame = synchronized(queueLock) {
                    while (running.get() && audioQueue.isEmpty() && latestVideoFrame == null && latestMetaFrame == null) {
                        try {
                            queueLock.wait(20)
                        } catch (_: InterruptedException) {
                            return@synchronized null
                        }
                    }
                    if (!running.get()) {
                        null
                    } else if (audioQueue.isNotEmpty()) {
                        audioQueue.removeFirst()
                    } else if (latestVideoFrame != null) {
                        val frame = latestVideoFrame
                        latestVideoFrame = null
                        frame
                    } else {
                        val frame = latestMetaFrame
                        latestMetaFrame = null
                        frame
                    }
                } ?: continue

                try {
                    outputStream?.write(frame.bytes)
                    txFrames.incrementAndGet()
                    txBytes.addAndGet(frame.bytes.size.toLong())
                    lastSendTimeMs.set(System.currentTimeMillis())
                    val sent = txFrames.get()
                    if (sent == 1L || sent % 120L == 0L) {
                        AppLog.i(TAG, "usb frame written count=$sent stream=${frame.streamType} bytes=${frame.bytes.size} queued=${pendingCount()}")
                    }
                } catch (e: IOException) {
                    if (running.get()) {
                        emit("Write error: ${e.message}")
                    }
                    connected.set(false)
                    break
                }
            }
        } catch (_: InterruptedException) {
            AppLog.d(TAG, "writeLoop interrupted")
        } catch (_: Throwable) {
        }
        AppLog.d(TAG, "writeLoop exiting")
    }

    private fun keepaliveLoop() {
        AppLog.d(TAG, "keepaliveLoop started")
        try {
            while (running.get() && connected.get()) {
                Thread.sleep(KEEPALIVE_INTERVAL_MS)
                if (!connected.get()) break

                val elapsed = System.currentTimeMillis() - lastSendTimeMs.get()
                if (elapsed >= KEEPALIVE_INTERVAL_MS) {
                    val nowUs = System.nanoTime() / 1000L
                    val v2Header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                    v2Header.put(1)
                    v2Header.put(STREAM_META.toByte())
                    v2Header.put(0.toByte())
                    v2Header.put(0.toByte())
                    v2Header.putLong(nowUs)
                    v2Header.putLong(nowUs)
                    v2Header.putInt(0)
                    synchronized(queueLock) {
                        latestMetaFrame = PendingFrame(STREAM_META, buildEnvelope(v2Header.array()))
                        queueLock.notifyAll()
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (_: Throwable) {
        }
        AppLog.d(TAG, "keepaliveLoop exiting")
    }

    fun close() {
        if (!running.compareAndSet(true, false)) return
        connected.set(false)
        synchronized(queueLock) {
            audioQueue.clear()
            latestVideoFrame = null
            latestMetaFrame = null
            queueLock.notifyAll()
        }

        val pfd = fileDescriptor
        fileDescriptor = null
        try {
            pfd?.close()
        } catch (_: Throwable) {
        }

        writeThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(3000)
            } catch (_: InterruptedException) {
            }
        }
        writeThread = null

        keepaliveThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(1000)
            } catch (_: InterruptedException) {
            }
        }
        keepaliveThread = null

        outputStream = null
        inputStream = null
        accessory = null
        emit("USB accessory transport closed")
    }

    fun isConnected(): Boolean = connected.get()

    fun getStats(): Triple<Long, Long, Long> = Triple(txFrames.get(), txBytes.get(), pendingCount())

    private fun pendingCount(): Long {
        return synchronized(queueLock) {
            audioQueue.size.toLong() +
                (if (latestVideoFrame != null) 1 else 0) +
                (if (latestMetaFrame != null) 1 else 0)
        }
    }
}
