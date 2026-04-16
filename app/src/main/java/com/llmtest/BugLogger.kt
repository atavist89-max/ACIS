package com.llmtest

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object BugLogger {
    private const val TAG = "BugLogger"
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null
    private var isInitialized = false
    
    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            val logsDir = File(context.filesDir, "logs")
            if (!logsDir.exists()) logsDir.mkdirs()
            logFile = File(logsDir, "bug_log.txt")
            logFile?.writeText("")
            isInitialized = true
            log("=== BUG LOG STARTED ===")
            log("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            log("Android: ${android.os.Build.VERSION.RELEASE}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init logger", e)
        }
    }
    
    fun log(message: String, level: String = "INFO") {
        val timestamp = dateFormat.format(Date())
        val logEntry = "[$timestamp] [$level] $message\n"
        Log.d(TAG, message)
        executor.execute {
            try {
                logFile?.appendText(logEntry)
            } catch (e: Exception) {}
        }
    }
    
    fun logError(message: String, throwable: Throwable? = null) {
        log(message, "ERROR")
        throwable?.let {
            log("Stack: ${it.stackTraceToString().take(500)}", "ERROR")
        }
    }
    
    fun readLog(): String {
        return try {
            logFile?.readText() ?: "No log file"
        } catch (e: Exception) {
            "Error reading log: ${e.message}"
        }
    }
}
