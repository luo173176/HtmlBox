package com.example.htmlbox.ui.webview

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.example.htmlbox.R
import com.example.htmlbox.data.HtmlRepository
import java.io.File
import java.util.Locale

private const val TAG = "HtmlBox"

/** 桌面模式使用的 User-Agent（桌面 Chrome） */
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * WebView 默认 UA 里带 `wv` 标记（形如 `...; wv) AppleWebKit/...`），
 * 表示「这是应用内嵌 WebView，不是浏览器」。不少站点/CDN 会据此拒绝服务，
 * 表现为同一个 HTML 在浏览器里正常、在盒子里白屏或功能缺失，所以把它去掉。
 */
private fun browserLikeUserAgent(webView: WebView): String =
    webView.settings.userAgentString.orEmpty()
        .replace("; wv)", ")")
        .replace(" wv)", ")")
        .replace("; wv;", "; ")

/**
 * 需要由 Compose 弹窗接管的 JS 对话框。
 * WebView 默认会自己弹系统对话框，这里改为交给 Compose，
 * 好处是可以显示中文按钮，并且不会因为 Activity 重建而泄漏。
 */
private sealed interface JsDialog {
    val result: JsResult

    data class Alert(val message: String, override val result: JsResult) : JsDialog
    data class Confirm(val message: String, override val result: JsResult) : JsDialog
    data class Prompt(
        val message: String,
        val defaultValue: String,
        override val result: JsPromptResult,
    ) : JsDialog
}

/**
 * 用一个普通对象持有 WebView 引用，避免把 View 放进 Compose State 造成多余重组。
 * 这里同时存放那些「只被 WebView 回调使用、不需要触发重组」的一次性引用。
 */
private class WebViewHolder {
    var webView: WebView? = null

    /** 手机浏览器的默认 UA，桌面模式关闭时用它还原 */
    var browserUserAgent: String? = null

    /** <input type="file"> 等待回填的回调，只能使用一次 */
    var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    /** HTML5 全屏（视频/画布 requestFullscreen）时挂上来的 View 与回调 */
    var customViewCallback: WebChromeClient.CustomViewCallback? = null
}

