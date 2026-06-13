package com.justb81.compassduel.crash

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal on-device crash capture.
 *
 * Release builds are minified and usually installed without a debugger attached
 * (e.g. from the Play internal track), so an uncaught exception just shows the
 * system "app keeps stopping" dialog and the stack trace is only in logcat —
 * which a non-developer tester cannot reach. [CrashReporter] persists the last
 * uncaught exception to a small file so it can be surfaced on the next launch
 * (see the crash dialog in the navigation graph) and copied/shared.
 *
 * The handler chains to the previously-installed default handler, so the OS
 * still records the crash in logcat and shows its normal dialog.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val CRASH_FILE_NAME = "last_crash.txt"

    /**
     * Installs a default uncaught-exception handler that writes the stack trace to
     * [CRASH_FILE_NAME] before delegating to the previous handler. Idempotent: a
     * second call replaces the existing handler but preserves the original chain.
     *
     * @param context Any context; the application context is used internally.
     */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(crashFile(appContext), thread, throwable) }
                .onFailure { Log.e(TAG, "Failed to persist crash report", it) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Returns the stored crash report (if any) and deletes the file so it is shown
     * only once. Returns null when no crash was recorded since the last consume.
     *
     * @param context Any context; the application context is used internally.
     */
    fun consumeLastCrash(context: Context): String? = consumeReport(crashFile(context.applicationContext))

    // ---------------------------------------------------------------------------
    // Context-free helpers (unit-tested)
    // ---------------------------------------------------------------------------

    /** Writes a formatted crash report for [throwable] (on [thread]) to [file]. */
    internal fun writeReport(file: File, thread: Thread, throwable: Throwable) {
        file.writeText(formatReport(thread, throwable))
    }

    /** Reads and deletes [file], returning its non-blank contents or null. */
    internal fun consumeReport(file: File): String? {
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
        file.delete()
        return text
    }

    /** Builds the human-readable report body: metadata header plus the full stack trace. */
    internal fun formatReport(thread: Thread, throwable: Throwable): String {
        val stackTrace = StringWriter().also { sw ->
            PrintWriter(sw).use { throwable.printStackTrace(it) }
        }.toString()

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("Time: $timestamp")
            appendLine("Thread: ${thread.name}")
            appendLine(
                "Device: ${Build.MANUFACTURER} ${Build.MODEL} " +
                    "(Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})",
            )
            appendLine()
            append(stackTrace)
        }
    }

    private fun crashFile(context: Context): File = File(context.filesDir, CRASH_FILE_NAME)
}
