package com.mediplus.spapp.domain.usecase

import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.session.SessionManager
import com.mediplus.spapp.data.repository.MemberRepository
import com.mediplus.spapp.domain.model.MemberNumber
import com.mediplus.spapp.domain.model.MemberVerification
import com.mediplus.spapp.domain.model.VerifiedIdentity
import javax.inject.Inject

/**
 * Turns a scanned card number into a verified-or-rejected outcome (FR-007, FR-008). A card is
 * member-verified ONLY when the back office returns VALID + memberVerified for a resolved member.
 * Any rejection surfaces a specific reason.
 *
 * Unlike the document flow this replaces, there is no local pre-check: a member card carries no
 * expiry date, so membership validity is entirely the back office's to decide.
 */
class VerifyMemberUseCase @Inject constructor(
    private val memberRepository: MemberRepository,
    private val sessionManager: SessionManager,
) {
    suspend operator fun invoke(memberNumber: MemberNumber): AppResult<MemberVerification> =
        when (val result = memberRepository.verify(memberNumber)) {
            is AppResult.Success -> interpret(memberNumber, result.data)
            else -> result
        }

    private fun interpret(
        memberNumber: MemberNumber,
        verification: MemberVerification,
    ): AppResult<MemberVerification> {
        // Without resolved details there is nothing to key /face/verify or /patients/... on.
        if (!verification.memberResolved || verification.member == null) {
            return AppResult.BusinessRejection(AppError.Business(BusinessCode.PATIENT_NOT_FOUND))
        }
        val verified = verification.status == MemberVerification.Status.VALID && verification.memberVerified
        if (!verified) {
            return AppResult.BusinessRejection(
                AppError.Business(BusinessCode.MEMBER_INVALID, serverReason = verification.reason),
            )
        }
        // A fresh scan resets the composite for this member; face verification comes next (FR-032).
        // The resolved details ride along so the enrollment step can show the operator who the
        // transaction is for — this is the only point in the journey where they arrive.
        sessionManager.updateVerifiedIdentity {
            VerifiedIdentity(
                memberNumber = memberNumber.value,
                memberVerified = true,
                patient = verification.member,
            )
        }
        return AppResult.Success(verification)
    }
}
