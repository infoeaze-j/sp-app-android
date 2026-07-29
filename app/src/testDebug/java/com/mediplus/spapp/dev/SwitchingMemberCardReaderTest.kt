package com.mediplus.spapp.dev

import android.app.Activity
import com.mediplus.spapp.core.nfc.UidMemberCardReader
import com.mediplus.spapp.core.nfc.NfcHost
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.dev.nfc.FakeMemberCardReader
import com.mediplus.spapp.dev.nfc.SwitchingMemberCardReader
import com.mediplus.spapp.domain.model.MemberNumber
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SwitchingMemberCardReaderTest {

    private val host = NfcHost(mockk<Activity>(relaxed = true))
    private val real = mockk<UidMemberCardReader>(relaxed = true)

    private fun reader(fakeEnabled: Boolean): Pair<SwitchingMemberCardReader, TestDevSettingsStore> {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = fakeEnabled, latencyMillis = 0L))
        return SwitchingMemberCardReader(real, FakeMemberCardReader(store), store) to store
    }

    @Test
    fun `the fake is used when the master toggle is on`() = runTest {
        val (switching, _) = reader(fakeEnabled = true)

        val result = switching.awaitAndRead(host)

        assertEquals(FakeData.memberNumber, (result as AppResult.Success).data)
        coVerify(exactly = 0) { real.awaitAndRead(any(), any()) }
    }

    @Test
    fun `the real reader is used when the master toggle is off`() = runTest {
        val (switching, _) = reader(fakeEnabled = false)
        val realNumber = MemberNumber.parse("7654321")!!
        coEvery { real.awaitAndRead(any(), any()) } returns AppResult.Success(realNumber)

        val result = switching.awaitAndRead(host)

        assertEquals(realNumber, (result as AppResult.Success).data)
    }

    @Test
    fun `availability follows the same routing`() = runTest {
        val (switching, _) = reader(fakeEnabled = true)

        switching.isAvailable()

        coVerify(exactly = 0) { real.isAvailable() }
    }
}
