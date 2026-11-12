package com.myiptv.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val versionTag: String,
    val downloadUrl: String,
    val notes: String
)

object GitHubUpdater {
    private const val GITHUB_USER = "cakecock"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun extractApkFromReleaseJson(json: JSONObject): ReleaseInfo? {
        val tag = json.optString("tag_name").ifEmpty { json.optString("name") }
        val notes = json.optString("body", "")
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val dlUrl = asset.optString("browser_download_url", "")
            if (dlUrl.endsWith(".apk", ignoreCase = true)) {
                return ReleaseInfo(tag.ifEmpty { "v1.0" }, dlUrl, notes)
            }
        }
        return null
    }

    suspend fun checkLatestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val candidateRepos = listOf(
                "$GITHUB_USER/CCTV-Plus",
                "$GITHUB_USER/CCTV",
                "$GITHUB_USER/CCTV_Plus",
                "$GITHUB_USER/MyIPTV"
            )

            var foundRelease: ReleaseInfo? = null

            // Способ 1 (Самый надежный): проверяем официальный раздел Releases на GitHub
            for (repoFullName in candidateRepos) {
                val listReq = Request.Builder()
                    .url("https://api.github.com/repos/$repoFullName/releases?per_page=5")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "CCTV-Plus-Updater")
                    .build()

                runCatching {
                    client.newCall(listReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val arr = JSONArray(resp.body?.string() ?: "[]")
                            for (i in 0 until arr.length()) {
                                val relObj = arr.optJSONObject(i) ?: continue
                                val info = extractApkFromReleaseJson(relObj)
                                if (info != null) {
                                    foundRelease = info
                                    break
                                }
                            }
                        }
                    }
                }
                if (foundRelease != null) break
            }

            // Способ 2 (Резервный): проверяем прямой version.json и наличие файла CCTV-Plus.apk
            if (foundRelease == null) {
                for (repoFullName in candidateRepos) {
                    val directVerUrl = "https://raw.githubusercontent.com/$repoFullName/main/version.json"
                    val apkUrl = "https://raw.githubusercontent.com/$repoFullName/main/CCTV-Plus.apk"
                    val directReq = Request.Builder().url(directVerUrl).header("User-Agent", "CCTV-Plus-Updater").build()
                    runCatching {
                        client.newCall(directReq).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val obj = JSONObject(resp.body?.string() ?: "{}")
                                val tag = obj.optString("tag_name", "").trim()
                                if (tag.isNotEmpty()) {
                                    // Проверяем, что сам файл APK действительно доступен по этой ссылке
                                    val headReq = Request.Builder().url(apkUrl).head().header("User-Agent", "CCTV-Plus-Updater").build()
                                    client.newCall(headReq).execute().use { headResp ->
                                        if (headResp.isSuccessful) {
                                            foundRelease = ReleaseInfo(tag, apkUrl, "Обновление из $repoFullName")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (foundRelease != null) break
                }
            }

            foundRelease ?: throw IllegalStateException("Сборка еще не опубликована в Releases. Подождите завершения сборки в Actions.")
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        onProgress: (percent: Int, downloadedMb: String, totalMb: String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(downloadUrl).header("User-Agent", "CCTV-Plus-Updater").build()
            val outDir = context.getExternalFilesDir(null) ?: context.cacheDir
            val apkFile = File(outDir, "CCTV-Plus-Update.apk")
            if (apkFile.exists()) apkFile.delete()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                val body = resp.body ?: throw IllegalStateException("Empty APK body")
                val totalBytes = body.contentLength()
                val inStream = body.byteStream()

                FileOutputStream(apkFile).use { outStream ->
                    val buffer = ByteArray(16 * 1024)
                    var downloadedBytes = 0L
                    var read: Int
                    var lastReportedPercent = -1

                    while (inStream.read(buffer).also { read = it } != -1) {
                        outStream.write(buffer, 0, read)
                        downloadedBytes += read
                        val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else -1
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            val dlMb = String.format(Locale.US, "%.1f", downloadedBytes / (1024f * 1024f))
                            val totMb = if (totalBytes > 0) {
                                String.format(Locale.US, "%.1f", totalBytes / (1024f * 1024f))
                            } else "?"
                            withContext(Dispatchers.Main) {
                                onProgress(percent.coerceIn(0, 100), dlMb, totMb)
                            }
                        }
                    }
                    outStream.flush()
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
