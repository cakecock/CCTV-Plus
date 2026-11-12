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

    suspend fun checkLatestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val candidateRepos = mutableListOf(
                "$GITHUB_USER/CCTV-Plus",
                "$GITHUB_USER/CCTV",
                "$GITHUB_USER/CCTV_Plus",
                "$GITHUB_USER/MyIPTV"
            )

            // Автоматически подтягиваем все публичные репозитории пользователя cakecock
            runCatching {
                val userReposReq = Request.Builder()
                    .url("https://api.github.com/users/$GITHUB_USER/repos")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "CCTV-Plus-Updater")
                    .build()
                client.newCall(userReposReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val arr = JSONArray(resp.body?.string() ?: "[]")
                        for (i in 0 until arr.length()) {
                            val fullName = arr.getJSONObject(i).optString("full_name")
                            if (fullName.isNotEmpty() && !candidateRepos.contains(fullName)) {
                                candidateRepos.add(0, fullName)
                            }
                        }
                    }
                }
            }

            var foundRelease: ReleaseInfo? = null
            var lastHttpCode = 404

            for (repoFullName in candidateRepos.distinct()) {
                val req = Request.Builder()
                    .url("https://api.github.com/repos/$repoFullName/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "CCTV-Plus-Updater")
                    .build()

                client.newCall(req).execute().use { resp ->
                    lastHttpCode = resp.code
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: "{}"
                        val json = JSONObject(body)
                        val tag = json.optString("tag_name", "v1.0")
                        val notes = json.optString("body", "")
                        val assets = json.optJSONArray("assets")
                        var apkUrl = ""
                        if (assets != null && assets.length() > 0) {
                            apkUrl = assets.getJSONObject(0).optString("browser_download_url", "")
                        }
                        if (apkUrl.isNotEmpty()) {
                            foundRelease = ReleaseInfo(tag, apkUrl, notes)
                        }
                    }
                }
                if (foundRelease != null) break
            }

            foundRelease ?: throw IllegalStateException("Релиз не найден (HTTP $lastHttpCode)")
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
