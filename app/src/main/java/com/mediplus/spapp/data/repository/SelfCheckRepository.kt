package com.mediplus.spapp.data.repository

import com.mediplus.spapp.core.di.IoDispatcher
import com.mediplus.spapp.core.network.apiCall
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.data.remote.SelfCheckApi
import com.mediplus.spapp.data.remote.VerifyMemberRequest
import com.mediplus.spapp.domain.model.CurrentAppVersion
import com.mediplus.spapp.domain.model.ProbeEndpoint
import com.mediplus.spapp.domain.model.ProbeOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ResponseBody
import retrofit2.Response
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Calls a back-office endpoint for the operator self check and reports only whether it answered. */
interface SelfCheckRepository {
    suspend fun probe(endpoint: ProbeEndpoint): ProbeOutcome
}

/**
 * Transport failures are classified by [apiCall], the same as every other call the app makes. On
 * top of that each probe gets its own [timeout]: the shared client's 15s connect and 30s read
 * timeouts would let a self check against a dead server run for minutes, and cancelling the
 * coroutine cancels the underlying OkHttp call.
 */
class SelfCheckRepositoryImpl internal constructor(
    private val api: SelfCheckApi,
    private val appVersion: CurrentAppVersion,
    private val dispatcher: CoroutineDispatcher,
    private val timeout: Duration,
) : SelfCheckRepository {

    @Inject
    constructor(
        api: SelfCheckApi,
        appVersion: CurrentAppVersion,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ) : this(api, appVersion, dispatcher, PROBE_TIMEOUT)

    override suspend fun probe(endpoint: ProbeEndpoint): ProbeOutcome =
        withTimeoutOrNull(timeout) {
            val result = apiCall(dispatcher, { call(endpoint) }) { response ->
                response.body()?.close()
                response.errorBody()?.close()
                AppResult.Success(response.code())
            }
            when (result) {
                is AppResult.Success -> ProbeOutcome.Responded(result.data)
                AppResult.Timeout -> ProbeOutcome.TimedOut
                else -> ProbeOutcome.Unreachable
            }
        } ?: ProbeOutcome.TimedOut

    private suspend fun call(endpoint: ProbeEndpoint): Response<ResponseBody> = when (endpoint) {
        ProbeEndpoint.LATEST_RELEASE -> api.latestRelease(appVersion.code)
        ProbeEndpoint.SESSION -> api.session()
        ProbeEndpoint.MEMBER_VERIFY -> api.memberVerify(VerifyMemberRequest(PLACEHOLDER_MEMBER_NUMBER))
    }

    internal companion object {
        val PROBE_TIMEOUT = 10.seconds

        /**
         * Placeholder until the back-office troubleshooting mode exists. It matches the server's
         * `^[0-9]{7,32}$` so a request is well-formed, and it is sent without a token, so the
         * expected answer is a 401 before any member lookup happens.
         */
        const val PLACEHOLDER_MEMBER_NUMBER = "0000000"
    }
}
