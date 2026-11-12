package com.myiptv.app

import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// === РЕКВИЗИТЫ ДЛЯ ПОДДЕРЖКИ РАЗРАБОТЧИКА (впиши свои в кавычки!) ===
const val DONATE_CARD_NUMBER = "ВПИШИ_НОМЕР_КАРТЫ_СЮДА"
const val DONATE_USDT_TRC20 = "ВПИШИ_АДРЕС_USDT_TRC20"
const val DONATE_USDT_TON = "ВПИШИ_АДРЕС_USDT_TON"
const val DONATE_BTC_ADDRESS = "ВПИШИ_АДРЕС_BTC"

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

data class ProgramReminder(
    val id: String,
    val channelId: String,
    val channelName: String,
    val programTitle: String,
    val startMs: Long
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

data class VodProgressItem(
    val key: String,
    val title: String,
    val posterUrl: String?,
    val streamUrl: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
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
            "🚀 Крупное обновление CCTV Plus v2.0!",
            "🔊 Подключен встроенный декодер FFmpeg (воспроизведение звука AC3 / EAC3 / DTS без внешних плееров).",
            "🎞 Добавлен выбор качества видео прямо в плеере (4K / 1080p / 720p / Авто) для адаптивных потоков и VOD.",
            "⬛ Настоящий полный экран (скрытие системной шторки и панели навигации) + экран не гаснет при просмотре.",
            "⚡ Кэширование EPG и плейлистов в памяти устройства (мгновенный запуск за 1 сек) + сдвиг времени EPG (-12ч..+12ч) + загрузка локальных файлов M3U.",
            "🔦 Яркая подсветка фокуса для ТВ-пульта в меню + полная поддержка Stalker create_link и Stalker VOD.",
            "🎨 Единый аккуратный фон в списке тем + кнопка «Поддержать разработчика»."
        ),
        changesEn = listOf(
            "🚀 Major release CCTV Plus v2.0!",
            "🔊 Built-in FFmpeg audio decoder (AC3 / EAC3 / DTS support natively in ExoPlayer).",
            "🎞 Video Quality selector inside player (4K / 1080p / 720p / Auto).",
            "⬛ True Immersive Fullscreen mode (hides system bars) + Keep Screen On.",
            "⚡ Local EPG & M3U caching for instant startup + EPG time offset (-12h..+12h) + local M3U file picker.",
            "🔦 Visible TV D-Pad focus highlights across menus + Stalker create_link & Stalker VOD.",
            "🎨 Unified theme picker background + 'Support Developer' dialog."
        )
    ),
    ChangelogRelease(
        version = "v1.62",
        date = "12.11.2026",
        changesRu = listOf(
            "✨ Полная переработка меню Настроек и эргономики кнопок в плеере смартфона.",
            "🎮 ТВ-пульт (Вверх/Вниз/0-9), скрытие категорий, карточка фильма и напоминания EPG."
        ),
        changesEn = listOf(
            "✨ Complete Settings & smartphone player controls redesign.",
            "🎮 TV Remote zapping, hidden categories, VOD details, and EPG reminders."
        )
    )
)

fun formatTimeDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

fun isAdultCategoryOrChannel(text: String): Boolean {
    val low = text.lowercase()
    return low.contains("18+") || low.contains("взросл") || low.contains("эротик") ||
        low.contains("эрот") || low.contains("adult") || low.contains("xxx") || low.contains("erotic")
}

val LicenseItemsRu = listOf(
    "1. Назначение: CCTV Plus — это программный медиаплеер для воспроизведения видеопотоков («как есть»).",
    "2. Контент: Разработчик (cakecock) не предоставляет, не хранит и не продает телеканалы, фильмы или плейлисты.",
    "3. Ответственность: Все ссылки (M3U, VPortal, Stalker, EPG) пользователь добавляет самостоятельно и несет за них ответственность.",
    "4. Открытый код: Используются библиотеки AndroidX, Media3 (ExoPlayer), Jellyfin FFmpeg Decoder (GPL 3.0), OkHttp, Coil и ZXing (Apache 2.0).",
    "5. Авторские права: © 2026 cakecock."
)

