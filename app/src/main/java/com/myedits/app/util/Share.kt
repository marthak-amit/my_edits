package com.myedits.app.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object Share {
    const val INSTAGRAM = "com.instagram.android"

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(pkg, 0); true
    }.getOrDefault(false)

    /** Opens the Android share sheet for a video. */
    fun shareVideo(context: Context, uri: Uri, toPackage: String? = null): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (toPackage != null) setPackage(toPackage)
        }
        return runCatching {
            if (toPackage != null) context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else context.startActivity(Intent.createChooser(intent, "Share video").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    @Suppress("unused")
    fun canResolve(context: Context, intent: Intent): Boolean =
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
}
