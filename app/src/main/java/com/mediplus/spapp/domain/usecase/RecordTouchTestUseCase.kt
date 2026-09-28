package com.mediplus.spapp.domain.usecase

import com.mediplus.spapp.data.local.PrefsDataStore
import com.mediplus.spapp.domain.model.TouchTestRecord
import com.mediplus.spapp.domain.model.TouchTestStatus
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

/** Records a finished touch test as this device's latest, replacing any earlier one. */
class RecordTouchTestUseCase @Inject constructor(
    private val prefs: PrefsDataStore,
    private val clock: Clock,
) {
    suspend operator fun invoke(passed: Boolean): TouchTestStatus {
        val at = Instant.now(clock)
        prefs.recordTouchTest(TouchTestRecord(passed, at))
        return if (passed) TouchTestStatus.Passed(at) else TouchTestStatus.Failed(at)
    }
}
