@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.myiptv.app

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val FAV_GROUP_TAG = "__FAV_CATEGORY__"

fun findChannelSchedule(ch: Channel, epgMap: Map<String, List<EpgProgramItem>>): List<EpgProgramItem> {
    if (epgMap.isEmpty()) return emptyList()
    ch.epgId?.let { id ->
        epgMap[id]?.let { return it }
        epgMap[id.lowercase()]?.let { return it }
    }
    val lowName = ch.name.trim().lowercase()
    epgMap[lowName]?.let { return it }
    val cleanName = lowName.replace(" hd", "").replace(" fhd", "").replace(" 4k", "").replace(" orig", "").trim()
    return epgMap[cleanName] ?: emptyList()
}

class MainActivity : ComponentActivity() {
    private var webServer: SimpleQrWebServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cctv_plus_prefs", Context.MODE_PRIVATE)

        setContent {
            var isFirstLaunch by remember { mutableStateOf(!prefs.contains("lang_selected")) }
            var isRu by remember { mutableStateOf(prefs.getString("lang", "ru") == "ru") }
            var deviceType by remember { mutableStateOf(prefs.getString("device_type", "mobile") ?: "mobile") }
            var controlMode by remember { mutableStateOf(prefs.getString("control_mode", "touch") ?: "touch") }
            var themePalette by remember {
                mutableStateOf(AppThemePalette.fromId(prefs.getString("theme_id", AppThemePalette.DARK_EMERALD.id)))
            }
            val S = if (isRu) RuStrings else EnStrings

            var playerResizeMode by remember { mutableStateOf(prefs.getInt("player_resize", 0)) }
            var playerBufferMode by remember { mutableStateOf(prefs.getInt("player_buffer", 1)) }
            var playerDefaultUa by remember { mutableStateOf(prefs.getString("player_ua", "Lavf/60.3.100") ?: "Lavf/60.3.100") }
            var openFullscreenOnTap by remember { mutableStateOf(prefs.getBoolean("player_auto_fs", false)) }

            var playlists by remember { mutableStateOf(loadNamedList(prefs, "playlists_json", "m3u_url", "Основной плейлист")) }
            var selectedPlaylistId by remember { mutableStateOf(prefs.getString("selected_playlist_id", playlists.firstOrNull()?.id ?: "") ?: "") }

            var epgSources by remember { mutableStateOf(loadNamedList(prefs, "epgs_json", "epg_url", "Моя программа EPG")) }
            var usePlaylistEpg by remember { mutableStateOf(prefs.getBoolean("use_playlist_epg", true)) }
            var selectedEpgId by remember { mutableStateOf(prefs.getString("selected_epg_id", "AUTO_PLAYLIST") ?: "AUTO_PLAYLIST") }

            var vportalUrl by remember { mutableStateOf(prefs.getString("vportal_url", "") ?: "") }
            var stalkerUrl by remember { mutableStateOf(prefs.getString("stalker_url", "") ?: "") }
            var stalkerMac by remember { mutableStateOf(prefs.getString("stalker_mac", "") ?: "") }

            var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
            var vodItems by remember { mutableStateOf<List<VodMovie>>(emptyList()) }
            var vodFolderStack by remember { mutableStateOf<List<Pair<String, List<VodMovie>>>>(emptyList()) }
            var currentVodFolderTitle by remember { mutableStateOf("Кинозал VPortal") }
            var isLoadingMoreVod by remember { mutableStateOf(false) }
            var vodNoticeMessage by remember { mutableStateOf("") }

            var epgScheduleMap by remember { mutableStateOf<Map<String, List<EpgProgramItem>>>(emptyMap()) }
            var detectedPlaylistEpgUrl by remember { mutableStateOf<String?>(null) }
            var isEpgLoading by remember { mutableStateOf(false) }
            var epgStatusText by remember { mutableStateOf(if (isRu) "Ожидание загрузки EPG..." else "Waiting for EPG...") }

            var favoriteIds by remember { mutableStateOf(prefs.getStringSet("favs", emptySet())?.toSet() ?: emptySet()) }
            var currentTab by remember { mutableStateOf(0) }
            var isLoading by remember { mutableStateOf(false) }
            var vportalDiagnostic by remember { mutableStateOf("") }

            var currentChannel by remember { mutableStateOf<Channel?>(null) }
            var previousChannel by remember { mutableStateOf<Channel?>(null) }
            var activeArchiveStream by remember { mutableStateOf<Pair<String, String>?>(null) }
            var currentVodStream by remember { mutableStateOf<Pair<String, String>?>(null) }
            var isFullscreen by remember { mutableStateOf(false) }
            var epgDialogChannel by remember { mutableStateOf<Channel?>(null) }
            var showQuickChannelPicker by remember { mutableStateOf(false) }

            val scope = rememberCoroutineScope()

            fun persistLists() {
                saveNamedList(prefs, "playlists_json", playlists)
                saveNamedList(prefs, "epgs_json", epgSources)
                prefs.edit()
                    .putString("device_type", deviceType)
                    .putString("control_mode", controlMode)
                    .putString("selected_playlist_id", selectedPlaylistId)
                    .putString("selected_epg_id", selectedEpgId)
                    .putBoolean("use_playlist_epg", usePlaylistEpg)
                    .putString("vportal_url", vportalUrl.trim())
                    .putString("stalker_url", stalkerUrl.trim())
                    .putString("stalker_mac", stalkerMac.trim())
                    .putInt("player_resize", playerResizeMode)
                    .putInt("player_buffer", playerBufferMode)
                    .putString("player_ua", playerDefaultUa)
                    .putBoolean("player_auto_fs", openFullscreenOnTap)
                    .apply()
            }

            fun loadEpgWithStatus(embeddedOverride: String? = detectedPlaylistEpgUrl) {
                val epgUrlToLoad = when {
                    usePlaylistEpg && !embeddedOverride.isNullOrBlank() -> embeddedOverride
                    selectedEpgId == "AUTO_PLAYLIST" && !embeddedOverride.isNullOrBlank() -> embeddedOverride
                    else -> epgSources.find { it.id == selectedEpgId }?.url ?: epgSources.firstOrNull()?.url
                }
                if (epgUrlToLoad.isNullOrBlank()) {
                    isEpgLoading = false
                    epgStatusText = if (isRu) "⚠️ Ссылка на EPG не указана" else "⚠️ No EPG URL configured"
                    return
                }
                scope.launch {
                    isEpgLoading = true
                    epgStatusText = if (isRu) "⏳ Загрузка EPG и Архива: $epgUrlToLoad" else "⏳ Loading EPG: $epgUrlToLoad"
                    val loadedSchedule = NetworkLoader.loadFullEpgSchedule(epgUrlToLoad.trim())
                    epgScheduleMap = loadedSchedule
                    isEpgLoading = false
                    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    epgStatusText = if (loadedSchedule.isNotEmpty()) {
                        if (isRu) "✅ Обновлено в $timeStr (каналов с программой: ${loadedSchedule.size / 2})"
                        else "✅ Updated at $timeStr (${loadedSchedule.size / 2} channels)"
                    } else {
                        if (isRu) "⚠️ Завершено ($timeStr), но передач не найдено"
                        else "⚠️ Finished ($timeStr), no programs found"
                    }
                }
            }

            fun reloadAllData() {
                persistLists()
                scope.launch {
                    isLoading = true
                    val loadedChannels = mutableListOf<Channel>()
                    val loadedVod = mutableListOf<VodMovie>()
                    var embeddedEpg: String? = null

                    val activePlaylist = playlists.find { it.id == selectedPlaylistId } ?: playlists.firstOrNull()
                    if (activePlaylist != null && activePlaylist.url.isNotBlank()) {
                        val m3uRes = NetworkLoader.loadM3u(activePlaylist.url.trim())
                        loadedChannels.addAll(m3uRes.channels)
                        loadedVod.addAll(m3uRes.movies)
                        embeddedEpg = m3uRes.embeddedEpgUrl
                        detectedPlaylistEpgUrl = embeddedEpg
                    }

                    if (vportalUrl.isNotBlank()) {
                        val vpRes = NetworkLoader.loadVPortalNode(vportalUrl.trim())
                        loadedVod.addAll(vpRes.first)
                        vportalDiagnostic = vpRes.second
                        vodFolderStack = emptyList()
                        currentVodFolderTitle = if (isRu) "Кинозал VPortal" else "VPortal Root"
                    }

                    if (stalkerUrl.isNotBlank() && stalkerMac.isNotBlank()) {
                        loadedChannels.addAll(NetworkLoader.loadStalker(stalkerUrl.trim(), stalkerMac.trim()))
                    }

                    channels = loadedChannels
                    vodItems = loadedVod
                    if (currentChannel == null && loadedChannels.isNotEmpty()) currentChannel = loadedChannels.first()
                    isLoading = false
                    loadEpgWithStatus(embeddedEpg)
                }
            }

            fun refreshStateFromPrefs() {
                isRu = prefs.getString("lang", "ru") == "ru"
                deviceType = prefs.getString("device_type", "mobile") ?: "mobile"
                controlMode = prefs.getString("control_mode", "touch") ?: "touch"
                themePalette = AppThemePalette.fromId(prefs.getString("theme_id", AppThemePalette.DARK_EMERALD.id))
                playlists = loadNamedList(prefs, "playlists_json", "m3u_url", "Основной плейлист")
                selectedPlaylistId = prefs.getString("selected_playlist_id", playlists.firstOrNull()?.id ?: "") ?: ""
                epgSources = loadNamedList(prefs, "epgs_json", "epg_url", "Моя программа EPG")
                usePlaylistEpg = prefs.getBoolean("use_playlist_epg", true)
                selectedEpgId = prefs.getString("selected_epg_id", "AUTO_PLAYLIST") ?: "AUTO_PLAYLIST"
                vportalUrl = prefs.getString("vportal_url", "") ?: ""
                stalkerUrl = prefs.getString("stalker_url", "") ?: ""
                stalkerMac = prefs.getString("stalker_mac", "") ?: ""
                playerResizeMode = prefs.getInt("player_resize", 0)
                playerBufferMode = prefs.getInt("player_buffer", 1)
                playerDefaultUa = prefs.getString("player_ua", "Lavf/60.3.100") ?: "Lavf/60.3.100"
                openFullscreenOnTap = prefs.getBoolean("player_auto_fs", false)
                favoriteIds = prefs.getStringSet("favs", emptySet())?.toSet() ?: emptySet()
                reloadAllData()
            }

            fun loadMoreVodAutomatically(nextPageMarker: VodMovie) {
                if (isLoadingMoreVod || vportalUrl.isBlank()) return
                scope.launch {
                    isLoadingMoreVod = true
                    val res = NetworkLoader.loadVPortalNode(vportalUrl.trim(), nextPageMarker.targetApiUrl, nextPageMarker.requestPayloadJson)
                    vportalDiagnostic = res.second
                    vodItems = vodItems.filterNot { it.id.startsWith("vp_next_") } + res.first
                    isLoadingMoreVod = false
                }
            }

            fun openVodNode(item: VodMovie) {
                vodNoticeMessage = ""
                if (item.id.startsWith("vp_next_")) {
                    loadMoreVodAutomatically(item)
                    return
                }
                if (!item.isFolder && item.streamUrl.isNotBlank()) {
                    currentVodStream = item.title to item.streamUrl
                    isFullscreen = true
                    return
                }
                if (vportalUrl.isBlank()) return
                scope.launch {
                    isLoading = true
                    val res = NetworkLoader.loadVPortalNode(vportalUrl.trim(), item.targetApiUrl, item.requestPayloadJson)
                    vportalDiagnostic = res.second
                    val children = res.first
                    isLoading = false

                    if (children.size == 1 && !children.first().isFolder && children.first().streamUrl.isNotBlank()) {
                        currentVodStream = item.title to children.first().streamUrl
                        isFullscreen = true
                    } else if (children.isNotEmpty()) {
                        vodFolderStack = vodFolderStack + (currentVodFolderTitle to vodItems)
                        currentVodFolderTitle = item.title
                        vodItems = children
                    } else {
                        vodNoticeMessage = "Ответ сервера: $vportalDiagnostic"
                    }
                }
            }

            fun searchInVPortal(query: String) {
                if (vportalUrl.isBlank() || query.isBlank()) return
                scope.launch {
                    isLoading = true
                    val res = NetworkLoader.loadVPortalNode(vportalUrl.trim(), searchQuery = query.trim())
                    vportalDiagnostic = res.second
                    isLoading = false
                    if (res.first.isNotEmpty()) {
                        vodFolderStack = vodFolderStack + (currentVodFolderTitle to vodItems)
                        currentVodFolderTitle = "🔍 $query"
                        vodItems = res.first
                    }
                }
            }

            fun goBackInVod() {
                vodNoticeMessage = ""
                if (vodFolderStack.isNotEmpty()) {
                    val last = vodFolderStack.last()
                    currentVodFolderTitle = last.first
                    vodItems = last.second
                    vodFolderStack = vodFolderStack.dropLast(1)
                }
            }

            DisposableEffect(isRu) {
                webServer?.stop()
                webServer = SimpleQrWebServer(8989, isRu) { payload ->
                    scope.launch(Dispatchers.Main) {
                        val m3u = payload["m3u"].orEmpty()
                        val m3uName = payload["m3u_name"].orEmpty().ifEmpty { "Плейлист ${playlists.size + 1}" }
                        if (m3u.isNotBlank()) {
                            val newId = "pl_${System.currentTimeMillis()}"
                            playlists = playlists + NamedSource(newId, m3uName, m3u)
                            selectedPlaylistId = newId
                        }
                        val epg = payload["epg"].orEmpty()
                        val epgName = payload["epg_name"].orEmpty().ifEmpty { "EPG ${epgSources.size + 1}" }
                        if (epg.isNotBlank()) {
                            val newEpgId = "epg_${System.currentTimeMillis()}"
                            epgSources = epgSources + NamedSource(newEpgId, epgName, epg)
                            selectedEpgId = newEpgId
                        }
                        if (payload["vportal"]?.isNotBlank() == true) vportalUrl = payload["vportal"]!!
                        reloadAllData()
                        currentTab = 0
                    }
                }.also { it.start() }
                onDispose { webServer?.stop() }
            }

            LaunchedEffect(Unit) {
                if (!isFirstLaunch && (playlists.isNotEmpty() || vportalUrl.isNotBlank() || stalkerUrl.isNotBlank())) {
                    reloadAllData()
                }
            }

            val colorScheme = if (themePalette.isDark) {
                darkColorScheme(primary = themePalette.primary, onPrimary = themePalette.onPrimary, background = themePalette.bg, surface = themePalette.card, onBackground = themePalette.text, onSurface = themePalette.text)
            } else {
                lightColorScheme(primary = themePalette.primary, onPrimary = themePalette.onPrimary, background = themePalette.bg, surface = themePalette.card, onBackground = themePalette.text, onSurface = themePalette.text)
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = themePalette.bg) {
                    if (epgDialogChannel != null) {
                        val ch = epgDialogChannel!!
                        val schedule = remember(ch, epgScheduleMap) { findChannelSchedule(ch, epgScheduleMap) }
                        EpgAndArchiveDialog(
                            S = S,
                            theme = themePalette,
                            channel = ch,
                            schedule = schedule,
                            onDismiss = { epgDialogChannel = null },
                            onPlayArchive = { prog ->
                                currentChannel = ch
                                currentVodStream = null
                                activeArchiveStream = prog.title to buildArchiveStreamUrl(ch, prog.startMs, prog.stopMs)
                                epgDialogChannel = null
                            },
                            onPlayLive = {
                                currentChannel = ch
                                currentVodStream = null
                                activeArchiveStream = null
                                epgDialogChannel = null
                            }
                        )
                    }

                    if (showQuickChannelPicker && channels.isNotEmpty()) {
                        QuickChannelPickerDialog(
                            S = S,
                            theme = themePalette,
                            channels = channels,
                            currentChannel = currentChannel,
                            onDismiss = { showQuickChannelPicker = false },
                            onSelect = { ch ->
                                if (currentChannel != null && currentChannel?.id != ch.id) previousChannel = currentChannel
                                activeArchiveStream = null
                                currentChannel = ch
                                showQuickChannelPicker = false
                            }
                        )
                    }

                    if (isFirstLaunch) {
                        FirstLaunchSetupScreen(
                            theme = themePalette,
                            initialRu = isRu,
                            initialDevice = deviceType,
                            initialControl = controlMode,
                            onFinishSetup = { selRu, selDevice, selControl ->
                                isRu = selRu
                                deviceType = selDevice
                                controlMode = selControl
                                prefs.edit()
                                    .putBoolean("lang_selected", true)
                                    .putString("lang", if (selRu) "ru" else "en")
                                    .putString("device_type", selDevice)
                                    .putString("control_mode", selControl)
                                    .apply()
                                isFirstLaunch = false
                                currentTab = 2
                            }
                        )
                    } else if (isFullscreen && (currentChannel != null || currentVodStream != null)) {
                        BackHandler { isFullscreen = false }
                        val playUrl = currentVodStream?.second ?: activeArchiveStream?.second ?: currentChannel?.streamUrl ?: ""
                        val playTitle = currentVodStream?.first
                            ?: activeArchiveStream?.let { "⏪ Архив: ${it.first}" }
                            ?: currentChannel?.name ?: ""
                        val resizeLabels = if (isRu) listOf("По размеру", "16:9", "Zoom") else listOf("Fit", "16:9", "Zoom")
                        val curHasEpg = currentChannel != null && findChannelSchedule(currentChannel!!, epgScheduleMap).isNotEmpty()
                        val isSmartphone = deviceType == "mobile"

                        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                            SmartVideoPlayer(
                                streamUrl = playUrl,
                                userAgent = currentChannel?.userAgent ?: playerDefaultUa,
                                resizeMode = playerResizeMode,
                                bufferMode = playerBufferMode,
                                reconnectingText = S.reconnecting,
                                modifier = Modifier.fillMaxSize()
                            )
                            Row(
                                modifier = Modifier.align(Alignment.TopStart).padding(12.dp).background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(playTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))

                                OutlinedButton(
                                    onClick = {
                                        playerResizeMode = (playerResizeMode + 1) % 3
                                        prefs.edit().putInt("player_resize", playerResizeMode).apply()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("📐 ${resizeLabels[playerResizeMode]}", color = Color.White, fontSize = 12.sp)
                                }

                                // Кнопки Каналов и Архива в плеере — ТОЛЬКО НА СМАРТФОНЕ (на ТВ скрыты)
                                if (isSmartphone && currentChannel != null && currentVodStream == null) {
                                    OutlinedButton(
                                        onClick = { showQuickChannelPicker = true },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(if (isRu) "📋 Каналы" else "📋 Channels", color = Color(0xFF38BDF8), fontSize = 12.sp)
                                    }
                                    if (curHasEpg) {
                                        OutlinedButton(
                                            onClick = { epgDialogChannel = currentChannel },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            val txt = if (currentChannel!!.catchupDays > 0) (if (isRu) "⏪ Архив" else "⏪ Archive") else (if (isRu) "📅 Гид" else "📅 EPG")
                                            Text(txt, color = Color(0xFF10B981), fontSize = 12.sp)
                                        }
                                    }
                                }

                                Button(onClick = { isFullscreen = false }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                                    Text(if (isRu) "Свернуть" else "Exit", fontSize = 12.sp)
                                }
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            NavigationBar(containerColor = themePalette.card, modifier = Modifier.height(64.dp)) {
                                NavigationBarItem(selected = currentTab == 0, onClick = { currentTab = 0; currentVodStream = null }, icon = { Icon(Icons.Default.Tv, null) }, label = { Text(S.liveTv) })
                                NavigationBarItem(selected = currentTab == 1, onClick = { currentTab = 1 }, icon = { Icon(Icons.Default.Movie, null) }, label = { Text("${S.vod} (${vodItems.count { !it.id.startsWith("vp_next_") }})") })
                                NavigationBarItem(selected = currentTab == 2, onClick = { currentTab = 2 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text(S.settings) })
                            }

                            if (isLoading || isLoadingMoreVod) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = themePalette.primary)
                            }

                            when (currentTab) {
                                0 -> LiveTvScreen(
                                    S = S,
                                    theme = themePalette,
                                    playlists = playlists,
                                    selectedPlaylistId = selectedPlaylistId,
                                    onSelectPlaylistId = { selectedPlaylistId = it; reloadAllData() },
                                    channels = channels,
                                    epgScheduleMap = epgScheduleMap,
                                    favoriteIds = favoriteIds,
                                    currentChannel = currentChannel,
                                    previousChannel = previousChannel,
                                    activeArchiveStream = activeArchiveStream,
                                    playerResizeMode = playerResizeMode,
                                    playerBufferMode = playerBufferMode,
                                    playerDefaultUa = playerDefaultUa,
                                    onSelectChannel = { ch ->
                                        if (currentChannel != null && currentChannel?.id != ch.id) previousChannel = currentChannel
                                        currentVodStream = null
                                        activeArchiveStream = null
                                        currentChannel = ch
                                        if (openFullscreenOnTap) isFullscreen = true
                                    },
                                    onOpenEpgDialog = { ch -> epgDialogChannel = ch },
                                    onResetToLive = { activeArchiveStream = null },
                                    onToggleFavorite = { ch ->
                                        val next = favoriteIds.toMutableSet()
                                        if (!next.add(ch.id)) next.remove(ch.id)
                                        favoriteIds = next
                                        prefs.edit().putStringSet("favs", next).apply()
                                    },
                                    onFullscreen = { isFullscreen = true },
                                    onOpenSettings = { currentTab = 2 }
                                )
                                1 -> VodScreen(
                                    S = S,
                                    theme = themePalette,
                                    movies = vodItems,
                                    folderTitle = currentVodFolderTitle,
                                    canGoBack = vodFolderStack.isNotEmpty(),
                                    isLoadingMore = isLoadingMoreVod,
                                    noticeMessage = vodNoticeMessage,
                                    diagnosticLog = vportalDiagnostic,
                                    onBackFolder = { goBackInVod() },
                                    onClickItem = { openVodNode(it) },
                                    onAutoLoadMore = { loadMoreVodAutomatically(it) },
                                    onServerSearch = { searchInVPortal(it) },
                                    onOpenSettings = { currentTab = 2 }
                                )
                                2 -> AccordionSettingsScreen(
                                    S = S,
                                    prefs = prefs,
                                    theme = themePalette,
                                    deviceType = deviceType,
                                    controlMode = controlMode,
                                    onDeviceTypeChange = { deviceType = it; persistLists() },
                                    onControlModeChange = { controlMode = it; persistLists() },
                                    onSelectTheme = { themePalette = it; prefs.edit().putString("theme_id", it.id).apply() },
                                    onToggleLanguage = { isRu = !isRu; prefs.edit().putString("lang", if (isRu) "ru" else "en").apply() },
                                    playerResizeMode = playerResizeMode,
                                    playerBufferMode = playerBufferMode,
                                    playerDefaultUa = playerDefaultUa,
                                    openFullscreenOnTap = openFullscreenOnTap,
                                    onPlayerResizeChange = { playerResizeMode = it; persistLists() },
                                    onPlayerBufferChange = { playerBufferMode = it; persistLists() },
                                    onPlayerUaChange = { playerDefaultUa = it; persistLists() },
                                    onAutoFsChange = { openFullscreenOnTap = it; persistLists() },
                                    playlists = playlists,
                                    selectedPlaylistId = selectedPlaylistId,
                                    onSelectPlaylist = { selectedPlaylistId = it; reloadAllData() },
                                    onAddPlaylist = { name, url ->
                                        val newId = "pl_${System.currentTimeMillis()}"
                                        playlists = playlists + NamedSource(newId, name, url)
                                        selectedPlaylistId = newId
                                        reloadAllData()
                                    },
                                    onDeletePlaylist = { id ->
                                        playlists = playlists.filterNot { it.id == id }
                                        if (selectedPlaylistId == id) selectedPlaylistId = playlists.firstOrNull()?.id ?: ""
                                        persistLists()
                                    },
                                    epgSources = epgSources,
                                    selectedEpgId = selectedEpgId,
                                    usePlaylistEpg = usePlaylistEpg,
                                    detectedPlaylistEpgUrl = detectedPlaylistEpgUrl,
                                    isEpgLoading = isEpgLoading,
                                    epgStatusText = epgStatusText,
                                    onRefreshEpgNow = { persistLists(); loadEpgWithStatus() },
                                    onToggleUsePlaylistEpg = { usePlaylistEpg = it; persistLists(); loadEpgWithStatus() },
                                    onSelectEpg = { selectedEpgId = it; persistLists(); loadEpgWithStatus() },
                                    onAddEpg = { name, url ->
                                        val newId = "epg_${System.currentTimeMillis()}"
                                        epgSources = epgSources + NamedSource(newId, name, url)
                                        selectedEpgId = newId
                                        persistLists()
                                        loadEpgWithStatus()
                                    },
                                    onDeleteEpg = { id ->
                                        epgSources = epgSources.filterNot { it.id == id }
                                        if (selectedEpgId == id) selectedEpgId = "AUTO_PLAYLIST"
                                        persistLists()
                                    },
                                    vportalUrl = vportalUrl,
                                    stalkerUrl = stalkerUrl,
                                    stalkerMac = stalkerMac,
                                    vportalDiagnostic = vportalDiagnostic,
                                    onVportalChange = { vportalUrl = it },
                                    onStalkerUrlChange = { stalkerUrl = it },
                                    onStalkerMacChange = { stalkerMac = it },
                                    onSaveAndReload = { reloadAllData() },
                                    onSettingsImported = { refreshStateFromPrefs() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickChannelPickerDialog(
    S: Strings,
    theme: AppThemePalette,
    channels: List<Channel>,
    currentChannel: Channel?,
    onDismiss: () -> Unit,
    onSelect: (Channel) -> Unit
) {
    var q by remember { mutableStateOf("") }
    val filtered = remember(channels, q) {
        if (q.isBlank()) channels else channels.filter { it.name.contains(q, ignoreCase = true) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = theme.card), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (S.isRu) "📋 Быстрый выбор канала" else "📋 Quick Channel List", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = theme.primary)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null, tint = theme.text) }
                }
                OutlinedTextField(value = q, onValueChange = { q = it }, placeholder = { Text(S.searchHint, color = theme.subtext) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { ch ->
                        val isCur = currentChannel?.id == ch.id
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (isCur) theme.selectedCard else theme.bg)
                                .clickable { onSelect(ch) }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${ch.number}", color = theme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp))
                            Text(ch.name, color = theme.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EpgAndArchiveDialog(
    S: Strings,
    theme: AppThemePalette,
    channel: Channel,
    schedule: List<EpgProgramItem>,
    onDismiss: () -> Unit,
    onPlayArchive: (EpgProgramItem) -> Unit,
    onPlayLive: () -> Unit
) {
    val now = System.currentTimeMillis()
    val channelHasArchive = channel.catchupDays > 0

    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = theme.card), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("📅 ${channel.name}", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = theme.primary, maxLines = 1)
                        Text(
                            if (channelHasArchive) (if (S.isRu) "Архив доступен: нажмите на прошедшую передачу для просмотра" else "Tap any past program to watch Archive")
                            else (if (S.isRu) "Расписание телепередач (прямой эфир)" else "Channel Schedule (Live only)"),
                            fontSize = 12.sp,
                            color = theme.subtext
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null, tint = theme.text) }
                }
                Spacer(Modifier.height(10.dp))

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                    items(schedule) { prog ->
                        val isPast = prog.stopMs < now
                        val isNow = now in prog.startMs..prog.stopMs
                        val canPlayThisPast = isPast && channelHasArchive

                        val badgeText = when {
                            isNow -> if (S.isRu) "🟢 СЕЙЧАС В ЭФИРЕ" else "🟢 LIVE NOW"
                            canPlayThisPast -> if (S.isRu) "⏪ СМОТРЕТЬ В АРХИВЕ" else "⏪ WATCH ARCHIVE"
                            isPast -> if (S.isRu) "ЗАВЕРШЕНО" else "ENDED"
                            else -> if (S.isRu) "🕒 ДАЛЕЕ" else "🕒 UPCOMING"
                        }
                        val badgeColor = when {
                            isNow -> Color(0xFF10B981)
                            canPlayThisPast -> Color(0xFFF59E0B)
                            else -> theme.subtext
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (isNow) theme.selectedCard else theme.bg)
                                .border(if (isNow) 1.5.dp else 0.dp, if (isNow) theme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable(enabled = canPlayThisPast || isNow) {
                                    if (canPlayThisPast) onPlayArchive(prog) else if (isNow) onPlayLive()
                                }
                                .padding(12.dp)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(prog.timeRangeText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = theme.subtext)
                                Text(badgeText, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = badgeColor)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(prog.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = theme.text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LiveTvScreen(
    S: Strings,
    theme: AppThemePalette,
    playlists: List<NamedSource>,
    selectedPlaylistId: String,
    onSelectPlaylistId: (String) -> Unit,
    channels: List<Channel>,
    epgScheduleMap: Map<String, List<EpgProgramItem>>,
    favoriteIds: Set<String>,
    currentChannel: Channel?,
    previousChannel: Channel?,
    activeArchiveStream: Pair<String, String>?,
    playerResizeMode: Int,
    playerBufferMode: Int,
    playerDefaultUa: String,
    onSelectChannel: (Channel) -> Unit,
    onOpenEpgDialog: (Channel) -> Unit,
    onResetToLive: () -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    onFullscreen: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        if (playlists.isNotEmpty()) {
            val activeName = playlists.find { it.id == selectedPlaylistId }?.name ?: playlists.first().name
            SourceDropdownSelector(S.activePlaylistLabel, "📺 $activeName", playlists.map { it.id to it.name }, theme, onSelectPlaylistId, Modifier.padding(bottom = 10.dp))
        }
        if (channels.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(S.noChannels, fontSize = 19.sp, color = theme.subtext)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onOpenSettings) { Text(S.goToSettings) }
                }
            }
            return
        }

        val favCount = remember(channels, favoriteIds) { channels.count { favoriteIds.contains(it.id) } }
        val groups = remember(channels, favCount, S) {
            val list = mutableListOf(S.allGroups)
            if (favCount > 0) list.add(FAV_GROUP_TAG)
            list.addAll(channels.map { it.group }.distinct())
            list
        }

        var selectedGroup by remember { mutableStateOf(S.allGroups) }
        var searchQuery by remember { mutableStateOf("") }

        LaunchedEffect(favCount) {
            if (favCount == 0 && selectedGroup == FAV_GROUP_TAG) selectedGroup = S.allGroups
        }

        val filtered = remember(channels, selectedGroup, searchQuery, favoriteIds, S) {
            channels.filter { ch ->
                val matchG = when (selectedGroup) {
                    S.allGroups -> true
                    FAV_GROUP_TAG -> favoriteIds.contains(ch.id)
                    else -> ch.group == selectedGroup
                }
                matchG && (searchQuery.isBlank() || ch.name.contains(searchQuery, ignoreCase = true))
            }
        }

        if (currentChannel != null) {
            val playUrl = activeArchiveStream?.second ?: currentChannel.streamUrl
            val curSchedule = remember(currentChannel, epgScheduleMap) { findChannelSchedule(currentChannel, epgScheduleMap) }
            val nowProg = remember(curSchedule) {
                val now = System.currentTimeMillis()
                curSchedule.find { now in it.startMs..it.stopMs }
            }
            val curHasEpg = curSchedule.isNotEmpty()
            val curHasArchive = currentChannel.catchupDays > 0 && curHasEpg

            Row(modifier = Modifier.fillMaxWidth().height(215.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                Box(modifier = Modifier.weight(1.3f).fillMaxHeight()) {
                    SmartVideoPlayer(
                        streamUrl = playUrl,
                        userAgent = currentChannel.userAgent ?: playerDefaultUa,
                        resizeMode = playerResizeMode,
                        bufferMode = playerBufferMode,
                        reconnectingText = S.reconnecting,
                        modifier = Modifier.fillMaxSize().clickable { onFullscreen() }
                    )
                }
                Column(modifier = Modifier.weight(1f).fillMaxHeight().background(theme.card).padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("${currentChannel.number}. ${currentChannel.name}", fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = theme.primary)
                        if (activeArchiveStream != null) {
                            Text("⏪ Архив: ${activeArchiveStream.first}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B), maxLines = 2)
                        } else if (nowProg != null) {
                            Text("🟢 ${nowProg.title}", fontSize = 12.sp, color = theme.text, maxLines = 2)
                            Text(nowProg.timeRangeText, fontSize = 11.sp, color = theme.subtext)
                        } else {
                            Text(currentChannel.group, fontSize = 12.sp, color = theme.subtext)
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (curHasEpg) {
                            OutlinedButton(
                                onClick = { onOpenEpgDialog(currentChannel) },
                                modifier = Modifier.fillMaxWidth().height(34.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(if (curHasArchive) Icons.Default.History else Icons.Default.EventNote, null, modifier = Modifier.size(16.dp), tint = theme.primary)
                                Spacer(Modifier.width(4.dp))
                                Text(if (curHasArchive) (if (S.isRu) "Программа и Архив" else "EPG & Archive") else (if (S.isRu) "Телепрограмма" else "TV Guide"), fontSize = 11.sp, maxLines = 1)
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = onFullscreen, modifier = Modifier.weight(1f).height(36.dp), contentPadding = PaddingValues(4.dp)) {
                                Icon(Icons.Default.Fullscreen, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(S.fullscreen, fontSize = 11.sp, maxLines = 1)
                            }
                            if (activeArchiveStream != null) {
                                OutlinedButton(onClick = onResetToLive, modifier = Modifier.height(36.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text("🔴 Эфир", fontSize = 11.sp, color = Color(0xFFEF4444))
                                }
                            } else if (previousChannel != null) {
                                OutlinedButton(onClick = { onSelectChannel(previousChannel) }, modifier = Modifier.height(36.dp), contentPadding = PaddingValues(6.dp)) {
                                    Icon(Icons.Default.History, null, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(S.searchHint, color = theme.subtext) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = theme.primary) },
            trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, null) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups) { grp ->
                val label = if (grp == FAV_GROUP_TAG) "⭐ ${S.favorites} ($favCount)" else grp
                FilterChip(selected = grp == selectedGroup, onClick = { selectedGroup = grp }, label = { Text(label) })
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { ch ->
                val isPlaying = currentChannel?.id == ch.id
                val isFav = favoriteIds.contains(ch.id)
                val chSchedule = findChannelSchedule(ch, epgScheduleMap)
                val hasEpg = chSchedule.isNotEmpty()
                val hasArchive = ch.catchupDays > 0 && hasEpg
                val nowProg = if (hasEpg) {
                    val now = System.currentTimeMillis()
                    chSchedule.find { now in it.startMs..it.stopMs }
                } else null

                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (isPlaying) theme.selectedCard else theme.card)
                        .border(if (isPlaying) 2.dp else 0.dp, if (isPlaying) theme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable { if (isPlaying) onFullscreen() else onSelectChannel(ch) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${ch.number}", color = theme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp))
                    if (!ch.logoUrl.isNullOrBlank()) {
                        AsyncImage(model = ch.logoUrl, contentDescription = ch.name, modifier = Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Fit)
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ch.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = theme.text)
                        if (nowProg != null) {
                            Text("📺 ${nowProg.title}", fontSize = 12.sp, color = theme.primary, maxLines = 1)
                        } else {
                            Text(ch.group, fontSize = 12.sp, color = theme.subtext, maxLines = 1)
                        }
                    }

                    // Значок EPG и Архива отображается ТОЛЬКО если у канала есть программа передач!
                    if (hasEpg) {
                        IconButton(onClick = { onOpenEpgDialog(ch) }) {
                            Icon(
                                imageVector = if (hasArchive) Icons.Default.History else Icons.Default.EventNote,
                                contentDescription = null,
                                tint = if (hasArchive) Color(0xFFF59E0B) else theme.primary
                            )
                        }
                    }

                    IconButton(onClick = { onToggleFavorite(ch) }) {
                        Icon(if (isFav) Icons.Default.Star else Icons.Default.StarBorder, null, tint = if (isFav) Color(0xFFF59E0B) else theme.subtext)
                    }
                }
            }
        }
    }
}

@Composable
fun VodScreen(
    S: Strings,
    theme: AppThemePalette,
    movies: List<VodMovie>,
    folderTitle: String,
    canGoBack: Boolean,
    isLoadingMore: Boolean,
    noticeMessage: String,
    diagnosticLog: String,
    onBackFolder: () -> Unit,
    onClickItem: (VodMovie) -> Unit,
    onAutoLoadMore: (VodMovie) -> Unit,
    onServerSearch: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    if (canGoBack) BackHandler { onBackFolder() }

    val filtered = remember(movies, searchQuery) {
        if (searchQuery.isBlank()) movies else movies.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canGoBack) {
                Button(onClick = onBackFolder, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), modifier = Modifier.padding(end = 10.dp)) {
                    Icon(Icons.Default.ArrowBack, null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (S.isRu) "Назад" else "Back")
                }
            }
            Text(folderTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = theme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }

        if (noticeMessage.isNotBlank()) {
            Text(noticeMessage, fontSize = 12.sp, color = Color(0xFFF59E0B), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(8.dp)).background(theme.card).padding(10.dp))
        }

        if (movies.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    Text(if (S.isRu) "В этом разделе пока пусто" else "No items loaded", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = theme.text)
                    if (diagnosticLog.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(diagnosticLog, fontSize = 12.sp, color = theme.subtext, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(theme.card).padding(12.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onOpenSettings) { Text(S.goToSettings) }
                }
            }
            return
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(S.searchHint, color = theme.subtext) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = theme.primary) },
                trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, null) } },
                singleLine = true,
                modifier = Modifier.weight(1f).height(52.dp)
            )
            Button(onClick = { onServerSearch(searchQuery) }, modifier = Modifier.height(52.dp)) { Text(if (S.isRu) "Найти" else "Find") }
        }
        Spacer(Modifier.height(10.dp))

        LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 150.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { movie ->
                if (movie.id.startsWith("vp_next_")) {
                    LaunchedEffect(movie.id) {
                        if (searchQuery.isBlank() && !isLoadingMore) onAutoLoadMore(movie)
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = theme.card), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable { onClickItem(movie) }) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(if (movie.isFolder && movie.posterUrl.isNullOrBlank()) 85.dp else 175.dp).clip(RoundedCornerShape(10.dp)).background(theme.bg),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!movie.posterUrl.isNullOrBlank()) {
                                AsyncImage(model = movie.posterUrl, contentDescription = movie.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            } else {
                                Icon(if (movie.id.startsWith("vp_next_")) Icons.Default.Sync else if (movie.isFolder) Icons.Default.Folder else Icons.Default.PlayCircleFilled, null, tint = theme.primary, modifier = Modifier.size(44.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(if (movie.id.startsWith("vp_next_")) "⏳ Подгрузка..." else movie.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = theme.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(if (movie.isFolder) (if (S.isRu) "📁 Открыть" else "📁 Open") else "▶ Смотреть", fontSize = 12.sp, color = theme.primary)
                    }
                }
            }
        }
    }
}

@Composable
fun SmartVideoPlayer(
    streamUrl: String,
    userAgent: String?,
    resizeMode: Int,
    bufferMode: Int,
    reconnectingText: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isReconnecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val exoPlayer = remember(bufferMode) {
        val (minBuf, maxBuf, playBuf) = when (bufferMode) {
            0 -> Triple(2000, 15000, 1000)
            2 -> Triple(12000, 60000, 4000)
            else -> Triple(5000, 30000, 2000)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuf, maxBuf, playBuf, 2000)
            .build()
        ExoPlayer.Builder(context).setLoadControl(loadControl).build().apply { playWhenReady = true }
    }

    LaunchedEffect(streamUrl, userAgent, exoPlayer) {
        if (streamUrl.isBlank()) return@LaunchedEffect
        isReconnecting = false
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent ?: "Lavf/60.3.100")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(15000)

        val mediaSource = DefaultMediaSourceFactory(httpFactory).createMediaSource(MediaItem.fromUri(streamUrl))
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                isReconnecting = true
                scope.launch {
                    delay(2500)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) isReconnecting = false
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener); exoPlayer.release() }
    }

    val aspectConst = when (resizeMode) {
        1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    setShowSubtitleButton(true)
                    this.resizeMode = aspectConst
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            update = { view ->
                view.player = exoPlayer
                view.resizeMode = aspectConst
            },
            modifier = Modifier.fillMaxSize()
        )
        if (isReconnecting) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(reconnectingText, color = Color(0xFFF59E0B), fontSize = 13.sp)
            }
        }
    }
}
