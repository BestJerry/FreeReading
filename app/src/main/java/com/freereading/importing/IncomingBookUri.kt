package com.freereading.importing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.IntentCompat
import java.io.File

/** 解析外部打开／分享入口的 URI；这里只筛选来源，EPUB 实际内容仍由书库校验。 */
object IncomingBookUri {
    /** 接受单本书的 VIEW／SEND 请求，拒绝不支持的协议及损坏的外部参数。 */
    fun fromIntent(context: Context, intent: Intent): Uri? {
        return try {
            val uri = when (intent.action) {
                Intent.ACTION_VIEW -> intent.data ?: singleClipUri(intent)
                Intent.ACTION_SEND -> if (intent.hasExtra(Intent.EXTRA_STREAM)) {
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    singleClipUri(intent)
                }
                else -> null
            } ?: return null
            when (uri.scheme) {
                "content" -> uri.takeIf { !it.authority.isNullOrBlank() }
                "file" -> publicFileUri(context, uri)
                else -> null
            }
        } catch (_: RuntimeException) {
            // 导出的入口可能收到损坏的 Parcelable 等参数，解析失败不应使应用崩溃。
            null
        }
    }

    /** 仅将单项 ClipData 作为回退来源，避免无意中忽略多文件分享中的其他文件。 */
    private fun singleClipUri(intent: Intent): Uri? =
        intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri

    /** 规范化文件路径，仅允许公共外部存储，排除应用私有目录及 Android/data、obb。 */
    private fun publicFileUri(context: Context, uri: Uri): Uri? {
        return try {
            if (!uri.authority.isNullOrEmpty() && uri.authority != "localhost") return null
            val path = uri.path?.takeIf { it.startsWith('/') } ?: return null
            val file = File(path).canonicalFile
            val roots = buildList {
                add(Environment.getExternalStorageDirectory())
                context.getExternalFilesDirs(null).filterNotNull().forEach { directory ->
                    val root = directory.absolutePath.substringBefore("/Android/data/", "")
                    if (root.isNotEmpty()) add(File(root))
                }
            }.map { it.canonicalFile }.distinct()
            val root = roots.firstOrNull { file.path.startsWith(it.path + File.separator) }
                ?: return null
            val relative = file.relativeTo(root).invariantSeparatorsPath
            if (relative == "Android/data" || relative.startsWith("Android/data/") ||
                relative == "Android/obb" || relative.startsWith("Android/obb/")
            ) return null
            Uri.fromFile(file)
        } catch (_: java.io.IOException) {
            null
        }
    }
}
