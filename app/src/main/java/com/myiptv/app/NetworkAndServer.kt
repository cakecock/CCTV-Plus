package com.myiptv.app

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
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
    private val vportalRegex = Regex("^portal::\\[key:([^\\]]+)\\](https?://.+)$", RegexOption.IGNORE_CASE)

    suspend fun loadM3u(url: String): M3uParseResult = withContext(Dispatchers.IO) {
        val channels = mutableListOf<Channel>()
        val movies = mutableListOf<VodMovie>()
        var embeddedEpgUrl: String? = null

        runCatching {
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
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

    suspend fun loadVPortal(rawUrl: String): Pair<List<VodMovie>, String> = withContext(Dispatchers.IO) {
        val movies = mutableListOf<VodMovie>()
        val logBuilder = StringBuilder()

        runCatching {
            val match = vportalRegex.find(rawUrl.trim())
            val key = match?.groupValues?.get(1)?.trim() ?: ""
            var baseUrl = match?.groupValues?.get(2)?.trim() ?: rawUrl.trim()
            if (!baseUrl.endsWith("/")) baseUrl += "/"

            val ottUa = "Mozilla/5.0 (SmartHub; SMART-TV; U; Linux/SmartTV) AppleWebKit/531.2+ OttPlayer/2.0"
            val jsonPayload = "{\"key\":\"" + key + "\"}"

            val probes = listOf(
                "GET ?key" to Request.Builder()
                    .url("${baseUrl}?key=$key")
                    .header("User-Agent", ottUa)
                    .header("key", key)
                    .header("X-Api-Key", key)
                    .build(),
                "GET Base+Headers" to Request.Builder()
                    .url(baseUrl)
                    .header("User-Agent", ottUa)
                    .header("key", key)
                    .header("X-Api-Key", key)
                    .header("Authorization", "Bearer $key")
                    .header("Cookie", "key=$key")
                    .build(),
                "POST Form" to Request.Builder()
                    .url(baseUrl)
                    .header("User-Agent", ottUa)
                    .header("key", key)
                    .post(FormBody.Builder().add("key", key).build())
                    .build(),
                "POST JSON" to Request.Builder()
                    .url(baseUrl)
                    .header("User-Agent", ottUa)
                    .header("key", key)
                    .post(jsonPayload.toRequestBody("application/json".toMediaType()))
                    .build()
            )

            for ((probeName, request) in probes) {
                runCatching {
                    client.newCall(request).execute().use { resp ->
                        val body = resp.body?.string()?.trim() ?: ""
                        val preview = body.take(150).replace("\n", " ")
                        logBuilder.append("[$probeName -> HTTP ${resp.code}: $preview]\n")
                        if (resp.isSuccessful && body.isNotEmpty()) {
                            parseVPortalResponse(body, baseUrl, key, ottUa, movies)
                        }
                    }
                }.onFailure { err ->
                    logBuilder.append("[$probeName -> Err: ${err.localizedMessage}]\n")
                }
                if (movies.isNotEmpty()) break
            }
        }.onFailure { e ->
            logBuilder.append("Error: ${e.localizedMessage}")
        }

        movies to logBuilder.toString().trim()
    }

    private fun parseVPortalResponse(
        body: String,
        baseUrl: String,
        key: String,
        ua: String,
        out: MutableList<VodMovie>
    ) {
        if (body.startsWith("#EXTM3U")) {
            var title = ""
            var group = "VPortal"
            var poster: String? = null
            var idx = 1
            for (l in body.lines()) {
                val t = l.trim()
                if (t.startsWith("#EXTINF:")) {
                    title = t.substringAfterLast(",").trim()
                    val attrs = attrRegex.findAll(t).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    poster = attrs["tvg-logo"]
                    group = attrs["group-title"] ?: group
                } else if (t.startsWith("http")) {
                    out.add(VodMovie("vp_$idx", title.ifEmpty { "Фильм $idx" }, group, poster, "", "", "", t))
                    idx++
                }
            }
            return
        }

        runCatching {
            val arr = if (body.startsWith("[")) {
                JSONArray(body)
            } else {
                val obj = JSONObject(body)
                obj.optJSONArray("data")
                    ?: obj.optJSONArray("items")
                    ?: obj.optJSONArray("movies")
                    ?: obj.optJSONArray("categories")
                    ?: obj.optJSONArray("result")
                    ?: obj.optJSONArray("playlist")
                    ?: JSONArray()
            }

            val categoryIds = mutableListOf<Pair<String, String>>()

            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val stream = item.optString("url")
                    .ifEmpty { item.optString("stream_url") }
                    .ifEmpty { item.optString("video_url") }
                    .ifEmpty { item.optString("file") }
                    .ifEmpty { item.optString("cmd") }

                val title = item.optString("name")
                    .ifEmpty { item.optString("title") }
                    .ifEmpty { "Видео #${i + 1}" }

                if (stream.isNotEmpty() && (stream.startsWith("http") || stream.contains(".m3u8") || stream.contains(".mp4"))) {
                    out.add(
                        VodMovie(
                            id = "vp_json_${out.size + 1}",
                            title = title,
                            category = item.optString("category", item.optString("genre", "Кинозал")),
                            posterUrl = item.optString("poster", item.optString("logo", item.optString("cover"))),
                            description = item.optString("description", item.optString("plot", "")),
                            year = item.optString("year", ""),
                            rating = item.optString("rating", item.optString("rating_kp", "")),
                            streamUrl = stream
                        )
                    )
                } else {
                    val catId = item.optString("id").ifEmpty { item.optString("category_id") }
                    if (catId.isNotEmpty() && categoryIds.size < 5) {
                        categoryIds.add(catId to title)
                    }
                }
            }

            if (out.isEmpty() && categoryIds.isNotEmpty()) {
                for ((catId, catName) in categoryIds) {
                    val catUrl = "${baseUrl}?key=$key&category_id=$catId&cat=$catId&id=$catId"
                    val req = Request.Builder().url(catUrl).header("User-Agent", ua).header("key", key).build()
                    runCatching {
                        client.newCall(req).execute().use { r ->
                            val subBody = r.body?.string()?.trim() ?: ""
                            if (r.isSuccessful && subBody.isNotEmpty()) {
                                val subArr = if (subBody.startsWith("[")) JSONArray(subBody)
                                else JSONObject(subBody).optJSONArray("data")
                                    ?: JSONObject(subBody).optJSONArray("items") ?: JSONArray()
                                for (j in 0 until subArr.length()) {
                                    val m = subArr.optJSONObject(j) ?: continue
                                    val sUrl = m.optString("url").ifEmpty { m.optString("stream_url") }.ifEmpty { m.optString("file") }
                                    if (sUrl.isNotEmpty()) {
                                        out.add(
                                            VodMovie(
                                                id = "vp_sub_${out.size + 1}",
                                                title = m.optString("name", m.optString("title", "Фильм")),
                                                category = catName,
                                                posterUrl = m.optString("poster", m.optString("cover")),
                                                description = m.optString("description"),
                                                year = m.optString("year"),
                                                rating = m.optString("rating"),
                                                streamUrl = sUrl
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
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
