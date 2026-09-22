package com.example.htmlbox.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * 一个已导入的 HTML 文件的元信息（只包含列表展示需要的字段，不含文件内容）。
 */
data class HtmlFile(
    /** 文件名，例如 index.html */
    val name: String,
    /** 文件大小，单位字节 */
    val sizeBytes: Long,
    /** 最后修改时间，毫秒时间戳 */
    val lastModified: Long,
)

/** 导入结果 */
sealed interface ImportResult {
    /** @param renamed 是否因为重名被自动加序号 */
    data class Success(val name: String, val renamed: Boolean) : ImportResult

    /** 所选文件不是 .html / .htm */
    data object NotHtmlFile : ImportResult

    /** 所选文件为空 */
    data object EmptyFile : ImportResult

    /** 其他失败（IO、权限等） */
    data class Failed(val reason: String) : ImportResult
}

/** 重命名结果 */
sealed interface RenameResult {
    /** @param renamedForConflict 是否因为重名被自动加序号 */
    data class Success(val name: String, val renamedForConflict: Boolean) : RenameResult

    data object EmptyName : RenameResult
    data object InvalidName : RenameResult
    data object NotFound : RenameResult
    data class Failed(val reason: String) : RenameResult
}

/** 编辑器保存结果 */
sealed interface SaveResult {
    /** @param renamed 是否因为重名被自动加序号 */
    data class Success(val name: String, val renamed: Boolean) : SaveResult

    data object EmptyName : SaveResult
    data object InvalidName : SaveResult
    data class Failed(val reason: String) : SaveResult
}

/**
 * HTML 文件仓库。
 *
 * 设计说明：
 * - 不引入 Room / 数据库，直接扫描 [htmlsDir] 目录生成列表，
 *   文件名即主键，目录即真相来源（single source of truth）。
 * - 目录位于应用内部存储 `/data/user/0/<包名>/files/htmls`，
 *   卸载应用才会清除，因此应用重启后列表依然存在。
 * - 目录中的文件会被 WebViewAssetLoader 映射到
 *   `https://appassets.androidplatform.net/htmls/`，供 WebView 加载。
 */
