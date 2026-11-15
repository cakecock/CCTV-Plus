package com.myiptv.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

fun normalizeChannelKey(raw: String): String {
    var s = raw.lowercase().trim()
    s = s.replace(Regex("\\[.*?\\]|\\(.*?\\)"), " ")
    for (tag in listOf("uhd", "fhd", "hd", "sd", "4k", "50fps", "hevc", "orig", "плюс", "канал")) {
        s = s.replace(Regex("\\b$tag\\b"), " ")
    }
    return s.filter { it.isLetterOrDigit() }
}

private inline fun <T> runSuspendCatching(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Result.failure(t)
    }
}

private fun shortSha256(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.take(8).joinToString("") { "%02x".format(it) }
}

object NetworkLoader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val attrRegex = Regex("([a-zA-Z0-9_-]+)=\"([^\"]*)\"")
    private val keyExtractRegex = Regex("\\[key:([^\\]]+)\\]", RegexOption.IGNORE_CASE)
    private val urlExtractRegex = Regex("(https?://[^\\s\"'\\]\\\\]+)", RegexOption.IGNORE_CASE)
    private const val OTT_UA = "Mozilla/5.0 (SmartHub; SMART-TV; U; Linux/SmartTV) AppleWebKit/531.2+ OttPlayer/2.0"
    private const val PAGE_SIZE = 50
    private const val STALKER_UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) MAG200 stbapp"

    private data class CachedStalkerToken(val token: String, val timestampMs: Long)
    private val stalkerTokenCache = ConcurrentHashMap<String, CachedStalkerToken>()
    @Volatile var lastM3uError: String? = null
        private set
    @Volatile var lastStalkerError: String? = null
        private set

    fun parseVPortalCredentials(rawUrl: String): Pair<String, String> {
        val key = keyExtractRegex.find(rawUrl)?.groupValues?.get(1)?.trim() ?: ""
        var baseUrl = urlExtractRegex.find(rawUrl)?.groupValues?.get(1)?.trim() ?: ""
        if (baseUrl.isNotEmpty() && !baseUrl.endsWith("/")) baseUrl += "/"
        return key to baseUrl
    }

    private fun isPortalOrImageUrl(u: String, baseUrl: String): Boolean {
        val clean = u.substringBefore("?").substringBefore("#").lowercase()
        if (clean.contains("/api/v1")) return true
        if (baseUrl.isNotEmpty() && u.lowercase() == baseUrl.lowercase()) return true
        return clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") ||
            clean.endsWith(".webp") || clean.endsWith(".gif") || clean.endsWith(".html") ||
            clean.endsWith(".htm") || clean.endsWith(".php") || clean.endsWith(".js") || clean.endsWith(".css")
    }

    private fun resolveRelativePosterUrl(raw: String, baseUrl: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        if (trimmed.startsWith("/") && baseUrl.startsWith("http", true)) {
            val origin = runCatching {
                val u = URI(baseUrl)
                "${u.scheme}://${u.authority}"
            }.getOrNull() ?: return trimmed
            return origin + trimmed
        }
        return trimmed
    }

    fun clearPlaylistCache(context: Context, url: String) {
        val cleanUrl = urlExtractRegex.find(url)?.groupValues?.get(1) ?: url.trim()
        if (cleanUrl.isNotEmpty()) {
            runCatching { File(context.cacheDir, "m3u_${shortSha256(cleanUrl)}.txt").delete() }
            runCatching { File(context.cacheDir, "m3u_${cleanUrl.hashCode()}.txt").delete() }
        }
    }

    fun clearEpgCache(context: Context, url: String) {
        if (url.isNotBlank()) {
            val clean = url.trim()
            runCatching { File(context.cacheDir, "epg_${shortSha256(clean)}.bin").delete() }
            runCatching { File(context.cacheDir, "epg_${clean.hashCode()}.bin").delete() }
        }
    }

    private fun extractExtInfName(line: String): String {
        var inQuotes = false
        var escaped = false
        for (i in line.indices) {
            val c = line[i]
            if (escaped) {
                escaped = false
                continue
            }
            when (c) {
                '\\' -> escaped = true
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return line.substring(i + 1).trim()
            }
        }
        return line.substringAfterLast(",").trim()
    }

    private fun isValidM3uFile(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return runCatching {
            val headBytes = ByteArray(2048)
            val n = FileInputStream(file).use { it.read(headBytes) }
            if (n <= 0) return false
            val head = String(headBytes, 0, n, Charsets.UTF_8).trim().lowercase()
            if (head.startsWith("<!doctype html") || head.startsWith("<html")) return false
            head.contains("#extm3u") || head.contains("#extinf")
        }.getOrDefault(false)
    }

    private fun isGzipStreamFile(file: File): Boolean {
        if (!file.exists() || file.length() < 2) return false
        return runCatching {
            FileInputStream(file).use { fis ->
                val b0 = fis.read()
                val b1 = fis.read()
                b0 == 0x1F && b1 == 0x8B
            }
        }.getOrDefault(false)
    }

    private fun isValidEpgFile(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return runCatching {
            val headBytes = ByteArray(2048)
            val rawFis = FileInputStream(file)
            val stream: InputStream = if (isGzipStreamFile(file)) GZIPInputStream(rawFis) else rawFis
            val n = stream.use { it.read(headBytes) }
            if (n <= 0) return false
            val head = String(headBytes, 0, n, Charsets.UTF_8).trimStart('\uFEFF').trim().lowercase()
            if (head.startsWith("<!doctype html") || head.startsWith("<html")) return false
            head.contains("<tv") || head.contains("<?xml")
        }.getOrDefault(false)
    }

    suspend fun loadM3u(
        url: String,
        context: Context? = null,
        forceRefresh: Boolean = false,
        defaultGroup: String = "Общие"
    ): M3uParseResult = withContext(Dispatchers.IO) {
        val channels = mutableListOf<Channel>()
        val movies = mutableListOf<VodMovie>()
        var embeddedEpgUrl: String? = null
        lastM3uError = null

        runSuspendCatching {
            var tempFileToDelete: File? = null
            val rawStream: InputStream? = if (url.startsWith("content://") && context != null) {
                context.contentResolver.openInputStream(Uri.parse(url))
            } else {
                val cleanUrl = urlExtractRegex.find(url)?.groupValues?.get(1) ?: url.trim()
                val cacheFile = context?.let { File(it.cacheDir, "m3u_${shortSha256(cleanUrl)}.txt") }
                val cacheFresh = cacheFile != null && cacheFile.exists() && !forceRefresh &&
                    (System.currentTimeMillis() - cacheFile.lastModified()) < 2L * 3600L * 1000L

                if (cacheFresh && isValidM3uFile(cacheFile!!)) {
                    FileInputStream(cacheFile)
                } else {
                    val req = Request.Builder().url(cleanUrl).header("User-Agent", "Mozilla/5.0").build()
                    val resp = client.newCall(req).execute()
                    if (resp.isSuccessful && resp.body != null) {
                        val targetFile = cacheFile ?: File.createTempFile("m3u_tmp_", ".txt", context?.cacheDir)
                        val tmpFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
                        runCatching {
                            resp.body!!.byteStream().use { input ->
                                FileOutputStream(tmpFile).use { output -> input.copyTo(output) }
                            }
                        }
                        resp.close()
                        if (isValidM3uFile(tmpFile)) {
                            if (cacheFile != null) {
                                tmpFile.copyTo(cacheFile, overwrite = true)
                                tmpFile.delete()
                                FileInputStream(cacheFile)
                            } else {
                                tempFileToDelete = tmpFile
                                FileInputStream(tmpFile)
                            }
                        } else {
                            tmpFile.delete()
                            lastM3uError = "Invalid M3U response (not a playlist)"
                            if (cacheFile != null && cacheFile.exists()) FileInputStream(cacheFile) else null
                        }
                    } else {
                        lastM3uError = "HTTP ${resp.code}"
                        resp.close()
                        if (cacheFile != null && cacheFile.exists()) FileInputStream(cacheFile) else null
                    }
                }
            }

            try {
                if (rawStream != null) {
                    rawStream.use { stream ->
                        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                        val seenIds = mutableMapOf<String, Int>()
                        var line: String?
                        var name = ""
                        var group = defaultGroup
                        var logo: String? = null
                        var epgId: String? = null
                        var tvgNameFallback: String? = null
                        var explicitChNo: Int? = null
                        var ua: String? = null
                        var referrer: String? = null
                        var cookie: String? = null
                        var catchupDays = 0
                        var catchupSource: String? = null
                        var catchupType: String? = null
                        var num = 1

                        while (reader.readLine().also { line = it } != null) {
                            val t = line!!.trim()
                            if (t.isEmpty()) continue
                            when {
                                t.startsWith("#EXTM3U", ignoreCase = true) -> {
                                    val attrs = attrRegex.findAll(t).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                                    embeddedEpgUrl = attrs["url-tvg"]?.takeIf { it.isNotBlank() }
                                        ?: attrs["x-tvg-url"]?.takeIf { it.isNotBlank() }
                                        ?: attrs["tvg-url"]?.takeIf { it.isNotBlank() }
                                }
                                t.startsWith("#EXTINF:", ignoreCase = true) -> {
                                    name = extractExtInfName(t)
                                    val attrs = attrRegex.findAll(t).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                                    logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                                    tvgNameFallback = attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                                    epgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() } ?: tvgNameFallback
                                    explicitChNo = attrs["tvg-chno"]?.toIntOrNull()?.takeIf { it > 0 }
                                    group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: defaultGroup
                                    catchupSource = attrs["catchup-source"]?.takeIf { it.isNotBlank() }
                                    catchupType = attrs["catchup"]?.takeIf { it.isNotBlank() }
                                    val explicitDays = attrs["catchup-days"]?.toIntOrNull() ?: attrs["tvg-rec"]?.toIntOrNull() ?: attrs["timeshift"]?.toIntOrNull()
                                    val hasCatchupTag = catchupType != null || !catchupSource.isNullOrBlank()
                                    catchupDays = when {
                                        explicitDays != null && explicitDays > 0 -> explicitDays
                                        hasCatchupTag -> 1
                                        else -> 0
                                    }
                                }
                                t.startsWith("#EXTGRP:", ignoreCase = true) -> {
                                    val grp = t.substringAfter(":").trim()
                                    if (grp.isNotEmpty()) group = grp
                                }
                                t.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) -> ua = t.substringAfter("=").trim()
                                t.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) -> referrer = t.substringAfter("=").trim()
                                t.startsWith("#EXTVLCOPT:http-cookie=", ignoreCase = true) -> cookie = t.substringAfter("=").trim()
                                !t.startsWith("#") -> {
                                    val resolvedName = name.ifBlank {
                                        tvgNameFallback ?: epgId ?: t.substringAfterLast("/").substringBefore("?").ifBlank { "CH $num" }
                                    }
                                    val pathNoQuery = t.substringBefore("?").substringBefore("#").lowercase()
                                    val isVod = pathNoQuery.endsWith(".mp4") || pathNoQuery.endsWith(".mkv") ||
                                        pathNoQuery.endsWith(".avi") || pathNoQuery.endsWith(".mov") || pathNoQuery.endsWith(".webm")
                                    val baseId = stableChannelId(t, resolvedName)
                                    val dupCount = (seenIds[baseId] ?: 0) + 1
                                    seenIds[baseId] = dupCount
                                    val finalId = if (dupCount == 1) baseId else "${baseId}_$dupCount"

                                    if (isVod) {
                                        movies.add(VodMovie("vod_$finalId", resolvedName, group, logo, "", "", "", t))
                                    } else {
                                        val finalCatchup = if (catchupDays > 0) catchupDays else if (t.contains("/iptv/")) 3 else 0
                                        channels.add(
                                            Channel(
                                                id = finalId,
                                                number = explicitChNo ?: num,
                                                name = resolvedName,
                                                group = group,
                                                logoUrl = logo,
                                                streamUrl = t,
                                                epgId = epgId,
                                                userAgent = ua,
                                                catchupDays = finalCatchup,
                                                catchupSource = catchupSource,
                                                referrer = referrer,
                                                cookie = cookie,
                                                catchupType = catchupType
                                            )
                                        )
                                    }
                                    num++
                                    name = ""; group = defaultGroup; logo = null; epgId = null
                                    tvgNameFallback = null; explicitChNo = null
                                    ua = null; referrer = null; cookie = null
                                    catchupDays = 0; catchupSource = null; catchupType = null
                                }
                            }
                        }
                    }
                }
            } finally {
                tempFileToDelete?.delete()
            }
        }.onFailure { err ->
            lastM3uError = err.localizedMessage ?: "Network error"
        }
        if (channels.isNotEmpty() || movies.isNotEmpty()) {
            lastM3uError = null
        }
        M3uParseResult(channels, movies, embeddedEpgUrl)
    }

    suspend fun loadVPortalNode(
        rawPortalUrl: String,
        customTargetUrl: String? = null,
        customRequestJson: String? = null,
        searchQuery: String? = null
    ): Pair<List<VodMovie>, String> = withContext(Dispatchers.IO) {
        val itemsOut = mutableListOf<VodMovie>()
        var diagnostic = ""
        runSuspendCatching {
            val (key, baseUrl) = parseVPortalCredentials(rawPortalUrl)
            val targetUrl = if (!customTargetUrl.isNullOrBlank() && customTargetUrl.contains("/api/")) customTargetUrl else baseUrl
            if (!targetUrl.startsWith("http")) {
                return@withContext emptyList<VodMovie>() to "⚠️ Очистите поле VPortal и вставьте ссылку portal::[key:...]http://..."
            }
            val reqObj = if (!customRequestJson.isNullOrBlank()) runCatching { JSONObject(customRequestJson) }.getOrElse { JSONObject() } else JSONObject()
            if (key.isNotEmpty() && !reqObj.has("key")) reqObj.put("key", key)
            if (reqObj.has("limit") && reqObj.optInt("limit", 0) <= 0) reqObj.put("limit", PAGE_SIZE)
            if (!searchQuery.isNullOrBlank()) {
                if (!reqObj.has("cmd")) reqObj.put("cmd", "flicks")
                reqObj.put("search", searchQuery); reqObj.put("query", searchQuery); reqObj.put("offset", 0); reqObj.put("limit", PAGE_SIZE)
            }
            val payloadStr = reqObj.toString()
            val request = Request.Builder().url(targetUrl).header("User-Agent", OTT_UA).header("Accept", "application/json")
                .post(payloadStr.toRequestBody("application/json; charset=utf-8".toMediaType())).build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()?.trim() ?: ""
                if (resp.isSuccessful && body.isNotEmpty()) {
                    parseVideoportalJson(body, baseUrl, reqObj, itemsOut)
                    diagnostic = "✅ HTTP ${resp.code} (элементов: ${itemsOut.size}) | Sent: $payloadStr | Resp: " + body.take(400).replace("\n", " ")
                } else {
                    diagnostic = "❌ HTTP ${resp.code} | Sent: $payloadStr | Resp: " + body.take(350)
                }
            }
        }.onFailure { diagnostic = "❌ Ошибка VPortal: ${it.localizedMessage}" }
        itemsOut to maskSensitiveKeys(diagnostic)
    }

    private fun extractStreamUrl(obj: JSONObject, baseUrl: String): String {
        for (f in listOf("stream_url", "video_url", "file", "stream", "src", "link", "hls", "play_url", "video", "url", "cmd")) {
            val v = obj.optString(f, "").replace("\\/", "/").trim()
            if (v.startsWith("http") && !isPortalOrImageUrl(v, baseUrl)) return v
        }
        return ""
    }

    private fun parseVideoportalJson(body: String, baseUrl: String, sentRequest: JSONObject, out: MutableList<VodMovie>) {
        val cleanBody = body.replace("\\/", "/")
        if (cleanBody.startsWith("http") && !isPortalOrImageUrl(cleanBody, baseUrl)) {
            out.add(VodMovie("vp_raw_1", "▶ Воспроизвести", "Видео", null, "", "", "", cleanBody.lines().first().trim(), isFolder = false))
            return
        }
        runCatching {
            val rootObj = if (cleanBody.startsWith("{")) JSONObject(cleanBody) else null
            val rootUrl = rootObj?.optString("url")?.takeIf { it.startsWith("http") && it.contains("/api/") } ?: baseUrl
            val rootReq = rootObj?.optJSONObject("request") ?: sentRequest
            if (rootObj != null) {
                val rootStream = extractStreamUrl(rootObj, baseUrl)
                if (rootStream.isNotEmpty()) {
                    val rootPoster = resolveRelativePosterUrl(rootObj.optString("poster"), rootUrl)
                    out.add(VodMovie("vp_direct_root", rootObj.optString("title", rootObj.optString("name", "▶ Смотреть")), "Видео", rootPoster.ifEmpty { null }, rootObj.optString("description", ""), rootObj.optString("year", ""), rootObj.optString("rating", ""), rootStream, false))
                }
            }
            val arr = when {
                cleanBody.startsWith("[") -> JSONArray(cleanBody)
                rootObj != null -> rootObj.optJSONArray("items") ?: rootObj.optJSONArray("data") ?: rootObj.optJSONArray("movies") ?: rootObj.optJSONArray("playlist") ?: rootObj.optJSONArray("streams") ?: rootObj.optJSONArray("files") ?: JSONArray()
                else -> JSONArray()
            }
            val seenVpIds = mutableMapOf<String, Int>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val itemType = item.optString("type", "").lowercase()
                if (itemType == "search" || itemType == "input") continue
                val title = item.optString("title").ifEmpty { item.optString("name") }.ifEmpty { item.optString("label") }.ifEmpty { "Серия #${i + 1}" }
                val rawPoster = item.optString("poster").ifEmpty { item.optString("logo") }.ifEmpty { item.optString("icon") }.ifEmpty { item.optString("cover") }.ifEmpty { item.optString("img") }.ifEmpty { item.optString("pic") }
                val poster = resolveRelativePosterUrl(rawPoster, rootUrl)
                val desc = item.optString("description").ifEmpty { item.optString("plot") }
                val year = item.optString("year", "")
                val rating = item.optString("rating", item.optString("rating_kp", ""))
                val childReqObj = item.optJSONObject("request")
                val directStream = extractStreamUrl(item, baseUrl).ifEmpty { if (childReqObj != null) extractStreamUrl(childReqObj, baseUrl) else "" }
                val mergedReq = JSONObject()
                val parentKey = rootReq.optString("key").ifEmpty { sentRequest.optString("key") }
                if (parentKey.isNotEmpty()) mergedReq.put("key", parentKey)
                if (childReqObj != null) {
                    val keys = childReqObj.keys()
                    while (keys.hasNext()) { val k = keys.next(); mergedReq.put(k, childReqObj.get(k)) }
                    if (mergedReq.has("limit") && mergedReq.optInt("limit", 0) <= 0) mergedReq.put("limit", PAGE_SIZE)
                } else {
                    for (f in listOf("id", "fid", "sid", "eid", "category_id", "cat_id", "category", "cat", "cmd", "action", "page", "season", "episode")) {
                        if (item.has(f)) mergedReq.put(f, item.get(f))
                    }
                }
                val baseVpKey = shortSha256("$title|${mergedReq}|$directStream")
                val vpDup = (seenVpIds[baseVpKey] ?: 0) + 1
                seenVpIds[baseVpKey] = vpDup
                val detSuffix = if (vpDup == 1) baseVpKey else "${baseVpKey}_$vpDup"

                if (directStream.isNotEmpty()) {
                    out.add(VodMovie("vp_media_$detSuffix", title, "Видео", poster.ifEmpty { null }, desc, year, rating, directStream, false, mergedReq.toString(), rootUrl))
                } else {
                    out.add(VodMovie("vp_node_$detSuffix", title, if (itemType == "category") "Категория" else "Папка / Серия", poster.ifEmpty { null }, desc, year, rating, "", true, mergedReq.toString(), rootUrl))
                }
            }
            if (out.isEmpty()) {
                val mediaUrls = urlExtractRegex.findAll(cleanBody).map { it.groupValues[1] }.filter { !isPortalOrImageUrl(it, baseUrl) }.distinct().toList()
                mediaUrls.forEachIndexed { idx, sUrl -> out.add(VodMovie("vp_auto_${shortSha256(sUrl)}_$idx", if (mediaUrls.size == 1) "▶ Воспроизвести" else "▶ Поток #${idx + 1}", "Видео", null, "", "", "", sUrl, false)) }
            }
            val curLimit = sentRequest.optInt("limit", PAGE_SIZE).coerceAtLeast(20)
            if (sentRequest.has("limit") && arr.length() >= curLimit) {
                val nextReq = JSONObject(sentRequest.toString())
                val curOffset = nextReq.optInt("offset", 0)
                nextReq.put("offset", curOffset + curLimit)
                nextReq.put("limit", curLimit)
                out.add(VodMovie("vp_next_${curOffset + curLimit}", "➡️ Ещё +$curLimit", "Навигация", null, "", "", "", "", true, nextReq.toString(), rootUrl))
            }
        }
    }

    private fun normalizeStalkerBase(portalUrl: String): String {
        var base = portalUrl.trim()
        if (base.endsWith("/c/")) base = base.removeSuffix("c/")
        if (!base.endsWith("/")) base += "/"
        return base
    }

    private fun getStalkerToken(base: String, mac: String, cookie: String, forceRefresh: Boolean = false): String {
        val cacheKey = "$base|$mac"
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = stalkerTokenCache[cacheKey]
            if (cached != null && cached.token.isNotEmpty() && (now - cached.timestampMs) < 10L * 60L * 1000L) {
                return cached.token
            }
        }
        val hsReq = Request.Builder().url("${base}portal.php?type=stb&action=handshake&JsHttpRequest=1-xml")
            .header("Cookie", cookie).header("User-Agent", STALKER_UA).build()
        val token = client.newCall(hsReq).execute().use { r ->
            JSONObject(r.body?.string() ?: "{}").optJSONObject("js")?.optString("token") ?: ""
        }
        if (token.isNotEmpty()) {
            stalkerTokenCache[cacheKey] = CachedStalkerToken(token, now)
        }
        return token
    }

    suspend fun loadStalkerFull(portalUrl: String, mac: String): Pair<List<Channel>, List<VodMovie>> = withContext(Dispatchers.IO) {
        val channels = mutableListOf<Channel>()
        val movies = mutableListOf<VodMovie>()
        lastStalkerError = null
        runSuspendCatching {
            val base = normalizeStalkerBase(portalUrl)
            val cookie = "mac=${java.net.URLEncoder.encode(mac, "UTF-8")}; stb_lang=ru; timezone=Europe/Moscow"
            val token = getStalkerToken(base, mac, cookie, forceRefresh = true)
            if (token.isNotEmpty()) {
                val genresMap = mutableMapOf<String, String>()
                runCatching {
                    val gReq = Request.Builder().url("${base}portal.php?type=itv&action=get_genres&JsHttpRequest=1-xml")
                        .header("Cookie", cookie).header("Authorization", "Bearer $token").header("User-Agent", STALKER_UA).build()
                    client.newCall(gReq).execute().use { r ->
                        val gArr = JSONObject(r.body?.string() ?: "{}").optJSONArray("js") ?: JSONArray()
                        for (i in 0 until gArr.length()) {
                            val g = gArr.optJSONObject(i) ?: continue
                            val gid = g.optString("id")
                            val gTitle = g.optString("title")
                            if (gid.isNotEmpty() && gTitle.isNotEmpty()) genresMap[gid] = gTitle
                        }
                    }
                }

                val chReq = Request.Builder().url("${base}portal.php?type=itv&action=get_all_channels&JsHttpRequest=1-xml")
                    .header("Cookie", cookie).header("Authorization", "Bearer $token").header("User-Agent", STALKER_UA).build()
                client.newCall(chReq).execute().use { r ->
                    val data = JSONObject(r.body?.string() ?: "{}").optJSONObject("js")?.optJSONArray("data") ?: JSONArray()
                    val seenIds = mutableMapOf<String, Int>()
                    for (i in 0 until data.length()) {
                        val item = data.getJSONObject(i)
                        val rawCmd = item.optString("cmd").trim()
                        if (rawCmd.isNotEmpty()) {
                            val chName = item.optString("name", "CH ${i + 1}")
                            val genreId = item.optString("tv_genre_id", "")
                            val chGroup = genresMap[genreId] ?: "Stalker TV"
                            val encoded = "stalker_cmd:::${base}|||${mac}|||${rawCmd}"
                            val baseId = stableChannelId("${item.optString("id")}_$rawCmd", chName)
                            val dupCount = (seenIds[baseId] ?: 0) + 1
                            seenIds[baseId] = dupCount
                            val finalId = if (dupCount == 1) baseId else "${baseId}_$dupCount"
                            channels.add(Channel(finalId, i + 1, chName, chGroup, item.optString("logo"), encoded, item.optString("xmltv_id")))
                        }
                    }
                }

                for (page in 1..3) {
                    val vodReq = Request.Builder().url("${base}portal.php?type=vod&action=get_ordered_list&p=$page&JsHttpRequest=1-xml")
                        .header("Cookie", cookie).header("Authorization", "Bearer $token").header("User-Agent", STALKER_UA).build()
                    var loadedOnPage = 0
                    runCatching {
                        client.newCall(vodReq).execute().use { r ->
                            val jsObj = JSONObject(r.body?.string() ?: "{}").optJSONObject("js")
                            val data = jsObj?.optJSONArray("data") ?: JSONArray()
                            loadedOnPage = data.length()
                            for (i in 0 until data.length()) {
                                val item = data.getJSONObject(i)
                                val cmd = item.optString("cmd").trim()
                                if (cmd.isNotEmpty()) {
                                    val title = item.optString("name", "Фильм")
                                    val poster = resolveRelativePosterUrl(item.optString("screenshot_uri"), base)
                                    val vodId = "stk_vod_${shortSha256("${item.optString("id")}_$cmd")}"
                                    movies.add(VodMovie(vodId, title, "Stalker VOD", poster.ifEmpty { null }, item.optString("description"), item.optString("year"), item.optString("rating_imdb"), "stalker_vod:::${base}|||${mac}|||${cmd}"))
                                }
                            }
                        }
                    }
                    if (loadedOnPage == 0) break
                }
            } else {
                lastStalkerError = "Stalker handshake failed (check URL or MAC)"
            }
        }.onFailure { err ->
            lastStalkerError = err.localizedMessage ?: "Stalker connection error"
        }
        if (channels.isNotEmpty() || movies.isNotEmpty()) {
            lastStalkerError = null
        }
        channels to movies
    }

    suspend fun resolveStalkerStreamIfNeeded(rawUrl: String): String = withContext(Dispatchers.IO) {
        if (!rawUrl.startsWith("stalker_cmd:::") && !rawUrl.startsWith("stalker_vod:::")) return@withContext rawUrl
        val isVod = rawUrl.startsWith("stalker_vod:::")
        val payload = rawUrl.removePrefix("stalker_cmd:::").removePrefix("stalker_vod:::")
        val parts = payload.split("|||")
        if (parts.size < 3) return@withContext rawUrl
        val base = parts[0]; val mac = parts[1]; val rawCmd = parts[2]
        val directFallback = rawCmd.removePrefix("ffrt ").removePrefix("ffmpeg ").trim()

        runSuspendCatching {
            val cookie = "mac=${java.net.URLEncoder.encode(mac, "UTF-8")}; stb_lang=ru; timezone=Europe/Moscow"
            val typeParam = if (isVod) "vod" else "itv"
            for (attempt in 0..1) {
                val token = getStalkerToken(base, mac, cookie, forceRefresh = (attempt == 1))
                if (token.isNotEmpty()) {
                    val linkReq = Request.Builder()
                        .url("${base}portal.php?type=$typeParam&action=create_link&cmd=${java.net.URLEncoder.encode(rawCmd, "UTF-8")}&JsHttpRequest=1-xml")
                        .header("Cookie", cookie).header("Authorization", "Bearer $token").header("User-Agent", STALKER_UA).build()
                    client.newCall(linkReq).execute().use { r ->
                        val resolvedCmd = JSONObject(r.body?.string() ?: "{}").optJSONObject("js")?.optString("cmd", "") ?: ""
                        val clean = resolvedCmd.removePrefix("ffrt ").removePrefix("ffmpeg ").trim()
                        if (clean.startsWith("http")) return@withContext clean
                    }
                }
            }
        }
        directFallback
    }

    private fun parseXmlDateFast(raw: String?): Long {
        if (raw.isNullOrBlank() || raw.length < 14) return 0L
        return runCatching {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.set(raw.substring(0, 4).toInt(), raw.substring(4, 6).toInt() - 1, raw.substring(6, 8).toInt(), raw.substring(8, 10).toInt(), raw.substring(10, 12).toInt(), raw.substring(12, 14).toInt())
            cal.set(Calendar.MILLISECOND, 0)
            var utcMs = cal.timeInMillis
            val tzPart = raw.substring(14).trim()
            if (tzPart.length >= 5 && (tzPart[0] == '+' || tzPart[0] == '-')) {
                val sign = if (tzPart[0] == '+') 1 else -1
                val cleanTz = tzPart.substring(1).replace(":", "")
                val offsetMs = sign * (((cleanTz.substring(0, 2).toIntOrNull() ?: 0) * 3600L + (cleanTz.substring(2, 4).toIntOrNull() ?: 0) * 60L) * 1000L)
                utcMs -= offsetMs
            }
            utcMs
        }.getOrDefault(0L)
    }

    suspend fun loadFullEpgSchedule(
        epgUrl: String,
        archiveDays: Int = 3,
        epgOffsetHours: Int = 0,
        context: Context? = null,
        forceRefresh: Boolean = false,
        allowedChannelKeys: Set<String> = emptySet()
    ): Map<String, List<EpgProgramItem>> = withContext(Dispatchers.IO) {
        val rawById = mutableMapOf<String, MutableList<EpgProgramItem>>()
        val aliasToId = mutableMapOf<String, String>()
        val wantedChannelIds = mutableSetOf<String>()
        val hasChannelFilter = allowedChannelKeys.isNotEmpty()
        val nowMs = System.currentTimeMillis()
        val daysBack = archiveDays.coerceIn(1, 7).toLong()
        val windowStart = nowMs - (daysBack * 24L * 3600L * 1000L)
        val windowEnd = nowMs + (12L * 3600L * 1000L)
        val maxItemsPerChannel = (daysBack.toInt() * 140) + 100
        val offsetMs = epgOffsetHours * 3600L * 1000L
        val timeFmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        val shortTimeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        runSuspendCatching {
            val cacheFile = context?.let { File(it.cacheDir, "epg_${shortSha256(epgUrl.trim())}.bin") }
            val cacheFresh = cacheFile != null && cacheFile.exists() && !forceRefresh &&
                (nowMs - cacheFile.lastModified()) < 6L * 3600L * 1000L && isValidEpgFile(cacheFile)

            var tempFileToDelete: File? = null
            var activeFile: File? = if (cacheFresh) cacheFile else null

            if (activeFile == null) {
                val req = Request.Builder().url(epgUrl).header("User-Agent", "Mozilla/5.0").build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful && resp.body != null) {
                    val targetBase = cacheFile ?: File.createTempFile("epg_tmp_", ".bin", context?.cacheDir)
                    val tmpFile = File(targetBase.parentFile, "${targetBase.name}.tmp")
                    runCatching {
                        resp.body!!.byteStream().use { input ->
                            FileOutputStream(tmpFile).use { out -> input.copyTo(out) }
                        }
                    }
                    resp.close()
                    if (isValidEpgFile(tmpFile)) {
                        if (cacheFile != null) {
                            tmpFile.copyTo(cacheFile, overwrite = true)
                            tmpFile.delete()
                            activeFile = cacheFile
                        } else {
                            tempFileToDelete = tmpFile
                            activeFile = tmpFile
                        }
                    } else {
                        tmpFile.delete()
                        if (cacheFile != null && cacheFile.exists() && isValidEpgFile(cacheFile)) {
                            activeFile = cacheFile
                        }
                    }
                } else {
                    resp.close()
                    if (cacheFile != null && cacheFile.exists() && isValidEpgFile(cacheFile)) {
                        activeFile = cacheFile
                    }
                }
            }

            try {
                if (activeFile != null && activeFile.exists()) {
                    val rawFis = FileInputStream(activeFile)
                    val inputStream: InputStream = if (isGzipStreamFile(activeFile)) GZIPInputStream(rawFis) else rawFis
                    inputStream.use { stream ->
                        val parser = XmlPullParserFactory.newInstance().newPullParser()
                        parser.setInput(stream, "UTF-8")
                        var eventType = parser.eventType
                        var curChannelTagId: String? = null
                        var progChannelId: String? = null
                        var progStart = 0L
                        var progStop = 0L
                        var isProgInWindow = false
                        var hasTitleForProg = false

                        while (eventType != XmlPullParser.END_DOCUMENT) {
                            when (eventType) {
                                XmlPullParser.START_TAG -> when (parser.name) {
                                    "channel" -> {
                                        curChannelTagId = parser.getAttributeValue(null, "id")
                                        val cid = curChannelTagId
                                        if (hasChannelFilter && cid != null) {
                                            val low = cid.lowercase()
                                            val norm = normalizeChannelKey(cid)
                                            if (low in allowedChannelKeys || (norm.isNotEmpty() && norm in allowedChannelKeys)) {
                                                wantedChannelIds.add(cid)
                                            }
                                        }
                                    }
                                    "display-name" -> if (curChannelTagId != null) {
                                        val rawName = runCatching { parser.nextText().trim() }.getOrDefault("")
                                        if (rawName.isNotEmpty()) {
                                            val low = rawName.lowercase()
                                            val norm = normalizeChannelKey(rawName)
                                            val matchesFilter = !hasChannelFilter || curChannelTagId in wantedChannelIds ||
                                                low in allowedChannelKeys || (norm.isNotEmpty() && norm in allowedChannelKeys)
                                            if (matchesFilter) {
                                                wantedChannelIds.add(curChannelTagId!!)
                                                aliasToId[low] = curChannelTagId!!
                                                if (norm.isNotEmpty()) aliasToId[norm] = curChannelTagId!!
                                            }
                                        }
                                    }
                                    "programme" -> {
                                        progChannelId = parser.getAttributeValue(null, "channel")
                                        val pid = progChannelId
                                        val isWanted = !hasChannelFilter || (pid != null && (
                                            pid in wantedChannelIds ||
                                                pid.lowercase() in allowedChannelKeys ||
                                                normalizeChannelKey(pid).let { it.isNotEmpty() && it in allowedChannelKeys }
                                            ))
                                        if (isWanted) {
                                            progStart = parseXmlDateFast(parser.getAttributeValue(null, "start")) + offsetMs
                                            progStop = parseXmlDateFast(parser.getAttributeValue(null, "stop")) + offsetMs
                                            isProgInWindow = progStop >= windowStart && progStart <= windowEnd
                                        } else {
                                            isProgInWindow = false
                                        }
                                        hasTitleForProg = false
                                    }
                                    "title" -> if (progChannelId != null && isProgInWindow && !hasTitleForProg) {
                                        val title = runCatching { parser.nextText().trim() }.getOrDefault("")
                                        if (title.isNotEmpty()) {
                                            val list = rawById.getOrPut(progChannelId!!) { mutableListOf() }
                                            if (list.size < maxItemsPerChannel) {
                                                list.add(EpgProgramItem(title, progStart, progStop, "${timeFmt.format(Date(progStart))} - ${shortTimeFmt.format(Date(progStop))}"))
                                            }
                                            hasTitleForProg = true
                                        }
                                    }
                                }
                                XmlPullParser.END_TAG -> when (parser.name) {
                                    "channel" -> curChannelTagId = null
                                    "programme" -> { progChannelId = null; isProgInWindow = false; hasTitleForProg = false }
                                }
                            }
                            eventType = parser.next()
                        }
                    }
                }
            } finally {
                tempFileToDelete?.delete()
            }
        }

        val finalMap = mutableMapOf<String, List<EpgProgramItem>>()
        rawById.forEach { (id, list) ->
            val sorted = list.sortedBy { it.startMs }
            finalMap[id] = sorted; finalMap[id.lowercase()] = sorted
            val normId = normalizeChannelKey(id); if (normId.isNotEmpty()) finalMap[normId] = sorted
        }
        aliasToId.forEach { (aliasKey, id) -> rawById[id]?.let { finalMap[aliasKey] = it.sortedBy { p -> p.startMs } } }
        finalMap
    }
}

