package com.example.htmlbox

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.htmlbox.data.HtmlFile
import com.example.htmlbox.data.HtmlRepository
import com.example.htmlbox.data.ImportResult
import com.example.htmlbox.data.RenameResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页 UI 状态。
 */
data class HomeUiState(
    /** 首次加载（列表为空时展示转圈） */
    val isLoading: Boolean = true,
    /** 下拉刷新中（展示下拉进度） */
    val isRefreshing: Boolean = false,
    /** 已导入的 HTML 列表，按修改时间倒序 */
    val files: List<HtmlFile> = emptyList(),
)

/**
 * 全局唯一的 ViewModel，作用域挂在 Activity 上。
 *
 * 之所以挂在 Activity 上（而不是每个页面的 NavBackStackEntry）：
 * 运行页返回首页时不需要重新加载数据，列表状态天然保留。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = HtmlRepository(application)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** 一次性提示（Snackbar）。用 Channel 保证只消费一次 */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    init {
        refresh()
    }

    /**
     * 重新扫描目录。
     * @param pullToRefresh true 表示由下拉手势触发，会展示下拉指示器
     */
    fun refresh(pullToRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isRefreshing = pullToRefresh,
                    isLoading = !pullToRefresh && it.files.isEmpty()
                )
            }
            val files = withContext(Dispatchers.IO) { repository.list() }
            _uiState.update { it.copy(files = files, isRefreshing = false, isLoading = false) }
        }
    }

    /** 导入一个 SAF 选中的 HTML 文件 */
    fun importHtml(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.importHtml(uri) }
            refresh()
            when (result) {
                is ImportResult.Success ->
                    if (result.renamed) {
                        notify(R.string.msg_import_renamed, result.name)
                    } else {
                        notify(R.string.msg_import_success, result.name)
                    }

                ImportResult.NotHtmlFile -> notify(R.string.msg_import_not_html)
                ImportResult.EmptyFile -> notify(R.string.msg_import_empty)
                is ImportResult.Failed -> notify(R.string.msg_import_failed, result.reason)
            }
        }
    }

    /** 删除文件 */
    fun deleteHtml(file: HtmlFile) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { repository.delete(file.name) }
            refresh()
            if (ok) notify(R.string.msg_deleted, file.name) else notify(R.string.msg_delete_failed)
        }
    }

    /** 重命名文件（只接收新名字，后缀由仓库保留） */
    fun renameHtml(file: HtmlFile, newBaseName: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.rename(file.name, newBaseName) }
            refresh()
            when (result) {
                is RenameResult.Success ->
                    if (result.renamedForConflict) {
                        notify(R.string.msg_rename_conflict, result.name)
                    } else {
                        notify(R.string.msg_rename_success, result.name)
                    }

                RenameResult.EmptyName -> notify(R.string.msg_rename_empty)
                RenameResult.InvalidName -> notify(R.string.msg_rename_invalid)
                RenameResult.NotFound -> notify(R.string.msg_file_missing)
                is RenameResult.Failed -> notify(R.string.msg_import_failed, result.reason)
            }
        }
    }

    /** 把资源字符串发到 Snackbar 队列 */
    private fun notify(@StringRes resId: Int, vararg args: Any) {
        val text = getApplication<Application>().getString(resId, *args)
        viewModelScope.launch { _messages.send(text) }
    }
}