val LicenseItemsEn = listOf(
    "1. Purpose: CCTV Plus is strictly a media player application provided 'AS IS'.",
    "2. Content: The developer (cakecock) does NOT provide, host, or distribute any channels, movies, or playlists.",
    "3. Responsibility: All links (M3U, VPortal, Stalker, EPG) are added solely by the user.",
    "4. Open Source: Uses AndroidX, Media3 (ExoPlayer), Jellyfin FFmpeg Decoder (GPL 3.0), OkHttp, Coil, and ZXing (Apache 2.0).",
    "5. Copyright: © 2026 cakecock."
)

val PrivacyItemsRu = listOf(
    "1. Локальное хранение: Все плейлисты, ключи порталов, кэш EPG, история просмотра и избранное хранятся только в памяти вашего устройства.",
    "2. Без слежки и рекламы: Приложение не собирает личные данные, не отслеживает просмотры и не содержит рекламы.",
    "3. Интернет: Используется только для подключения к вашим серверам ТВ/EPG и к GitHub при проверке обновлений.",
    "4. QR-загрузка: Веб-сервер работает только внутри вашей домашней Wi-Fi сети.",
    "5. Отчеты: При копировании отчета об ошибке ваши ключи подписки автоматически скрываются звездочками (***)."
)

val PrivacyItemsEn = listOf(
    "1. Local Storage: All playlists, portal keys, EPG cache, watch history, and favorites stay strictly on your device.",
    "2. No Tracking & Ads: Zero personal data collection, zero analytics, and zero ads.",
    "3. Network: Connects only to your configured IPTV/EPG servers and GitHub for updates.",
    "4. QR Loader: Runs strictly inside your local home Wi-Fi network.",
    "5. Bug Reports: Private subscription keys are automatically masked with asterisks (***)."
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
    DARK_EMERALD("dark_emerald", "🌑 Графит и Изумруд", "🌑 Obsidian & Emerald", true, Color(0xFF121417), Color(0xFF1C2026), Color(0xFF19322B), Color(0xFF10B981), Color.Black, Color(0xFFF3F4F6), Color(0xFF9CA3AF)),
    DARK_AMETHYST("dark_amethyst", "🌌 Королевский Аметист", "🌌 Royal Amethyst", true, Color(0xFF16121E), Color(0xFF231D30), Color(0xFF35284D), Color(0xFFF59E0B), Color.Black, Color(0xFFFAF5FF), Color(0xFFB8A9C9)),
    DARK_OCEAN("dark_ocean", "🌊 Полночный Сапфир", "🌊 Midnight Sapphire", true, Color(0xFF0E141D), Color(0xFF172130), Color(0xFF1E324A), Color(0xFF38BDF8), Color(0xFF082F49), Color(0xFFF1F5F9), Color(0xFF94A3B8)),
    DARK_RUBY("dark_ruby", "🍷 Бархатный Рубин", "🍷 Velvet Ruby", true, Color(0xFF141115), Color(0xFF211B22), Color(0xFF381E28), Color(0xFFF43F5E), Color.White, Color(0xFFF8FAFC), Color(0xFFA89FA8)),
    DARK_MOCHA("dark_mocha", "☕ Кофейный Кварц", "☕ Espresso & Caramel", true, Color(0xFF141210), Color(0xFF211D1A), Color(0xFF332922), Color(0xFFE5A967), Color(0xFF1C130A), Color(0xFFF5F0EB), Color(0xFFA89F96)),
    LIGHT_NORDIC("light_nordic", "☀️ Нордический Лёд", "☀️ Nordic Ice", false, Color(0xFFE2E8F0), Color(0xFFF8FAFC), Color(0xFFDBEAFE), Color(0xFF0284C7), Color.White, Color(0xFF1E293B), Color(0xFF64748B)),
    LIGHT_LATTE("light_latte", "🌅 Песочный Кашемир", "🌅 Warm Cashmere", false, Color(0xFFEAE3DC), Color(0xFFFAF7F2), Color(0xFFF5E6D3), Color(0xFFC25E00), Color.White, Color(0xFF292524), Color(0xFF78716C)),
    LIGHT_MINT("light_mint", "🌿 Шалфей и Эвкалипт", "🌿 Sage & Eucalyptus", false, Color(0xFFDCE7E1), Color(0xFFF4F9F6), Color(0xFFCBE3D6), Color(0xFF0D9488), Color.White, Color(0xFF1E2925), Color(0xFF5C7067)),
    LIGHT_SAKURA("light_sakura", "🌸 Пудровый Кварц", "🌸 Rose Quartz", false, Color(0xFFEAE0E3), Color(0xFFFAF5F6), Color(0xFFF2D9E0), Color(0xFFBE185D), Color.White, Color(0xFF2D1E24), Color(0xFF7A636C)),
    LIGHT_LAVENDER("light_lavender", "💎 Туманный Ирис", "💎 Misty Iris", false, Color(0xFFE2E0EC), Color(0xFFF7F6FB), Color(0xFFDDD6FE), Color(0xFF6D28D9), Color.White, Color(0xFF221E30), Color(0xFF6B6680));

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
    isRu = true, liveTv = "Телеканалы", vod = "Кинозал", favorites = "Избранное", settings = "Настройки",
    allGroups = "Все категории", searchHint = "Поиск...", prevChannel = "Пред. канал", fullscreen = "На весь экран",
    noChannels = "Плейлист пуст или не выбран", goToSettings = "Открыть Настройки", loading = "Загрузка...", reconnecting = "Переподключение...",
    catSourcesGroup = "📡 Источники", catAppearance = "🎨 Тема и Язык", catPlayer = "🎬 Видеоплеер",
    catPlaylists = "📺 Плейлисты", catEpg = "📅 Телепрограмма", catPortals = "🎬 Порталы VOD", catQr = "📱 QR-загрузка",
    catBackup = "💾 Резервная копия", catChangelog = "📜 Что нового", catLicense = "⚖️ Лицензия", catPrivacy = "🔒 Конфиденциальность", catReportIssue = "🐞 Поддержка",
    activePlaylistLabel = "Текущий плейлист:", activeEpgLabel = "Источник EPG:", epgFromPlaylistOption = "🔄 Авто",
    usePlaylistEpgSwitch = "Автозагрузка EPG", addSourceName = "Название", addSourceUrl = "Ссылка (http://...)",
    addBtn = "➕ Добавить", saveAndReloadBtn = "💾 Сохранить и загрузить", copyLogBtn = "📋 Копировать лог"
)

