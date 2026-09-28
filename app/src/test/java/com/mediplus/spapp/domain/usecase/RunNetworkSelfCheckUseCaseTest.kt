package com.mediplus.spapp.domain.usecase

import com.mediplus.spapp.core.diagnostics.NetworkTransport
import com.mediplus.spapp.core.selfcheck.ConnectivityInspector
import com.mediplus.spapp.core.selfcheck.ConnectivityReading
import com.mediplus.spapp.data.repository.SelfCheckRepository
import com.mediplus.spapp.domain.model.CheckFailure
import com.mediplus.spapp.domain.model.CheckStatus
import com.mediplus.spapp.domain.model.CheckWarning
import com.mediplus.spapp.domain.model.NETWORK_CHECKS
import com.mediplus.spapp.domain.model.ProbeEndpoint
import com.mediplus.spapp.domain.model.ProbeOutcome
import com.mediplus.spapp.domain.model.SelfCheckItem
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The network half of the self check: local network, then internet, then the API, then the
 * endpoints — and which later steps a failure makes pointless to run.
 */
class RunNetworkSelfCheckUseCaseTest {

    private var reading = ConnectivityReading(
        connected = true,
        transport = NetworkTransport.WIFI,
        isValidated = true,
        airplaneMode = false,
    )
    private val outcomes = mutableMapOf<ProbeEndpoint, ProbeOutcome>(
        ProbeEndpoint.LATEST_RELEASE to ProbeOutcome.Responded(200),
        ProbeEndpoint.SESSION to ProbeOutcome.Responded(401),
        ProbeEndpoint.MEMBER_VERIFY to ProbeOutcome.Responded(401),
    )
    private val probed = mutableListOf<ProbeEndpoint>()

    private val useCase = RunNetworkSelfCheckUseCase(
        connectivity = object : ConnectivityInspector {
            override fun current() = reading
        },
        repository = object : SelfCheckRepository {
            override suspend fun probe(endpoint: ProbeEndpoint): ProbeOutcome {
                probed += endpoint
                return outcomes.getValue(endpoint)
            }
        },
    )

    private val downstreamOfNetwork = NETWORK_CHECKS - SelfCheckItem.LOCAL_NETWORK - SelfCheckItem.ENDPOINT_LOGIN
    private val endpoints = listOf(SelfCheckItem.ENDPOINT_SESSION, SelfCheckItem.ENDPOINT_MEMBER_VERIFY)

    @Test
    fun `a healthy device passes every network check and probes each endpoint once`() = runTest {
        val result = useCase().last()

        assertEquals(CheckStatus.Passed(via = NetworkTransport.WIFI), result[SelfCheckItem.LOCAL_NETWORK])
        assertEquals(CheckStatus.Passed(), result[SelfCheckItem.INTERNET])
        assertEquals(CheckStatus.Passed(httpStatus = 200), result[SelfCheckItem.API])
        assertEquals(CheckStatus.Passed(httpStatus = 401), result[SelfCheckItem.ENDPOINT_SESSION])
        assertEquals(CheckStatus.Passed(httpStatus = 401), result[SelfCheckItem.ENDPOINT_MEMBER_VERIFY])
        assertEquals(
            listOf(ProbeEndpoint.LATEST_RELEASE, ProbeEndpoint.SESSION, ProbeEndpoint.MEMBER_VERIFY),
            probed,
        )
    }

    @Test
    fun `results come back in display order`() = runTest {
        assertEquals(NETWORK_CHECKS, useCase().last().keys.toList())
    }

    @Test
    fun `no network fails the first check and skips everything after it`() = runTest {
        reading = reading.copy(connected = false, transport = NetworkTransport.NONE, isValidated = false)

        val result = useCase().last()

        assertEquals(CheckStatus.Failed(CheckFailure.NO_NETWORK), result[SelfCheckItem.LOCAL_NETWORK])
        downstreamOfNetwork.forEach { assertEquals(it.name, CheckStatus.Skipped, result[it]) }
        assertTrue(probed.isEmpty())
    }

    @Test
    fun `airplane mode is named as the reason there is no network`() = runTest {
        reading = reading.copy(connected = false, transport = NetworkTransport.NONE, airplaneMode = true)

        val result = useCase().last()

        assertEquals(CheckStatus.Failed(CheckFailure.AIRPLANE_MODE), result[SelfCheckItem.LOCAL_NETWORK])
    }

    @Test
    fun `mobile data is connected but warned about, since Wi-Fi is preferred`() = runTest {
        reading = reading.copy(transport = NetworkTransport.CELLULAR)

        assertEquals(
            CheckStatus.Warning(CheckWarning.MOBILE_DATA),
            useCase().last()[SelfCheckItem.LOCAL_NETWORK],
        )
    }

    @Test
    fun `a VPN is warned about like mobile data`() = runTest {
        reading = reading.copy(transport = NetworkTransport.VPN)

        assertEquals(CheckStatus.Warning(CheckWarning.VPN), useCase().last()[SelfCheckItem.LOCAL_NETWORK])
    }

