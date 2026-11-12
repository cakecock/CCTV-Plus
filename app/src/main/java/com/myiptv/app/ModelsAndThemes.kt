package com.myiptv.app

import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

data class NamedSource(val id: String, val name: String, val url: String)

data class Channel(
    val id: String,
    val number: Int,
    val name: String,
    val group: String,
    val logoUrl: String?,
    val streamUrl: String,
    val epgId: String?,
    val userAgent: String? = null,
    val catchupDays: Int = 0,
    val catchupSource: String? = null
)

data class EpgProgramItem(
    val title: String,
    val startMs: Long,
    val stopMs: Long,
    val timeRangeText: String
)

data class VodMovie(
    val id: String,
    val title: String,
    val category: String,
    val posterUrl: String?,
    val description: String,
    val year: String,
    val rating: String,
    val streamUrl: String,
    val isFolder: Boolean = false,
    val requestPayloadJson: String? = null,
    val targetApiUrl: String? = null
)

data class M3uParseResult(
    val channels: List<Channel>,
    val movies: List<VodMovie>,
    val embeddedEpgUrl: String?
)

data class ChangelogRelease(
    val version: String,
    val date: String,
    val changesRu: List<String>,
    val changesEn: List<String>
)

val AppChangelogHistory = listOf(
    ChangelogRelease(
        version = "CURRENT",
        date = "12.11.2026",
        changesRu = listOf(
            "📺 Справа от мини-плеера кнопка «Программа / Архив» заменена на информативную кликабельную плашку с текущей передачей, временем эфира и полоской прогресса, а список каналов очищен от лишних значков.",
            "📊 Исправлено дергание временной полоски в прямом эфире: теперь в эфире отображается плавная шкала времени от начала текущей передачи, а в режиме Архива работает ползунок перемотки по всей записи.",
            "🕒 Добавлена настройка отображения компактных часов реального времени (Выкл / В левом верхнем углу / В правом верхнем углу).",
            "📅 Добавлена автоматическая резервная подгрузка телепрограммы (EPG), если в самом M3U-плейлисте не прописан тег url-tvg."
        ),
        changesEn = listOf(
            "📺 Replaced the 'EPG / Archive' button next to the mini-player with an interactive live program info card & progress bar, and cleaned up channel list rows.",
            "📊 Fixed live stream timeline jitter: Live TV now shows smooth elapsed/remaining broadcast progress, while Archive mode provides a full seek/rewind slider.",
            "🕒 Added real-time overlay clock setting (Off / Top-Left / Top-Right).",
            "📅 Added automatic fallback EPG loading when playlist lacks an embedded url-tvg tag."
        )
    ),
    ChangelogRelease(
        version = "v1.26",
        date = "12.11.2026",
        changesRu = listOf(
            "📡 Пункты «Плейлисты», «Телепрограмма», «Порталы» и «QR-Пульт» объединены в раздел «Источники и Контент» с выпадающим списком.",
            "📱 Выбор устройства (Смартфон / Телевизор) и управления (Сенсор / Пульт ДУ) + обновленные 10 тем оформления."
        ),
        changesEn = listOf(
            "📡 Grouped Playlists, EPG, Portals, and QR Remote into a unified 'Sources & Content' dropdown menu.",
            "📱 Device Type & Control Mode selection + 10 refined color themes."
        )
    ),
    ChangelogRelease(
        version = "v1.25",
        date = "12.11.2026",
        changesRu = listOf(
            "⚖️ Добавлены разделы «Лицензия», «Конфиденциальность» и «Сообщить о проблеме».",
            "📅 Интерактивное окно «Телепрограмма и Архив (Catch-Up)» и настройки видеоплеера."
        ),
        changesEn = listOf(
            "⚖️ Added License, Privacy Policy, and Report an Issue sections.",
            "📅 Interactive TV Guide & Catch-Up Archive window and player settings."
        )
    )
)

