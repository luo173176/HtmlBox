// =====================================================================
// HtmlBox —— 项目设置
// 声明插件仓库、依赖仓库与被包含的模块
// =====================================================================
pluginManagement {
    repositories {
        // 国内网络环境可取消注释以下阿里云镜像加速（可选）
        // maven("https://maven.aliyun.com/repository/google")
        // maven("https://maven.aliyun.com/repository/public")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 只允许在这里声明仓库，模块内再声明会直接报错
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // maven("https://maven.aliyun.com/repository/google")
        // maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "HtmlBox"
include(":app")