    @Test
    fun `wired Ethernet passes like Wi-Fi`() = runTest {
        reading = reading.copy(transport = NetworkTransport.ETHERNET)

        assertEquals(
            CheckStatus.Passed(via = NetworkTransport.ETHERNET),
            useCase().last()[SelfCheckItem.LOCAL_NETWORK],
        )
    }

    @Test
    fun `mobile data still runs every later check`() = runTest {
        reading = reading.copy(transport = NetworkTransport.CELLULAR)

        val result = useCase().last()

        assertEquals(CheckStatus.Passed(), result[SelfCheckItem.INTERNET])
        assertEquals(CheckStatus.Passed(httpStatus = 200), result[SelfCheckItem.API])
        assertEquals(
            listOf(ProbeEndpoint.LATEST_RELEASE, ProbeEndpoint.SESSION, ProbeEndpoint.MEMBER_VERIFY),
            probed,
        )
    }

    @Test
    fun `a connection of a kind we do not name still counts as connected`() = runTest {
        reading = reading.copy(transport = NetworkTransport.NONE)

        assertEquals(CheckStatus.Passed(via = null), useCase().last()[SelfCheckItem.LOCAL_NETWORK])
    }

    @Test
    fun `no internet still probes the API, which may be on the local network`() = runTest {
        reading = reading.copy(isValidated = false)

        val result = useCase().last()

        assertEquals(CheckStatus.Failed(CheckFailure.NOT_VALIDATED), result[SelfCheckItem.INTERNET])
        assertEquals(CheckStatus.Passed(httpStatus = 200), result[SelfCheckItem.API])
    }

    @Test
    fun `a 404 from the release check still proves the server is reachable`() = runTest {
        outcomes[ProbeEndpoint.LATEST_RELEASE] = ProbeOutcome.Responded(404)

        assertEquals(CheckStatus.Passed(httpStatus = 404), useCase().last()[SelfCheckItem.API])
    }

    @Test
    fun `a server error fails the API check but the endpoints are still probed`() = runTest {
        outcomes[ProbeEndpoint.LATEST_RELEASE] = ProbeOutcome.Responded(500)

        val result = useCase().last()

        assertEquals(CheckStatus.Failed(CheckFailure.SERVER_ERROR, 500), result[SelfCheckItem.API])
        assertEquals(CheckStatus.Passed(httpStatus = 401), result[SelfCheckItem.ENDPOINT_SESSION])
    }

    @Test
    fun `an unreachable API skips the endpoints`() = runTest {
        outcomes[ProbeEndpoint.LATEST_RELEASE] = ProbeOutcome.Unreachable

        val result = useCase().last()

        assertEquals(CheckStatus.Failed(CheckFailure.UNREACHABLE), result[SelfCheckItem.API])
        endpoints.forEach { assertEquals(it.name, CheckStatus.Skipped, result[it]) }
        assertEquals(listOf(ProbeEndpoint.LATEST_RELEASE), probed)
    }

    @Test
    fun `an API that times out skips the endpoints`() = runTest {
        outcomes[ProbeEndpoint.LATEST_RELEASE] = ProbeOutcome.TimedOut

        val result = useCase().last()

        assertEquals(CheckStatus.Failed(CheckFailure.TIMEOUT), result[SelfCheckItem.API])
        endpoints.forEach { assertEquals(it.name, CheckStatus.Skipped, result[it]) }
    }

    @Test
    fun `an authenticated endpoint that answers without a token fails as unexpected`() = runTest {
        outcomes[ProbeEndpoint.SESSION] = ProbeOutcome.Responded(200)

        assertEquals(
            CheckStatus.Failed(CheckFailure.UNEXPECTED_STATUS, 200),
            useCase().last()[SelfCheckItem.ENDPOINT_SESSION],
        )
    }

    @Test
    fun `endpoint failures are classified like the API's`() = runTest {
        outcomes[ProbeEndpoint.SESSION] = ProbeOutcome.Responded(503)
        outcomes[ProbeEndpoint.MEMBER_VERIFY] = ProbeOutcome.Unreachable

        val result = useCase().last()

        assertEquals(CheckStatus.Failed(CheckFailure.SERVER_ERROR, 503), result[SelfCheckItem.ENDPOINT_SESSION])
        assertEquals(CheckStatus.Failed(CheckFailure.UNREACHABLE), result[SelfCheckItem.ENDPOINT_MEMBER_VERIFY])
    }

    @Test
    fun `sign-in is never probed and reads as not yet available throughout`() = runTest {
        val emissions = useCase().toList()

        emissions.forEach { assertEquals(CheckStatus.NotYetAvailable, it[SelfCheckItem.ENDPOINT_LOGIN]) }
    }

    @Test
    fun `each probe is shown running before its result`() = runTest {
        val apiStates = useCase().toList().map { it.getValue(SelfCheckItem.API) }.distinct()

        assertEquals(
            listOf(CheckStatus.Pending, CheckStatus.Running, CheckStatus.Passed(httpStatus = 200)),
            apiStates,
        )
    }
}
