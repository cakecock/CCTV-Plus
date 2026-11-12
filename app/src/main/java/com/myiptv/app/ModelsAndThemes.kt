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
    val catchupDays: Int = 3,
    val catchupSource: String? = null
)

data class EpgProgramItem(
    val title: String,
    val startMs: Long,
    val stopMs: Long,
    val timeRangeText: String
)

data class PlayerSettingsConfig(
    val resizeMode: Int = 0, // 0 = Fit, 1 = Fill (Растянуть), 2 = Zoom (Обрезка)
    val bufferMode: Int = 1, // 0 = Быстрый (2с), 1 = Средний (5с), 2 = Большой (12с)
    val defaultUserAgent: String = "Lavf/60.3.100",
    val openFullscreenOnTap: Boolean = false
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
            "📅 Добавлено интерактивное окно «Телепрограмма и Архив (Catch-Up)»: просмотр расписания канала и запуск прошедших передач в записи одним нажатием.",
            "🔍 Улучшена привязка телепрограммы (EPG): автоматическое сопоставление каналов как по ID, так и по названию канала.",
            "⚙️ Добавлен раздел «Настройки видеоплеера»: выбор формата экрана (По размеру / Растянуть 16:9 / Увеличить), размер буфера (Быстрый / Средний / Большой), выбор User-Agent и кнопка пропорций прямо в полноэкранном плеере.",
            "🔢 Полная синхронизация номера версии между плашкой разработчика и верхним пунктом Чейнжлога."
        ),
        changesEn = listOf(
            "📅 Added interactive TV Guide & Catch-Up Archive window: browse channel schedule and play past broadcasts.",
            "🔍 Improved EPG matching by both channel ID and display name.",
            "⚙️ Added Video Player Settings: Aspect Ratio (Fit / Fill / Zoom), Buffer size, User-Agent, and quick aspect ratio button in fullscreen.",
            "🔢 Synchronized version number between the developer badge and Changelog."
        )
    ),
    ChangelogRelease(
        version = "v1.24",
        date = "12.11.2026",
        changesRu = listOf(
            "🖼 Новая фирменная векторная иконка приложения CCTV Plus.",
            "🎨 Палитра расширена до 10 тем оформления (5 Тёмных и 5 Светлых)."
        ),
        changesEn = listOf(
            "🖼 New custom vector app icon for CCTV Plus.",
            "🎨 Expanded to 10 color themes (5 Dark & 5 Light)."
        )
    ),
    ChangelogRelease(
        version = "v1.23",
        date = "12.11.2026",
        changesRu = listOf(
            "🏷 В нижней части Настроек добавлена карточка с подписью разработчика (cakecock) и номером версии.",
            "📊 Добавлена полоска состояния скачивания обновлений (0% - 100% + МБ) и меню Импорта/Экспорта настроек."
        ),
        changesEn = listOf(
            "🏷 Added developer signature (cakecock) and version badge in Settings.",
            "📊 Added update download progress bar (0% - 100%) and Backup Import/Export."
        )
    )
)

