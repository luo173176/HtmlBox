# HTML 盒子 (HtmlBox)

一个用来**导入并离线运行单个 HTML 文件**的 Android 应用。

它不是浏览器，也不是「把 HTML 打包成 APK」的工具，而是一个
**单 HTML 文件管理器 + 运行器**。既可以从系统文件选择器导入现成文件，
也可以直接在应用里写代码、预览执行、保存：

```
导入 .html / 应用内编写  →  存入应用内部存储  →  方块网格管理（编辑/重命名/删除）  →  WebView 离线运行
```

---

## 一、技术栈

| 项 | 选型 |
|---|---|
| 语言 | Kotlin 2.0.21 |
| UI | Jetpack Compose + Material 3 |
| 架构 | 单 Activity + Navigation Compose + ViewModel + StateFlow |
| 首页布局 | 自适应方块网格：`GridCells.Adaptive(160.dp)` + `aspectRatio(1f)` 正方形卡片，增删带位移动画 |
| 内置编辑器 | 应用内编写 / 修改 HTML 源码（`BasicTextField` + 等宽字体），运行预览、保存后出现在首页 |
| 配色 | Material 3 自定义靛蓝调色板：用 `@material/material-color-utilities` 从应用图标底色 `#3B2A9C` 生成完整的明暗两套色调，**关闭动态取色**以保证品牌色一致 |
| 图标 | Android 8+ 自适应图标（靛蓝背景 + 白色 `</>` 前景），Android 13+ 支持主题图标（monochrome） |
| 构建 | Gradle 8.9 + AGP 8.7.3 + Kotlin DSL |
| SDK | minSdk 24 / compileSdk 35 / targetSdk 35 |
| WebView | androidx.webkit `WebViewAssetLoader`（非 file://） |
| 文件导入 | Storage Access Framework（`ActivityResultContracts.OpenDocument`） |
| 持久化 | 直接扫描 `filesDir/htmls/`，无 Room、无数据库 |
| 权限 | 仅 `INTERNET`，**不需要任何存储权限** |

---

## 二、直接安装 APK（不想编译就用这个）

