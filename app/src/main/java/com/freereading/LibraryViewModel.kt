package com.freereading

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freereading.audio.PlaybackHub
import com.freereading.data.BookImportProgress
import com.freereading.data.BookRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ReadingApplication
    private val repository = app.repository
    val books = repository.books
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _importProgress = MutableStateFlow<BookImportProgress?>(null)
    val importProgress = _importProgress.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            try {
                repository.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _error.value = app.getString(R.string.library_load_failed)
            }
        }
    }

    fun importBook(uri: Uri) {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        _importProgress.value = BookImportProgress()
        viewModelScope.launch {
            val message = try {
                val book = repository.importBook(uri) { _importProgress.value = it }
                app.getString(R.string.import_success, book.title)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _error.value = error.message ?: app.getString(R.string.import_failed)
                null
            } finally {
                _importProgress.value = null
                _busy.value = false
            }
            // A previous snackbar may still be visible; it must not keep a completed import busy.
            if (message != null) _messages.emit(message)
        }
    }

    fun removeBook(book: BookRecord) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                if (PlaybackHub.state.value.bookId == book.id) {
                    PlaybackHub.stop(app)
                    val stopped = withTimeoutOrNull(5_000) {
                        PlaybackHub.state.first { it.bookId != book.id }
                    }
                    check(stopped != null) { "听书仍在停止中，请稍后再移除。" }
                }
                repository.removeBook(book.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _error.value = error.message ?: app.getString(R.string.operation_failed)
            } finally {
                _busy.value = false
            }
        }
    }

    fun dismissError() { _error.value = null }
}