// Формирование ссылки на архивную передачу (Catch-Up / Timeshift)
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
    DARK_RUBY("dark_ruby", "🎬 Тёмная 3: Кинозал Рубин", "🎬 Dark 3: Cinema Ruby", true, Color(0xFF0F0F12), Color(0xFF1A1A22), Color(0xFF3B1520), Color(0xFFE11D48), Color.White, Color(0xFFF8FAFC), Color(0xFF94A3B8)),
    DARK_OCEAN("dark_ocean", "🌊 Тёмная 4: Глубокий Океан", "🌊 Dark 4: Deep Ocean Sapphire", true, Color(0xFF0B132B), Color(0xFF1C2541), Color(0xFF1E3A5F), Color(0xFF38BDF8), Color.Black, Color(0xFFF0F9FF), Color(0xFF93C5FD)),
    DARK_MOCHA("dark_mocha", "☕ Тёмная 5: Тёплый Мокко", "☕ Dark 5: Warm Espresso Bronze", true, Color(0xFF181311), Color(0xFF261E1B), Color(0xFF3D2B22), Color(0xFFFB923C), Color.Black, Color(0xFFFFF7ED), Color(0xFFD6BCFA)),
    LIGHT_NORDIC("light_nordic", "☀️ Светлая 1: Скандинавское Утро", "☀️ Light 1: Nordic Ocean", false, Color(0xFFF1F5F9), Color(0xFFFFFFFF), Color(0xFFDBEAFE), Color(0xFF2563EB), Color.White, Color(0xFF0F172A), Color(0xFF64748B)),
    LIGHT_LATTE("light_latte", "🌅 Светлая 2: Тёплый Латте", "🌅 Light 2: Warm Sand & Terracotta", false, Color(0xFFFAF6F0), Color(0xFFFFFFFF), Color(0xFFFFEDD5), Color(0xFFEA580C), Color.White, Color(0xFF292524), Color(0xFF78716C)),
    LIGHT_MINT("light_mint", "🌿 Светлая 3: Мятный Сад", "🌿 Light 3: Mint & Forest", false, Color(0xFFECFDF5), Color(0xFFFFFFFF), Color(0xFFD1FAE5), Color(0xFF059669), Color.White, Color(0xFF064E3B), Color(0xFF047857)),
    LIGHT_SAKURA("light_sakura", "🌸 Светлая 4: Сакура и Роза", "🌸 Light 4: Sakura Blossom", false, Color(0xFFFFF1F2), Color(0xFFFFFFFF), Color(0xFFFFE4E6), Color(0xFFDB2777), Color.White, Color(0xFF4C0519), Color(0xFF9F1239)),
    LIGHT_LAVENDER("light_lavender", "💎 Светлая 5: Лавандовый Кварц", "💎 Light 5: Lavender Quartz", false, Color(0xFFF5F3FF), Color(0xFFFFFFFF), Color(0xFFEDE9FE), Color(0xFF7C3AED), Color.White, Color(0xFF2E1065), Color(0xFF6D28D9));

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
    val catAppearance: String,
    val catPlayer: String,
    val catPlaylists: String,
    val catEpg: String,
    val catPortals: String,
    val catQr: String,
    val catBackup: String,
    val catChangelog: String,
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
    catAppearance = "🎨 Вид, Темы и Язык (10 тем)",
    catPlayer = "⚙️ Настройки видеоплеера",
    catPlaylists = "📺 Плейлисты (M3U / M3U8)",
    catEpg = "📅 Телепрограмма (EPG)",
    catPortals = "🎬 Порталы (VPortal и Stalker)",
    catQr = "📱 QR-Пульт (Ввод с телефона)",
    catBackup = "💾 Импорт и Экспорт настроек",
    catChangelog = "📜 Список изменений (Changelog)",
    activePlaylistLabel = "Активный плейлист:",
    activeEpgLabel = "Активный источник EPG:",
    epgFromPlaylistOption = "🔄 Автоматически из плейлиста (url-tvg)",
    usePlaylistEpgSwitch = "Брать телепрограмму (EPG) из самого плейлиста",
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
    catAppearance = "🎨 Theme & Language (10 Themes)",
    catPlayer = "⚙️ Video Player Settings",
    catPlaylists = "📺 Playlists (M3U / M3U8)",
    catEpg = "📅 TV Guide (EPG)",
    catPortals = "🎬 Portals (VPortal & Stalker)",
    catQr = "📱 QR Remote Loader",
    catBackup = "💾 Backup (Import & Export)",
    catChangelog = "📜 Changelog (What's New)",
    activePlaylistLabel = "Active Playlist:",
    activeEpgLabel = "Active EPG Source:",
    epgFromPlaylistOption = "🔄 Auto from Playlist (url-tvg)",
    usePlaylistEpgSwitch = "Prefer EPG URL embedded inside M3U playlist",
    addSourceName = "Name (e.g. Main TV)",
    addSourceUrl = "URL (http://...)",
    addBtn = "➕ Add to list",
    saveAndReloadBtn = "🔄 Save & Reload All",
    copyLogBtn = "📋 Copy VPortal Diagnostic Log"
)

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
        .putStringSet("favs", favSet)
        .apply()
    true
}.getOrDefault(false)
