package com.mediplus.faceverify

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. Hosts the Hilt dependency graph for the whole process.
 *
 * All verification state is process/session-scoped and held in memory only (Decision 6); nothing
 * biometric is ever persisted here.
 */
@HiltAndroidApp
class FaceVerifyApp : Application()
