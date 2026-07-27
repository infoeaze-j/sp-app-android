package com.mediplus.spapp.core.network

import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.TransientKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Executes a Retrofit [call] off the main thread and translates the outcome into an [AppResult]
 * via [map]. Transport failures are classified so no path can silently succeed (FR-027):
 *  - a socket timeout → [AppResult.Timeout] (uncertain; never success),
 *  - any other IO failure → transient no-connectivity,
 *  - anything unexpected → transient unknown.
 *
 * HTTP status codes and response bodies are interpreted by the caller's [map], which decides
 * success vs. business rejection for that specific endpoint.
 */
suspend fun <T, R> apiCall(
    dispatcher: CoroutineDispatcher,
    call: suspend () -> Response<T>,
    map: (Response<T>) -> AppResult<R>,
): AppResult<R> = withContext(dispatcher) {
    try {
        map(call())
    } catch (_: SocketTimeoutException) {
        AppResult.Timeout
    } catch (e: IOException) {
        AppResult.TransientFailure(AppError.Transient(TransientKind.NO_CONNECTIVITY, e))
    } catch (e: Exception) {
        AppResult.TransientFailure(AppError.Transient(TransientKind.UNKNOWN, e))
    }
}
