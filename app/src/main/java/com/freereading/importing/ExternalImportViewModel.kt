package com.freereading.importing

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.freereading.R
import com.freereading.ReadingApplication
import com.freereading.data.BookImportProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 外部导入页面的状态机；完成导航后转为 Finished，防止界面恢复时重复打开阅读页。 */
sealed interface ExternalImportState {
    /** 尚未接收本次外部请求。 */
    data object Ready : ExternalImportState
    /** 正在导入；请求编号隔离切换文件后迟到的进度回调。 */
    data class Importing(
        val progress: BookImportProgress = BookImportProgress(),
        internal val requestId: Long = 0,
    ) : ExternalImportState
    /** 书库已提交成功，等待界面打开对应书籍。 */
    data class Imported(val bookId: String) : ExternalImportState
    /** 导入失败；保留已解析的来源 URI 时允许重试。 */
    data class Failed(val message: String, val canRetry: Boolean) : ExternalImportState
    /** 已消费成功结果，当前导入页面可以关闭。 */
    data object Finished : ExternalImportState
}

/** 配置变更时保留导入任务；成功导航前先将临时授权 URI 中的 EPUB 复制到本地。 */
class ExternalImportViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ReadingApplication
    private val mutableState = MutableStateFlow<ExternalImportState>(ExternalImportState.Ready)
    val state = mutableState.asStateFlow()
    private var source: Uri? = null
    private var request = 0L
    private var importing: Job? = null

    /** 接收新来源；同一 URI 的重复 Intent 不重启任务，失败后的同源请求允许重新导入。 */
    fun accept(intent: Intent) {
        val uri = IncomingBookUri.fromIntent(app, intent)
        if (uri != null && uri == source && state.value !is ExternalImportState.Failed) return
        source = uri
        startImport()
    }

    /** 仅重试仍保留已解析来源 URI 的失败请求，实际读取权限和内容在导入时重新校验。 */
    fun retry() {
        if (state.value is ExternalImportState.Failed && source != null) startImport()
    }

    /** 只消费当前书籍的成功结果，避免旧页面确认覆盖较新的导入状态。 */
    fun markOpened(bookId: String) {
        val imported = state.value as? ExternalImportState.Imported ?: return
        if (imported.bookId == bookId) mutableState.value = ExternalImportState.Finished
    }

    /** 更新请求代次并取消旧任务；只有当前代次可以发布进度、成功或失败结果。 */
    private fun startImport() {
        val token = ++request
        importing?.cancel()
        val uri = source
        if (uri == null) {
            mutableState.value = ExternalImportState.Failed(app.getString(R.string.external_import_invalid), false)
            return
        }
        mutableState.value = ExternalImportState.Importing(requestId = token)
        importing = viewModelScope.launch {
            try {
                // 外部导入可能是进程的首个页面，因此需要自行加载书架，不能依赖首页已初始化。
                app.repository.refresh()
                val book = app.repository.importBook(uri) { progress ->
                    mutableState.update { current ->
                        if (current is ExternalImportState.Importing && current.requestId == token) {
                            current.copy(progress = progress)
                        } else current
                    }
                }
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
