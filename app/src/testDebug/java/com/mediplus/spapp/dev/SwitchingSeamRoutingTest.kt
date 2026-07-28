package com.mediplus.spapp.dev

import android.app.Activity
import com.mediplus.spapp.core.camera.FaceCamera
import com.mediplus.spapp.core.camera.RealFaceCameraFactory
import com.mediplus.spapp.core.camera.TransientFrame
import com.mediplus.spapp.core.device.DeviceIdStore
import com.mediplus.spapp.core.diagnostics.AndroidDeviceDiagnostics
import com.mediplus.spapp.core.diagnostics.UptimeState
import com.mediplus.spapp.core.nfc.NdefMemberCardReader
import com.mediplus.spapp.core.nfc.NfcHost
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.session.InMemorySessionManager
import com.mediplus.spapp.core.update.InstallOutcome
import com.mediplus.spapp.core.update.PackageInstallerApkInstaller
import com.mediplus.spapp.data.repository.AuthRepositoryImpl
import com.mediplus.spapp.data.repository.DeviceRepositoryImpl
import com.mediplus.spapp.data.repository.DiagnosticsRepositoryImpl
import com.mediplus.spapp.data.repository.EnrollmentRepositoryImpl
import com.mediplus.spapp.data.repository.FaceRepositoryImpl
import com.mediplus.spapp.data.repository.MemberRepositoryImpl
import com.mediplus.spapp.data.repository.UpdateRepositoryImpl
import com.mediplus.spapp.dev.camera.FakeFaceCamera
import com.mediplus.spapp.dev.camera.SwitchingFaceCameraFactory
import com.mediplus.spapp.dev.diagnostics.FakeDeviceDiagnostics
import com.mediplus.spapp.dev.diagnostics.SwitchingDeviceDiagnostics
import com.mediplus.spapp.dev.nfc.FakeMemberCardReader
import com.mediplus.spapp.dev.nfc.SwitchingMemberCardReader
import com.mediplus.spapp.dev.repository.FakeAuthRepository
import com.mediplus.spapp.dev.repository.FakeDeviceRepository
import com.mediplus.spapp.dev.repository.FakeEnrollmentRepository
import com.mediplus.spapp.dev.repository.FakeFaceRepository
import com.mediplus.spapp.dev.repository.FakeMemberRepository
import com.mediplus.spapp.dev.repository.FakeUpdateRepository
import com.mediplus.spapp.dev.repository.SwitchingAuthRepository
import com.mediplus.spapp.dev.repository.SwitchingDeviceRepository
import com.mediplus.spapp.dev.repository.SwitchingDiagnosticsRepository
import com.mediplus.spapp.dev.repository.SwitchingEnrollmentRepository
import com.mediplus.spapp.dev.repository.SwitchingFaceRepository
import com.mediplus.spapp.dev.repository.SwitchingMemberRepository
import com.mediplus.spapp.dev.repository.SwitchingUpdateRepository
import com.mediplus.spapp.dev.update.FakeApkInstaller
import com.mediplus.spapp.dev.update.SwitchingApkInstaller
import com.mediplus.spapp.domain.model.CurrentAppVersion
import com.mediplus.spapp.domain.model.EnrollmentRequest
import com.mediplus.spapp.domain.model.MemberVerification
import com.mediplus.spapp.domain.model.Money
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Every `Switching*` router honours its own [FakeSeam] toggle while the master toggle stays on.
 * The existing `Switching*Test` classes already cover the master toggle; these cover the seam that
 * the master toggle gates, so a router wired to the wrong seam constant fails here.
 */
class SwitchingSeamRoutingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Master on, [off] seams routed to their real impls. */
    private fun store(vararg off: FakeSeam) = TestDevSettingsStore(
        DevSettings(
            fakeEnabled = true,
            fakeSeams = DevSettings().fakeSeams + off.associateWith { false },
            latencyMillis = 0L,
        ),
    )

    // ---- Auth ----

    @Test
    fun `auth uses the real impl when only the auth seam is off`() = runTest {
        val store = store(FakeSeam.AUTH)
        val real = mockk<AuthRepositoryImpl>(relaxed = true)
        val fake = spyk(FakeAuthRepository(store, InMemorySessionManager()))

        SwitchingAuthRepository(real, fake, store).signIn("demo", "demo")

        coVerify(exactly = 1) { real.signIn("demo", "demo") }
        coVerify(exactly = 0) { fake.signIn(any(), any()) }
    }

    @Test
    fun `auth stays faked when a different seam is off`() = runTest {
        val store = store(FakeSeam.FACE)
        val real = mockk<AuthRepositoryImpl>(relaxed = true)
        val fake = spyk(FakeAuthRepository(store, InMemorySessionManager()))

        SwitchingAuthRepository(real, fake, store).signIn("demo", "demo")

        coVerify(exactly = 1) { fake.signIn("demo", "demo") }
        coVerify(exactly = 0) { real.signIn(any(), any()) }
    }

    @Test
    fun `revalidateSession uses the real impl when only the auth seam is off`() = runTest {
        val store = store(FakeSeam.AUTH)
        val real = mockk<AuthRepositoryImpl>(relaxed = true)
        val fake = spyk(FakeAuthRepository(store, InMemorySessionManager()))

        SwitchingAuthRepository(real, fake, store).revalidateSession()

        coVerify(exactly = 1) { real.revalidateSession() }
        coVerify(exactly = 0) { fake.revalidateSession() }
    }

    @Test
    fun `revalidateSession stays faked when a different seam is off`() = runTest {
        val store = store(FakeSeam.FACE)
        val real = mockk<AuthRepositoryImpl>(relaxed = true)
        val fake = spyk(FakeAuthRepository(store, InMemorySessionManager()))

        SwitchingAuthRepository(real, fake, store).revalidateSession()

        coVerify(exactly = 1) { fake.revalidateSession() }
        coVerify(exactly = 0) { real.revalidateSession() }
    }

    // ---- Device registration ----

    @Test
    fun `device registration uses the real impl when only the device seam is off`() = runTest {
        val store = store(FakeSeam.DEVICE)
        val real = mockk<DeviceRepositoryImpl>(relaxed = true)
        val fake = spyk(FakeDeviceRepository(store, DeviceIdStore()))

        SwitchingDeviceRepository(real, fake, store).register()

        coVerify(exactly = 1) { real.register() }
        coVerify(exactly = 0) { fake.register() }
    }

    @Test
    fun `device registration stays faked when a different seam is off`() = runTest {
        val store = store(FakeSeam.AUTH)
        val real = mockk<DeviceRepositoryImpl>(relaxed = true)
        val deviceIdStore = DeviceIdStore()

        val result = SwitchingDeviceRepository(real, FakeDeviceRepository(store, deviceIdStore), store).register()

        assertEquals(AppResult.Success(FakeData.deviceId), result)
        assertEquals(FakeData.deviceId, deviceIdStore.deviceId.value)
        coVerify(exactly = 0) { real.register() }
    }

    // ---- Member ----

    @Test
    fun `member verify uses the real impl when only the member seam is off`() = runTest {
        val store = store(FakeSeam.MEMBER)
        val real = mockk<MemberRepositoryImpl>().also {
            coEvery { it.verify(any()) } returns AppResult.Success(
                MemberVerification(MemberVerification.Status.VALID, "REAL", referenceOnFile = true, member = null),
            )
        }

        val result = SwitchingMemberRepository(real, FakeMemberRepository(store), store)
            .verify(FakeData.memberNumber) as AppResult.Success

        assertEquals("REAL", result.data.reason)
    }

    // ---- Face ----

    @Test
    fun `face verify uses the real impl when only the face seam is off`() = runTest {
        val store = store(FakeSeam.FACE)
        val real = mockk<FaceRepositoryImpl>(relaxed = true)
        val fake = spyk(FakeFaceRepository(store))

        SwitchingFaceRepository(real, fake, store).verify("X123", TransientFrame(byteArrayOf(1, 2, 3)))

        coVerify(exactly = 1) { real.verify("X123", any()) }
        coVerify(exactly = 0) { fake.verify(any(), any()) }
    }

    // ---- Enrollment (services, currencies and enroll share one seam) ----

    @Test
    fun `enrollment uses the real impl for every call when the enrollment seam is off`() = runTest {
        val store = store(FakeSeam.ENROLLMENT)
        val real = mockk<EnrollmentRepositoryImpl>(relaxed = true)
        val fake = spyk(FakeEnrollmentRepository(store))
        val switching = SwitchingEnrollmentRepository(real, fake, store)

        switching.listServices("X123")
        switching.enroll("X123", EnrollmentRequest("svc-blood", "ver-1", "ZAR", Money(15_000), "key-1"))
        switching.recheck("X123", "key-1")

        coVerify(exactly = 1) { real.listServices("X123") }
        coVerify(exactly = 1) { real.enroll("X123", EnrollmentRequest("svc-blood", "ver-1", "ZAR", Money(15_000), "key-1")) }
        coVerify(exactly = 1) { real.recheck("X123", "key-1") }
        coVerify(exactly = 0) { fake.listServices(any()) }
        coVerify(exactly = 0) { fake.enroll(any(), any()) }
        coVerify(exactly = 0) { fake.recheck(any(), any()) }
    }

    // ---- Card tap ----

    @Test
    fun `the card reader uses real NFC when only the card seam is off`() = runTest {
        val store = store(FakeSeam.CARD)
        val real = mockk<NdefMemberCardReader>(relaxed = true)
        val host = NfcHost(mockk<Activity>(relaxed = true))

        SwitchingMemberCardReader(real, FakeMemberCardReader(store), store).awaitAndRead(host)

        coVerify(exactly = 1) { real.awaitAndRead(any(), any()) }
    }

    // ---- Camera ----

    @Test
    fun `the camera uses real CameraX when only the camera seam is off`() = runTest {
        val store = store(FakeSeam.CAMERA)
        val real = mockk<RealFaceCameraFactory>(relaxed = true)
        val realCamera = mockk<FaceCamera>(relaxed = true)
        coEvery { real.create() } returns realCamera
        val fake = FakeFaceCamera(store)

        val camera = SwitchingFaceCameraFactory(real, Provider { fake }, store).create()

        assertEquals(realCamera, camera)
        assertNotSame(fake, camera)
    }

    // ---- Self-update (repository and installer share one seam) ----

    @Test
    fun `the update repository uses the real impl when the update seam is off`() = runTest {
        val store = store(FakeSeam.UPDATE)
        val real = mockk<UpdateRepositoryImpl>()
        coEvery { real.fetchVersionInfo() } returns AppResult.Timeout
        val fake = FakeUpdateRepository(store, CurrentAppVersion(5, "1.4"), tempFolder.root)

        assertEquals(AppResult.Timeout, SwitchingUpdateRepository(real, fake, store).fetchVersionInfo())
    }

    @Test
    fun `the apk installer uses the real impl when the update seam is off`() = runTest {
        val store = store(FakeSeam.UPDATE)
        val real = mockk<PackageInstallerApkInstaller>()
        coEvery { real.install(any()) } returns InstallOutcome.Aborted

        val outcome = SwitchingApkInstaller(real, FakeApkInstaller(store), store)
            .install(tempFolder.newFile())

        assertEquals(InstallOutcome.Aborted, outcome)
    }

    // ---- Diagnostics: back office and device reader toggle separately ----

    @Test
    fun `the diagnostics repository uses the real impl when the diagnostics seam is off`() = runTest {
        val store = store(FakeSeam.DIAGNOSTICS)
        val real = mockk<DiagnosticsRepositoryImpl>()
        coEvery { real.poll() } returns AppResult.Timeout

        assertEquals(AppResult.Timeout, SwitchingDiagnosticsRepository(real, store).poll())
    }

    @Test
    fun `the device reader stays faked when only the diagnostics seam is off`() = runTest {
        val store = store(FakeSeam.DIAGNOSTICS)
        val real = mockk<AndroidDeviceDiagnostics>()
        val fake = FakeDeviceDiagnostics()

        assertEquals(fake.snapshot(), SwitchingDeviceDiagnostics(real, fake, store).snapshot())
        coVerify(exactly = 0) { real.snapshot() }
    }

    @Test
    fun `the device reader uses real sensors when only the device state seam is off`() = runTest {
        val store = store(FakeSeam.DEVICE_STATE)
        val fake = FakeDeviceDiagnostics()
        val realSnapshot = fake.snapshot().copy(uptime = UptimeState(999_999L, 999_999L))
        val real = mockk<AndroidDeviceDiagnostics> { coEvery { snapshot() } returns realSnapshot }

        assertEquals(realSnapshot, SwitchingDeviceDiagnostics(real, fake, store).snapshot())
    }
}