到 [Releases](https://github.com/luo173176/HtmlBox/releases) 页面下载
`HtmlBox-v1.2-debug.apk`（约 10.3 MB）。

| 项 | 值 |
|---|---|
| 包名 | `com.example.htmlbox` |
| 版本 | versionCode 3 / versionName 1.2 |
| 支持系统 | Android 7.0 (API 24) 及以上 |
| 应用名 | HTML 盒子 |
| 签名 | APK Signature Scheme v2，Android Debug 证书 |
| 权限 | 仅 `INTERNET` |
| SHA-256 | `ea72d691f21e2f107d1b7a6075bb89b8e321edba2ed30f1b40b96af50968c07d` |

安装方式二选一：

```bash
# 方式一：adb（手机开启「USB 调试」后连电脑）
adb install -r HtmlBox-v1.2-debug.apk
```

方式二：把 `HtmlBox-v1.2-debug.apk` 直接拷到手机（微信/QQ/数据线均可），
在文件管理器里点开安装；若提示「禁止安装未知来源应用」，
到系统设置里允许对应来源即可。

> 本仓库的 `dist/` 目录是本地构建产物，已经在 `.gitignore` 里，
> 不会随仓库分发；请从 Releases 页面获取安装包，或自行构建。

> 这是 **debug 签名** 的包，可以直接装到任何手机上正常使用（所有功能完整，
> 没有阉割），但**不能上架应用商店**，也无法覆盖安装正式签名的版本。
> 需要正式包时见第三节末尾的说明。

---

## 三、运行步骤（Android Studio）

1. **准备环境**
   - Android Studio Ladybug (2024.2) 或更高版本
   - JDK 17+（Android Studio 自带的 JBR 即可，无需单独安装）
   - Android SDK：需安装 **API 35 的 Platform 与 Build-Tools**

2. **打开项目**
   - `File → Open`，选择本目录（含 `settings.gradle.kts` 的那一层）
   - 不要选择 `app/` 子目录

3. **首次同步**
   - Android Studio 会读取 `gradle/wrapper/gradle-wrapper.properties`
     并自动下载 Gradle 8.9（首次约需几分钟，国内网络可能较慢）
   - 仓库地址可在 `settings.gradle.kts` 中取消注释阿里云镜像加速
   - `gradle/wrapper/gradle-wrapper.jar` 已随项目提供，
     `gradlew` / `gradlew.bat` 均可直接用于命令行构建

4. **运行**
   - 连接 Android 7.0 (API 24) 以上设备，或创建模拟器
   - 点击 ▶ Run 'app'

5. **构建安装包（可选）**

   ```bash
   ./gradlew assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
   ```

   正式包需要先创建自己的签名证书，再执行 `./gradlew assembleRelease`：

   ```bash
   keytool -genkeypair -v -keystore htmlbox.jks -alias htmlbox \
           -keyalg RSA -keysize 2048 -validity 10000
   ```

   然后参考 `app/build.gradle.kts` 注释补上 `signingConfigs` / `buildTypes.release`。
   本项目**未内置**任何签名配置，避免密钥被误提交。

> **国内网络 / Windows 证书报错**
> 若 `./gradlew` 首次下载 Gradle 时报
> `schannel: ... CRYPT_E_NO_REVOCATION_CHECK`（Windows 关闭了证书吊销检查、
> 导致 TLS 校验失败），有两个办法：
>
> 1. 把 `gradle/wrapper/gradle-wrapper.properties` 里的
>    `services.gradle.org` 换成腾讯云镜像：
>    `https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip`
> 2. 或绕开 wrapper，用本机已解压的 Gradle 直接构建：
>    `D:\dev\gradle-8.9\gradle-8.9\bin\gradle.bat assembleDebug`
>
> 本仓库的 `dist/HtmlBox-v1.2-debug.apk` 就是用第 2 种方式产出的。

6. **验收走一遍**
   - 首页显示「还没有 HTML」，点右下角「新建 HTML」→ 编辑器打开，已预填可运行模板
   - 改几行代码 → 右上角 ▶「运行预览」→ WebView 执行（模板里的按钮、alert 都有效）
   - 预览页返回键回到编辑器，**代码原样保留**
   - 「保存」→ 输入文件名（`.html` 自动加）→ 返回首页，方块网格出现新卡片
   - 首页右上角 + 或空状态按钮「导入 HTML」，用系统文件选择器导入 `.html`
   - 点卡片 → WebView 运行，JS 生效
   - 卡片长按或右上角 ⋮ → 编辑 / 重命名 / 删除，均有确认与提示
   - 「编辑」打开的是该文件的最新内容，保存即覆盖
   - 编辑器里改了代码直接按返回 → 弹「放弃修改？」确认，不会静默丢代码
   - 按返回键：网页有历史则后退，无历史则回上一页
   - 杀掉进程重新打开 → 卡片仍在（文件在内部存储，不会丢）

---

## 四、目录说明

运行时数据存放在应用**内部存储**：

```
/data/user/0/com.example.htmlbox/files/htmls/
```

该目录通过 `WebViewAssetLoader` 映射为：

```
https://appassets.androidplatform.net/htmls/
```

WebView 加载地址形如：

```
https://appassets.androidplatform.net/htmls/index.html
```

---

## 五、已知限制

1. **单个 HTML 文件，不含外部资源**
   导入的只是一个文件。如果 HTML 里用 `<img src="./pic.png">` 之类的
   相对路径引用本地图片 / CSS / JS，这些文件不会一起被导入，会 404。
   解决办法：把这些资源改成 **data URI 内联**，或直接用 CDN 绝对地址。
   （由于 `/htmls/` 是整目录映射，只要资源文件也在该目录里，相对路径其实是可用的——
   未来可通过「导入文件夹」功能补齐。）

2. **WebView 内核版本决定一切**
   API 24 的模拟器自带 Chromium 51，现代 CSS/JS 可能跑不起来。
   真机上请在应用商店更新「Android System WebView」。

3. **`databaseEnabled` 实际已失效**
   WebSQL 已被 Chromium 移除，该开关在现代 WebView 上是空操作。
   真正能持久化的是 `localStorage` / `IndexedDB`（由 `domStorageEnabled` 提供）。
   注意：这两者的数据也存在应用私有目录里，**卸载应用或清除应用数据会一并丢失**。

4. **不支持 `<input type="file">`**
   没有实现 `WebChromeClient.onShowFileChooser`，页面里的文件选择框点了没反应。

5. **`allowFileAccess = true` 是兼容性妥协**
   按需求保留了该开关，理论上 HTML 可以读取 `file://` 路径。
   如果只跑自己的 HTML，建议改为 `false`（本应用的资源加载走 asset loader，不依赖它）。

6. **不支持多窗口 / 新标签页**
   `setSupportMultipleWindows(false)`，`window.open` 会在同一个 WebView 里打开。

7. **缩放开关不重载页面**
   桌面模式需要重载才能生效（UA 变更必须重新发起请求）；
   缩放开关即时生效，不重载，所以不会丢失页面状态。

8. **内置编辑器是纯文本编辑器**
   没有语法高亮、自动补全，也不自动保存。运行预览用的是隐藏草稿
   `.preview.html`（首页列表会过滤点开头的文件），它不代表已保存。
   编辑超大文件（几百 KB 以上）时输入可能明显卡顿。

---

## 六、后续可扩展方向

- 导入文件夹（`ActivityResultContracts.OpenDocumentTree`），让相对路径资源真正可用
- 编辑器加语法高亮（行号栏 + 简易 HTML/JS 着色）
- 用 Zip 打包多文件 HTML 项目并解压到 `htmls/<名字>/`
- `WebViewClient.onPageFinished` 时把标题写回卡片
- 给 HTML 暴露一个最小的原生桥（`@JavascriptInterface`）用于退出 / 震动 / 读取剪贴板
- 文件分享导出（`ACTION_SEND` + `FileProvider`）
- 「最近打开」排序、搜索、收藏
- 开启 release 混淆（`isMinifyEnabled = true`），`proguard-rules.pro` 已备好规则

---

## 七、开源协议

[MIT License](LICENSE) © 2026 luo173176

随便用、随便改、可以商用，保留版权声明即可。
