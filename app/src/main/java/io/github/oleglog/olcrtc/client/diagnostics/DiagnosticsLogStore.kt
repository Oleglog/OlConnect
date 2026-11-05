package io.github.oleglog.olcrtc.client.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant

internal class DiagnosticsLogStore(
    private val directory: File,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    init {
        directory.mkdirs()
    }

    @Synchronized
    fun append(level: String, message: String, throwable: Throwable? = null) {
        val timestamp = clockMillis()
        val safeMessage = DiagnosticsRedactor.redact(message)
        val line = buildString {
            append(Instant.ofEpochMilli(timestamp))
            append(' ')
            append(level.uppercase())
            append(' ')
            append(safeMessage.lineSequence().joinToString("\\n"))
            throwable?.let {
                append(" | ")
                append(DiagnosticsRedactor.redact(it.javaClass.simpleName + ": " + (it.message ?: "")))
            }
            append('\n')
        }
        // Also mirror to logcat: the file lives in app-private storage, so a
        // release build cannot be inspected over adb without exporting from the
        // UI. Redacted text only, so a logcat dump stays safe to share.
        // runCatching: android.util.Log is not mocked on the JVM unit tests.
        runCatching { Log.println(logcatPriority(level), LOGCAT_TAG, safeMessage) }
        currentLogFile(timestamp).appendText(line)
    }

    @Synchronized
    fun readRedacted(maxBytes: Int = DEFAULT_EXPORT_BYTES): String {
        val builder = StringBuilder()
        logFiles().forEach { file ->
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append("# ").append(file.name).append('\n')
            builder.append(DiagnosticsRedactor.redact(file.readText()))
            if (builder.length >= maxBytes) return builder.toString().takeLast(maxBytes)
        }
        return builder.toString()
    }

    @Synchronized
    fun prune(maxAgeMillis: Long = MAX_AGE_MILLIS, maxTotalBytes: Long = MAX_TOTAL_BYTES) {
        val oldestAllowed = clockMillis() - maxAgeMillis
        logFiles().forEach { file ->
            if (file.lastModified() < oldestAllowed) file.delete()
        }
        val files = logFiles().sortedByDescending(File::lastModified)
        var total = files.sumOf(File::length)
        files.drop(1).asReversed().forEach { file ->
            if (total <= maxTotalBytes) return
            total -= file.length()
            file.delete()
        }
    }

    private fun currentLogFile(timestamp: Long): File {
        val day = Instant.ofEpochMilli(timestamp).toString().substring(0, 10)
        return File(directory, "olcrtc-$day.log")
    }

    private fun logFiles(): List<File> = directory
        .listFiles { file -> file.isFile && file.name.endsWith(".log") }
        ?.sortedBy { it.name }
        .orEmpty()

    companion object {
        private const val DEFAULT_EXPORT_BYTES = 256 * 1024
        private const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_TOTAL_BYTES = 20L * 1024 * 1024

        /** Tag used for the mirrored logcat lines. */
        const val LOGCAT_TAG = "olcrtc"

        private fun logcatPriority(level: String): Int = when (level.lowercase()) {
            "error" -> Log.ERROR
            "warn", "warning" -> Log.WARN
            else -> Log.INFO
        }

        fun open(context: Context): DiagnosticsLogStore = DiagnosticsLogStore(
            File(context.noBackupFilesDir, "diagnostics"),
        )
    }
}