val EnStrings = Strings(
    isRu = false, liveTv = "Live TV", vod = "Movies (VOD)", favorites = "Favorites", settings = "Settings",
    allGroups = "All Categories", searchHint = "Search...", prevChannel = "Recall", fullscreen = "Fullscreen",
    noChannels = "No playlist loaded", goToSettings = "Open Settings", loading = "Loading...", reconnecting = "Reconnecting...",
    catSourcesGroup = "📡 Sources", catAppearance = "🎨 Theme & Language", catPlayer = "🎬 Video Player",
    catPlaylists = "📺 Playlists", catEpg = "📅 TV Guide", catPortals = "🎬 VOD Portals", catQr = "📱 QR Loader",
    catBackup = "💾 Backup", catChangelog = "📜 Changelog", catLicense = "⚖️ License", catPrivacy = "🔒 Privacy", catReportIssue = "🐞 Support",
    activePlaylistLabel = "Active playlist:", activeEpgLabel = "EPG source:", epgFromPlaylistOption = "🔄 Auto",
    usePlaylistEpgSwitch = "Auto-load EPG", addSourceName = "Name", addSourceUrl = "URL (http://...)",
    addBtn = "➕ Add", saveAndReloadBtn = "💾 Save & Load", copyLogBtn = "📋 Copy log"
)

