package com.mediplus.faceverify.data.repository

import com.mediplus.faceverify.core.di.IoDispatcher
import com.mediplus.faceverify.core.network.apiCall
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.remote.MemberApi
import com.mediplus.faceverify.data.remote.MemberDto
import com.mediplus.faceverify.data.remote.VerifyMemberRequest
import com.mediplus.faceverify.data.remote.VerifyMemberResponse
import com.mediplus.faceverify.domain.model.MemberDetails
import com.mediplus.faceverify.domain.model.MemberNumber
import com.mediplus.faceverify.domain.model.MemberVerification
import kotlinx.coroutines.CoroutineDispatcher
import java.net.HttpURLConnection
import javax.inject.Inject

/**
 * Submits a scanned member card number for the authoritative verdict and member resolution
 * (FR-008, FR-011a). Transport outcomes become [AppResult]; the business interpretation
 * (verified vs. rejected) is [com.mediplus.faceverify.domain.usecase.VerifyMemberUseCase]'s job.
 */
interface MemberRepository {
    suspend fun verify(memberNumber: MemberNumber): AppResult<MemberVerification>
}

class MemberRepositoryImpl @Inject constructor(
    private val api: MemberApi,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : MemberRepository {

    override suspend fun verify(memberNumber: MemberNumber): AppResult<MemberVerification> =
        apiCall(dispatcher, { api.verify(VerifyMemberRequest(memberNumber.value)) }) { response ->
            val body = response.body()
            when {
                response.isSuccessful && body != null -> AppResult.Success(body.toVerification())
                response.code() == HttpURLConnection.HTTP_NOT_FOUND ->
                    AppResult.BusinessRejection(AppError.Business(BusinessCode.PATIENT_NOT_FOUND))
                response.code() in SERVER_ERROR_RANGE ->
                    AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
                else -> AppResult.BusinessRejection(AppError.Business(BusinessCode.MEMBER_INVALID))
            }
        }

    private companion object {
        val SERVER_ERROR_RANGE = 500..599
    }
}

private fun VerifyMemberResponse.toVerification() = MemberVerification(
    status = if (status.equals("VALID", ignoreCase = true)) {
        MemberVerification.Status.VALID
    } else {
        MemberVerification.Status.INVALID
    },
    reason = reason,
    memberVerified = memberVerified,
    memberResolved = memberResolved,
    referenceOnFile = referenceOnFile,
    member = member?.toDomain(),
)

private fun MemberDto.toDomain() = MemberDetails(
    memberNumber = memberNumber,
    fullName = fullName,
    dateOfBirth = dateOfBirth,
    membershipStatus = membershipStatus,
    plan = plan,
)
