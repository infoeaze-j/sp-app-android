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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.net.HttpURLConnection
import javax.inject.Inject

/**
 * The network half of the operator self check: is there a network, does it reach the internet,
 * does the back office answer, and do the endpoints the journey uses answer. Emits the whole
 * picture after every change so the screen can show each step running and then its result.
 *
 * A failure only skips what it makes pointless. No network skips everything. No internet does
 * **not** skip the API: the debug back office is on the LAN, and a clinic firewall can block the
 * host Android uses to validate a network while the back office stays reachable. An API that does
 * not answer at all skips the endpoints; one that answers with an error does not, because the
 * server is there to ask.
 */
class RunNetworkSelfCheckUseCase @Inject constructor(
    private val connectivity: ConnectivityInspector,
    private val repository: SelfCheckRepository,
) {
    operator fun invoke(): Flow<Map<SelfCheckItem, CheckStatus>> = flow {
        val progress = Progress(this)
        progress.publish()

        val reading = connectivity.current()
        progress.record(SelfCheckItem.LOCAL_NETWORK, localNetwork(reading))
        if (!reading.connected) return@flow progress.skipTheRest()

        progress.record(
            SelfCheckItem.INTERNET,
            if (reading.isValidated) CheckStatus.Passed() else CheckStatus.Failed(CheckFailure.NOT_VALIDATED),
        )

        val api = progress.probe(SelfCheckItem.API, ProbeEndpoint.LATEST_RELEASE, ::releaseCheckPasses)
        if (api !is ProbeOutcome.Responded) return@flow progress.skipTheRest()

        progress.probe(SelfCheckItem.ENDPOINT_SESSION, ProbeEndpoint.SESSION, ::rejectsMissingToken)
        progress.probe(SelfCheckItem.ENDPOINT_MEMBER_VERIFY, ProbeEndpoint.MEMBER_VERIFY, ::rejectsMissingToken)
    }

    private fun localNetwork(reading: ConnectivityReading): CheckStatus = when {
        reading.connected -> connectedVia(reading.transport)
        reading.airplaneMode -> CheckStatus.Failed(CheckFailure.AIRPLANE_MODE)
        else -> CheckStatus.Failed(CheckFailure.NO_NETWORK)
    }

    /**
     * Wi-Fi is the preferred link, so mobile data and a VPN are connected but warned about: both are
     * often slower and less reliable. A warning is not a failure, so nothing after it is skipped.
     */
    private fun connectedVia(transport: NetworkTransport): CheckStatus = when (transport) {
        NetworkTransport.CELLULAR -> CheckStatus.Warning(CheckWarning.MOBILE_DATA)
        NetworkTransport.VPN -> CheckStatus.Warning(CheckWarning.VPN)
        NetworkTransport.NONE -> CheckStatus.Passed(via = null)
        NetworkTransport.WIFI, NetworkTransport.ETHERNET -> CheckStatus.Passed(via = transport)
    }

    /** The results so far, republished on every change. */
    private inner class Progress(private val collector: FlowCollector<Map<SelfCheckItem, CheckStatus>>) {
        private val statuses: MutableMap<SelfCheckItem, CheckStatus> = NETWORK_CHECKS.associateWithTo(
            LinkedHashMap(),
        ) { if (it == SelfCheckItem.ENDPOINT_LOGIN) CheckStatus.NotYetAvailable else CheckStatus.Pending }

        suspend fun publish() = collector.emit(statuses.toMap())

        suspend fun record(item: SelfCheckItem, status: CheckStatus) {
            statuses[item] = status
            publish()
        }

        suspend fun probe(item: SelfCheckItem, endpoint: ProbeEndpoint, passes: (Int) -> Boolean): ProbeOutcome {
            record(item, CheckStatus.Running)
            val outcome = repository.probe(endpoint)
            record(item, statusOf(outcome, passes))
            return outcome
        }

        suspend fun skipTheRest() {
            statuses.replaceAll { _, status -> if (status == CheckStatus.Pending) CheckStatus.Skipped else status }
            publish()
        }
    }
}

private fun statusOf(outcome: ProbeOutcome, passes: (Int) -> Boolean): CheckStatus = when (outcome) {
    ProbeOutcome.Unreachable -> CheckStatus.Failed(CheckFailure.UNREACHABLE)
    ProbeOutcome.TimedOut -> CheckStatus.Failed(CheckFailure.TIMEOUT)
    is ProbeOutcome.Responded -> when {
        passes(outcome.code) -> CheckStatus.Passed(httpStatus = outcome.code)
        outcome.code >= HttpURLConnection.HTTP_INTERNAL_ERROR ->
            CheckStatus.Failed(CheckFailure.SERVER_ERROR, outcome.code)
        else -> CheckStatus.Failed(CheckFailure.UNEXPECTED_STATUS, outcome.code)
    }
}

/**
 * The contract always answers 200. A 404 is how a back office that has not deployed the endpoint
 * answers (see `UpdateRepository`) — still a server that is there, which is all this row asks.
 */
private fun releaseCheckPasses(code: Int): Boolean =
    code in HttpURLConnection.HTTP_OK until HttpURLConnection.HTTP_MULT_CHOICE ||
        code == HttpURLConnection.HTTP_NOT_FOUND

/**
 * Probed without a token, an authenticated route should refuse. A success here means the back
 * office served a protected route to an anonymous caller — worth a failed row, not a pass.
 */
private fun rejectsMissingToken(code: Int): Boolean = code == HttpURLConnection.HTTP_UNAUTHORIZED