/**
 * 运行页：用 WebView 离线运行 filesDir/htmls/ 下的单个 HTML 文件。
 *
 * 关键点：不使用 file:// 加载，而是把内部目录通过 WebViewAssetLoader 映射到
 * `https://appassets.androidplatform.net/htmls/`。
 * 好处：
 * 1. 页面处于 https 安全上下文，localStorage / IndexedDB / Service Worker 等 API 才可用；
 * 2. 不暴露 file:// 协议，避免本地文件被 HTML 读取；
 * 3. 目录内的相对路径资源（若存在）也能被正确解析。
 *
 * 「浏览器里正常、盒子里不正常」通常不是 HTML 的问题，而是 WebView 与浏览器的差异，
 * 本页针对下列差异逐项做了处理（详见 README「运行异常排查」）：
 * - 渲染进程被系统回收 / 脚本 OOM：不接 onRenderProcessGone 会让整个 App 一起闪退；
 * - 单页应用 history.pushState 换页：不接 doUpdateVisitedHistory，返回键会直接跳出页面；
 * - 第三方 Cookie 默认被禁：内嵌 iframe（地图、播放器、登录态）拿不到 Cookie；
 * - <input type="file">：不实现 onShowFileChooser，点击按钮毫无反应；
 * - 视频/画布全屏：不实现 onShowCustomView，点全屏毫无反应；
 * - UA 带 wv 标记：部分站点识别为内嵌 WebView 后拒绝服务；
 * - 附件下载：不接 setDownloadListener，点下载链接毫无反应。
 *
 * 无法弥补的一条：window.print() 依赖的 WebChromeClient.onPrintRequest 不是公开 API，
 * 页面里的打印按钮仍无响应，请用菜单里的「打印」走系统打印（可另存为 PDF）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION") // databaseEnabled 自 API 33 起废弃，这里按需求保留以兼容老页面
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewScreen(
    fileName: String,
    onBackToHome: () -> Unit,
    modifier: Modifier = Modifier,
    /** true 表示这是编辑器的「运行预览」：标题显示「预览」，返回键回到编辑器 */
    preview: Boolean = false,
) {
    val context = LocalContext.current
    val holder = remember { WebViewHolder() }
    val appName = stringResource(R.string.app_name)
    val downloadHint = stringResource(R.string.webview_download_handoff)

    val htmlsDir = remember { File(context.filesDir, HtmlRepository.DIR_NAME) }
    val startUrl = remember(fileName) { HtmlRepository.buildAssetUrl(fileName) }

    // WebViewAssetLoader：把 /htmls/ 路径映射到内部存储目录
    val assetLoader = remember {
        WebViewAssetLoader.Builder()
            .addPathHandler(
                "/${HtmlRepository.DIR_NAME}/",
                WebViewAssetLoader.InternalStoragePathHandler(context.applicationContext, htmlsDir)
            )
            .build()
    }

    var isLoading by remember { mutableStateOf(true) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var desktopMode by rememberSaveable { mutableStateOf(false) }
    var zoomEnabled by rememberSaveable { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var jsDialog by remember { mutableStateOf<JsDialog?>(null) }
    var fullscreenView by remember { mutableStateOf<View?>(null) }

    /**
     * 渲染进程挂掉后原来的 WebView 永久失效，必须换一个实例。
     * 把它做成 key 套在 AndroidView 外面，自增即可强制重建整棵 WebView 子树。
     */
    var webViewInstance by remember { mutableIntStateOf(0) }

    /**
     * 页面文件选择器。用 OpenDocument（SAF）而不是硬编码路径：
     * 用户能自己挑文件，盒子里不需要任何存储权限。
     */
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val callback = holder.fileChooserCallback
        holder.fileChooserCallback = null
        if (callback != null) {
            if (uris.isNullOrEmpty()) {
                // 必须回填 null，否则这个 WebView 以后再也弹不出文件选择框
                callback.onReceiveValue(null)
            } else {
                uris.forEach { uri ->
                    // 转成持久化授权：Activity 重建后页面仍可读同一个文件
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }
                callback.onReceiveValue(uris.toTypedArray())
            }
        }
    }

    /** 退出 HTML5 全屏：通知 WebView 侧收起，并移除覆盖层 */
    val exitFullscreen: () -> Unit = {
        val view = fullscreenView
        val callback = holder.customViewCallback
        fullscreenView = null
        holder.customViewCallback = null
        callback?.onCustomViewHidden()
        if (view != null) (view.parent as? ViewGroup)?.removeView(view)
        holder.webView?.visibility = View.VISIBLE
    }

    // ---------------- 返回键 ----------------
    // 全屏播放时返回键先退出全屏；
    // canGoBack 时优先回退网页历史，否则交给系统默认行为（返回首页）
    BackHandler(enabled = fullscreenView != null, onBack = exitFullscreen)
    BackHandler(enabled = canGoBack && fullscreenView == null) {
        holder.webView?.goBack()
    }

    // ---------------- 页面销毁：彻底释放 WebView ----------------
    // onDispose 里读到的必须是最新的 lambda，所以用 rememberUpdatedState 包一层
    val latestDialog by rememberUpdatedState(jsDialog)
    val latestExitFullscreen by rememberUpdatedState(exitFullscreen)
    DisposableEffect(Unit) {
        onDispose {
            latestExitFullscreen()
            latestDialog?.let { runCatching { it.result.cancel() } }
            // 文件选择框还挂着时必须回填，否则页面侧永远等不到结果
            holder.fileChooserCallback?.onReceiveValue(null)
            holder.fileChooserCallback = null
            holder.webView?.let { webView ->
                // Cookie / localStorage 落盘，避免刚写入的数据随进程一起丢
                runCatching { CookieManager.getInstance().flush() }
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.stopLoading()
                webView.removeAllViews()
                webView.destroy()
                Log.d(TAG, "WebView destroyed")
            }
            holder.webView = null
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (preview) {
                            stringResource(R.string.editor_preview_title)
                        } else {
                            fileName.ifEmpty { appName }
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackToHome) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { holder.webView?.reload() }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.action_refresh)
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.action_more)
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_refresh)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    errorMessage = null
                                    holder.webView?.reload()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_print)) },
                                onClick = {
                                    menuExpanded = false
                                    holder.webView?.printPage(context, fileName)
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (desktopMode) R.string.action_desktop_mode_off
                                            else R.string.action_desktop_mode
                                        )
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    val next = !desktopMode
                                    desktopMode = next
                                    holder.webView?.applyDesktopMode(next, holder.browserUserAgent)
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (zoomEnabled) R.string.action_zoom_off
                                            else R.string.action_zoom
                                        )
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    val next = !zoomEnabled
                                    zoomEnabled = next
                                    holder.webView?.applyZoom(next)
                                }
                            )
                            // 预览模式下返回键就是回编辑器，不需要「返回首页」
                            if (!preview) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_back_home)) },
                                    onClick = {
                                        menuExpanded = false
                                        onBackToHome()
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // 让 HTML 里的输入框不会被软键盘挡住
                .imePadding()
        ) {
            if (isLoading) {
                // 一条细圆角进度条：默认的 M3 进度条会带"停止点"小圆点，这里关掉
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
            }

            Box(modifier = Modifier.weight(1f)) {

                key(webViewInstance) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                holder.webView = this
                                // 记住「看起来像浏览器」的 UA，供桌面模式关闭时还原
                                val mobileUa = browserLikeUserAgent(this)
                                holder.browserUserAgent = mobileUa

                                // ---------- WebView 设置 ----------
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true          // localStorage / sessionStorage
                                    databaseEnabled = true            // 已废弃，仅为兼容老页面保留
                                    allowFileAccess = true            // 兼容 HTML 内部引用 file:// 的老写法
                                    allowContentAccess = true
                                    mediaPlaybackRequiresUserGesture = false
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                    javaScriptCanOpenWindowsAutomatically = true
                                    setSupportMultipleWindows(false)  // window.open 复用当前 WebView
                                    // 与手机浏览器一致：按 <meta name="viewport"> 排版，
                                    // 没有声明 viewport 的老页面按 980px 桌面宽度整体缩放显示
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    displayZoomControls = false
                                    setSupportZoom(zoomEnabled)
                                    builtInZoomControls = zoomEnabled
                                    textZoom = 100
                                    // 页面没写 charset 时的兜底；本项目导出的文件都是 UTF-8
                                    defaultTextEncodingName = "UTF-8"
                                    userAgentString = mobileUa
                                }

                                // 允许第三方 Cookie：内嵌地图/播放器/登录态的 iframe 才能正常工作
                                runCatching {
                                    val cookies = CookieManager.getInstance()
                                    cookies.setAcceptCookie(true)
                                    cookies.setAcceptThirdPartyCookies(this, true)
                                }

                                // 附件下载 WebView 自己处理不了，交给系统浏览器
                                setDownloadListener { url, _, _, _, _ ->
                                    Log.d(TAG, "转交下载: $url")
                                    Toast.makeText(context, downloadHint, Toast.LENGTH_SHORT).show()
                                    openExternally(context, Uri.parse(url))
                                }

                                overScrollMode = View.OVER_SCROLL_NEVER

                                // ---------- WebViewClient ----------
                                webViewClient = object : WebViewClient() {

                                    /** 拦截 appassets.androidplatform.net 的请求，交给 AssetLoader 读本地文件 */
                                    override fun shouldInterceptRequest(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): WebResourceResponse? {
                                        return if (request.url.host == WebViewAssetLoader.DEFAULT_DOMAIN) {
                                            assetLoader.shouldInterceptRequest(request.url)
                                        } else {
                                            null // 交给 WebView 自己处理（例如 CDN）
                                        }
                                    }

                                    /** 非 http(s) 协议（mailto / tel / intent 等）交给系统处理 */
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean {
                                        val scheme = request.url.scheme?.lowercase(Locale.ROOT)
                                        return when (scheme) {
                                            "http", "https", "file", "content",
                                            "data", "blob", "about", "javascript" -> false

                                            else -> {
                                                openExternally(context, request.url)
                                                true
                                            }
                                        }
                                    }

                                    override fun onPageStarted(
                                        view: WebView,
                                        url: String?,
                                        favicon: Bitmap?,
                                    ) {
                                        isLoading = true
                                        progress = 0
                                        errorMessage = null
                                        canGoBack = view.canGoBack()
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        isLoading = false
                                        canGoBack = view.canGoBack()
                                        // 把内存里的 Cookie 写回磁盘，页面重开后才还在登录态
                                        runCatching { CookieManager.getInstance().flush() }
                                    }

                                    /**
                                     * 历史记录变化。单页应用用 history.pushState / location.hash
                                     * 换页时不会触发 onPageStarted，必须在这里刷新 canGoBack，
                                     * 否则返回键会跳过站内历史直接退回首页。
                                     */
                                    override fun doUpdateVisitedHistory(
                                        view: WebView,
                                        url: String?,
                                        isReload: Boolean,
                                    ) {
                                        canGoBack = view.canGoBack()
                                    }

                                    /**
                                     * 渲染进程消失（脚本内存超限 / 系统回收 / 崩溃）。
                                     * 默认行为是整个 App 一起闪退，浏览器只是刷新该标签页；
                                     * 这里主动销毁失效的 WebView 并提示重试。
                                     */
                                    override fun onRenderProcessGone(
                                        view: WebView,
                                        detail: RenderProcessGoneDetail,
                                    ): Boolean {
                                        Log.w(
                                            TAG,
                                            "渲染进程结束 didCrash=${detail.didCrash()}",
                                        )
                                        // 无论如何都要把出事的 WebView 摘掉销毁，否则它会一直占着视图树
                                        if (view === holder.webView) {
                                            holder.webView = null
                                            isLoading = false
                                            errorMessage =
                                                context.getString(R.string.webview_error_render)
                                        }
                                        runCatching {
                                            (view.parent as? ViewGroup)?.removeView(view)
                                            view.destroy()
                                        }
                                        return true
                                    }

                                    override fun onReceivedError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        error: WebResourceError,
                                    ) {
                                        if (request.isForMainFrame) {
                                            // 只关心主文档的错误，子资源（图片/CDN）失败不打断运行
                                            isLoading = false
                                            errorMessage = error.description?.toString()
                                                ?: context.getString(R.string.webview_error_unknown)
                                        } else {
                                            Log.w(
                                                TAG,
                                                "子资源加载失败 ${error.errorCode}: ${request.url}",
                                            )
                                        }
                                    }

                                    override fun onReceivedHttpError(
                                        view: WebView,
                                        request: WebResourceRequest,
                                        errorResponse: WebResourceResponse,
                                    ) {
                                        if (request.isForMainFrame && errorResponse.statusCode == 404) {
                                            isLoading = false
                                            errorMessage = context.getString(R.string.msg_file_missing)
                                        } else if (!request.isForMainFrame) {
                                            Log.w(
                                                TAG,
                                                "子资源 ${errorResponse.statusCode}: ${request.url}",
                                            )
                                        }
                                    }
                                }

                                // ---------- WebChromeClient ----------
                                webChromeClient = object : WebChromeClient() {

                                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                                        progress = newProgress
                                        if (newProgress >= 100) isLoading = false
                                    }

                                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                                        Log.d(
                                            TAG,
                                            "[JS] ${consoleMessage.message()} " +
                                                "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                                        )
                                        return true
                                    }

                                    /** <input type="file">：弹出系统文件选择器 */
                                    override fun onShowFileChooser(
                                        view: WebView,
                                        filePathCallback: ValueCallback<Array<Uri>>,
                                        fileChooserParams: FileChooserParams,
                                    ): Boolean {
                                        // 上一次没回填先补一个 null，避免页面永远挂着
                                        holder.fileChooserCallback?.onReceiveValue(null)
                                        holder.fileChooserCallback = filePathCallback
                                        // acceptTypes 里常见的是 .csv / .pdf 这类后缀而非合法 MIME，
                                        // 直接丢给 SAF 会让部分文件管理器打不开，所以统一放开类型
                                        return runCatching {
                                            fileChooserLauncher.launch(arrayOf("*/*"))
                                            true
                                        }.getOrDefault(false)
                                    }

                                    /** 视频、canvas 的 HTML5 全屏 */
                                    override fun onShowCustomView(
                                        view: View,
                                        callback: CustomViewCallback,
                                    ) {
                                        if (holder.customViewCallback != null) {
                                            callback.onCustomViewHidden()
                                            return
                                        }
                                        holder.customViewCallback = callback
                                        holder.webView?.visibility = View.INVISIBLE
                                        fullscreenView = view
                                    }

                                    override fun onHideCustomView() {
                                        exitFullscreen()
                                    }

                                    // 交给 Compose 弹窗，显示中文按钮
                                    override fun onJsAlert(
                                        view: WebView,
                                        url: String,
                                        message: String,
                                        result: JsResult,
                                    ): Boolean {
                                        jsDialog = JsDialog.Alert(message, result)
                                        return true
                                    }

                                    override fun onJsConfirm(
                                        view: WebView,
                                        url: String,
                                        message: String,
                                        result: JsResult,
                                    ): Boolean {
                                        jsDialog = JsDialog.Confirm(message, result)
                                        return true
                                    }

                                    override fun onJsPrompt(
                                        view: WebView,
                                        url: String,
                                        message: String,
                                        defaultValue: String?,
                                        result: JsPromptResult,
                                    ): Boolean {
                                        jsDialog = JsDialog.Prompt(message, defaultValue.orEmpty(), result)
                                        return true
                                    }
                                }

                                loadUrl(startUrl)
                            }
                        }
                    )
                }

                // HTML5 全屏内容覆盖在页面之上
                fullscreenView?.let { view ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = {
                                view.apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                }
                            }
                        )
                    }
                }

                // 加载失败覆盖层
                errorMessage?.let { message ->
                    ErrorOverlay(
                        message = message,
                        onRetry = {
                            errorMessage = null
                            val alive = holder.webView
                            if (alive != null) alive.reload() else webViewInstance++
                        },
                        onBackToHome = onBackToHome
                    )
                }
            }
        }
    }

    // ---------------- JS 对话框 ----------------
    jsDialog?.let { dialog ->
        when (dialog) {
            is JsDialog.Alert -> AlertDialog(
                onDismissRequest = {
                    dialog.result.confirm()
                    jsDialog = null
                },
                title = { Text(stringResource(R.string.dialog_js_alert_title)) },
                text = { Text(dialog.message) },
                confirmButton = {
                    TextButton(onClick = {
                        dialog.result.confirm()
                        jsDialog = null
                    }) {
                        Text(stringResource(R.string.action_ok))
                    }
                }
            )

            is JsDialog.Confirm -> AlertDialog(
                onDismissRequest = {
                    dialog.result.cancel()
                    jsDialog = null
                },
                title = { Text(stringResource(R.string.dialog_js_confirm_title)) },
                text = { Text(dialog.message) },
                confirmButton = {
                    TextButton(onClick = {
                        dialog.result.confirm()
                        jsDialog = null
                    }) {
                        Text(stringResource(R.string.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        dialog.result.cancel()
                        jsDialog = null
                    }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )

            is JsDialog.Prompt -> {
                var input by remember(dialog) { mutableStateOf(dialog.defaultValue) }
                AlertDialog(
                    onDismissRequest = {
                        dialog.result.cancel()
                        jsDialog = null
                    },
                    title = { Text(stringResource(R.string.dialog_js_prompt_title)) },
                    text = {
                        Column {
                            Text(dialog.message)
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            dialog.result.confirm(input)
                            jsDialog = null
                        }) {
                            Text(stringResource(R.string.action_ok))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            dialog.result.cancel()
                            jsDialog = null
                        }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                )
            }
        }
    }
}

/** 加载失败提示 + 重试 */
@Composable
private fun ErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    onBackToHome: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.webview_error_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry))
                }
                Spacer(modifier = Modifier.height(4.dp))
                TextButton(onClick = onBackToHome) {
                    Text(stringResource(R.string.action_back_home))
                }
            }
        }
    }
}

