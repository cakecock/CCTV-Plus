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
    val userAgent: String? = null
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
    DARK_EMERALD(
        id = "dark_emerald",
        titleRu = "🌑 Тёмная: Графит и Изумруд",
        titleEn = "🌑 Dark: Obsidian & Emerald",
        isDark = true,
        bg = Color(0xFF121417),
        card = Color(0xFF1C2026),
        selectedCard = Color(0xFF19322B),
        primary = Color(0xFF10B981),
        onPrimary = Color.Black,
        text = Color(0xFFF3F4F6),
        subtext = Color(0xFF9CA3AF)
    ),
    DARK_AMETHYST(
        id = "dark_amethyst",
        titleRu = "🌌 Тёмная: Королевский Аметист",
        titleEn = "🌌 Dark: Royal Amethyst & Gold",
        isDark = true,
        bg = Color(0xFF16121E),
        card = Color(0xFF231D30),
        selectedCard = Color(0xFF35284D),
        primary = Color(0xFFF59E0B),
        onPrimary = Color.Black,
        text = Color(0xFFFAF5FF),
        subtext = Color(0xFFB8A9C9)
    ),
    LIGHT_NORDIC(
        id = "light_nordic",
        titleRu = "☀️ Светлая: Скандинавское Утро",
        titleEn = "☀️ Light: Nordic Ocean",
        isDark = false,
        bg = Color(0xFFF1F5F9),
        card = Color(0xFFFFFFFF),
        selectedCard = Color(0xFFDBEAFE),
        primary = Color(0xFF2563EB),
        onPrimary = Color.White,
        text = Color(0xFF0F172A),
        subtext = Color(0xFF64748B)
    ),
    LIGHT_LATTE(
        id = "light_latte",
        titleRu = "🌅 Светлая: Тёплый Латте",
        titleEn = "🌅 Light: Warm Sand & Terracotta",
        isDark = false,
        bg = Color(0xFFFAF6F0),
        card = Color(0xFFFFFFFF),
        selectedCard = Color(0xFFFFEDD5),
        primary = Color(0xFFEA580C),
        onPrimary = Color.White,
        text = Color(0xFF292524),
        subtext = Color(0xFF78716C)
    );

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
    val catPlaylists: String,
    val catEpg: String,
    val catPortals: String,
    val catQr: String,
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
    catAppearance = "🎨 Вид, Темы и Язык",
    catPlaylists = "📺 Плейлисты (M3U / M3U8)",
    catEpg = "📅 Телепрограмма (EPG)",
    catPortals = "🎬 Порталы (VPortal и Stalker)",
    catQr = "📱 QR-Пульт (Ввод с телефона)",
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
    catAppearance = "🎨 Theme & Language",
    catPlaylists = "📺 Playlists (M3U / M3U8)",
    catEpg = "📅 TV Guide (EPG)",
    catPortals = "🎬 Portals (VPortal & Stalker)",
    catQr = "📱 QR Remote Loader",
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

fun loadNamedList(
    prefs: SharedPreferences,
    jsonKey: String,
    legacySingleKey: String,
    defaultName: String
): List<NamedSource> {
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
        o.put("id", item.id)
        o.put("name", item.name)
        o.put("url", item.url)
        arr.put(o)
    }
    prefs.edit().putString(jsonKey, arr.toString()).apply()
}
