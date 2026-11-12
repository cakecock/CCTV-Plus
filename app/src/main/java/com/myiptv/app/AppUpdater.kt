package com.myiptv.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val versionTag: String,
    val downloadUrl: String,
    val notes: String
)

object GitHubUpdater {
    private const val REPO_API = "https://api.github.com/repos/cakecock/MyIPTV/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun checkLatestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(REPO_API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "CCTV-Plus-Updater")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code}")
                }
                val body = resp.body?.string() ?: "{}"
                val json = JSONObject(body)
                val tag = json.optString("tag_name", "v1.0")
                val notes = json.optString("body", "")
                val assets = json.optJSONArray("assets")
                var apkUrl = ""
                if (assets != null && assets.length() > 0) {
                    apkUrl = assets.getJSONObject(0).optString("browser_download_url", "")
                }
                if (apkUrl.isEmpty()) throw IllegalStateException("APK not found in release")
                ReleaseInfo(tag, apkUrl, notes)
            }
        }
    }

    suspend fun downloadAndInstall(context: Context, downloadUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(downloadUrl).build()
            val outDir = context.getExternalFilesDir(null) ?: context.cacheDir
            val apkFile = File(outDir, "CCTV-Plus-Update.apk")
            if (apkFile.exists()) apkFile.delete()

            client.newCall(req).execute().use { resp ->
                val inStream = resp.body?.byteStream() ?: throw IllegalStateException("Empty APK stream")
                FileOutputStream(apkFile).use { outStream ->
                    inStream.copyTo(outStream)
                }
            }

            withContext(Dispatchers.Main) {
                val uri = FileProvider.getUriForFile(context, "com.myiptv.app.provider", apkFile)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }
}
