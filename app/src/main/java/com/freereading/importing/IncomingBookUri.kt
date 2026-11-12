package com.freereading.importing

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.IntentCompat
import java.io.File

/** Intent metadata selects the entry point; BookRepository still validates the actual EPUB. */
object IncomingBookUri {
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
            // Malformed external extras must not crash the exported entry point.
            null
        }
    }

    private fun singleClipUri(intent: Intent): Uri? =
        intent.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri

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
