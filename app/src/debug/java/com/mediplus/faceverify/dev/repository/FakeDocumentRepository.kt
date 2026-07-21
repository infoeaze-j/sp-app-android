package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.repository.DocumentRepository
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.dev.DocumentScenario
import com.mediplus.faceverify.dev.FakeData
import com.mediplus.faceverify.domain.model.DocumentValidation
import com.mediplus.faceverify.domain.model.ReadDocument
import kotlinx.coroutines.delay
import javax.inject.Inject

/** Fake document validation: returns the persisted [DocumentScenario]. */
class FakeDocumentRepository @Inject constructor(
    private val store: DevSettingsStore,
) : DocumentRepository {

    override suspend fun validate(read: ReadDocument): AppResult<DocumentValidation> {
        val settings = store.current()
        delay(settings.latencyMillis)
        return when (settings.document) {
            DocumentScenario.SUCCESS -> AppResult.Success(FakeData.validationValid)
            DocumentScenario.INVALID -> AppResult.Success(FakeData.validationInvalid)
            DocumentScenario.PATIENT_NOT_FOUND ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.PATIENT_NOT_FOUND))
            DocumentScenario.SERVER_ERROR ->
                AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        }
    }
}
