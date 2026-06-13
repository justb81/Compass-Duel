package com.justb81.compassduel

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.justb81.compassduel.crash.CrashReporter
import com.justb81.compassduel.session.GameSession
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. [HiltAndroidApp] triggers Hilt's code generation and
 * creates the application-level dependency container that every Activity,
 * ViewModel and Service in the app branches off.
 */
@HiltAndroidApp
class CompassDuelApp : Application() {

    // Field-injected: Hilt populates this before onCreate() runs super, so it is
    // available when the process-lifecycle observer is registered below.
    @Inject
    lateinit var session: GameSession

    override fun onCreate() {
        super.onCreate()
        // Capture uncaught exceptions to a file so the stack trace can be shown on
        // the next launch — release builds are usually installed without a debugger
        // attached, so logcat is not reachable by testers.
        CrashReporter.install(this)

        // Tear the session down when the whole app goes to the background (#73).
        // The GameSession singleton otherwise keeps Nearby advertising/discovery,
        // its transport collectors and the engine tick loop alive indefinitely —
        // battery drain plus a privacy concern (the device stays discoverable).
        // A phone-orientation game cannot be played while backgrounded, so a full
        // leave() (stop all Nearby radios + cancel jobs + reset state) is correct.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    session.leave()
                }
            },
        )
    }
}