val LicenseItemsRu = listOf(
    "1. Назначение программы: Приложение «CCTV Plus» является исключительно программным медиаплеером (оболочкой для воспроизведения потокового видео) и предоставляется по принципу «как есть» (AS IS).",
    "2. Отсутствие встроенного контента: Разработчик (cakecock) НЕ предоставляет, НЕ хранит, НЕ ретранслирует и НЕ продает телеканалы, фильмы, сериалы или плейлисты. Приложение не содержит встроенных медиа-источников.",
    "3. Ответственность пользователя: Все ссылки на плейлисты (M3U/M3U8), порталы (VPortal, Stalker) и телепрограмму (EPG) добавляются пользователем самостоятельно.",
    "4. Компоненты с открытым кодом: В составе приложения используются библиотеки AndroidX Jetpack Compose, Media3 (ExoPlayer), OkHttp, Coil и ZXing (Apache License 2.0).",
    "5. Авторские права: © 2026 cakecock. Все права защищены."
)

val LicenseItemsEn = listOf(
    "1. Software Purpose: 'CCTV Plus' is strictly a media player application provided 'AS IS'.",
    "2. No Built-in Content: The developer (cakecock) does NOT provide, host, stream, or distribute any TV channels, movies, series, or playlists.",
    "3. User Responsibility: All playlist URLs, portal credentials, and EPG links are configured solely by the user.",
    "4. Open Source Licenses: Uses AndroidX Jetpack Compose, Media3 (ExoPlayer), OkHttp, Coil, and ZXing under Apache License 2.0.",
    "5. Copyright: © 2026 cakecock. All rights reserved."
)

val PrivacyItemsRu = listOf(
    "1. 100% локальное хранение данных: Все ваши настройки, ссылки на плейлисты, ключи доступа VPortal, MAC-адреса и список избранных каналов хранятся исключительно во внутренней памяти вашего устройства.",
    "2. Отсутствие слежки и рекламы: Приложение CCTV Plus НЕ собирает персональные данные, НЕ отслеживает историю просмотров и НЕ передает ваши ключи на сторонние серверы.",
    "3. Прямые сетевые соединения: Доступ к интернету используется только для прямого обращения к указанным вами серверам IPTV/EPG и к GitHub (github.com/cakecock) при проверке обновлений.",
    "4. Безопасность QR-Пульта: Встроенный веб-сервер быстрой настройки работает исключительно внутри вашей домашней сети Wi-Fi.",
    "5. Защита при отправке отчетов: При копировании тех. отчета ваши ключи подписки автоматически скрываются звездочками (***)."
)

val PrivacyItemsEn = listOf(
    "1. 100% Local Storage: All settings, playlists, VPortal keys, and favorites are stored exclusively on your device.",
    "2. Zero Tracking & Zero Ads: CCTV Plus collects NO personal data and contains NO advertising or analytics SDKs.",
    "3. Direct Network Connections: Connects only to your configured IPTV/EPG servers and GitHub for updates.",
    "4. QR Remote Security: Runs strictly within your local home Wi-Fi network.",
    "5. Safe Bug Reports: Private subscription keys are automatically masked with asterisks (***)."
)

fun buildArchiveStreamUrl(channel: Channel, startMs: Long, stopMs: Long): String {
    val startSec = startMs / 1000L
    val nowSec = System.currentTimeMillis() / 1000L
    val durationSec = ((stopMs - startMs) / 1000L).coerceAtLeast(60L)
    val base = channel.streamUrl

    if (!channel.catchupSource.isNullOrBlank()) {
        return channel.catchupSource
            .replace("\${start}", startSec.toString())
            .replace("\${timestamp}", nowSec.toString())
            .replace("\${offset}", (nowSec - startSec).toString())
            .replace("\${duration}", durationSec.toString())
            .replace("{utc}", startSec.toString())
            .replace("{lutc}", nowSec.toString())
    }

    val sep = if (base.contains("?")) "&" else "?"
    return "${base}${sep}utc=$startSec&lutc=$nowSec"
}

