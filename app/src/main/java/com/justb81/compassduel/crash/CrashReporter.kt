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
 *
 * ## Privacy policy — keep peer- and user-supplied data out of crash reports
 *
 * The persisted report is app-private (written to [Context.getFilesDir]), but it
 * is surfaced in a copy/shareable dialog, so anything that reaches it can leave the
 * device the moment a tester shares it. The report intentionally contains only a
 * timestamp, the thread name, a device fingerprint ([Build]), and the stack trace —
 * no credentials, location, player names, or network payloads. To keep the share
 * path safe as the code evolves:
 *
 * - **Exception messages must never embed peer- or user-supplied strings** — player
 *   names, decoded payload contents, discovered endpoint names, file paths, etc.
 *   Throw with static, developer-authored messages; never interpolate untrusted
 *   input into a throw (e.g. `IllegalStateException("bad name: $peerName")`). A stack
 *   trace carries no such data on its own — only this policy keeps it that way, since
 *   an exception message is rendered into the trace and thus into the shared report.
 * - The captured trace is capped at [MAX_STACK_TRACE_CHARS] before it is stored
 *   ([capStackTrace]) — a defence-in-depth bound on how much can ever be exported in
 *   one share if the rule above is violated or a trace is pathologically large.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val CRASH_FILE_NAME = "last_crash.txt"

    /**
     * Upper bound on the stack-trace characters included in a report. A real trace is
     * far smaller; this only bounds the worst case (see the privacy policy above).
     */
    private const val MAX_STACK_TRACE_CHARS = 8_000

    private const val TRUNCATION_MARKER = "\n… [stack trace truncated]"

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

    /** Builds the human-readable report body: metadata header plus the (capped) stack trace. */
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
            append(capStackTrace(stackTrace))
        }
    }

    /**
     * Caps [stackTrace] at [MAX_STACK_TRACE_CHARS], appending [TRUNCATION_MARKER] when
     * truncated, so a shared report can never export an unbounded blob (see policy).
     */
    internal fun capStackTrace(stackTrace: String): String =
        if (stackTrace.length <= MAX_STACK_TRACE_CHARS) {
            stackTrace
        } else {
            stackTrace.take(MAX_STACK_TRACE_CHARS) + TRUNCATION_MARKER
        }

    private fun crashFile(context: Context): File = File(context.filesDir, CRASH_FILE_NAME)
}
