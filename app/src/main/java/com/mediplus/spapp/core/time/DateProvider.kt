package com.mediplus.spapp.core.time

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Wall-clock date source for the local document not-expired check. Injected for deterministic tests. */
fun interface DateProvider {
    fun today(): LocalDate
}

@Singleton
class SystemDateProvider @Inject constructor() : DateProvider {
    override fun today(): LocalDate = LocalDate.now()
}