fun getDeviceLocalIp(): String {
    var fallbackPrivate: String? = null
    var fallbackAny: String? = null
    runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (intf in interfaces) {
            if (!intf.isUp || intf.isLoopback || intf.isVirtual) continue
            val name = intf.name.lowercase()
            val isVpnOrCell = name.startsWith("tun") || name.startsWith("tap") || name.startsWith("ppp") ||
                name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")
            val isPreferredLan = name.startsWith("wlan") || name.startsWith("wifi") ||
                name.startsWith("eth") || name.startsWith("en") || name.startsWith("ap")

            for (addr in intf.inetAddresses) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    val ip = addr.hostAddress ?: continue
                    if (isPreferredLan && addr.isSiteLocalAddress) return ip
                    if (!isVpnOrCell && addr.isSiteLocalAddress && fallbackPrivate == null) fallbackPrivate = ip
                    if (!isVpnOrCell && fallbackAny == null) fallbackAny = ip
                }
            }
        }
    }
    return fallbackPrivate ?: fallbackAny ?: "127.0.0.1"
}

fun generateQrBitmap(content: String, size: Int): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    bmp
}.getOrNull()

class SimpleQrWebServer(
    private val port: Int,
    private val isRu: Boolean,
    private val onReceived: (Map<String, String>) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile var isListening: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile private var startedAtMs: Long = System.currentTimeMillis()
    @Volatile private var consumed: Boolean = false

    @Volatile var accessToken: String = buildToken()
        private set
    var onStatusChanged: ((Boolean, String?) -> Unit)? = null

    fun getAccessUrl(ip: String): String = "http://$ip:$port/$accessToken/"

    private fun buildToken(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        return (1..10).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    fun start() {
        if (running) return
        running = true
        consumed = false
        startedAtMs = System.currentTimeMillis()
        thread(isDaemon = true) {
            runCatching {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                isListening = true
                lastError = null
                onStatusChanged?.invoke(true, null)
                while (running) {
                    val client = ss.accept()
                    thread(isDaemon = true) { handle(client) }
                }
            }.onFailure { e ->
                if (running) {
                    isListening = false
                    lastError = e.localizedMessage ?: "Port $port busy"
                    onStatusChanged?.invoke(false, lastError)
                }
            }
        }
    }

    fun stop() {
        running = false
        isListening = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        accessToken = buildToken()
        onStatusChanged?.invoke(false, null)
    }

    private fun readAsciiLine(input: InputStream, maxBytes: Int = 8192): String? {
        val bos = ByteArrayOutputStream()
        var prevCr = false
        while (bos.size() < maxBytes) {
            val b = input.read()
            if (b == -1) return if (bos.size() == 0) null else bos.toString("UTF-8")
            if (b == '\n'.code) {
                val bytes = bos.toByteArray()
                val len = if (prevCr && bytes.isNotEmpty()) bytes.size - 1 else bytes.size
                return String(bytes, 0, len, Charsets.UTF_8)
            }
            prevCr = (b == '\r'.code)
            bos.write(b)
        }
        return bos.toString("UTF-8")
    }

    private fun handle(socket: java.net.Socket) {
        socket.use { s ->
            s.soTimeout = 10_000
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            val reqLine = readAsciiLine(input) ?: return
            val reqParts = reqLine.split(" ")
            val method = reqParts.getOrNull(0)?.uppercase() ?: return
            val reqPath = reqParts.getOrNull(1)?.substringBefore("?") ?: "/"

            val headers = mutableMapOf<String, String>()
            var headerCount = 0
            while (headerCount < 64) {
                val hLine = readAsciiLine(input) ?: break
                if (hLine.isEmpty()) break
                val colon = hLine.indexOf(':')
                if (colon > 0) {
                    headers[hLine.substring(0, colon).trim().lowercase()] = hLine.substring(colon + 1).trim()
                }
                headerCount++
            }

            if (consumed || (System.currentTimeMillis() - startedAtMs) > 300_000L) {
                sendResponse(out, "403 Forbidden", "<html><body style='background:#121417;color:#F59E0B;font-family:sans-serif;text-align:center;padding:40px;'><h2>403 QR link expired — reopen QR tab</h2></body></html>")
                stop()
                return
            }

            if (reqPath != "/$accessToken" && reqPath != "/$accessToken/") {
                sendResponse(out, "403 Forbidden", "<html><body style='background:#121417;color:#EF4444;font-family:sans-serif;text-align:center;padding:40px;'><h2>403 Forbidden — Invalid or expired QR link</h2></body></html>")
                return
            }

            if (method == "POST") {
                val hostHeader = headers["host"]?.substringBefore(":")?.lowercase() ?: ""
                val originOrRef = headers["origin"] ?: headers["referer"]
                if (!originOrRef.isNullOrBlank() && hostHeader.isNotEmpty()) {
                    val reqHost = runCatching { URI(originOrRef).host?.lowercase() }.getOrNull() ?: ""
                    if (reqHost.isNotEmpty() && reqHost != hostHeader) {
                        sendResponse(out, "403 Forbidden", "<html><body>403 Origin mismatch</body></html>")
                        return
                    }
                }

                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val maxPostBytes = 64 * 1024
                if (contentLength <= 0 || contentLength > maxPostBytes) {
                    sendResponse(out, "413 Payload Too Large", "<html><body>Request size out of bounds (max 64 KB)</body></html>")
                    return
                }

                val bodyBytes = ByteArray(contentLength)
                var offset = 0
                while (offset < contentLength) {
                    val n = input.read(bodyBytes, offset, contentLength - offset)
                    if (n <= 0) break
                    offset += n
                }
                val bodyStr = String(bodyBytes, 0, offset, Charsets.UTF_8)
                val map = bodyStr.split("&")
                    .filter { it.contains("=") }
                    .associate { pair ->
                        val idx = pair.indexOf('=')
                        val k = runCatching { URLDecoder.decode(pair.substring(0, idx), "UTF-8") }.getOrDefault("")
                        val v = runCatching { URLDecoder.decode(pair.substring(idx + 1), "UTF-8").trim() }.getOrDefault("")
                        k to v
                    }
                consumed = true
                val ok = if (isRu) "✅ Отправлено в CCTV Plus!" else "✅ Sent to CCTV Plus!"
                sendResponse(out, "200 OK", "<html><body style='background:#121417;color:#10B981;font-family:sans-serif;text-align:center;padding:40px;'><h2>$ok</h2></body></html>")
                onReceived(map)
                thread(isDaemon = true) {
                    Thread.sleep(400)
                    stop()
                }
            } else {
                sendResponse(out, "200 OK", buildHtmlForm())
            }
        }
    }

    private fun sendResponse(out: java.io.OutputStream, status: String, html: String) {
        val bodyBytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(bodyBytes)
        out.flush()
    }

    private fun buildHtmlForm(): String {
        val title = if (isRu) "Пульт загрузки CCTV Plus" else "CCTV Plus Remote Loader"
        val btn = if (isRu) "🚀 Отправить в приложение" else "🚀 Send to App"
        return "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<style>body{background:#121417;color:#f3f4f6;font-family:sans-serif;padding:18px;}" +
            ".box{background:#1C2026;padding:20px;border-radius:16px;max-width:460px;margin:auto;}" +
            "input{width:100%;padding:12px;margin:6px 0 14px 0;border-radius:8px;border:1px solid #374151;background:#121417;color:#fff;box-sizing:border-box;}" +
            "button{width:100%;padding:14px;background:#10B981;color:#000;font-weight:bold;border:none;border-radius:10px;font-size:16px;}</style></head>" +
            "<body><div class='box'><h2 style='color:#10B981;margin-top:0;'>📺 $title</h2>" +
            "<form method='POST' action='/$accessToken/'>" +
            "<label>🏷 " + (if (isRu) "Название плейлиста:" else "Playlist Name:") + "</label><input name='m3u_name' placeholder='My IPTV'/>" +
            "<label>📺 " + (if (isRu) "Ссылка M3U / M3U8:" else "M3U / M3U8 URL:") + "</label><input name='m3u' placeholder='http://.../playlist.m3u8'/>" +
            "<label>🏷 " + (if (isRu) "Название EPG:" else "EPG Name:") + "</label><input name='epg_name' placeholder='TV Guide'/>" +
            "<label>📅 " + (if (isRu) "Ссылка на EPG (.xml / .xml.gz):" else "EPG URL (.xml / .xml.gz):") + "</label><input name='epg' placeholder='http://.../epg.xml.gz'/>" +
            "<label>🎬 VPortal (portal::[key:...]http://...):</label><input name='vportal' placeholder='portal::[key:...]http://...'/>" +
            "<label>📡 Stalker Portal URL:</label><input name='stalker_url' placeholder='http://portal.com/stalker_portal/c/'/>" +
            "<label>🔑 Stalker MAC:</label><input name='stalker_mac' placeholder='00:1A:79:XX:XX:XX'/>" +
            "<button type='submit'>$btn</button></form></div></body></html>"
    }
}
