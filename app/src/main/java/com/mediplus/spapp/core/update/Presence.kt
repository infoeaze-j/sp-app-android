package com.mediplus.spapp.core.update

/**
 * Whether a human is looking at the app right now. The install flow needs this exactly once: when
 * the platform refuses a confirmation-free commit, a foregrounded app can raise the system dialog
 * directly, while a headless one must post a notification instead (design §3).
 */
enum class Presence { Foreground, Headless }

/**
 * How an update attempt ended, from the point of view of "is it worth trying again soon".
 * [RETRYABLE] is reserved for transient transport failures and timeouts, where WorkManager's
 * exponential backoff is the right response. Every definite answer — up to date, a business
 * rejection, a corrupt APK, a commit awaiting confirmation — is [COMPLETED]: the next periodic run
 * is the correct cadence, and hammering a server that gave a clear answer helps nobody.
 */
enum class UpdateAttempt { COMPLETED, RETRYABLE }