enum class AppThemePalette(
    val id: String,
    val titleRu: String,
    val titleEn: String,
    val isDark: Boolean,
    val bg: Color,
    val card: Color,
    val selectedCard: Color,
    val primary: Color,
    val onPrimary: Color,
    val text: Color,
    val subtext: Color
) {
    DARK_EMERALD("dark_emerald", "🌑 Тёмная 1: Графит и Изумруд", "🌑 Dark 1: Obsidian & Emerald", true, Color(0xFF121417), Color(0xFF1C2026), Color(0xFF19322B), Color(0xFF10B981), Color.Black, Color(0xFFF3F4F6), Color(0xFF9CA3AF)),
    DARK_AMETHYST("dark_amethyst", "🌌 Тёмная 2: Королевский Аметист", "🌌 Dark 2: Royal Amethyst & Gold", true, Color(0xFF16121E), Color(0xFF231D30), Color(0xFF35284D), Color(0xFFF59E0B), Color.Black, Color(0xFFFAF5FF), Color(0xFFB8A9C9)),
    DARK_OCEAN("dark_ocean", "🌊 Тёмная 3: Полночный Сапфир", "🌊 Dark 3: Midnight Sapphire", true, Color(0xFF0E141D), Color(0xFF172130), Color(0xFF1E324A), Color(0xFF38BDF8), Color(0xFF082F49), Color(0xFFF1F5F9), Color(0xFF94A3B8)),
    DARK_RUBY("dark_ruby", "🍷 Тёмная 4: Бархатный Рубин", "🍷 Dark 4: Velvet Ruby", true, Color(0xFF141115), Color(0xFF211B22), Color(0xFF381E28), Color(0xFFF43F5E), Color.White, Color(0xFFF8FAFC), Color(0xFFA89FA8)),
    DARK_MOCHA("dark_mocha", "☕ Тёмная 5: Кофейный Кварц", "☕ Dark 5: Espresso & Caramel", true, Color(0xFF141210), Color(0xFF211D1A), Color(0xFF332922), Color(0xFFE5A967), Color(0xFF1C130A), Color(0xFFF5F0EB), Color(0xFFA89F96)),
    LIGHT_NORDIC("light_nordic", "☀️ Светлая 1: Нордический Лёд", "☀️ Light 1: Nordic Ice", false, Color(0xFFE2E8F0), Color(0xFFF8FAFC), Color(0xFFDBEAFE), Color(0xFF0284C7), Color.White, Color(0xFF1E293B), Color(0xFF64748B)),
    LIGHT_LATTE("light_latte", "🌅 Светлая 2: Песочный Кашемир", "🌅 Light 2: Warm Cashmere", false, Color(0xFFEAE3DC), Color(0xFFFAF7F2), Color(0xFFF5E6D3), Color(0xFFC25E00), Color.White, Color(0xFF292524), Color(0xFF78716C)),
    LIGHT_MINT("light_mint", "🌿 Светлая 3: Шалфей и Эвкалипт", "🌿 Light 3: Sage & Eucalyptus", false, Color(0xFFDCE7E1), Color(0xFFF4F9F6), Color(0xFFCBE3D6), Color(0xFF0D9488), Color.White, Color(0xFF1E2925), Color(0xFF5C7067)),
    LIGHT_SAKURA("light_sakura", "🌸 Светлая 4: Пудровый Кварц", "🌸 Light 4: Rose Quartz", false, Color(0xFFEAE0E3), Color(0xFFFAF5F6), Color(0xFFF2D9E0), Color(0xFFBE185D), Color.White, Color(0xFF2D1E24), Color(0xFF7A636C)),
    LIGHT_LAVENDER("light_lavender", "💎 Светлая 5: Туманный Ирис", "💎 Light 5: Misty Iris", false, Color(0xFFE2E0EC), Color(0xFFF7F6FB), Color(0xFFDDD6FE), Color(0xFF6D28D9), Color.White, Color(0xFF221E30), Color(0xFF6B6680));

    companion object {
        fun fromId(id: String?): AppThemePalette = entries.find { it.id == id } ?: DARK_EMERALD
    }
}

data class Strings(
    val isRu: Boolean,
    val liveTv: String,
    val vod: String,
    val favorites: String,
    val settings: String,
    val allGroups: String,
    val searchHint: String,
    val prevChannel: String,
    val fullscreen: String,
    val noChannels: String,
    val goToSettings: String,
    val loading: String,
    val reconnecting: String,
    val catSourcesGroup: String,
    val catAppearance: String,
    val catPlayer: String,
    val catPlaylists: String,
    val catEpg: String,
    val catPortals: String,
    val catQr: String,
    val catBackup: String,
    val catChangelog: String,
    val catLicense: String,
    val catPrivacy: String,
    val catReportIssue: String,
    val activePlaylistLabel: String,
    val activeEpgLabel: String,
    val epgFromPlaylistOption: String,
    val usePlaylistEpgSwitch: String,
    val addSourceName: String,
    val addSourceUrl: String,
    val addBtn: String,
    val saveAndReloadBtn: String,
    val copyLogBtn: String
)

