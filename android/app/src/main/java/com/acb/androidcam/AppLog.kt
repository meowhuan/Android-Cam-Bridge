package com.acb.androidcam

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object AppLog {
    private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AcbFileLogger").apply { isDaemon = true }
    }
    private val initialized = AtomicBoolean(false)
    private val uncaughtHandlerInstalled = AtomicBoolean(false)
    private val fileLock = Any()

    @Volatile private var appContext: Context? = null
    @Volatile private var logDirPath: String = ""
    @Volatile private var activeDate = ""
    @Volatile private var activeFile: File? = null

    private val dayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val tsFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val dir = resolveLogDir(ctx)
        logDirPath = dir.absolutePath

        if (initialized.compareAndSet(false, true)) {
            installUncaughtHandler()
            i("AppLog", "file logging initialized dir=$logDirPath")
        }
    }

    fun getLogDirPath(): String = logDirPath

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        write("D", tag, message, null)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        write("I", tag, message, null)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
        write("W", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
        write("E", tag, message, throwable)
    }

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        val ctx = appContext ?: return
        val suffix = if (throwable != null) {
            "\n${Log.getStackTraceString(throwable)}"
        } else {
            ""
        }
        val line = "${LocalDateTime.now().format(tsFormatter)} $level/$tag: $message$suffix\n"
        writerExecutor.execute {
            try {
                val file = currentLogFile(ctx)
                file.appendText(line, StandardCharsets.UTF_8)
            } catch (_: Throwable) {
            }
        }
    }

    private fun installUncaughtHandler() {
        if (!uncaughtHandlerInstalled.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write("E", "Uncaught", "fatal thread=${thread.name}", throwable)
                Thread.sleep(80)
            } catch (_: Throwable) {
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun currentLogFile(context: Context): File {
        val today = LocalDate.now().format(dayFormatter)
        synchronized(fileLock) {
            val cached = activeFile
            if (cached != null && activeDate == today) {
                return cached
            }
            val dir = resolveLogDir(context)
            val file = File(dir, "acb-$today.log")
            activeDate = today
            activeFile = file
            return file
        }
    }

    private fun resolveLogDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "logs")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
}
