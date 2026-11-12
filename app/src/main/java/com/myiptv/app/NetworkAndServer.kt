package com.myiptv.app

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
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
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

object NetworkLoader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val attrRegex = Regex("([a-zA-Z0-9_-]+)=\"([^\"]*)\"")
    private val keyExtractRegex = Regex("\\[key:([^\\]]+)\\]", RegexOption.IGNORE_CASE)
    private val urlExtractRegex = Regex("(https?://[^\\s\"'\\]\\\\]+)", RegexOption.IGNORE_CASE)
    private const val OTT_UA = "Mozilla/5.0 (SmartHub; SMART-TV; U; Linux/SmartTV) AppleWebKit/531.2+ OttPlayer/2.0"
    private const val PAGE_SIZE = 50

    fun parseVPortalCredentials(rawUrl: String): Pair<String, String> {
        val key = keyExtractRegex.find(rawUrl)?.groupValues?.get(1)?.trim() ?: ""
        var baseUrl = urlExtractRegex.find(rawUrl)?.groupValues?.get(1)?.trim() ?: ""
        if (baseUrl.isNotEmpty() && !baseUrl.endsWith("/")) baseUrl += "/"
        return key to baseUrl
    }

    private fun isPortalOrImageUrl(u: String, baseUrl: String): Boolean {
        val low = u.lowercase()
        if (low.contains("/api/v1")) return true
        if (baseUrl.isNotEmpty() && low == baseUrl.lowercase()) return true
        return low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png") || low.endsWith(".webp") || low.endsWith(".gif")
    }

    suspend fun loadM3u(url: String): M3uParseResult = withContext(Dispatchers.IO) {
        val channels = mutableListOf<Channel>()
        val movies = mutableListOf<VodMovie>()
        var embeddedEpgUrl: String? = null

        runCatching {
            val cleanUrl = urlExtractRegex.find(url)?.groupValues?.get(1) ?: url.trim()
            val req = Request.Builder().url(cleanUrl).header("User-Agent", "Mozilla/5.0").build()
            client.newCall(req).execute().use { resp ->
                val stream = resp.body?.byteStream() ?: return@use
                val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                var line: String?
                var name = ""
                var group = "Общие"
                var logo: String? = null
                var epgId: String? = null
                var ua: String? = null
                var catchupDays = 3
                var catchupSource: String? = null
                var num = 1

                while (reader.readLine().also { line = it } != null) {
                    val t = line!!.trim()
                    if (t.isEmpty()) continue
                    when {
                        t.startsWith("#EXTM3U", ignoreCase = true) -> {
                            val attrs = attrRegex.findAll(t).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                            embeddedEpgUrl = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"]
                        }
                        t.startsWith("#EXTINF:") -> {
                            name = t.substringAfterLast(",").trim()
                            val attrs = attrRegex.findAll(t).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                            logo = attrs["tvg-logo"]
                            epgId = attrs["tvg-id"] ?: attrs["tvg-name"]
                            group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: group
                            catchupDays = attrs["catchup-days"]?.toIntOrNull()
                                ?: attrs["tvg-rec"]?.toIntOrNull()
                                ?: attrs["timeshift"]?.toIntOrNull()
                                ?: 3
                            catchupSource = attrs["catchup-source"]
                        }
                        t.startsWith("#EXTGRP:") -> group = t.substringAfter("#EXTGRP:").trim()
                        t.startsWith("#EXTVLCOPT:http-user-agent=") -> ua = t.substringAfter("=").trim()
                        !t.startsWith("#") -> {
                            if (name.isNotEmpty()) {
                                val isVod = t.endsWith(".mp4", true) || t.endsWith(".mkv", true) || t.endsWith(".avi", true)
                                if (isVod) {
                                    movies.add(VodMovie("m3u_vod_$num", name, group, logo, "", "", "", t))
                                } else {
                                    channels.add(Channel("ch_$num", num, name, group, logo, t, epgId, ua, catchupDays, catchupSource))
                                }
                                num++
                                name = ""; logo = null; epgId = null; ua = null; catchupDays = 3; catchupSource = null
                            }
                        }
                    }
                }
            }
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

        runCatching {
            val (key, baseUrl) = parseVPortalCredentials(rawPortalUrl)
            val targetUrl = if (!customTargetUrl.isNullOrBlank() && customTargetUrl.contains("/api/")) customTargetUrl else baseUrl

            if (!targetUrl.startsWith("http")) {
                return@withContext emptyList<VodMovie>() to "⚠️ Очистите поле VPortal крестиком и вставьте ссылку portal::[key:...]http://..."
            }

            val reqObj = if (!customRequestJson.isNullOrBlank()) {
                runCatching { JSONObject(customRequestJson) }.getOrElse { JSONObject() }
            } else JSONObject()

            if (key.isNotEmpty() && !reqObj.has("key")) reqObj.put("key", key)
            if (reqObj.has("limit") && reqObj.optInt("limit", 0) <= 0) reqObj.put("limit", PAGE_SIZE)

            if (!searchQuery.isNullOrBlank()) {
                if (!reqObj.has("cmd")) reqObj.put("cmd", "flicks")
                reqObj.put("search", searchQuery)
                reqObj.put("query", searchQuery)
                reqObj.put("offset", 0)
                reqObj.put("limit", PAGE_SIZE)
            }

            val payloadStr = reqObj.toString()
            val request = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", OTT_UA)
                .header("Accept", "application/json")
                .post(payloadStr.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()?.trim() ?: ""
                if (resp.isSuccessful && body.isNotEmpty()) {
                    parseVideoportalJson(body, baseUrl, reqObj, itemsOut)
                    diagnostic = "✅ HTTP ${resp.code} (элементов: ${itemsOut.size}) | Sent: $payloadStr | Resp: " + body.take(400).replace("\n", " ")
                } else {
                    diagnostic = "❌ HTTP ${resp.code} | Sent: $payloadStr | Resp: " + body.take(350)
                }
            }
        }.onFailure { err ->
            diagnostic = "❌ Ошибка VPortal: ${err.localizedMessage}"
        }
        itemsOut to diagnostic
    }

    private fun extractStreamUrl(obj: JSONObject, baseUrl: String): String {
        val fields = listOf("stream_url", "video_url", "file", "stream", "src", "link", "hls", "play_url", "video", "url", "cmd")
        for (f in fields) {
            val v = obj.optString(f, "").replace("\\/", "/").trim()
            if (v.startsWith("http") && !isPortalOrImageUrl(v, baseUrl)) return v
        }
        return ""
    }

    private fun parseVideoportalJson(
        body: String,
        baseUrl: String,
        sentRequest: JSONObject,
        out: MutableList<VodMovie>
    ) {
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
                    out.add(
                        VodMovie(
                            id = "vp_direct_root",
                            title = rootObj.optString("title", rootObj.optString("name", "▶ Смотреть")),
                            category = "Видео",
                            posterUrl = rootObj.optString("poster", rootObj.optString("cover")),
                            description = rootObj.optString("description", ""),
                            year = rootObj.optString("year", ""),
                            rating = rootObj.optString("rating", ""),
                            streamUrl = rootStream,
                            isFolder = false
                        )
                    )
                }
            }

            val arr = when {
                cleanBody.startsWith("[") -> JSONArray(cleanBody)
                rootObj != null -> rootObj.optJSONArray("items")
                    ?: rootObj.optJSONArray("data")
                    ?: rootObj.optJSONArray("movies")
                    ?: rootObj.optJSONArray("playlist")
                    ?: rootObj.optJSONArray("streams")
                    ?: rootObj.optJSONArray("files")
                    ?: JSONArray()
                else -> JSONArray()
            }

            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val itemType = item.optString("type", "").lowercase()
                if (itemType == "search" || itemType == "input") continue

                val title = item.optString("title")
                    .ifEmpty { item.optString("name") }
                    .ifEmpty { item.optString("label") }
                    .ifEmpty { "Серия #${i + 1}" }

                val poster = item.optString("poster")
                    .ifEmpty { item.optString("logo") }
                    .ifEmpty { item.optString("icon") }
                    .ifEmpty { item.optString("cover") }
                    .ifEmpty { item.optString("img") }
                    .ifEmpty { item.optString("pic") }

                val desc = item.optString("description").ifEmpty { item.optString("plot") }
                val year = item.optString("year", "")
                val rating = item.optString("rating", item.optString("rating_kp", ""))

                val childReqObj = item.optJSONObject("request")
                val directStream = extractStreamUrl(item, baseUrl).ifEmpty {
                    if (childReqObj != null) extractStreamUrl(childReqObj, baseUrl) else ""
                }

                val mergedReq = JSONObject()
                val parentKey = rootReq.optString("key").ifEmpty { sentRequest.optString("key") }
                if (parentKey.isNotEmpty()) mergedReq.put("key", parentKey)

                if (childReqObj != null) {
                    val keys = childReqObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        mergedReq.put(k, childReqObj.get(k))
                    }
                    if (mergedReq.has("limit") && mergedReq.optInt("limit", 0) <= 0) mergedReq.put("limit", PAGE_SIZE)
                } else {
                    for (f in listOf("id", "fid", "sid", "eid", "category_id", "cat_id", "category", "cat", "cmd", "action", "page", "season", "episode")) {
                        if (item.has(f)) mergedReq.put(f, item.get(f))
                    }
                }

                if (directStream.isNotEmpty()) {
                    out.add(VodMovie("vp_media_${i}_${System.nanoTime()}", title, "Видео", poster.ifEmpty { null }, desc, year, rating, directStream, false, mergedReq.toString(), rootUrl))
                } else {
                    out.add(VodMovie("vp_node_${i}_${System.nanoTime()}", title, if (itemType == "category") "Категория" else "Папка / Серия", poster.ifEmpty { null }, desc, year, rating, "", true, mergedReq.toString(), rootUrl))
                }
            }

            if (out.isEmpty()) {
                val mediaUrls = urlExtractRegex.findAll(cleanBody).map { it.groupValues[1] }.filter { !isPortalOrImageUrl(it, baseUrl) }.distinct().toList()
                mediaUrls.forEachIndexed { idx, sUrl ->
                    out.add(VodMovie("vp_auto_$idx", if (mediaUrls.size == 1) "▶ Воспроизвести" else "▶ Поток #${idx + 1}", "Видео", null, "", "", "", sUrl, false))
                }
            }

            if (sentRequest.has("limit") && arr.length() >= 20) {
                val nextReq = JSONObject(sentRequest.toString())
                val curOffset = nextReq.optInt("offset", 0)
                val curLimit = nextReq.optInt("limit", PAGE_SIZE).coerceAtLeast(20)
                nextReq.put("offset", curOffset + curLimit)
                nextReq.put("limit", curLimit)
                out.add(VodMovie("vp_next_${curOffset + curLimit}", "➡️ Ещё +$curLimit", "Навигация", null, "", "", "", "", true, nextReq.toString(), rootUrl))
            }
        }
    }

    suspend fun loadStalker(portalUrl: String, mac: String): List<Channel> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Channel>()
        runCatching {
            var base = portalUrl.trim()
            if (base.endsWith("/c/")) base = base.removeSuffix("c/")
            if (!base.endsWith("/")) base += "/"
            val cookie = "mac=${java.net.URLEncoder.encode(mac, "UTF-8")}; stb_lang=ru; timezone=Europe/Moscow"

            val hsReq = Request.Builder()
                .url("${base}portal.php?type=stb&action=handshake&JsHttpRequest=1-xml")
                .header("Cookie", cookie)
                .header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C) MAG200 stbapp")
                .build()

            var token = ""
            client.newCall(hsReq).execute().use { r ->
                token = JSONObject(r.body?.string() ?: "{}").optJSONObject("js")?.optString("token") ?: ""
            }

            if (token.isNotEmpty()) {
                val chReq = Request.Builder()
                    .url("${base}portal.php?type=itv&action=get_all_channels&JsHttpRequest=1-xml")
                    .header("Cookie", cookie)
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C) MAG200 stbapp")
                    .build()

                client.newCall(chReq).execute().use { r ->
                    val data = JSONObject(r.body?.string() ?: "{}").optJSONObject("js")?.optJSONArray("data") ?: JSONArray()
                    for (i in 0 until data.length()) {
                        val item = data.getJSONObject(i)
                        val rawCmd = item.optString("cmd").removePrefix("ffrt ").removePrefix("ffmpeg ").trim()
                        if (rawCmd.isNotEmpty()) {
                            list.add(Channel("stk_$i", i + 1, item.optString("name", "CH ${i + 1}"), "Stalker TV", item.optString("logo"), rawCmd, item.optString("xmltv_id")))
                        }
                    }
                }
            }
        }
        list
    }

    // Полная загрузка EPG: связывает каналы и по ID, и по названию (<display-name>) + собирает расписание для Архива
    suspend fun loadFullEpgSchedule(epgUrl: String): Map<String, List<EpgProgramItem>> = withContext(Dispatchers.IO) {
        val rawById = mutableMapOf<String, MutableList<EpgProgramItem>>()
        val displayNameToId = mutableMapOf<String, String>()
        val nowMs = System.currentTimeMillis()
        val windowStart = nowMs - 48L * 3600L * 1000L // 2 дня назад (для Архива)
        val windowEnd = nowMs + 24L * 3600L * 1000L   // 1 день вперед
        val sdfWithTz = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        val sdfNoTz = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
        val timeFmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        val shortTimeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        fun parseXmlTime(raw: String?): Long {
            if (raw.isNullOrBlank()) return 0L
            return runCatching { sdfWithTz.parse(raw.trim())?.time ?: 0L }.getOrElse {
                runCatching { sdfNoTz.parse(raw.trim().take(14))?.time ?: 0L }.getOrDefault(0L)
            }
        }

        runCatching {
            val req = Request.Builder().url(epgUrl).header("User-Agent", "Mozilla/5.0").build()
            client.newCall(req).execute().use { resp ->
                val rawStream = resp.body?.byteStream() ?: return@use
                val inputStream: InputStream = if (epgUrl.endsWith(".gz", true)) GZIPInputStream(rawStream) else rawStream
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(inputStream, "UTF-8")

                var eventType = parser.eventType
                var curChannelTagId: String? = null
                var insideDisplayName = false

                var progChannelId: String? = null
                var progStart = 0L
                var progStop = 0L
                var insideTitle = false
                var totalSaved = 0

                while (eventType != XmlPullParser.END_DOCUMENT && totalSaved < 35000) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            when (parser.name) {
                                "channel" -> curChannelTagId = parser.getAttributeValue(null, "id")
                                "display-name" -> if (curChannelTagId != null) insideDisplayName = true
                                "programme" -> {
                                    progChannelId = parser.getAttributeValue(null, "channel")
                                    progStart = parseXmlTime(parser.getAttributeValue(null, "start"))
                                    progStop = parseXmlTime(parser.getAttributeValue(null, "stop"))
                                }
                                "title" -> if (progChannelId != null && progStop in windowStart..windowEnd) {
                                    insideTitle = true
                                }
                            }
                        }
                        XmlPullParser.TEXT -> {
                            if (insideDisplayName && curChannelTagId != null) {
                                val dName = parser.text.trim().lowercase()
                                if (dName.isNotEmpty()) displayNameToId[dName] = curChannelTagId!!
                                insideDisplayName = false
                            } else if (insideTitle && progChannelId != null) {
                                val title = parser.text.trim()
                                val list = rawById.getOrPut(progChannelId!!) { mutableListOf() }
                                if (list.size < 45) {
                                    val tLabel = "${timeFmt.format(Date(progStart))} - ${shortTimeFmt.format(Date(progStop))}"
                                    list.add(EpgProgramItem(title, progStart, progStop, tLabel))
                                    totalSaved++
                                }
                                insideTitle = false
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            when (parser.name) {
                                "channel" -> { curChannelTagId = null; insideDisplayName = false }
                                "programme" -> { progChannelId = null; insideTitle = false }
                            }
                        }
                    }
                    eventType = parser.next()
                }
            }
        }

        // Создаем итоговую карту, где программы доступны и по ID, и по названию канала в нижнем регистре
        val finalMap = mutableMapOf<String, List<EpgProgramItem>>()
        rawById.forEach { (id, list) ->
            val sorted = list.sortedBy { it.startMs }
            finalMap[id] = sorted
            finalMap[id.lowercase()] = sorted
        }
        displayNameToId.forEach { (dName, id) ->
            rawById[id]?.let { list ->
                finalMap[dName] = list.sortedBy { it.startMs }
            }
        }
        finalMap
    }
}