class HtmlRepository(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 内部存储目录：filesDir/htmls */
    val htmlsDir: File = File(appContext.filesDir, DIR_NAME).apply {
        if (!exists()) mkdirs()
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /**
     * 扫描目录，按修改时间倒序返回（最新的排在最前）。
     * 以点开头的文件是内部草稿（如编辑器的 .preview.html），不进列表。
     */
    fun list(): List<HtmlFile> = htmlsDir.listFiles()
        .orEmpty()
        .asSequence()
        .filter { it.isFile && !it.name.startsWith(".") && it.name.hasHtmlExtension() }
        .sortedByDescending { it.lastModified() }
        .map { HtmlFile(name = it.name, sizeBytes = it.length(), lastModified = it.lastModified()) }
        .toList()

    /** 根据文件名取真实文件对象，做路径穿越校验 */
    fun fileFor(name: String): File? = resolve(name)?.takeIf { it.isFile }

    /** 读取文件全文（编辑器打开已有文件时用），失败返回 null */
    fun read(name: String): String? = try {
        resolve(name)?.takeIf { it.isFile }?.readText()
    } catch (e: IOException) {
        null
    }

    /**
     * 把当前编辑内容写进预览草稿文件 .preview.html，
     * 运行页用它加载，从而实现「不保存也能预览」。
     * 该文件被 [list] 过滤，永远不会出现在首页。
     */
    fun writePreview(content: String) {
        runCatching { File(htmlsDir, PREVIEW_FILE_NAME).writeText(content) }
    }

    /**
     * 编辑器保存已有文件：直接覆盖。
     * 文件若已在别处被删掉，会按原文件名重新创建，编辑内容不丢。
     */
    fun overwrite(name: String, content: String): Boolean = try {
        resolve(name)?.let { it.writeText(content); true } ?: false
    } catch (e: IOException) {
        false
    }

    /**
     * 编辑器新建文件：固定 .html 后缀，重名自动加序号。
     * 校验规则与 [rename] 一致。
     */
    fun create(baseName: String, content: String): SaveResult {
        val base = cleanedBaseName(baseName)
            ?: return if (baseName.isBlank()) SaveResult.EmptyName else SaveResult.InvalidName
        val desiredName = "$base.html"
        val target = resolveUniqueFile(desiredName)
        return try {
            target.writeText(content)
            SaveResult.Success(name = target.name, renamed = target.name != desiredName)
        } catch (e: IOException) {
            target.delete()
            SaveResult.Failed(e.message ?: "写入失败")
        }
    }

    // ------------------------------------------------------------------
    // 导入
    // ------------------------------------------------------------------

    /**
     * 把 SAF 返回的 content Uri 拷贝到内部目录。
     * 全程流式拷贝，不会把整个 HTML 读进内存。
     */
    fun importHtml(uri: Uri): ImportResult {
        val rawName = queryDisplayName(uri) ?: uri.lastPathSegment.orEmpty()
        val displayName = sanitizeFileName(rawName)
        if (displayName.isEmpty()) return ImportResult.EmptyFile
        if (!displayName.hasHtmlExtension()) return ImportResult.NotHtmlFile

        val target = resolveUniqueFile(displayName)
        val renamed = target.name != displayName

        return try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: return ImportResult.Failed("无法打开所选文件")
            input.use { source ->
                target.outputStream().use { dest -> source.copyTo(dest) }
            }
            if (target.length() == 0L) {
                target.delete()
                return ImportResult.EmptyFile
            }
            ImportResult.Success(name = target.name, renamed = renamed)
        } catch (e: IOException) {
            target.delete()
            ImportResult.Failed(e.message ?: "读写失败")
        } catch (e: SecurityException) {
            target.delete()
            ImportResult.Failed("没有读取该文件的权限")
        }
    }

    // ------------------------------------------------------------------
    // 删除 / 重命名
    // ------------------------------------------------------------------

    fun delete(name: String): Boolean = resolve(name)?.takeIf { it.isFile }?.delete() ?: false

    /**
     * 重命名：后缀始终沿用原文件的后缀（.html / .htm）。
     * 若目标名已存在，自动加序号 `名字 (1).html`。
     */
    fun rename(oldName: String, newBaseName: String): RenameResult {
        val source = resolve(oldName)?.takeIf { it.isFile } ?: return RenameResult.NotFound

        val base = cleanedBaseName(newBaseName)
            ?: return if (newBaseName.isBlank()) RenameResult.EmptyName else RenameResult.InvalidName

        val extension = source.name.substringAfterLast('.', "")
        val desiredName =
            if (extension.isEmpty()) base else "$base.$extension"

        // 名字没变，直接算成功
        if (desiredName == source.name) {
            return RenameResult.Success(source.name, renamedForConflict = false)
        }

        val target = resolveUniqueFile(desiredName)
        val conflict = target.name != desiredName

        return try {
            if (!source.renameTo(target)) {
                RenameResult.Failed("重命名失败")
            } else {
                // 触碰修改时间，让列表排序正确
                target.setLastModified(System.currentTimeMillis())
                RenameResult.Success(target.name, renamedForConflict = conflict)
            }
        } catch (e: SecurityException) {
            RenameResult.Failed(e.message ?: "重命名失败")
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 校验并清洗用户输入的基础名：
     * - 剥掉用户可能连着输入的 .html / .htm 后缀（后缀统一由调用方决定）
     * - 去掉首尾空白与首尾点号。去掉开头的点是为了防止造出隐藏文件
     *   （以点开头会被 [list] 过滤，用户会「保存成功却在首页看不到」）
     * - 含路径分隔符等非法字符时返回 null
     */
    private fun cleanedBaseName(raw: String): String? {
        var base = raw.trim()
        if (base.isEmpty()) return null

        val lower = base.lowercase(Locale.ROOT)
        if (lower.endsWith(EXT_HTML) || lower.endsWith(EXT_HTM)) {
            base = base.substringBeforeLast('.')
        }

        base = base.trim().trim('.')
        if (base.isEmpty()) return null
        if (base.any { it in ILLEGAL_CHARS || it.isISOControl() }) return null
        return base
    }

    /**
     * 把用户输入的名字解析成 htmlsDir 下的 File。
     * 返回 null 表示名字非法（不允许出现路径分隔符，防目录穿越）。
     */
    private fun resolve(name: String): File? {
        if (name.isEmpty()) return null
        if (name != File(name).name) return null
        if (name == "." || name == "..") return null
        return File(htmlsDir, name)
    }

    /**
     * 在 htmlsDir 下找一个不冲突的文件名。
     * `index.html` -> `index (1).html` -> `index (2).html` ...
     */
    private fun resolveUniqueFile(desiredName: String): File {
        val base = desiredName.substringBeforeLast('.', desiredName)
        val ext = desiredName.substringAfterLast('.', "")
        var index = 0
        while (true) {
            val candidate = when {
                index == 0 -> desiredName
                ext.isEmpty() -> "$base ($index)"
                else -> "$base ($index).$ext"
            }
            val file = File(htmlsDir, candidate)
            if (!file.exists()) return file
            index++
        }
    }

    /** 从 SAF 的 Uri 中读出文件显示名 */
    private fun queryDisplayName(uri: Uri): String? = try {
        appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    } catch (e: Exception) {
        // 某些第三方 Provider 不支持查询，降级用 lastPathSegment
        null
    }

    /** 去掉路径分隔符与非法字符，避免写文件失败或目录穿越 */
    private fun sanitizeFileName(raw: String): String {
        val fileName = raw.substringAfterLast('/').substringAfterLast('\\')
        val builder = StringBuilder(fileName.length)
        fileName.trim().forEach { ch ->
            builder.append(if (ch.isISOControl() || ch in ILLEGAL_CHARS) '_' else ch)
        }
        return builder.toString().trim().trimEnd('.')
    }

    private fun String.hasHtmlExtension(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.endsWith(EXT_HTML) || lower.endsWith(EXT_HTM)
    }

    companion object {
        /** 内部目录名，同时也是 URL 路径段：htmls */
        const val DIR_NAME = "htmls"

        /** WebViewAssetLoader 的默认域名 */
        const val ASSET_DOMAIN = "appassets.androidplatform.net"

        /**
         * 编辑器的预览草稿文件。以点开头，[list] 会把它过滤掉，
         * 所以它只用于「运行预览」，永远不会出现在首页。
         */
        const val PREVIEW_FILE_NAME = ".preview.html"

        const val EXT_HTML = ".html"
        const val EXT_HTM = ".htm"

        private val ILLEGAL_CHARS = charArrayOf(
            '\\', '/', ':', '*', '?', '"', '<', '>', '|'
        )

        /**
         * 生成 WebView 加载该文件时使用的 URL。
         * 文件名可能是中文 / 空格 / 括号，必须做 URL 编码。
         * 例：index.html -> https://appassets.androidplatform.net/htmls/index.html
         */
        fun buildAssetUrl(fileName: String): String =
            "https://$ASSET_DOMAIN/$DIR_NAME/${Uri.encode(fileName)}"
    }
}
