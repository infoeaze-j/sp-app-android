package com.mediplus.spapp.domain.usecase

import com.mediplus.spapp.core.diagnostics.AppInfo
import com.mediplus.spapp.core.diagnostics.BatteryPlug
import com.mediplus.spapp.core.diagnostics.BatteryState
import com.mediplus.spapp.core.diagnostics.DeviceDiagnostics
import com.mediplus.spapp.core.diagnostics.DeviceInfo
import com.mediplus.spapp.core.diagnostics.DeviceStateSnapshot
import com.mediplus.spapp.core.diagnostics.DisplayState
import com.mediplus.spapp.core.diagnostics.EnvironmentState
import com.mediplus.spapp.core.diagnostics.MemoryState
import com.mediplus.spapp.core.diagnostics.NetworkState
import com.mediplus.spapp.core.diagnostics.NetworkTransport
import com.mediplus.spapp.core.diagnostics.StorageState
import com.mediplus.spapp.core.diagnostics.ThermalState
import com.mediplus.spapp.core.diagnostics.UptimeState
import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.data.repository.DiagnosticsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PollAndReportDiagnosticsUseCaseTest {

    private val snapshot = DeviceStateSnapshot(
        battery = BatteryState(80, true, BatteryPlug.USB, "2", 250, 4100, false),
        network = NetworkState(NetworkTransport.WIFI, isMetered = false, isValidated = true),
        storage = StorageState(1L, 2L),
        memory = MemoryState(1L, 2L, false),
        display = DisplayState(1, 2, 3, 60f, 0),
        device = DeviceInfo("m", "mo", "b", "d", 34, "14"),
        app = AppInfo("1.0", 1),
        environment = EnvironmentState("en-ZA", "UTC", false),
        thermal = ThermalState(null, null),
        uptime = UptimeState(1L, 2L),
    )

    private val diagnostics = mockk<DeviceDiagnostics> { coEvery { snapshot() } returns snapshot }

    private fun transient() = AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))

    @Test
    fun `nothing requested - no snapshot, no report`() = runTest {
        val repo = mockk<DiagnosticsRepository>(relaxed = true) {
            coEvery { poll() } returns AppResult.Success(null)
        }
        val useCase = PollAndReportDiagnosticsUseCase(repo, diagnostics)
        assertEquals(PollOutcome.NothingRequested, useCase())
        coVerify(exactly = 0) { diagnostics.snapshot() }
        coVerify(exactly = 0) { repo.report(any(), any()) }
    }

    @Test
    fun `fresh request id - collects and reports`() = runTest {
        val repo = mockk<DiagnosticsRepository> {
            coEvery { poll() } returns AppResult.Success("req-1")
            coEvery { report("req-1", snapshot) } returns AppResult.Success(Unit)
        }
        val useCase = PollAndReportDiagnosticsUseCase(repo, diagnostics)
        assertEquals(PollOutcome.Reported, useCase())
        coVerify(exactly = 1) { repo.report("req-1", snapshot) }
    }

    @Test
    fun `same request id twice - reports once`() = runTest {
        val repo = mockk<DiagnosticsRepository> {
            coEvery { poll() } returns AppResult.Success("req-1")
            coEvery { report("req-1", snapshot) } returns AppResult.Success(Unit)
        }
        val useCase = PollAndReportDiagnosticsUseCase(repo, diagnostics)
        assertEquals(PollOutcome.Reported, useCase())
        assertEquals(PollOutcome.AlreadyHandled, useCase())
        coVerify(exactly = 1) { repo.report("req-1", snapshot) }
    }

    @Test
    fun `poll failure - no report`() = runTest {
        val repo = mockk<DiagnosticsRepository>(relaxed = true) {
            coEvery { poll() } returns transient()
        }
        val useCase = PollAndReportDiagnosticsUseCase(repo, diagnostics)
        assertEquals(PollOutcome.PollFailed, useCase())
        coVerify(exactly = 0) { repo.report(any(), any()) }
    }

    @Test
    fun `report failure - not recorded, retried next time`() = runTest {
        val repo = mockk<DiagnosticsRepository> {
            coEvery { poll() } returns AppResult.Success("req-1")
            coEvery { report("req-1", snapshot) } returnsMany listOf(transient(), AppResult.Success(Unit))
        }
        val useCase = PollAndReportDiagnosticsUseCase(repo, diagnostics)
        assertEquals(PollOutcome.ReportFailed, useCase())
        assertEquals(PollOutcome.Reported, useCase())
        coVerify(exactly = 2) { repo.report("req-1", snapshot) }
    }
}
