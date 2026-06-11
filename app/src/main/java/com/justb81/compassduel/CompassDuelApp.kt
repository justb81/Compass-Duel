package com.justb81.compassduel

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. [HiltAndroidApp] triggers Hilt's code generation and
 * creates the application-level dependency container that every Activity,
 * ViewModel and Service in the app branches off.
 */
@HiltAndroidApp
class CompassDuelApp : Application()
