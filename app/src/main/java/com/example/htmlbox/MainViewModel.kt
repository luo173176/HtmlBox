package com.example.htmlbox

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.htmlbox.data.HtmlFile
import com.example.htmlbox.data.HtmlRepository
import com.example.htmlbox.data.ImportResult
import com.example.htmlbox.data.RenameResult
import com.example.htmlbox.data.SaveResult
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
 * 新建文件时预填的模板：一个能直接跑、能点、有样式的最小演示页，
 * 让第一次打开编辑器的用户马上能看到「写代码 → 运行」的完整闭环。
 */
private val NEW_FILE_TEMPLATE = """
<!DOCTYPE html>
<html lang="zh">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>我的页面</title>
  <style>
    body {
      margin: 0;
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      background: #f4f2ff;
      color: #2b2554;
      font-family: system-ui, sans-serif;
    }
    .card {
      padding: 32px 40px;
      border-radius: 16px;
      background: #fff;
      box-shadow: 0 8px 24px rgba(59, 42, 156, .12);
      text-align: center;
    }
    h1 { margin: 0 0 8px; font-size: 22px; }
    p  { margin: 0; color: #6b6792; }
    button {
      margin-top: 16px;
      padding: 10px 22px;
      border: none;
      border-radius: 999px;
      background: #3b2a9c;
      color: #fff;
      font-size: 15px;
    }
  </style>
</head>
<body>
  <div class="card">
    <h1>Hello HTML 盒子</h1>
    <p>改改这段代码，点右上角「运行预览」试试</p>
    <button onclick="alert('按钮可以点！')">点我</button>
  </div>
</body>
</html>
""".trimIndent()

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

    // ------------------------------------------------------------------
    // 应用内编辑器
    // ------------------------------------------------------------------

    /**
     * 编辑器草稿。放在 ViewModel（Activity 作用域）而不是 rememberSaveable：
     * 「运行预览」跳转运行页会销毁编辑器的组合，返回时 remember 的内容会丢；
     * 放这里跳转预览再返回、旋转屏幕都不丢，也不用把整段代码塞进
     * Bundle（编辑大文件时可能触发 TransactionTooLargeException）。
     */
    var draftCode by mutableStateOf<String?>(null)
        private set

    /** 草稿属于哪个编辑器会话（导航栈条目 id），用于判断是否要重新读盘 */
    var draftSession by mutableStateOf<String?>(null)
        private set

    /** 草稿对应的文件名；null 表示新建 */
    var draftOwner by mutableStateOf<String?>(null)
        private set

    /** 打开草稿时的内容，用来判断有没有未保存的修改 */
    var draftOriginal by mutableStateOf<String?>(null)
        private set

    val draftDirty: Boolean get() = draftCode != null && draftCode != draftOriginal

    /**
     * 打开编辑器。
     *
     * 会话键取导航栈条目 id：同一个条目重复组合（运行预览返回、旋转）
     * 一律保留草稿——尤其是「新建后保存再预览」的场景，此时草稿的
     * owner 已从 null 变成真实文件名，只有会话键能识别出还是同一份草稿。
     * 换了条目（重新进入编辑器）才按 [fileName] 重新加载。
     */
    fun openDraft(sessionKey: String, fileName: String?) {
        if (draftSession == sessionKey && draftCode != null) return
        draftSession = sessionKey
        draftOwner = fileName
        if (fileName == null) {
            draftCode = NEW_FILE_TEMPLATE
            draftOriginal = NEW_FILE_TEMPLATE
            return
        }
        draftCode = null
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) { repository.read(fileName) }
            if (content == null) notify(R.string.editor_load_failed)
            // 加载期间用户可能已切去别的会话，旧结果不覆盖新草稿
            if (draftSession == sessionKey) {
                draftCode = content.orEmpty()
                draftOriginal = content.orEmpty()
            }
        }
    }

    /** 编辑内容变化（输入框每击键一次调用一次） */
    fun updateDraft(content: String) {
        draftCode = content
    }

    /** 放弃修改：清空草稿，下次进入按磁盘内容重新加载 */
    fun clearDraft() {
        draftCode = null
        draftSession = null
        draftOwner = null
        draftOriginal = null
    }

    /**
     * 编辑器保存。成功后草稿切换为「已保存」状态，继续编辑即覆盖。
     *
     * @param fileName 已有文件名；null 表示新建，此时用 [newBaseName] 命名
     */
    fun saveHtml(fileName: String?, newBaseName: String, content: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (fileName != null) {
                    if (repository.overwrite(fileName, content)) {
                        SaveResult.Success(fileName, renamed = false)
                    } else {
                        SaveResult.Failed("写入失败")
                    }
                } else {
                    repository.create(newBaseName, content)
                }
            }
            refresh()
            when (result) {
                is SaveResult.Success -> {
                    notify(
                        if (result.renamed) R.string.editor_saved_renamed else R.string.editor_saved,
                        result.name
                    )
                    draftOwner = result.name
                    draftOriginal = content
                }

                SaveResult.EmptyName -> notify(R.string.msg_rename_empty)
                SaveResult.InvalidName -> notify(R.string.msg_rename_invalid)
                is SaveResult.Failed -> notify(R.string.msg_import_failed, result.reason)
            }
        }
    }

    /** 把编辑中的代码写进预览草稿，写完回调（回调在主线程） */
    fun writePreview(content: String, onReady: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.writePreview(content) }
            onReady()
        }
    }

    /** 把资源字符串发到 Snackbar 队列 */
    private fun notify(@StringRes resId: Int, vararg args: Any) {
        val text = getApplication<Application>().getString(resId, *args)
        viewModelScope.launch { _messages.send(text) }
    }
}