fun maskSensitiveKeys(text: String): String {
    return text
        .replace(Regex("\\[key:[^\\]]+\\]"), "[key:***HIDDEN***]")
        .replace(Regex("\"key\"\\s*:\\s*\"[^\"]+\""), "\"key\":\"***HIDDEN***\"")
}

fun loadHiddenGroups(prefs: SharedPreferences): Set<String> = prefs.getStringSet("hidden_groups", emptySet())?.toSet() ?: emptySet()
fun saveHiddenGroups(prefs: SharedPreferences, groups: Set<String>) { prefs.edit().putStringSet("hidden_groups", groups).apply() }

fun loadVodHistory(prefs: SharedPreferences): List<VodProgressItem> {
    val raw = prefs.getString("vod_history_json", null) ?: return emptyList()
    val list = mutableListOf<VodProgressItem>()
    runCatching {
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(VodProgressItem(o.optString("key"), o.optString("title"), o.optString("posterUrl").ifEmpty { null }, o.optString("streamUrl"), o.optLong("positionMs", 0L), o.optLong("durationMs", 0L), o.optLong("updatedAt", 0L)))
        }
    }
    return list.sortedByDescending { it.updatedAt }
}

fun saveVodProgressItem(prefs: SharedPreferences, item: VodProgressItem): List<VodProgressItem> {
    val current = loadVodHistory(prefs).filterNot { it.key == item.key || it.streamUrl == item.streamUrl }.toMutableList()
    current.add(0, item)
    val trimmed = current.take(20)
    val arr = JSONArray()
    trimmed.forEach { v ->
        val o = JSONObject()
        o.put("key", v.key); o.put("title", v.title); o.put("posterUrl", v.posterUrl ?: "")
        o.put("streamUrl", v.streamUrl); o.put("positionMs", v.positionMs); o.put("durationMs", v.durationMs); o.put("updatedAt", v.updatedAt)
        arr.put(o)
    }
    prefs.edit().putString("vod_history_json", arr.toString()).apply()
    return trimmed
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
    root.put("player_engine", prefs.getInt("player_engine", 0))
    root.put("archive_days", prefs.getInt("archive_days", 3))
    root.put("epg_offset_hours", prefs.getInt("epg_offset_hours", 0))
    root.put("parental_enabled", prefs.getBoolean("parental_enabled", false))
    root.put("parental_pin", prefs.getString("parental_pin", "0000"))
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
    val favArr = JSONArray(); prefs.getStringSet("favs", emptySet())?.forEach { favArr.put(it) }; root.put("favs", favArr)
    val hidArr = JSONArray(); prefs.getStringSet("hidden_groups", emptySet())?.forEach { hidArr.put(it) }; root.put("hidden_groups", hidArr)
    return root.toString(2)
}

fun importAllSettingsFromJson(prefs: SharedPreferences, jsonStr: String): Boolean = runCatching {
    val root = JSONObject(jsonStr.trim())
    val favSet = mutableSetOf<String>()
    root.optJSONArray("favs")?.let { arr -> for (i in 0 until arr.length()) favSet.add(arr.getString(i)) }
    val hidSet = mutableSetOf<String>()
    root.optJSONArray("hidden_groups")?.let { arr -> for (i in 0 until arr.length()) hidSet.add(arr.getString(i)) }
    prefs.edit()
        .putBoolean("lang_selected", true)
        .putString("lang", root.optString("lang", "ru"))
        .putString("device_type", root.optString("device_type", "mobile"))
        .putString("control_mode", root.optString("control_mode", "touch"))
        .putInt("clock_pos", root.optInt("clock_pos", 0))
        .putInt("player_engine", root.optInt("player_engine", 0))
        .putInt("archive_days", root.optInt("archive_days", 3))
        .putInt("epg_offset_hours", root.optInt("epg_offset_hours", 0))
        .putBoolean("parental_enabled", root.optBoolean("parental_enabled", false))
        .putString("parental_pin", root.optString("parental_pin", "0000"))
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
        .putStringSet("hidden_groups", hidSet)
        .apply()
    true
}.getOrDefault(false)
