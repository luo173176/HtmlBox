// =====================================================================
// HtmlBox —— 项目级构建脚本
// 只负责声明插件版本，具体配置放在 app/build.gradle.kts
// =====================================================================
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 起，Compose 编译器随 Kotlin 一起发布，必须显式声明该插件
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
