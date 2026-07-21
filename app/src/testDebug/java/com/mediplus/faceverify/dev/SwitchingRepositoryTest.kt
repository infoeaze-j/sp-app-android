package com.mediplus.faceverify.dev

import com.mediplus.faceverify.core.camera.TransientFrame
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.session.InMemorySessionManager
import com.mediplus.faceverify.data.repository.AuthRepositoryImpl
import com.mediplus.faceverify.data.repository.EnrollmentRepositoryImpl
import com.mediplus.faceverify.data.repository.FaceRepositoryImpl
import com.mediplus.faceverify.data.repository.MemberRepositoryImpl
import com.mediplus.faceverify.dev.repository.FakeAuthRepository
import com.mediplus.faceverify.dev.repository.FakeEnrollmentRepository
import com.mediplus.faceverify.dev.repository.FakeFaceRepository
import com.mediplus.faceverify.dev.repository.FakeMemberRepository
import com.mediplus.faceverify.dev.repository.SwitchingAuthRepository
import com.mediplus.faceverify.dev.repository.SwitchingEnrollmentRepository
import com.mediplus.faceverify.dev.repository.SwitchingFaceRepository
import com.mediplus.faceverify.dev.repository.SwitchingMemberRepository
import com.mediplus.faceverify.domain.model.MemberVerification
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers all four Switching*Repository routers for both master-toggle states. Each test asserts
 * both sides of the routing: the expected delegate was called AND the other delegate was not, so a
 * transposed `fake`/`real` at any call site — including inside a class's own `pick()` — fails a test.
 */
class SwitchingRepositoryTest {

    // ---- Member ----

    private val realMember = mockk<MemberRepositoryImpl>().also {
        coEvery { it.verify(any()) } returns AppResult.Success(
            MemberVerification(MemberVerification.Status.VALID, "REAL", true, true, true, null),
        )
    }

