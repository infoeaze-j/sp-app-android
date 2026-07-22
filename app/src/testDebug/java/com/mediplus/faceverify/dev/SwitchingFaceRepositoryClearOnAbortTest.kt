package com.mediplus.faceverify.dev

import com.mediplus.faceverify.core.camera.TransientFrame
import com.mediplus.faceverify.data.repository.FaceRepositoryImpl
import com.mediplus.faceverify.dev.repository.FakeFaceRepository
import com.mediplus.faceverify.dev.repository.SwitchingFaceRepository
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * FR-017: [TransientFrame.clear] must happen even when the store read that precedes dispatch
 * throws or is cancelled before either delegate's own try/finally is reached.
 */
class SwitchingFaceRepositoryClearOnAbortTest {

    /** A [DevSettingsStore] whose [current] always throws. */
    private class ThrowingStore : DevSettingsStore {
        override val settings: Flow<DevSettings> get() = throw NotImplementedError("unused")
        override suspend fun current(): DevSettings = throw IOException("corrupt preferences file")
        override suspend fun setFakeEnabled(enabled: Boolean) = throw NotImplementedError("unused")
        override suspend fun setAuth(scenario: AuthScenario) = throw NotImplementedError("unused")
        override suspend fun setCard(scenario: CardScenario) = throw NotImplementedError("unused")
        override suspend fun setCamera(scenario: CameraScenario) = throw NotImplementedError("unused")
        override suspend fun setMember(scenario: MemberScenario) = throw NotImplementedError("unused")
        override suspend fun setFace(scenario: FaceScenario) = throw NotImplementedError("unused")
        override suspend fun setServices(scenario: ServicesScenario) = throw NotImplementedError("unused")
        override suspend fun setEnroll(scenario: EnrollScenario) = throw NotImplementedError("unused")
        override suspend fun setLatencyMillis(millis: Long) = throw NotImplementedError("unused")
        override suspend fun setVerificationWindowSeconds(seconds: Long) = throw NotImplementedError("unused")
    }

    /** A [DevSettingsStore] whose [current] suspends forever, until the caller is cancelled. */
    private class ForeverSuspendingStore : DevSettingsStore {
        override val settings: Flow<DevSettings> get() = throw NotImplementedError("unused")
        override suspend fun current(): DevSettings = awaitCancellation()
        override suspend fun setFakeEnabled(enabled: Boolean) = throw NotImplementedError("unused")
        override suspend fun setAuth(scenario: AuthScenario) = throw NotImplementedError("unused")
        override suspend fun setCard(scenario: CardScenario) = throw NotImplementedError("unused")
        override suspend fun setCamera(scenario: CameraScenario) = throw NotImplementedError("unused")
        override suspend fun setMember(scenario: MemberScenario) = throw NotImplementedError("unused")
        override suspend fun setFace(scenario: FaceScenario) = throw NotImplementedError("unused")
        override suspend fun setServices(scenario: ServicesScenario) = throw NotImplementedError("unused")
        override suspend fun setEnroll(scenario: EnrollScenario) = throw NotImplementedError("unused")
        override suspend fun setLatencyMillis(millis: Long) = throw NotImplementedError("unused")
        override suspend fun setVerificationWindowSeconds(seconds: Long) = throw NotImplementedError("unused")
    }

    private fun switching(store: DevSettingsStore) = SwitchingFaceRepository(
        mockk<FaceRepositoryImpl>(relaxed = true),
        mockk<FakeFaceRepository>(relaxed = true),
        store,
    )

    @Test
    fun `verify clears the frame when the store read throws`() = runTest {
        val frame = TransientFrame(byteArrayOf(1, 2, 3))

        val thrown = try {
            switching(ThrowingStore()).verify("X123", frame)
            null
        } catch (e: IOException) {
            e
        }

        assertTrue("verify() must propagate the store's IOException", thrown is IOException)
        assertTrue("frame must be cleared even when store.current() throws (FR-017)", frame.isCleared)
    }

    @Test
    fun `verify clears the frame when cancelled while the store read is suspended`() = runTest {
        val frame = TransientFrame(byteArrayOf(1, 2, 3))

        val job = launch { switching(ForeverSuspendingStore()).verify("X123", frame) }
        yield() // let the coroutine reach the suspended store.current() call
        job.cancelAndJoin()

        assertTrue("frame must be cleared even when cancelled mid store.current() (FR-017)", frame.isCleared)
    }
}
