package com.mediplus.spapp.dev

import com.mediplus.spapp.BuildConfig
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.dev.repository.FakeUpdateRepository
import com.mediplus.spapp.domain.model.CurrentAppVersion
import com.mediplus.spapp.domain.model.UpdateInfo
import com.mediplus.spapp.domain.model.UpdateStatus
import com.mediplus.spapp.domain.usecase.CheckForUpdateUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The fake publishes a build one versionCode above the running one, and its payload must survive
 * the REAL gating in CheckForUpdateUseCase — a fake the use case rejects as degenerate would make
 * every scenario look like a failed check on the emulator.
 */
class FakeUpdateRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val store = TestDevSettingsStore()
    private val currentVersion = CurrentAppVersion(code = 5, name = "1.4")

    private fun fake() = FakeUpdateRepository(store, currentVersion, tempFolder.root)

    private fun setScenario(scenario: UpdateScenario) {
        store.value = store.value.copy(update = scenario)
    }

    private suspend fun gated(): AppResult<UpdateStatus> =
        CheckForUpdateUseCase(fake(), currentVersion, BuildConfig.BASE_URL)()

    private suspend fun publishedInfo(): UpdateInfo =
        ((fake().fetchVersionInfo() as AppResult.Success).data)!!

    @Test
    fun `UP_TO_DATE gates to up to date`() = runTest {
        setScenario(UpdateScenario.UP_TO_DATE)

        assertEquals(AppResult.Success(UpdateStatus.UpToDate), gated())
    }

    @Test
    fun `OPTIONAL_UPDATE gates to an optional next build`() = runTest {
        setScenario(UpdateScenario.OPTIONAL_UPDATE)

        val status = (gated() as AppResult.Success).data as UpdateStatus.Optional

        assertEquals(currentVersion.code + 1, status.info.latestVersionCode)
    }

    @Test
    fun `FORCED_UPDATE gates to a forced next build`() = runTest {
        setScenario(UpdateScenario.FORCED_UPDATE)

        val status = (gated() as AppResult.Success).data as UpdateStatus.Forced

        assertEquals(currentVersion.code + 1, status.info.latestVersionCode)
    }

    @Test
    fun `CHECK_FAILS is a transient check failure`() = runTest {
        setScenario(UpdateScenario.CHECK_FAILS)

        assertTrue(gated() is AppResult.TransientFailure)
    }

    @Test
    fun `later-stage failure scenarios still offer the update at check time`() = runTest {
        listOf(UpdateScenario.DOWNLOAD_FAILS, UpdateScenario.HASH_MISMATCH, UpdateScenario.INSTALL_FAILS)
            .forEach { scenario ->
                setScenario(scenario)
                assertTrue(
                    "$scenario must offer an update",
                    (gated() as AppResult.Success).data is UpdateStatus.Optional,
                )
            }
    }

    @Test
    fun `a successful download reports paced progress and lands a real file`() = runTest {
        setScenario(UpdateScenario.OPTIONAL_UPDATE)
        val progress = mutableListOf<Pair<Long, Long>>()

        val result = fake().downloadAndVerify(publishedInfo()) { sofar, total ->
            progress.add(sofar to total)
        }

        val downloaded = (result as AppResult.Success).data
        assertTrue("fake APK file must exist", downloaded.file.exists())
        assertEquals(currentVersion.code + 1, downloaded.versionCode)
        assertTrue("progress must be paced in several steps", progress.size >= 3)
        assertEquals("progress must complete", progress.last().second, progress.last().first)
    }

    @Test
    fun `DOWNLOAD_FAILS fails mid-stream as transient`() = runTest {
        setScenario(UpdateScenario.DOWNLOAD_FAILS)
        val progress = mutableListOf<Pair<Long, Long>>()

        val result = fake().downloadAndVerify(publishedInfo()) { sofar, total ->
            progress.add(sofar to total)
        }

        assertTrue(result is AppResult.TransientFailure)
        assertTrue("some progress must be visible before the failure", progress.isNotEmpty())
        assertTrue("progress must not complete", progress.last().first < progress.last().second)
    }

    @Test
    fun `HASH_MISMATCH completes the download then rejects it as corrupted`() = runTest {
        setScenario(UpdateScenario.HASH_MISMATCH)
        val progress = mutableListOf<Pair<Long, Long>>()

        val result = fake().downloadAndVerify(publishedInfo()) { sofar, total ->
            progress.add(sofar to total)
        }

        assertEquals(
            BusinessCode.UPDATE_CORRUPTED,
            (result as AppResult.BusinessRejection).error.code,
        )
        assertEquals("the whole download must have appeared to finish", progress.last().second, progress.last().first)
    }
}