    @Test
    fun `member verify delegates to fake when fake is enabled`() = runTest {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = true, latencyMillis = 0L))
        val switching = SwitchingMemberRepository(realMember, FakeMemberRepository(store), store)

        val result = switching.verify(FakeData.memberNumber) as AppResult.Success
        assertEquals(null, result.data.reason) // fake VALID has null reason
    }

    @Test
    fun `member verify delegates to real when fake is disabled`() = runTest {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = false, latencyMillis = 0L))
        val switching = SwitchingMemberRepository(realMember, FakeMemberRepository(store), store)

        val result = switching.verify(FakeData.memberNumber) as AppResult.Success
        assertEquals("REAL", result.data.reason)
    }

    // ---- Auth ----

    @Test
    fun `auth signIn delegates to fake when fake is enabled`() = runTest {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = true, latencyMillis = 0L))
        val real = mockk<AuthRepositoryImpl>(relaxed = true)
        val fake = spyk(FakeAuthRepository(store, InMemorySessionManager()))
        val switching = SwitchingAuthRepository(real, fake, store)

        switching.signIn("demo", "demo")

        coVerify(exactly = 1) { fake.signIn("demo", "demo") }
        coVerify(exactly = 0) { real.signIn(any(), any()) }
    }

    @Test
    fun `auth signIn delegates to real when fake is disabled`() = runTest {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = false, latencyMillis = 0L))
        val real = mockk<AuthRepositoryImpl>(relaxed = true)
        val fake = spyk(FakeAuthRepository(store, InMemorySessionManager()))
        val switching = SwitchingAuthRepository(real, fake, store)

        switching.signIn("demo", "demo")

        coVerify(exactly = 1) { real.signIn("demo", "demo") }
        coVerify(exactly = 0) { fake.signIn(any(), any()) }
    }

    @Test
    fun `auth signOut delegates to fake when fake is enabled`() = runTest {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = true, latencyMillis = 0L))
        val real = mockk<AuthRepositoryImpl>(relaxed = true)
        val fake = spyk(FakeAuthRepository(store, InMemorySessionManager()))
        val switching = SwitchingAuthRepository(real, fake, store)

        switching.signOut()

        coVerify(exactly = 1) { fake.signOut() }
        coVerify(exactly = 0) { real.signOut() }
    }

    @Test
    fun `auth signOut delegates to real when fake is disabled`() = runTest {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = false, latencyMillis = 0L))
        val real = mockk<AuthRepositoryImpl>(relaxed = true)
        val fake = spyk(FakeAuthRepository(store, InMemorySessionManager()))
        val switching = SwitchingAuthRepository(real, fake, store)

        switching.signOut()

        coVerify(exactly = 1) { real.signOut() }
        coVerify(exactly = 0) { fake.signOut() }
    }

    // ---- Face ----

    private fun frame() = TransientFrame(byteArrayOf(1, 2, 3))

    @Test
    fun `face verify delegates to fake when fake is enabled`() = runTest {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = true, latencyMillis = 0L))
        val real = mockk<FaceRepositoryImpl>(relaxed = true)
        val fake = spyk(FakeFaceRepository(store))
        val switching = SwitchingFaceRepository(real, fake, store)

        switching.verify("X123", frame())

        coVerify(exactly = 1) { fake.verify("X123", any()) }
        coVerify(exactly = 0) { real.verify(any(), any()) }
    }

    @Test
    fun `face verify delegates to real when fake is disabled`() = runTest {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = false, latencyMillis = 0L))
        val real = mockk<FaceRepositoryImpl>(relaxed = true)
        val fake = spyk(FakeFaceRepository(store))
        val switching = SwitchingFaceRepository(real, fake, store)

        switching.verify("X123", frame())

        coVerify(exactly = 1) { real.verify("X123", any()) }
        coVerify(exactly = 0) { fake.verify(any(), any()) }
    }

    // ---- Enrollment ----

    @Test
    fun `enrollment listServices delegates to fake when fake is enabled`() = runTest {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = true, latencyMillis = 0L))
        val real = mockk<EnrollmentRepositoryImpl>(relaxed = true)
        val fake = spyk(FakeEnrollmentRepository(store))
        val switching = SwitchingEnrollmentRepository(real, fake, store)

        switching.listServices("X123")

        coVerify(exactly = 1) { fake.listServices("X123") }
        coVerify(exactly = 0) { real.listServices(any()) }
    }

    @Test
    fun `enrollment listServices delegates to real when fake is disabled`() = runTest {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = false, latencyMillis = 0L))
        val real = mockk<EnrollmentRepositoryImpl>(relaxed = true)
        val fake = spyk(FakeEnrollmentRepository(store))
        val switching = SwitchingEnrollmentRepository(real, fake, store)

        switching.listServices("X123")

        coVerify(exactly = 1) { real.listServices("X123") }
        coVerify(exactly = 0) { fake.listServices(any()) }
    }

    @Test
    fun `enrollment enroll delegates to fake when fake is enabled`() = runTest {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = true, latencyMillis = 0L))
        val real = mockk<EnrollmentRepositoryImpl>(relaxed = true)
        val fake = spyk(FakeEnrollmentRepository(store))
        val switching = SwitchingEnrollmentRepository(real, fake, store)

        switching.enroll("X123", "svc-blood", "key-1")

        coVerify(exactly = 1) { fake.enroll("X123", "svc-blood", "key-1") }
        coVerify(exactly = 0) { real.enroll(any(), any(), any()) }
    }

    @Test
    fun `enrollment enroll delegates to real when fake is disabled`() = runTest {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = false, latencyMillis = 0L))
        val real = mockk<EnrollmentRepositoryImpl>(relaxed = true)
        val fake = spyk(FakeEnrollmentRepository(store))
        val switching = SwitchingEnrollmentRepository(real, fake, store)

        switching.enroll("X123", "svc-blood", "key-1")

        coVerify(exactly = 1) { real.enroll("X123", "svc-blood", "key-1") }
        coVerify(exactly = 0) { fake.enroll(any(), any(), any()) }
    }

    @Test
    fun `enrollment recheck delegates to fake when fake is enabled`() = runTest {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = true, latencyMillis = 0L))
        val real = mockk<EnrollmentRepositoryImpl>(relaxed = true)
        val fake = spyk(FakeEnrollmentRepository(store))
        val switching = SwitchingEnrollmentRepository(real, fake, store)

        switching.recheck("X123", "key-1")

        coVerify(exactly = 1) { fake.recheck("X123", "key-1") }
        coVerify(exactly = 0) { real.recheck(any(), any()) }
    }

    @Test
    fun `enrollment recheck delegates to real when fake is disabled`() = runTest {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = false, latencyMillis = 0L))
        val real = mockk<EnrollmentRepositoryImpl>(relaxed = true)
        val fake = spyk(FakeEnrollmentRepository(store))
        val switching = SwitchingEnrollmentRepository(real, fake, store)

        switching.recheck("X123", "key-1")

        coVerify(exactly = 1) { real.recheck("X123", "key-1") }
        coVerify(exactly = 0) { fake.recheck(any(), any()) }
    }
}