val RuStrings = Strings(
    isRu = true,
    liveTv = "Телеканалы",
    vod = "Кинозал",
    favorites = "Избранное",
    settings = "Настройки",
    allGroups = "Все категории",
    searchHint = "Поиск...",
    prevChannel = "Пред. канал",
    fullscreen = "На весь экран",
    noChannels = "Плейлист не выбран или пуст",
    goToSettings = "Перейти в Настройки",
    loading = "Загрузка данных...",
    reconnecting = "Поток прервался, переподключаемся...",
    catSourcesGroup = "📡 Источники и Контент (Плейлисты, EPG, Порталы, QR)",
    catAppearance = "🎨 Внешний вид, Темы и Язык",
    catPlayer = "⚙️ Плеер, Часы, Устройство и Управление",
    catPlaylists = "📺 Плейлисты (M3U / M3U8)",
    catEpg = "📅 Телепрограмма (EPG)",
    catPortals = "🎬 Порталы (VPortal и Stalker)",
    catQr = "📱 QR-Пульт (Ввод с телефона)",
    catBackup = "💾 Импорт и Экспорт настроек",
    catChangelog = "📜 Список изменений (Changelog)",
    catLicense = "⚖️ Лицензия и Условия",
    catPrivacy = "🔒 Конфиденциальность",
    catReportIssue = "🐞 Сообщить о проблеме",
    activePlaylistLabel = "Активный плейлист:",
    activeEpgLabel = "Активный источник EPG:",
    epgFromPlaylistOption = "🔄 Автоматически (из плейлиста / TVLider)",
    usePlaylistEpgSwitch = "Брать телепрограмму (EPG) автоматически",
    addSourceName = "Название (например: TVLider Основной)",
    addSourceUrl = "Ссылка (http://...)",
    addBtn = "➕ Добавить в список",
    saveAndReloadBtn = "🔄 Сохранить и перезагрузить источники",
    copyLogBtn = "📋 Скопировать диагностику VPortal"
)

val EnStrings = Strings(
    isRu = false,
    liveTv = "Live TV",
    vod = "Movies (VOD)",
    favorites = "Favorites",
    settings = "Settings",
    allGroups = "All Categories",
    searchHint = "Search...",
    prevChannel = "Recall",
    fullscreen = "Fullscreen",
    noChannels = "No playlist selected or empty",
    goToSettings = "Open Settings",
    loading = "Loading data...",
    reconnecting = "Stream dropped, reconnecting...",
    catSourcesGroup = "📡 Sources & Content (Playlists, EPG, Portals, QR)",
    catAppearance = "🎨 Appearance, Themes & Language",
    catPlayer = "⚙️ Player, Clock, Device & Controls",
    catPlaylists = "📺 Playlists (M3U / M3U8)",
    catEpg = "📅 TV Guide (EPG)",
    catPortals = "🎬 Portals (VPortal & Stalker)",
    catQr = "📱 QR Remote Loader",
    catBackup = "💾 Backup (Import & Export)",
    catChangelog = "📜 Changelog (What's New)",
    catLicense = "⚖️ License & Terms",
    catPrivacy = "🔒 Privacy Policy",
    catReportIssue = "🐞 Report an Issue",
    activePlaylistLabel = "Active Playlist:",
    activeEpgLabel = "Active EPG Source:",
    epgFromPlaylistOption = "🔄 Auto (from Playlist / Default)",
    usePlaylistEpgSwitch = "Load EPG automatically",
    addSourceName = "Name (e.g. Main TV)",
    addSourceUrl = "URL (http://...)",
    addBtn = "➕ Add to list",
    saveAndReloadBtn = "🔄 Save & Reload All",
    copyLogBtn = "📋 Copy VPortal Diagnostic Log"
)

fun maskSensitiveKeys(text: String): String {
    return text
        .replace(Regex("\\[key:[^\\]]+\\]"), "[key:***HIDDEN***]")
        .replace(Regex("\"key\"\\s*:\\s*\"[^\"]+\""), "\"key\":\"***HIDDEN***\"")
}

