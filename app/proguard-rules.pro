# =====================================================================
# HtmlBox —— 混淆规则
# 默认 release 未开启混淆（isMinifyEnabled = false），
# 若开启混淆，请保留以下规则，否则 WebView 的 JS 桥接 / 反射会失效。
# =====================================================================

# 保留所有被 @JavascriptInterface 标注的方法（本项目目前没有用到，
# 但后续如果给 HTML 暴露原生能力，必须保留）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 保留 WebView / WebChromeClient 相关回调（避免被裁剪）
-keepclassmembers class * extends android.webkit.WebViewClient {
    public <methods>;
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public <methods>;
}

# androidx.webkit（WebViewAssetLoader）内部使用了注解与反射
-keep class androidx.webkit.** { *; }
-dontwarn androidx.webkit.**

# 保留 Compose 运行时需要的元数据
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# 保留行号，方便崩溃定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
