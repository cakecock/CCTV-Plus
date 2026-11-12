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
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

object NetworkLoader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val attrRegex = Regex("([a-zA-Z0-9_-]+)=\"([^\"]*)\"")
    private val keyExtractRegex = Regex("\\[key:([^\\]]+)\\]", RegexOption.IGNORE_CASE)
    private val urlExtractRegex = Regex("(https?://[^\\s\"'\\]]+)", RegexOption.IGNORE_CASE)
    private const val OTT_UA = "Mozilla/5.0 (SmartHub; SMART-TV; U; Linux/SmartTV) AppleWebKit/531.2+ OttPlayer/2.0"

    // Умное извлечение ключа и http-адреса даже если вокруг есть лишний текст
    fun parseVPortalCredentials(rawUrl: String): Pair<String, String> {
        val keyMatch = keyExtractRegex.find(rawUrl)
        val urlMatch = urlExtractRegex.find(rawUrl)

        val key = keyMatch?.groupValues?.get(1)?.trim() ?: ""
        var baseUrl = urlMatch?.groupValues?.get(1)?.trim() ?: ""

        if (baseUrl.isNotEmpty() && !baseUrl.endsWith("/")) {
            baseUrl += "/"
        }
        return key to baseUrl
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
                var num = 1

                while (reader.readLine().also { line = it } != null) {
                    val t = line!!.trim()
                    if (t.isEmpty()) continue
                    when {
                        t.startsWith("#EXTM3U", ignoreCase = true) -> {
                            val attrs = attrRegex.findAll(t).associate {
                                it.groupValues[1].lowercase() to it.groupValues[2]
                            }
                            embeddedEpgUrl = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"]
                        }
                        t.startsWith("#EXTINF:") -> {
                            name = t.substringAfterLast(",").trim()
                            val attrs = attrRegex.findAll(t).associate {
                                it.groupValues[1].lowercase() to it.groupValues[2]
                            }
                            logo = attrs["tvg-logo"]
                            epgId = attrs["tvg-id"] ?: attrs["tvg-name"]
                            group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: group
                        }
                        t.startsWith("#EXTGRP:") -> {
                            group = t.substringAfter("#EXTGRP:").trim()
                        }
                        t.startsWith("#EXTVLCOPT:http-user-agent=") -> {
                            ua = t.substringAfter("=").trim()
                        }
                        !t.startsWith("#") -> {
                            if (name.isNotEmpty()) {
                                val isVod = t.endsWith(".mp4", true) || t.endsWith(".mkv", true) || t.endsWith(".avi", true)
                                if (isVod) {
                                    movies.add(VodMovie("m3u_vod_$num", name, group, logo, "", "", "", t))
                                } else {
                                    channels.add(Channel("ch_$num", num, name, group, logo, t, epgId, ua))
                                }
                                num++
                                name = ""
                                logo = null
                                epgId = null
                                ua = null
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
            val targetUrl = customTargetUrl?.takeIf { it.startsWith("http") } ?: baseUrl

            if (!targetUrl.startsWith("http")) {
                return@withContext emptyList<VodMovie>() to "В поле VPortal нет ссылки http://! Очистите поле крестиком и вставьте ссылку portal::[key:...]http://..."
            }

            val reqObj = if (!customRequestJson.isNullOrBlank()) {
                runCatching { JSONObject(customRequestJson) }.getOrElse { JSONObject() }
            } else {
                JSONObject()
            }
            if (key.isNotEmpty() && !reqObj.has("key")) {
                reqObj.put("key", key)
            }
            if (!searchQuery.isNullOrBlank()) {
                reqObj.put("search", searchQuery)
                reqObj.put("query", searchQuery)
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
                diagnostic = "HTTP ${resp.code} | Sent: $payloadStr | Resp: " + body.take(400).replace("\n", " ")
                if (resp.isSuccessful && body.isNotEmpty()) {
                    parseVideoportalJson(body, targetUrl, reqObj, itemsOut)
                }
            }
        }.onFailure { err ->
            diagnostic = "Ошибка VPortal: ${err.localizedMessage}"
        }

        itemsOut to diagnostic
    }

    private fun parseVideoportalJson(
        body: String,
        defaultApiUrl: String,
        parentRequest: JSONObject,
        out: MutableList<VodMovie>
    ) {
        runCatching {
            val rootObj = if (body.startsWith("{")) JSONObject(body) else null
            val rootUrl = rootObj?.optString("url")?.takeIf { it.startsWith("http") } ?: defaultApiUrl
            val rootReq = rootObj?.optJSONObject("request") ?: parentRequest

            if (rootObj != null) {
                val rootStream = rootObj.optString("stream_url")
                    .ifEmpty { rootObj.optString("video_url") }
                    .ifEmpty { rootObj.optString("file") }
                    .ifEmpty { rootObj.optString("stream") }
                if (rootStream.startsWith("http")) {
                    out.add(
                        VodMovie(
                            id = "vp_direct_1",
                            title = rootObj.optString("title", rootObj.optString("name", "Видео")),
                            category = "VPortal",
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
                body.startsWith("[") -> JSONArray(body)
                rootObj != null -> rootObj.optJSONArray("items")
                    ?: rootObj.optJSONArray("data")
                    ?: rootObj.optJSONArray("movies")
                    ?: rootObj.optJSONArray("playlist")
                    ?: rootObj.optJSONArray("channels")
                    ?: JSONArray()
                else -> JSONArray()
            }

            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val itemType = item.optString("type", "").lowercase()
                val title = item.optString("title")
                    .ifEmpty { item.optString("name") }
                    .ifEmpty { item.optString("label") }
                    .ifEmpty { "Раздел #${i + 1}" }

                val poster = item.optString("poster")
                    .ifEmpty { item.optString("logo") }
                    .ifEmpty { item.optString("icon") }
                    .ifEmpty { item.optString("cover") }
                    .ifEmpty { item.optString("img") }
                    .ifEmpty { item.optString("image") }

                val desc = item.optString("description")
                    .ifEmpty { item.optString("plot") }
                    .ifEmpty { item.optString("info") }

                val year = item.optString("year", "")
                val rating = item.optString("rating", item.optString("rating_kp", item.optString("imdb", "")))

                val itemUrl = item.optString("url").takeIf { it.startsWith("http") } ?: rootUrl
                val directMedia = item.optString("stream_url")
                    .ifEmpty { item.optString("video_url") }
                    .ifEmpty { item.optString("file") }
                    .ifEmpty { item.optString("stream") }
                    .ifEmpty { item.optString("src") }

                val childReqObj = item.optJSONObject("request")

                val isMediaUrl = directMedia.startsWith("http") ||
                    itemUrl.endsWith(".m3u8", true) ||
                    itemUrl.endsWith(".mp4", true) ||
                    itemUrl.endsWith(".mkv", true) ||
                    itemUrl.endsWith(".ts", true) ||
                    (itemType in listOf("stream", "video", "file") && childReqObj == null && !itemUrl.contains("/api/v1"))

                if (isMediaUrl && childReqObj == null) {
                    val finalStream = directMedia.ifEmpty { itemUrl }
                    out.add(
                        VodMovie(
                            id = "vp_media_${i}_${System.nanoTime()}",
                            title = title,
                            category = "Видео",
                            posterUrl = poster.ifEmpty { null },
                            description = desc,
                            year = year,
                            rating = rating,
                            streamUrl = finalStream,
                            isFolder = false
                        )
                    )
                } else {
                    val mergedReq = JSONObject(rootReq.toString())
                    if (childReqObj != null) {
                        val keys = childReqObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            mergedReq.put(k, childReqObj.get(k))
                        }
                    } else {
                        for (field in listOf("id", "category_id", "cat_id", "category", "cat", "action", "page")) {
                            if (item.has(field)) {
                                mergedReq.put(field, item.get(field))
                            }
                        }
                    }

                    out.add(
                        VodMovie(
                            id = "vp_node_${i}_${System.nanoTime()}",
                            title = title,
                            category = if (itemType == "category") "Категория" else itemType.ifEmpty { "Папка" },
                            posterUrl = poster.ifEmpty { null },
                            description = desc,
                            year = year,
                            rating = rating,
                            streamUrl = "",
                            isFolder = true,
                            requestPayloadJson = mergedReq.toString(),
                            targetApiUrl = itemUrl
                        )
                    )
                }
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
                val b = r.body?.string() ?: ""
                token = JSONObject(b).optJSONObject("js")?.optString("token") ?: ""
            }

            if (token.isNotEmpty()) {
                val chReq = Request.Builder()
                    .url("${base}portal.php?type=itv&action=get_all_channels&JsHttpRequest=1-xml")
                    .header("Cookie", cookie)
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C) MAG200 stbapp")
                    .build()

                client.newCall(chReq).execute().use { r ->
                    val b = r.body?.string() ?: ""
                    val data = JSONObject(b).optJSONObject("js")?.optJSONArray("data") ?: JSONArray()
                    for (i in 0 until data.length()) {
                        val item = data.getJSONObject(i)
                        val rawCmd = item.optString("cmd").removePrefix("ffrt ").removePrefix("ffmpeg ").trim()
                        if (rawCmd.isNotEmpty()) {
                            list.add(
                                Channel(
                                    id = "stk_$i",
                                    number = i + 1,
                                    name = item.optString("name", "Channel ${i + 1}"),
                                    group = "Stalker TV",
                                    logoUrl = item.optString("logo"),
                                    streamUrl = rawCmd,
                                    epgId = item.optString("xmltv_id")
                                )
                            )
                        }
                    }
                }
            }
        }
        list
    }

    suspend fun loadEpgCurrentPrograms(epgUrl: String): Map<String, String> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, String>()
        val nowMs = System.currentTimeMillis()
        val sdf = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)

        runCatching {
            val req = Request.Builder().url(epgUrl).header("User-Agent", "Mozilla/5.0").build()
            client.newCall(req).execute().use { resp ->
                val rawStream = resp.body?.byteStream() ?: return@use
                val inputStream: InputStream = if (epgUrl.endsWith(".gz", true)) {
                    GZIPInputStream(rawStream)
                } else {
                    rawStream
                }

                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(inputStream, "UTF-8")

                var eventType = parser.eventType
                var currentChannelId: String? = null
                var isCurrentTimeMatch = false
                var insideTitle = false
                var count = 0

                while (eventType != XmlPullParser.END_DOCUMENT && count < 6000) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            if (parser.name == "programme") {
                                currentChannelId = parser.getAttributeValue(null, "channel")
                                val startStr = parser.getAttributeValue(null, "start")
                                val stopStr = parser.getAttributeValue(null, "stop")
                                isCurrentTimeMatch = runCatching {
                                    val s = sdf.parse(startStr ?: "")?.time ?: 0L
                                    val e = sdf.parse(stopStr ?: "")?.time ?: Long.MAX_VALUE
                                    nowMs in s..e
                                }.getOrDefault(false)
                            } else if (parser.name == "title" && currentChannelId != null) {
                                insideTitle = true
                            }
                        }
                        XmlPullParser.TEXT -> {
                            if (insideTitle && currentChannelId != null) {
                                val titleText = parser.text
                                if (isCurrentTimeMatch || !result.containsKey(currentChannelId!!)) {
                                    result[currentChannelId!!] = titleText
                                    if (isCurrentTimeMatch) count++
                                }
                                insideTitle = false
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (parser.name == "programme") {
                                currentChannelId = null
                                insideTitle = false
                            }
                        }
                    }
                    eventType = parser.next()
                }
            }
        }
        result
    }
}

fun getDeviceLocalIp(): String {
    runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        for (intf in interfaces) {
            for (addr in intf.inetAddresses) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    return addr.hostAddress ?: "127.0.0.1"
                }
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
                    URLDecoder.decode(p.getOrNull(0) ?: "", "UTF-8") to
                        URLDecoder.decode(p.getOrNull(1) ?: "", "UTF-8").trim()
                }
                onReceived(map)
                val ok = if (isRu) "✅ Отправлено в CCTV Plus!" else "✅ Sent to CCTV Plus!"
                sendHtml(out, "<html><body style='background:#121417;color:#10B981;font-family:sans-serif;text-align:center;padding:40px;'><h2>$ok</h2></body></html>")
            } else {
                sendHtml(out, buildHtmlForm())
            }
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
