package com.acb.androidcam

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.charset.StandardCharsets
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
    private const val MaxLogFilesToKeep = 3

    @Volatile private var appContext: Context? = null
    @Volatile private var logDirPath: String = ""
    @Volatile private var activeFile: File? = null

    private val tsFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val sessionFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val dir = resolveLogDir(ctx)
        logDirPath = dir.absolutePath

        if (initialized.compareAndSet(false, true)) {
            synchronized(fileLock) {
                activeFile = createSessionLogFile(dir)
            }
            pruneOldLogs(dir, keepCount = MaxLogFilesToKeep)
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
        synchronized(fileLock) {
            val cached = activeFile
            if (cached != null) {
                return cached
            }

            val dir = resolveLogDir(context)
            val file = createSessionLogFile(dir)
            activeFile = file
            pruneOldLogs(dir, keepCount = MaxLogFilesToKeep)
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

    private fun createSessionLogFile(dir: File): File {
        val baseName = "acb-${LocalDateTime.now().format(sessionFormatter)}"
        val primary = File(dir, "$baseName.log")
        if (!primary.exists()) {
            return primary
        }

        var suffix = 1
        while (true) {
            val candidate = File(dir, "$baseName-$suffix.log")
            if (!candidate.exists()) {
                return candidate
            }
            suffix++
        }
    }

    private fun pruneOldLogs(dir: File, keepCount: Int) {
        val files = dir.listFiles { file ->
            file.isFile && file.name.startsWith("acb-") && file.name.endsWith(".log")
        }?.sortedByDescending { it.lastModified() } ?: return

        files.drop(keepCount).forEach { file ->
            runCatching { file.delete() }
        }
    }
}
