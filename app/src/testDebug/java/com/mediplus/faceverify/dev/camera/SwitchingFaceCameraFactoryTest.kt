package com.mediplus.faceverify.dev.camera

import com.mediplus.faceverify.core.camera.FaceCamera
import com.mediplus.faceverify.core.camera.RealFaceCameraFactory
import com.mediplus.faceverify.dev.DevSettings
import com.mediplus.faceverify.dev.TestDevSettingsStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test

class SwitchingFaceCameraFactoryTest {

    private val real = mockk<RealFaceCameraFactory>(relaxed = true)

    private fun factory(fakeEnabled: Boolean): Pair<SwitchingFaceCameraFactory, FakeFaceCamera> {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = fakeEnabled, latencyMillis = 0L))
        val fake = FakeFaceCamera(store)
        return SwitchingFaceCameraFactory(real, Provider { fake }, store) to fake
    }

    @Test
    fun `the fake camera is used when the master toggle is on`() = runTest {
        val (switching, fake) = factory(fakeEnabled = true)

        val camera = switching.create()

        assertSame(fake, camera)
        coVerify(exactly = 0) { real.create() }
    }

    @Test
    fun `the real camera is used when the master toggle is off`() = runTest {
        val (switching, _) = factory(fakeEnabled = false)
        val realCamera = mockk<FaceCamera>(relaxed = true)
        coEvery { real.create() } returns realCamera

        val camera = switching.create()

        assertSame(realCamera, camera)
    }
}
