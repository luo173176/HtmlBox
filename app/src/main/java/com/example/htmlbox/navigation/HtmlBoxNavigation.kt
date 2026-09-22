package com.example.htmlbox.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.htmlbox.MainViewModel
import com.example.htmlbox.data.HtmlRepository
import com.example.htmlbox.ui.editor.EditorScreen
import com.example.htmlbox.ui.home.HomeScreen
import com.example.htmlbox.ui.webview.WebViewScreen

/**
 * 所有路由定义。
 *
 * 文件名可能包含空格、中文、括号等字符，因此拼进路由前必须 Uri.encode，
 * Navigation 在解析 navArgument 时会自动 Uri.decode 还原。
 */
object HtmlRoutes {

    /** 首页：文件列表 */
    const val HOME = "home"

    /** 运行页参数名。注意：必须声明在 VIEWER 之前，const val 不允许前向引用 */
    const val ARG_FILE_NAME = "fileName"

    /** 运行页的预览标记参数名（编辑器「运行预览」跳转时为 true） */
    const val ARG_PREVIEW = "preview"

    /** 运行页：viewer/index.html */
    const val VIEWER = "viewer/{$ARG_FILE_NAME}?$ARG_PREVIEW={$ARG_PREVIEW}"

    /** 编辑器（新建）：没有文件名参数 */
    const val EDITOR_NEW = "editor"

    /** 编辑器（编辑已有文件）：editor/index.html */
    const val EDITOR_EDIT = "editor/{$ARG_FILE_NAME}"

    fun viewer(fileName: String, preview: Boolean = false): String =
        if (preview) {
            "viewer/${Uri.encode(fileName)}?$ARG_PREVIEW=true"
        } else {
            "viewer/${Uri.encode(fileName)}"
        }

    fun editor(fileName: String): String = "editor/${Uri.encode(fileName)}"

    /** 编辑器「运行预览」跳转：加载隐藏草稿 .preview.html */
    fun preview(): String = viewer(HtmlRepository.PREVIEW_FILE_NAME, preview = true)
}

@Composable
fun HtmlBoxNavHost(
    navController: NavHostController = rememberNavController(),
    // 这里在 NavHost 之外取 viewModel()，LocalViewModelStoreOwner 是 Activity，
    // 因此整个导航图共享同一个 MainViewModel，返回首页时列表不会重新加载
    viewModel: MainViewModel = viewModel(),
) {
    NavHost(
        navController = navController,
        startDestination = HtmlRoutes.HOME
    ) {
        composable(route = HtmlRoutes.HOME) {
            HomeScreen(
                viewModel = viewModel,
                onOpenHtml = { fileName ->
                    navController.navigate(HtmlRoutes.viewer(fileName))
                },
                onNewHtml = {
                    navController.navigate(HtmlRoutes.EDITOR_NEW)
                },
                onEditHtml = { fileName ->
                    navController.navigate(HtmlRoutes.editor(fileName))
                }
            )
        }

        composable(
            route = HtmlRoutes.EDITOR_NEW
        ) { backStackEntry ->
            EditorScreen(
                sessionKey = backStackEntry.id,
                fileName = null,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onRunPreview = { navController.navigate(HtmlRoutes.preview()) }
            )
        }

        composable(
            route = HtmlRoutes.EDITOR_EDIT,
            arguments = listOf(
                navArgument(HtmlRoutes.ARG_FILE_NAME) { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val fileName = backStackEntry.arguments
                ?.getString(HtmlRoutes.ARG_FILE_NAME)

            EditorScreen(
                sessionKey = backStackEntry.id,
                fileName = fileName,
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onRunPreview = { navController.navigate(HtmlRoutes.preview()) }
            )
        }

        composable(
            route = HtmlRoutes.VIEWER,
            arguments = listOf(
                navArgument(HtmlRoutes.ARG_FILE_NAME) { type = NavType.StringType },
                navArgument(HtmlRoutes.ARG_PREVIEW) {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val fileName = backStackEntry.arguments
                ?.getString(HtmlRoutes.ARG_FILE_NAME)
                .orEmpty()
            val isPreview = backStackEntry.arguments
                ?.getBoolean(HtmlRoutes.ARG_PREVIEW)
                ?: false

            WebViewScreen(
                fileName = fileName,
                preview = isPreview,
                onBackToHome = { navController.popBackStack() }
            )
        }
    }
}