/**
 * 调用系统打印（可另存为 PDF）。
 * 页面里的 window.print() 走不通（onPrintRequest 非公开 API），这是唯一的出口。
 */
private fun WebView.printPage(context: Context, fileName: String) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
    val jobName = fileName.trimStart('.').substringBeforeLast('.', "HTML")
    runCatching {
        printManager.print(
            jobName,
            createPrintDocumentAdapter(jobName),
            PrintAttributes.Builder().build()
        )
    }.onFailure { Log.w(TAG, "打印失败: ${it.message}") }
}

/** 桌面模式：切换 UA 并重新加载（UA 变更必须重新请求才生效） */
private fun WebView.applyDesktopMode(enabled: Boolean, mobileUserAgent: String?) {
    settings.userAgentString = if (enabled) DESKTOP_USER_AGENT else mobileUserAgent
    reload()
}

/** 缩放开关：即时生效，不需要重新加载页面 */
private fun WebView.applyZoom(enabled: Boolean) {
    settings.setSupportZoom(enabled)
    settings.builtInZoomControls = enabled
    settings.displayZoomControls = false
}

/** 用系统浏览器 / 对应 App 打开非 http(s) 链接 */
private fun openExternally(context: Context, uri: Uri) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Log.w(TAG, "无法打开外部链接: $uri")
    }
}
