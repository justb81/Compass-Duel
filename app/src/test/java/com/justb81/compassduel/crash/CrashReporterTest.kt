package com.justb81.compassduel.crash

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class CrashReporterTest {

    @Test
    fun `formatReport includes the throwable message and stack trace`() {
        val throwable = IllegalStateException("boom")

        val report = CrashReporter.formatReport(Thread.currentThread(), throwable)

        assertTrue(report.contains("IllegalStateException"), "report should name the exception type")
        assertTrue(report.contains("boom"), "report should include the message")
        assertTrue(report.contains("CrashReporterTest"), "report should include the originating stack frame")
        assertTrue(report.contains("Thread: ${Thread.currentThread().name}"), "report should record the thread")
    }

    @Test
    fun `consumeReport returns null when no crash file exists`(@TempDir dir: Path) {
        val file = File(dir.toFile(), "last_crash.txt")

        assertNull(CrashReporter.consumeReport(file))
    }

    @Test
    fun `writeReport then consumeReport returns the report and deletes the file`(@TempDir dir: Path) {
        val file = File(dir.toFile(), "last_crash.txt")

        CrashReporter.writeReport(file, Thread.currentThread(), RuntimeException("kaboom"))
        assertTrue(file.exists(), "writeReport should create the file")

        val consumed = CrashReporter.consumeReport(file)

        assertNotNull(consumed)
        assertTrue(consumed.orEmpty().contains("kaboom"))
        assertFalse(file.exists(), "consumeReport should delete the file so it shows only once")
    }

    @Test
    fun `consumeReport returns null for a blank file and removes it`(@TempDir dir: Path) {
        val file = File(dir.toFile(), "last_crash.txt").apply { writeText("   ") }

        assertNull(CrashReporter.consumeReport(file))
        assertFalse(file.exists())
    }

    @Test
    fun `second consume after a single write returns null`(@TempDir dir: Path) {
        val file = File(dir.toFile(), "last_crash.txt")
        CrashReporter.writeReport(file, Thread.currentThread(), RuntimeException("once"))

        assertNotNull(CrashReporter.consumeReport(file))
        assertNull(CrashReporter.consumeReport(file))
    }

    @Test
    fun `formatReport starts with the timestamp header`() {
        val report = CrashReporter.formatReport(Thread.currentThread(), RuntimeException("tail"))

        assertTrue(report.lineSequence().first().startsWith("Time: "), "report should start with a Time header")
    }

    @Test
    fun `capStackTrace leaves a short trace unchanged`() {
        val trace = "short trace\n\tat Foo.bar(Foo.kt:1)"

        assertTrue(CrashReporter.capStackTrace(trace) == trace, "a short trace must not be altered")
    }

    @Test
    fun `capStackTrace truncates an oversized trace and marks it`() {
        val oversized = "x".repeat(20_000)

        val capped = CrashReporter.capStackTrace(oversized)

        assertTrue(capped.length < oversized.length, "an oversized trace must be shortened")
        assertTrue(capped.endsWith("[stack trace truncated]"), "a truncated trace must be marked")
    }
}
