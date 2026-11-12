package com.freereading.importing

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freereading.R
import com.freereading.ReadingApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ExternalImportState {
    data object Ready : ExternalImportState
    data object Importing : ExternalImportState
    data class Imported(val bookId: String) : ExternalImportState
    data class Failed(val message: String, val canRetry: Boolean) : ExternalImportState
    data object Finished : ExternalImportState
}

/** A retained import copies the temporary URI grant before navigating away from its Activity. */
class ExternalImportViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ReadingApplication
    private val mutableState = MutableStateFlow<ExternalImportState>(ExternalImportState.Ready)
    val state = mutableState.asStateFlow()
    private var source: Uri? = null
    private var request = 0L
    private var importing: Job? = null

    fun accept(intent: Intent) {
        val uri = IncomingBookUri.fromIntent(app, intent)
        if (uri != null && uri == source && state.value !is ExternalImportState.Failed) return
        source = uri
        startImport()
    }

    fun retry() {
        if (state.value is ExternalImportState.Failed && source != null) startImport()
    }

    fun markOpened(bookId: String) {
        val imported = state.value as? ExternalImportState.Imported ?: return
        if (imported.bookId == bookId) mutableState.value = ExternalImportState.Finished
    }

    private fun startImport() {
        val token = ++request
        importing?.cancel()
        val uri = source
        if (uri == null) {
            mutableState.value = ExternalImportState.Failed(app.getString(R.string.external_import_invalid), false)
            return
        }
        mutableState.value = ExternalImportState.Importing
        importing = viewModelScope.launch {
            try {
                // This Activity can be the process's first screen, without opening the shelf.
                app.repository.refresh()
                val book = app.repository.importBook(uri)
                ensureActive()
                if (request == token) mutableState.value = ExternalImportState.Imported(book.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (request == token) mutableState.value = ExternalImportState.Failed(
                    error.message ?: app.getString(R.string.import_failed), true,
                )
            }
        }
    }
}
