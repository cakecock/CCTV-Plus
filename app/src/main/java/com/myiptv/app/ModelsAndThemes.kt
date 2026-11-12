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

data class ChangelogRelease(
    val version: String,
    val date: String,
    val changesRu: List<String>,
    val changesEn: List<String>
)

val AppChangelogHistory = listOf(
    ChangelogRelease(
        version = "v1.24 (Текущая)",
        date = "12.11.2026",
        changesRu = listOf(
            "🖼 Новая фирменная векторная иконка приложения CCTV Plus (экран ТВ с неоновой рамкой и знаком Plus).",
            "🎨 Палитра расширена до 10 тем оформления: 5 Тёмных (добавлены Кинозал Рубин, Глубокий Океан, Тёплый Мокко) и 5 Светлых (добавлены Мятный Сад, Сакура и Роза, Лавандовый Кварц).",
            "📝 Обновлена документация проекта README.md."
        ),
        changesEn = listOf(
            "🖼 New custom vector app icon for CCTV Plus.",
            "🎨 Expanded to 10 color themes: 5 Dark (added Cinema Ruby, Deep Ocean, Warm Mocha) and 5 Light (added Mint Garden, Sakura Rose, Lavender Quartz).",
            "📝 Updated project README.md."
        )
    ),
    ChangelogRelease(
        version = "v1.23",
        date = "12.11.2026",
        changesRu = listOf(
            "🏷 В нижней части Настроек добавлена карточка с подписью разработчика (cakecock) и автоматическим номером текущей версии и сборки.",
            "🔄 В меню обновления теперь отображается текущая установленная версия приложения."
        ),
        changesEn = listOf(
            "🏷 Added developer signature (cakecock) and automatic version/build badge at the bottom of Settings.",
            "🔄 Added current installed version display in the Update menu."
        )
    ),
    ChangelogRelease(
        version = "v1.22",
        date = "12.11.2026",
        changesRu = listOf(
            "📊 Добавлена полоска состояния и точный процент скачивания обновлений (0% - 100% + МБ).",
            "🎬 Исправлен запуск серий и фильмов в VPortal (глубокий сканер видеопотоков).",
            "💾 Добавлено меню «Импорт и Экспорт настроек» (файл .json и буфер обмена).",
            "📜 Добавлено меню «Список изменений (Changelog)».",
            "🛡 Переход на чистовую сборку Release (V1+V2) для снижения срабатываний Play Защиты."
        ),
        changesEn = listOf(
            "📊 Added real-time progress bar and percentage (0% - 100% + MB) for updates.",
            "🎬 Fixed episode & movie playback in VPortal with deep stream scanner.",
            "💾 Added Backup Import & Export menu (.json file and clipboard).",
            "📜 Added in-app Changelog menu in Settings.",
            "🛡 Switched to signed Release build (V1+V2)."
        )
    ),
    ChangelogRelease(
        version = "v1.20",
        date = "12.11.2026",
        changesRu = listOf(
            "♾ Бесконечная автоподгрузка (+50 фильмов) при прокрутке Кинозала до конца.",
            "📅 Добавлена полоска загрузки и статус обновления EPG в Настройках.",
            "⭐ Категория «Избранное» перенесена внутрь полоски категорий телеканалов."
        ),
        changesEn = listOf(
            "♾ Infinite auto-scroll pagination (+50 movies) in VPortal VOD.",
            "📅 Added EPG loading progress bar and status in Settings.",
            "⭐ Moved Favorites into the dynamic channel categories bar."
        )
    )
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
    // === 5 ТЁМНЫХ ТЕМ ===
    DARK_EMERALD(
        id = "dark_emerald",
        titleRu = "🌑 Тёмная 1: Графит и Изумруд",
        titleEn = "🌑 Dark 1: Obsidian & Emerald",
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
        titleRu = "🌌 Тёмная 2: Королевский Аметист",
        titleEn = "🌌 Dark 2: Royal Amethyst & Gold",
        isDark = true,
        bg = Color(0xFF16121E),
        card = Color(0xFF231D30),
        selectedCard = Color(0xFF35284D),
        primary = Color(0xFFF59E0B),
        onPrimary = Color.Black,
        text = Color(0xFFFAF5FF),
        subtext = Color(0xFFB8A9C9)
    ),
    DARK_RUBY(
        id = "dark_ruby",
        titleRu = "🎬 Тёмная 3: Кинозал Рубин",
        titleEn = "🎬 Dark 3: Cinema Ruby",
        isDark = true,
        bg = Color(0xFF0F0F12),
        card = Color(0xFF1A1A22),
        selectedCard = Color(0xFF3B1520),
        primary = Color(0xFFE11D48),
        onPrimary = Color.White,
        text = Color(0xFFF8FAFC),
        subtext = Color(0xFF94A3B8)
    ),
    DARK_OCEAN(
        id = "dark_ocean",
        titleRu = "🌊 Тёмная 4: Глубокий Океан",
        titleEn = "🌊 Dark 4: Deep Ocean Sapphire",
        isDark = true,
        bg = Color(0xFF0B132B),
        card = Color(0xFF1C2541),
        selectedCard = Color(0xFF1E3A5F),
        primary = Color(0xFF38BDF8),
        onPrimary = Color.Black,
        text = Color(0xFFF0F9FF),
        subtext = Color(0xFF93C5FD)
    ),
    DARK_MOCHA(
        id = "dark_mocha",
        titleRu = "☕ Тёмная 5: Тёплый Мокко",
        titleEn = "☕ Dark 5: Warm Espresso Bronze",
        isDark = true,
        bg = Color(0xFF181311),
        card = Color(0xFF261E1B),
        selectedCard = Color(0xFF3D2B22),
        primary = Color(0xFFFB923C),
        onPrimary = Color.Black,
        text = Color(0xFFFFF7ED),
        subtext = Color(0xFFD6BCFA)
    ),

    // === 5 СВЕТЛЫХ ТЕМ ===
    LIGHT_NORDIC(
        id = "light_nordic",
        titleRu = "☀️ Светлая 1: Скандинавское Утро",
        titleEn = "☀️ Light 1: Nordic Ocean",
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
        titleRu = "🌅 Светлая 2: Тёплый Латте",
        titleEn = "🌅 Light 2: Warm Sand & Terracotta",
        isDark = false,
        bg = Color(0xFFFAF6F0),
        card = Color(0xFFFFFFFF),
        selectedCard = Color(0xFFFFEDD5),
        primary = Color(0xFFEA580C),
        onPrimary = Color.White,
        text = Color(0xFF292524),
        subtext = Color(0xFF78716C)
    ),
    LIGHT_MINT(
        id = "light_mint",
        titleRu = "🌿 Светлая 3: Мятный Сад",
        titleEn = "🌿 Light 3: Mint & Forest",
        isDark = false,
        bg = Color(0xFFECFDF5),
        card = Color(0xFFFFFFFF),
        selectedCard = Color(0xFFD1FAE5),
        primary = Color(0xFF059669),
        onPrimary = Color.White,
        text = Color(0xFF064E3B),
        subtext = Color(0xFF047857)
    ),
    LIGHT_SAKURA(
        id = "light_sakura",
        titleRu = "🌸 Светлая 4: Сакура и Роза",
        titleEn = "🌸 Light 4: Sakura Blossom",
        isDark = false,
        bg = Color(0xFFFFF1F2),
        card = Color(0xFFFFFFFF),
        selectedCard = Color(0xFFFFE4E6),
        primary = Color(0xFFDB2777),
        onPrimary = Color.White,
        text = Color(0xFF4C0519),
        subtext = Color(0xFF9F1239)
    ),
    LIGHT_LAVENDER(
        id = "light_lavender",
        titleRu = "💎 Светлая 5: Лавандовый Кварц",
        titleEn = "💎 Light 5: Lavender Quartz",
        isDark = false,
        bg = Color(0xFFF5F3FF),
        card = Color(0xFFFFFFFF),
        selectedCard = Color(0xFFEDE9FE),
        primary = Color(0xFF7C3AED),
        onPrimary = Color.White,
        text = Color(0xFF2E1065),
        subtext = Color(0xFF6D28D9)
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
        for (i in 0 until favArr.length()) {
            favSet.add(favArr.getString(i))
        }
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
        .putStringSet("favs", favSet)
        .apply()
    true
}.getOrDefault(false)
