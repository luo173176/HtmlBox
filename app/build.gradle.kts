// =====================================================================
// HtmlBox —— app 模块构建脚本
// =====================================================================
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.htmlbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.htmlbox"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"

        // 矢量图兼容支持（低版本设备也能渲染 VectorDrawable）
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 如需开启混淆，把 isMinifyEnabled 改为 true 即可
            // proguard-rules.pro 里已经写好 WebView 相关的 keep 规则
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // BuildConfig 用于在 debug 包中开启 WebView 调试
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // ---------- 基础 ----------
    implementation("androidx.core:core-ktx:1.15.0")

    // ---------- 生命周期 / ViewModel ----------
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // ---------- Activity + Compose ----------
    implementation("androidx.activity:activity-compose:1.9.3")

    // ---------- Compose BOM（统一管理 Compose 各库版本）----------
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // material3 已传递依赖 material-icons-core，图标可直接使用

    // ---------- 导航 ----------
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // ---------- WebView 增强：WebViewAssetLoader ----------
    implementation("androidx.webkit:webkit:1.12.1")

    // ---------- 调试工具 ----------
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
