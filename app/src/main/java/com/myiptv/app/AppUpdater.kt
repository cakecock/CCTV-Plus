package com.myiptv.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class ReleaseInfo(
    val versionTag: String,
    val downloadUrl: String,
    val notes: String,
    val sha256Url: String? = null
)

object GitHubUpdater {
    private const val REPO_FULL_NAME = "cakecock/CCTV-Plus"
    private val sha256UrlsByApkUrl = ConcurrentHashMap<String, String>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun getInstalledVersionCode(context: Context): Long {
        return runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
            info.longVersionCode
        }.getOrDefault(1L)
    }

    private fun extractApkFromReleaseJson(json: JSONObject): ReleaseInfo? {
        if (json.optBoolean("draft", false)) return null
        val tag = json.optString("tag_name").ifEmpty { json.optString("name") }
        val notes = json.optString("body", "")
        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        var shaUrl: String? = null

        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val dlUrl = asset.optString("browser_download_url", "")
            val name = asset.optString("name", dlUrl)
            if (apkUrl == null && name.endsWith(".apk", ignoreCase = true)) {
                apkUrl = dlUrl
            } else if (shaUrl == null && name.endsWith(".sha256", ignoreCase = true)) {
                shaUrl = dlUrl
            }
        }
        if (apkUrl.isNullOrBlank()) return null
        if (!shaUrl.isNullOrBlank()) {
            sha256UrlsByApkUrl[apkUrl] = shaUrl
        }
        return ReleaseInfo(tag.ifEmpty { "v2.0" }, apkUrl, notes, shaUrl)
    }

    suspend fun checkLatestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val listReq = Request.Builder()
                .url("https://api.github.com/repos/$REPO_FULL_NAME/releases?per_page=5")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "CCTV-Plus-Updater")
                .build()

            var foundRelease: ReleaseInfo? = null
            client.newCall(listReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("GitHub API HTTP ${resp.code}")
                }
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

            foundRelease ?: throw IllegalStateException("Релиз пока не найден в GitHub Releases.")
        }
    }

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16 * 1024)
        FileInputStream(file).use { fis ->
            var read: Int
            while (fis.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fetchExpectedSha256(shaUrl: String): String? {
        return runCatching {
            val req = Request.Builder().url(shaUrl).header("User-Agent", "CCTV-Plus-Updater").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val raw = resp.body?.string()?.trim().orEmpty()
                val token = raw.split(Regex("\\s+")).firstOrNull()?.trim().orEmpty()
                if (token.matches(Regex("^[0-9a-fA-F]{64}$"))) token.lowercase(Locale.US) else null
            }
        }.getOrNull()
    }

    private fun getSignerHashes(pkgInfo: PackageInfo): Set<String> {
        val signingInfo = pkgInfo.signingInfo ?: return emptySet()
        val signers = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        } ?: return emptySet()
        return signers.map { sig ->
            val digest = MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun verifyApkPackageAndSignature(context: Context, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() < 100_000L) {
            throw IllegalStateException("Скачанный файл повреждён или не является APK")
        }
        val magic = ByteArray(2)
        FileInputStream(apkFile).use { it.read(magic) }
        if (magic[0] != 0x50.toByte() || magic[1] != 0x4B.toByte()) {
            throw IllegalStateException("Сервер вернул не APK-архив")
        }

        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archiveInfo = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
        } ?: throw IllegalStateException("Не удалось разобрать APK-пакет")

        if (archiveInfo.packageName != context.packageName) {
            throw IllegalStateException("Чужой packageName: ${archiveInfo.packageName}")
        }

        val currentInfo = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, flags)
        }

        if (archiveInfo.longVersionCode < currentInfo.longVersionCode) {
            throw IllegalStateException("Откат на более старую версию заблокирован")
        }

        val expectedSigners = getSignerHashes(currentInfo)
        val archiveSigners = getSignerHashes(archiveInfo)
        if (expectedSigners.isEmpty() || archiveSigners.isEmpty() || expectedSigners != archiveSigners) {
            throw IllegalStateException("Подпись APK не совпадает с текущим приложением!")
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        onProgress: (percent: Int, downloadedMb: String, totalMb: String) -> Unit
    ): Result<Unit> = downloadAndInstallVerified(
        context = context,
        downloadUrl = downloadUrl,
        sha256Url = sha256UrlsByApkUrl[downloadUrl],
        onProgress = onProgress
    )

    suspend fun downloadAndInstallVerified(
        context: Context,
        downloadUrl: String,
        sha256Url: String? = null,
        onProgress: (percent: Int, downloadedMb: String, totalMb: String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val baseDir = context.getExternalFilesDir(null) ?: context.cacheDir
        val outDir = File(baseDir, "updates").apply { mkdirs() }
        val tmpFile = File(outDir, "CCTV-Plus-Update.apk.tmp")
        val apkFile = File(outDir, "CCTV-Plus-Update.apk")

        runCatching {
            if (!context.packageManager.canRequestPackageInstalls()) {
                withContext(Dispatchers.Main) {
                    val permIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(permIntent)
                }
                throw IllegalStateException("Разрешите установку из неизвестных источников и повторите обновление")
            }

            if (tmpFile.exists()) tmpFile.delete()
            if (apkFile.exists()) apkFile.delete()

            val req = Request.Builder().url(downloadUrl).header("User-Agent", "CCTV-Plus-Updater").build()
            val call = client.newCall(req)

            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IllegalStateException("Пустой ответ сервера")
                    val totalBytes = body.contentLength()
                    val requiredSpace = (if (totalBytes > 0) totalBytes else 60L * 1024L * 1024L) + 20L * 1024L * 1024L
                    if (outDir.usableSpace < requiredSpace) {
                        throw IllegalStateException("Недостаточно свободного места на устройстве")
                    }

                    val inStream = body.byteStream()
                    FileOutputStream(tmpFile).use { outStream ->
                        val buffer = ByteArray(16 * 1024)
                        var downloadedBytes = 0L
                        var read: Int
                        var lastReportedPercent = -2
                        var lastReportedStepBytes = 0L

                        while (inStream.read(buffer).also { read = it } != -1) {
                            coroutineContext.ensureActive()
                            outStream.write(buffer, 0, read)
                            downloadedBytes += read

                            val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else -1
                            val shouldReport = if (percent >= 0) {
                                percent != lastReportedPercent
                            } else {
                                (downloadedBytes - lastReportedStepBytes) >= 256L * 1024L
                            }
                            if (shouldReport) {
                                lastReportedPercent = percent
                                lastReportedStepBytes = downloadedBytes
                                val dlMb = String.format(Locale.US, "%.1f", downloadedBytes / (1024f * 1024f))
                                val totMb = if (totalBytes > 0) {
                                    String.format(Locale.US, "%.1f", totalBytes / (1024f * 1024f))
                                } else "?"
                                withContext(Dispatchers.Main) {
                                    onProgress(percent, dlMb, totMb)
                                }
                            }
                        }
                        outStream.flush()
                    }
                }
            } catch (t: Throwable) {
                call.cancel()
                tmpFile.delete()
                throw t
            }

            if (!sha256Url.isNullOrBlank()) {
                val expectedHash = fetchExpectedSha256(sha256Url)
                    ?: throw IllegalStateException("Не удалось загрузить контрольную сумму SHA-256")
                val actualHash = computeFileSha256(tmpFile)
                if (actualHash != expectedHash) {
                    tmpFile.delete()
                    throw IllegalStateException("Контрольная сумма SHA-256 не совпала! Загрузка отклонена.")
                }
            }

            verifyApkPackageAndSignature(context, tmpFile)
            if (!tmpFile.renameTo(apkFile)) {
                tmpFile.copyTo(apkFile, overwrite = true)
                tmpFile.delete()
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
