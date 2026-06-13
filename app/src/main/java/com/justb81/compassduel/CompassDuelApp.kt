package com.justb81.compassduel

import android.app.Application
import com.justb81.compassduel.crash.CrashReporter
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. [HiltAndroidApp] triggers Hilt's code generation and
 * creates the application-level dependency container that every Activity,
 * ViewModel and Service in the app branches off.
 */
@HiltAndroidApp
class CompassDuelApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Capture uncaught exceptions to a file so the stack trace can be shown on
        // the next launch — release builds are usually installed without a debugger
        // attached, so logcat is not reachable by testers.
        CrashReporter.install(this)
    }
}
