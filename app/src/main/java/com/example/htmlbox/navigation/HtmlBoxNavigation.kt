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

    /** 运行页：viewer/index.html */
    const val VIEWER = "viewer/{$ARG_FILE_NAME}"

    fun viewer(fileName: String): String = "viewer/${Uri.encode(fileName)}"
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
                }
            )
        }

        composable(
            route = HtmlRoutes.VIEWER,
            arguments = listOf(
                navArgument(HtmlRoutes.ARG_FILE_NAME) { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val fileName = backStackEntry.arguments
                ?.getString(HtmlRoutes.ARG_FILE_NAME)
                .orEmpty()

            WebViewScreen(
                fileName = fileName,
                onBackToHome = { navController.popBackStack() }
            )
        }
    }
}
