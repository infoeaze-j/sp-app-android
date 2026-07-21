package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.camera.TransientFrame
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.data.repository.AuthRepository
import com.mediplus.faceverify.data.repository.AuthRepositoryImpl
import com.mediplus.faceverify.data.repository.DocumentRepository
import com.mediplus.faceverify.data.repository.DocumentRepositoryImpl
import com.mediplus.faceverify.data.repository.EnrollmentRepository
import com.mediplus.faceverify.data.repository.EnrollmentRepositoryImpl
import com.mediplus.faceverify.data.repository.FaceRepository
import com.mediplus.faceverify.data.repository.FaceRepositoryImpl
import com.mediplus.faceverify.data.repository.MemberRepository
import com.mediplus.faceverify.data.repository.MemberRepositoryImpl
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.domain.model.DocumentValidation
import com.mediplus.faceverify.domain.model.Enrollment
import com.mediplus.faceverify.domain.model.FaceDecision
import com.mediplus.faceverify.domain.model.MemberNumber
import com.mediplus.faceverify.domain.model.MemberVerification
import com.mediplus.faceverify.domain.model.ReadDocument
import com.mediplus.faceverify.domain.model.Service
import com.mediplus.faceverify.domain.model.Session
import com.mediplus.faceverify.domain.model.SessionState
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Debug-only routers: use the fake when the master toggle is on, else the real impl. */

class SwitchingAuthRepository @Inject constructor(
    private val real: AuthRepositoryImpl,
    private val fake: FakeAuthRepository,
    private val store: DevSettingsStore,
) : AuthRepository {
    override suspend fun signIn(identifier: String, secret: String): AppResult<Session> =
        pick().signIn(identifier, secret)

    override suspend fun signOut(): AppResult<Unit> = pick().signOut()

    // Both delegate to the same singleton SessionManager, so either is fine; use the real one.
    override fun sessionState(): StateFlow<SessionState> = real.sessionState()

    private suspend fun pick(): AuthRepository = if (store.current().fakeEnabled) fake else real
}

class SwitchingDocumentRepository @Inject constructor(
    private val real: DocumentRepositoryImpl,
    private val fake: FakeDocumentRepository,
    private val store: DevSettingsStore,
) : DocumentRepository {
    override suspend fun validate(read: ReadDocument): AppResult<DocumentValidation> =
        pick().validate(read)

    private suspend fun pick(): DocumentRepository = if (store.current().fakeEnabled) fake else real
}

class SwitchingMemberRepository @Inject constructor(
    private val real: MemberRepositoryImpl,
    private val fake: FakeMemberRepository,
    private val store: DevSettingsStore,
) : MemberRepository {
    override suspend fun verify(memberNumber: MemberNumber): AppResult<MemberVerification> =
        pick().verify(memberNumber)

    private suspend fun pick(): MemberRepository = if (store.current().fakeEnabled) fake else real
}

class SwitchingFaceRepository @Inject constructor(
    private val real: FaceRepositoryImpl,
    private val fake: FakeFaceRepository,
    private val store: DevSettingsStore,
) : FaceRepository {
    override suspend fun verify(memberNumber: String, frame: TransientFrame): AppResult<FaceDecision> =
        try {
            pick().verify(memberNumber, frame)
        } finally {
            frame.clear()
        }

    private suspend fun pick(): FaceRepository = if (store.current().fakeEnabled) fake else real
}

class SwitchingEnrollmentRepository @Inject constructor(
    private val real: EnrollmentRepositoryImpl,
    private val fake: FakeEnrollmentRepository,
    private val store: DevSettingsStore,
) : EnrollmentRepository {
    override suspend fun listServices(memberNumber: String): AppResult<List<Service>> =
        pick().listServices(memberNumber)

    override suspend fun enroll(memberNumber: String, serviceId: String, idempotencyKey: String): AppResult<Enrollment> =
        pick().enroll(memberNumber, serviceId, idempotencyKey)

    override suspend fun recheck(memberNumber: String, idempotencyKey: String): AppResult<Enrollment?> =
        pick().recheck(memberNumber, idempotencyKey)

    private suspend fun pick(): EnrollmentRepository = if (store.current().fakeEnabled) fake else real
}
