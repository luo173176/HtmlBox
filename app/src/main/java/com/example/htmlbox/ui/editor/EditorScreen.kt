package com.example.htmlbox.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.htmlbox.MainViewModel
import com.example.htmlbox.R

/**
 * 应用内编辑器：直接写 / 改 HTML 源码。
 *
 * - [fileName] 为 null 表示新建（预填模板），否则加载该文件内容；
 * - 代码存在 ViewModel 的草稿里，跳预览、旋转都不会丢；
 * - 「运行预览」把当前代码写进隐藏草稿 .preview.html 再跳运行页，
 *   不用保存也能看效果，返回键回到编辑器继续改；
 * - 「保存」写入 filesDir/htmls/，首页列表随即可见；
 * - 有未保存修改时退出会先确认，避免误触丢代码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    sessionKey: String,
    fileName: String?,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onRunPreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // 同一会话重复组合不重新读盘：预览返回 / 旋转后草稿原样保留
    LaunchedEffect(sessionKey, fileName) { viewModel.openDraft(sessionKey, fileName) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val code = viewModel.draftCode
    val dirty = viewModel.draftDirty

    var showSaveDialog by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    // 顶栏返回箭头和系统返回键走同一套逻辑：干净直接退，有修改先确认
    val attemptLeave: () -> Unit = {
        if (dirty) showDiscardDialog = true else onBack()
    }
    BackHandler(enabled = dirty) { showDiscardDialog = true }

    val save: () -> Unit = {
        val content = code.orEmpty()
        if (viewModel.draftOwner != null) {
            viewModel.saveHtml(viewModel.draftOwner, "", content)
        } else {
            showSaveDialog = true
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = viewModel.draftOwner
                            ?: stringResource(R.string.editor_title_new),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = attemptLeave) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.writePreview(code.orEmpty()) { onRunPreview() }
                        },
                        enabled = code != null
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.action_run_preview)
                        )
                    }
                    TextButton(onClick = save, enabled = code != null) {
                        Text(stringResource(R.string.action_save))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                    actionIconContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->

        if (code == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            CodeField(
                code = code,
                onCodeChange = viewModel::updateDraft,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }
    }

    if (showSaveDialog) {
        SaveDialog(
            onDismiss = { showSaveDialog = false },
            onConfirm = { name ->
                showSaveDialog = false
                viewModel.saveHtml(null, name, code.orEmpty())
            }
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.editor_discard_title)) },
            text = { Text(stringResource(R.string.editor_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    viewModel.clearDraft()
                    onBack()
                }) {
                    Text(
                        text = stringResource(R.string.editor_discard_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.editor_keep_editing))
                }
            }
        )
    }
}

/** 代码输入区：等宽字体 + 垂直滚动的纯文本编辑 */
@Composable
private fun CodeField(
    code: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurface
    )

    BasicTextField(
        value = code,
        onValueChange = onCodeChange,
        modifier = modifier
            .fillMaxSize()
            // 软键盘弹出时缩小可视高度，输入区不被遮挡
            .imePadding()
            .verticalScroll(rememberScrollState()),
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                if (code.isEmpty()) {
                    Text(
                        text = stringResource(R.string.editor_code_hint),
                        style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    )
                }
                innerTextField()
            }
        }
    )
}

/** 新建保存对话框：只输入基础名，.html 后缀自动添加 */
@Composable
private fun SaveDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_save_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.editor_save_dialog_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.editor_save_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(input) },
                enabled = input.isNotBlank()
            ) {
                Text(stringResource(R.string.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
