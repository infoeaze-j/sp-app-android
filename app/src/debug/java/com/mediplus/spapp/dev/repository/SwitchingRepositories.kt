package com.mediplus.spapp.dev.repository

import com.mediplus.spapp.core.camera.TransientFrame
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.data.repository.AuthRepository
import com.mediplus.spapp.data.repository.AuthRepositoryImpl
import com.mediplus.spapp.data.repository.DeviceRepository
import com.mediplus.spapp.data.repository.DeviceRepositoryImpl
import com.mediplus.spapp.data.repository.EnrollmentRepository
import com.mediplus.spapp.data.repository.EnrollmentRepositoryImpl
import com.mediplus.spapp.data.repository.FaceRepository
import com.mediplus.spapp.data.repository.FaceRepositoryImpl
import com.mediplus.spapp.data.repository.MemberRepository
import com.mediplus.spapp.data.repository.MemberRepositoryImpl
import com.mediplus.spapp.data.repository.SessionCheck
import com.mediplus.spapp.data.repository.UpdateRepository
import com.mediplus.spapp.data.repository.UpdateRepositoryImpl
import com.mediplus.spapp.dev.DevSettingsStore
import com.mediplus.spapp.dev.FakeSeam
import com.mediplus.spapp.domain.model.DownloadedApk
import com.mediplus.spapp.domain.model.Enrollment
import com.mediplus.spapp.domain.model.EnrollmentRequest
import com.mediplus.spapp.domain.model.FaceDecision
import com.mediplus.spapp.domain.model.MemberNumber
import com.mediplus.spapp.domain.model.MemberVerification
import com.mediplus.spapp.domain.model.ServiceCatalog
import com.mediplus.spapp.domain.model.Session
import com.mediplus.spapp.domain.model.SessionState
import com.mediplus.spapp.domain.model.UpdateInfo
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Debug-only routers: use the fake when the master toggle and this seam's own toggle are on. */

class SwitchingAuthRepository @Inject constructor(
    private val real: AuthRepositoryImpl,
    private val fake: FakeAuthRepository,
    private val store: DevSettingsStore,
) : AuthRepository {
    override suspend fun signIn(identifier: String, secret: String): AppResult<Session> =
        pick().signIn(identifier, secret)

    override suspend fun signOut(): AppResult<Unit> = pick().signOut()

    override suspend fun revalidateSession(): SessionCheck = pick().revalidateSession()

    // Both delegate to the same singleton SessionManager, so either is fine; use the real one.
    override fun sessionState(): StateFlow<SessionState> = real.sessionState()

    private suspend fun pick(): AuthRepository =
        if (store.current().isFakeActive(FakeSeam.AUTH)) fake else real
}

class SwitchingDeviceRepository @Inject constructor(
    private val real: DeviceRepositoryImpl,
    private val fake: FakeDeviceRepository,
    private val store: DevSettingsStore,
) : DeviceRepository {
    override suspend fun register(): AppResult<String> = pick().register()

    private suspend fun pick(): DeviceRepository =
        if (store.current().isFakeActive(FakeSeam.DEVICE)) fake else real
}

class SwitchingMemberRepository @Inject constructor(
    private val real: MemberRepositoryImpl,
    private val fake: FakeMemberRepository,
    private val store: DevSettingsStore,
) : MemberRepository {
    override suspend fun verify(memberNumber: MemberNumber): AppResult<MemberVerification> =
        pick().verify(memberNumber)

    private suspend fun pick(): MemberRepository =
        if (store.current().isFakeActive(FakeSeam.MEMBER)) fake else real
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

    private suspend fun pick(): FaceRepository =
        if (store.current().isFakeActive(FakeSeam.FACE)) fake else real
}

class SwitchingEnrollmentRepository @Inject constructor(
    private val real: EnrollmentRepositoryImpl,
    private val fake: FakeEnrollmentRepository,
    private val store: DevSettingsStore,
) : EnrollmentRepository {
    override suspend fun listServices(memberNumber: String): AppResult<ServiceCatalog> =
        pick().listServices(memberNumber)

    override suspend fun enroll(memberNumber: String, request: EnrollmentRequest): AppResult<Enrollment> =
        pick().enroll(memberNumber, request)

    override suspend fun recheck(memberNumber: String, idempotencyKey: String): AppResult<Enrollment?> =
        pick().recheck(memberNumber, idempotencyKey)

    private suspend fun pick(): EnrollmentRepository =
        if (store.current().isFakeActive(FakeSeam.ENROLLMENT)) fake else real
}

class SwitchingUpdateRepository @Inject constructor(
    private val real: UpdateRepositoryImpl,
    private val fake: FakeUpdateRepository,
    private val store: DevSettingsStore,
) : UpdateRepository {
    override suspend fun fetchVersionInfo(): AppResult<UpdateInfo?> = pick().fetchVersionInfo()

    override suspend fun downloadAndVerify(
        info: UpdateInfo,
        onProgress: suspend (bytesSoFar: Long, totalBytes: Long) -> Unit,
    ): AppResult<DownloadedApk> = pick().downloadAndVerify(info, onProgress)

    override suspend fun pruneObsoleteDownloads() = pick().pruneObsoleteDownloads()

    private suspend fun pick(): UpdateRepository =
        if (store.current().isFakeActive(FakeSeam.UPDATE)) fake else real
}