fun getDeviceLocalIp(): String {
    runCatching {
        for (intf in NetworkInterface.getNetworkInterfaces()) {
            for (addr in intf.inetAddresses) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress ?: "127.0.0.1"
            }
        }
    }
    return "127.0.0.1"
}

fun generateQrBitmap(content: String, size: Int): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    bmp
}.getOrNull()

class SimpleQrWebServer(
    private val port: Int,
    private val isRu: Boolean,
    private val onReceived: (Map<String, String>) -> Unit
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        running = true
        thread(isDaemon = true) {
            runCatching {
                serverSocket = ServerSocket(port)
                while (running) {
                    val client = serverSocket?.accept() ?: break
                    thread(isDaemon = true) { handle(client) }
                }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
    }

    private fun handle(socket: java.net.Socket) {
        socket.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val out = PrintWriter(s.getOutputStream(), true)
            val reqLine = reader.readLine() ?: return
            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                if (line!!.lowercase().startsWith("content-length:")) {
                    contentLength = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }
            if (reqLine.startsWith("POST")) {
                val buf = CharArray(contentLength)
                reader.read(buf, 0, contentLength)
                val map = String(buf).split("&").associate {
                    val p = it.split("=")
                    URLDecoder.decode(p.getOrNull(0) ?: "", "UTF-8") to URLDecoder.decode(p.getOrNull(1) ?: "", "UTF-8").trim()
                }
                onReceived(map)
                val ok = if (isRu) "✅ Отправлено в CCTV Plus!" else "✅ Sent to CCTV Plus!"
                sendHtml(out, "<html><body style='background:#121417;color:#10B981;font-family:sans-serif;text-align:center;padding:40px;'><h2>$ok</h2></body></html>")
            } else sendHtml(out, buildHtmlForm())
        }
    }

    private fun sendHtml(out: PrintWriter, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        out.print("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: ${bytes.size}\r\n\r\n$html")
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
            "<form method='POST' action='/'>" +
            "<label>🏷 Название плейлиста:</label><input name='m3u_name' placeholder='TVLider Основной'/>" +
            "<label>📺 Ссылка M3U / M3U8:</label><input name='m3u' placeholder='http://.../playlist.m3u8'/>" +
            "<label>🏷 Название EPG:</label><input name='epg_name' placeholder='Программа TVLider'/>" +
            "<label>📅 Ссылка на EPG (.xml / .xml.gz):</label><input name='epg' placeholder='http://.../epg.xml.gz'/>" +
            "<label>🎬 VPortal (portal::[key:...]http://...):</label><input name='vportal' placeholder='portal::[key:...]http://...'/>" +
            "<button type='submit'>$btn</button></form></div></body></html>"
    }
}
