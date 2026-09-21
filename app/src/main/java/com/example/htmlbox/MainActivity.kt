package com.example.htmlbox

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.htmlbox.navigation.HtmlBoxNavHost
import com.example.htmlbox.ui.theme.HtmlBoxTheme

/**
 * 单 Activity 架构的唯一 Activity。
 * 所有页面都是 Compose 可组合函数，由 Navigation Compose 管理。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 边到边显示（Android 15 / targetSdk 35 已强制开启，这里显式声明保证低版本一致）
        enableEdgeToEdge()

        // 仅 debug 包允许 Chrome 远程调试 WebView：chrome://inspect
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        setContent {
            HtmlBoxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HtmlBoxNavHost()
                }
            }
        }
    }
}
