package dev.idadroid.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

object RootfsFileSharing {
    fun contentUri(context: Context, file: File) = FileProvider.getUriForFile(
        context,
        "${context.packageName}.rootfs-file-provider",
        file
    )

    fun openFile(context: Context, file: File, writable: Boolean = true) {
        val mimeType = mimeTypeFor(file.name)
        val intent = viewIntent(context, file, mimeType, writable)
            .takeIf { it.resolveActivity(context.packageManager) != null }
            ?: viewIntent(context, file, "*/*", writable)
                .takeIf { it.resolveActivity(context.packageManager) != null }
            ?: throw ActivityNotFoundException("没有可打开该文件的应用")

        val chooser = Intent.createChooser(intent, "打开 ${file.name}")
        chooser.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                (if (writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        )
        context.startActivity(chooser)
    }

    /** 通过系统分享面板把文件作为附件发送给其它应用（ACTION_SEND）。 */
    fun shareFile(context: Context, file: File, mimeType: String? = null) {
        val uri = contentUri(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType ?: mimeTypeFor(file.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.name)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享 ${file.name}")
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
    }

    fun mimeTypeFor(fileName: String): String {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotBlank() }
        return extension?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            ?: "application/octet-stream"
    }

    private fun viewIntent(context: Context, file: File, mimeType: String, writable: Boolean = true): Intent {
        val uri = contentUri(context, file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            putExtra(Intent.EXTRA_TITLE, file.name)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    (if (writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            )
        }
    }
}