fun loadNamedList(prefs: SharedPreferences, jsonKey: String, legacySingleKey: String, defaultName: String): List<NamedSource> {
    val raw = prefs.getString(jsonKey, null)
    if (!raw.isNullOrBlank()) {
        val list = mutableListOf<NamedSource>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(NamedSource(o.optString("id"), o.optString("name"), o.optString("url")))
            }
        }
        if (list.isNotEmpty()) return list
    }
    val legacy = prefs.getString(legacySingleKey, "") ?: ""
    return if (legacy.isNotBlank()) listOf(NamedSource("legacy_1", defaultName, legacy)) else emptyList()
}

fun saveNamedList(prefs: SharedPreferences, jsonKey: String, list: List<NamedSource>) {
    val arr = JSONArray()
    list.forEach { item ->
        val o = JSONObject()
        o.put("id", item.id); o.put("name", item.name); o.put("url", item.url)
        arr.put(o)
    }
    prefs.edit().putString(jsonKey, arr.toString()).apply()
}

fun exportAllSettingsToJson(prefs: SharedPreferences): String {
    val root = JSONObject()
    root.put("app", "CCTV Plus")
    root.put("lang", prefs.getString("lang", "ru"))
    root.put("device_type", prefs.getString("device_type", "mobile"))
    root.put("control_mode", prefs.getString("control_mode", "touch"))
    root.put("clock_pos", prefs.getInt("clock_pos", 0))
    root.put("theme_id", prefs.getString("theme_id", AppThemePalette.DARK_EMERALD.id))
    root.put("playlists_json", prefs.getString("playlists_json", "[]"))
    root.put("selected_playlist_id", prefs.getString("selected_playlist_id", ""))
    root.put("epgs_json", prefs.getString("epgs_json", "[]"))
    root.put("selected_epg_id", prefs.getString("selected_epg_id", "AUTO_PLAYLIST"))
    root.put("use_playlist_epg", prefs.getBoolean("use_playlist_epg", true))
    root.put("vportal_url", prefs.getString("vportal_url", ""))
    root.put("stalker_url", prefs.getString("stalker_url", ""))
    root.put("stalker_mac", prefs.getString("stalker_mac", ""))
    root.put("player_resize", prefs.getInt("player_resize", 0))
    root.put("player_buffer", prefs.getInt("player_buffer", 1))
    root.put("player_ua", prefs.getString("player_ua", "Lavf/60.3.100"))
    root.put("player_auto_fs", prefs.getBoolean("player_auto_fs", false))
    val favArr = JSONArray()
    prefs.getStringSet("favs", emptySet())?.forEach { favArr.put(it) }
    root.put("favs", favArr)
    return root.toString(2)
}

fun importAllSettingsFromJson(prefs: SharedPreferences, jsonStr: String): Boolean = runCatching {
    val root = JSONObject(jsonStr.trim())
    val favSet = mutableSetOf<String>()
    val favArr = root.optJSONArray("favs")
    if (favArr != null) {
        for (i in 0 until favArr.length()) favSet.add(favArr.getString(i))
    }
    prefs.edit()
        .putBoolean("lang_selected", true)
        .putString("lang", root.optString("lang", "ru"))
        .putString("device_type", root.optString("device_type", "mobile"))
        .putString("control_mode", root.optString("control_mode", "touch"))
        .putInt("clock_pos", root.optInt("clock_pos", 0))
        .putString("theme_id", root.optString("theme_id", AppThemePalette.DARK_EMERALD.id))
        .putString("playlists_json", root.optString("playlists_json", "[]"))
        .putString("selected_playlist_id", root.optString("selected_playlist_id", ""))
        .putString("epgs_json", root.optString("epgs_json", "[]"))
        .putString("selected_epg_id", root.optString("selected_epg_id", "AUTO_PLAYLIST"))
        .putBoolean("use_playlist_epg", root.optBoolean("use_playlist_epg", true))
        .putString("vportal_url", root.optString("vportal_url", ""))
        .putString("stalker_url", root.optString("stalker_url", ""))
        .putString("stalker_mac", root.optString("stalker_mac", ""))
        .putInt("player_resize", root.optInt("player_resize", 0))
        .putInt("player_buffer", root.optInt("player_buffer", 1))
        .putString("player_ua", root.optString("player_ua", "Lavf/60.3.100"))
        .putBoolean("player_auto_fs", root.optBoolean("player_auto_fs", false))
        .putStringSet("favs", favSet)
        .apply()
    true
}.getOrDefault(false)
