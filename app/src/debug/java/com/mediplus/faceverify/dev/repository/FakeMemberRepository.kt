package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.repository.MemberRepository
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.dev.FakeData
import com.mediplus.faceverify.dev.MemberScenario
import com.mediplus.faceverify.domain.model.MemberNumber
import com.mediplus.faceverify.domain.model.MemberVerification
import kotlinx.coroutines.delay
import javax.inject.Inject

/** Fake member verification: returns the persisted [MemberScenario]. */
class FakeMemberRepository @Inject constructor(
    private val store: DevSettingsStore,
) : MemberRepository {

    override suspend fun verify(memberNumber: MemberNumber): AppResult<MemberVerification> {
        val settings = store.current()
        delay(settings.latencyMillis)
        return when (settings.member) {
            MemberScenario.SUCCESS -> AppResult.Success(FakeData.verificationValid)
            MemberScenario.INVALID -> AppResult.Success(FakeData.verificationInvalid)
            MemberScenario.PATIENT_NOT_FOUND ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.PATIENT_NOT_FOUND))
            MemberScenario.SERVER_ERROR ->
                AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        }
    }
}
