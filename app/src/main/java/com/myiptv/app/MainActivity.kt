@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.myiptv.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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

fun getAppVersionInfo(context: Context): Pair<String, Long> = runCatching {
    val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    (pInfo.versionName ?: "1.0") to pInfo.longVersionCode
}.getOrDefault("1.0" to 1L)

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

fun findCurrentProgram(ch: Channel, epgMap: Map<String, List<EpgProgramItem>>): EpgProgramItem? {
    val list = findChannelSchedule(ch, epgMap)
    if (list.isEmpty()) return null
    val now = System.currentTimeMillis()
    return list.find { now in it.startMs..it.stopMs }
}

class MainActivity : ComponentActivity() {
    private var webServer: SimpleQrWebServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("cctv_plus_prefs", Context.MODE_PRIVATE)

        setContent {
            var isFirstLaunch by remember { mutableStateOf(!prefs.contains("lang_selected")) }
            var isRu by remember { mutableStateOf(prefs.getString("lang", "ru") == "ru") }
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

            val scope = rememberCoroutineScope()

            fun persistLists() {
                saveNamedList(prefs, "playlists_json", playlists)
                saveNamedList(prefs, "epgs_json", epgSources)
                prefs.edit()
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
                        if (isRu) "✅ Обновлено в $timeStr (расписание и архив для ${loadedSchedule.size / 2} каналов)"
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

                    if (isFirstLaunch) {
                        FirstLaunchLanguageScreen(themePalette) { selectedRu ->
                            isRu = selectedRu
                            prefs.edit().putBoolean("lang_selected", true).putString("lang", if (selectedRu) "ru" else "en").apply()
                            isFirstLaunch = false
                            currentTab = 2
                        }
                    } else if (isFullscreen && (currentChannel != null || currentVodStream != null)) {
                        BackHandler { isFullscreen = false }
                        val playUrl = currentVodStream?.second ?: activeArchiveStream?.second ?: currentChannel?.streamUrl ?: ""
                        val playTitle = currentVodStream?.first
                            ?: activeArchiveStream?.let { "⏪ Архив: ${it.first}" }
                            ?: currentChannel?.name ?: ""
                        val resizeLabels = if (isRu) listOf("По размеру", "16:9 Растянуть", "Zoom") else listOf("Fit", "Fill", "Zoom")

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
                                modifier = Modifier.align(Alignment.TopStart).padding(14.dp).background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(playTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, modifier = Modifier.widthIn(max = 260.dp))
                                OutlinedButton(
                                    onClick = {
                                        playerResizeMode = (playerResizeMode + 1) % 3
                                        prefs.edit().putInt("player_resize", playerResizeMode).apply()
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("📐 ${resizeLabels[playerResizeMode]}", color = Color.White, fontSize = 12.sp)
                                }
                                if (currentChannel != null && currentVodStream == null) {
                                    OutlinedButton(
                                        onClick = { epgDialogChannel = currentChannel },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(if (isRu) "📅 Гид / Архив" else "📅 EPG / Archive", color = Color(0xFF10B981), fontSize = 12.sp)
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
    Dialog(onDismissRequest = onDismiss) {
        Card(
            colors = CardDefaults.cardColors(containerColor = theme.card),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("📅 ${channel.name}", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = theme.primary, maxLines = 1)
                        Text(
                            if (S.isRu) "Нажмите на прошедшую передачу для просмотра в Архиве" else "Tap any past program to watch Catch-Up Archive",
                            fontSize = 12.sp,
                            color = theme.subtext
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null, tint = theme.text) }
                }
                Spacer(Modifier.height(10.dp))

                if (schedule.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (S.isRu) "Для канала «${channel.name}» программа передач пока не найдена.\nПроверьте статус загрузки EPG в Настройках."
                            else "No TV guide found for ${channel.name}.",
                            fontSize = 14.sp,
                            color = theme.subtext
                        )
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                        items(schedule) { prog ->
                            val isPast = prog.stopMs < now
                            val isNow = now in prog.startMs..prog.stopMs
                            val badgeText = when {
                                isNow -> if (S.isRu) "🟢 СЕЙЧАС В ЭФИРЕ" else "🟢 LIVE NOW"
                                isPast -> if (S.isRu) "⏪ СМОТРЕТЬ В АРХИВЕ" else "⏪ WATCH ARCHIVE"
                                else -> if (S.isRu) "🕒 БУДЕТ В ЭФИРЕ" else "🕒 UPCOMING"
                            }
                            val badgeColor = when {
                                isNow -> Color(0xFF10B981)
                                isPast -> Color(0xFFF59E0B)
                                else -> theme.subtext
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isNow) theme.selectedCard else theme.bg)
                                    .border(if (isNow) 1.5.dp else 0.dp, if (isNow) theme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                                    .clickable(enabled = isPast || isNow) {
                                        if (isPast) onPlayArchive(prog) else if (isNow) onPlayLive()
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
}

@Composable
fun SourceDropdownSelector(
    label: String,
    selectedTitle: String,
    options: List<Pair<String, String>>,
    theme: AppThemePalette,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(theme.bg)
                .border(1.dp, theme.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .clickable { expanded = true }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 11.sp, color = theme.subtext)
                Text(selectedTitle, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = theme.text, maxLines = 1)
            }
            Icon(Icons.Default.ArrowDropDown, null, tint = theme.primary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(theme.card)) {
            options.forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title, color = theme.text) }, onClick = { expanded = false; onSelect(id) })
            }
        }
    }
}

@Composable
fun ExpandableSectionRow(
    title: String,
    isExpanded: Boolean,
    theme: AppThemePalette,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = theme.card),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().border(if (isExpanded) 1.5.dp else 0.dp, if (isExpanded) theme.primary else Color.Transparent, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (isExpanded) theme.primary else theme.text)
                Icon(if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = theme.primary)
            }
            if (isExpanded) {
                HorizontalDivider(color = theme.subtext.copy(alpha = 0.2f))
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
        }
    }
}

@Composable
fun FirstLaunchLanguageScreen(theme: AppThemePalette, onSelectLanguage: (Boolean) -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(colors = CardDefaults.cardColors(containerColor = theme.card), shape = RoundedCornerShape(24.dp), modifier = Modifier.padding(24.dp).widthIn(max = 460.dp)) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Language, null, tint = theme.primary, modifier = Modifier.size(60.dp))
                Spacer(Modifier.height(14.dp))
                Text("CCTV Plus", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = theme.primary)
                Spacer(Modifier.height(6.dp))
                Text("Выберите язык / Choose Language", fontSize = 17.sp, color = theme.text)
                Spacer(Modifier.height(24.dp))
                Button(onClick = { onSelectLanguage(true) }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("🇷🇺  Русский", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(14.dp))
                OutlinedButton(onClick = { onSelectLanguage(false) }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("🇬🇧  English", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = theme.text) }
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
            val nowProg = findCurrentProgram(currentChannel, epgScheduleMap)

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
                        OutlinedButton(
                            onClick = { onOpenEpgDialog(currentChannel) },
                            modifier = Modifier.fillMaxWidth().height(34.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Schedule, null, modifier = Modifier.size(16.dp), tint = theme.primary)
                            Spacer(Modifier.width(4.dp))
                            Text(if (S.isRu) "Программа / Архив" else "EPG / Archive", fontSize = 11.sp, maxLines = 1)
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
                val nowProg = findCurrentProgram(ch, epgScheduleMap)

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
                    IconButton(onClick = { onOpenEpgDialog(ch) }) {
                        Icon(Icons.Default.Schedule, null, tint = theme.primary)
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
fun AccordionSettingsScreen(
    S: Strings,
    prefs: SharedPreferences,
    theme: AppThemePalette,
    onSelectTheme: (AppThemePalette) -> Unit,
    onToggleLanguage: () -> Unit,
    playerResizeMode: Int,
    playerBufferMode: Int,
    playerDefaultUa: String,
    openFullscreenOnTap: Boolean,
    onPlayerResizeChange: (Int) -> Unit,
    onPlayerBufferChange: (Int) -> Unit,
    onPlayerUaChange: (String) -> Unit,
    onAutoFsChange: (Boolean) -> Unit,
    playlists: List<NamedSource>,
    selectedPlaylistId: String,
    onSelectPlaylist: (String) -> Unit,
    onAddPlaylist: (String, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    epgSources: List<NamedSource>,
    selectedEpgId: String,
    usePlaylistEpg: Boolean,
    detectedPlaylistEpgUrl: String?,
    isEpgLoading: Boolean,
    epgStatusText: String,
    onRefreshEpgNow: () -> Unit,
    onToggleUsePlaylistEpg: (Boolean) -> Unit,
    onSelectEpg: (String) -> Unit,
    onAddEpg: (String, String) -> Unit,
    onDeleteEpg: (String) -> Unit,
    vportalUrl: String,
    stalkerUrl: String,
    stalkerMac: String,
    vportalDiagnostic: String,
    onVportalChange: (String) -> Unit,
    onStalkerUrlChange: (String) -> Unit,
    onStalkerMacChange: (String) -> Unit,
    onSaveAndReload: () -> Unit,
    onSettingsImported: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val (installedVerName, installedVerCode) = remember { getAppVersionInfo(context) }

    var expandedRow by remember { mutableStateOf(0) }
    fun toggleRow(idx: Int) { expandedRow = if (expandedRow == idx) -1 else idx }

    var newPlName by remember { mutableStateOf("") }
    var newPlUrl by remember { mutableStateOf("") }
    var newEpgName by remember { mutableStateOf("") }
    var newEpgUrl by remember { mutableStateOf("") }

    var updateStatus by remember { mutableStateOf("") }
    var downloadPercent by remember { mutableStateOf(-1) }
    var latestRelease by remember { mutableStateOf<ReleaseInfo?>(null) }
    var isUpdating by remember { mutableStateOf(false) }
    var backupStatus by remember { mutableStateOf("") }

    var issueDescription by remember { mutableStateOf("") }
    var reportStatus by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(exportAllSettingsToJson(prefs).toByteArray(Charsets.UTF_8))
                }
                backupStatus = if (S.isRu) "✅ Настройки успешно сохранены в файл!" else "✅ Settings exported!"
            }.onFailure { backupStatus = "❌ Ошибка: ${it.localizedMessage}" }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val jsonText = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
                if (importAllSettingsFromJson(prefs, jsonText)) {
                    backupStatus = if (S.isRu) "✅ Настройки успешно импортированы!" else "✅ Settings imported!"
                    onSettingsImported()
                } else backupStatus = "❌ Неверный формат файла"
            }.onFailure { backupStatus = "❌ Ошибка: ${it.localizedMessage}" }
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 0. ВИД, 10 ТЕМ И ЯЗЫК
        ExpandableSectionRow(S.catAppearance, expandedRow == 0, theme, { toggleRow(0) }) {
            AppThemePalette.entries.forEach { item ->
                val isChosen = item == theme
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(item.bg)
                        .border(if (isChosen) 2.5.dp else 1.dp, if (isChosen) item.primary else item.subtext, RoundedCornerShape(12.dp))
                        .clickable { onSelectTheme(item) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(item.primary))
                        Spacer(Modifier.width(12.dp))
                        Text(if (S.isRu) item.titleRu else item.titleEn, fontWeight = FontWeight.Bold, color = item.text)
                    }
                    if (isChosen) Icon(Icons.Default.Check, null, tint = item.primary)
                }
            }
            Button(onClick = onToggleLanguage, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Icon(Icons.Default.Language, null)
                Spacer(Modifier.width(8.dp))
                Text(if (S.isRu) "Язык: Русский 🇷🇺 (Сменить на EN)" else "Language: English 🇬🇧 (Switch to RU)")
            }
        }

        // 1. НАСТРОЙКИ ВИДЕОПЛЕЕРА
        ExpandableSectionRow(S.catPlayer, expandedRow == 1, theme, { toggleRow(1) }) {
            val resizeOpts = listOf(
                "0" to (if (S.isRu) "📐 По размеру экрана (Fit — без обрезки)" else "📐 Fit Screen"),
                "1" to (if (S.isRu) "📐 Растянуть на весь экран (16:9 Fill)" else "📐 Stretch 16:9 Fill"),
                "2" to (if (S.isRu) "📐 Заполнить с обрезкой краев (Zoom)" else "📐 Zoom Crop")
            )
            SourceDropdownSelector(
                label = if (S.isRu) "Формат изображения (Пропорции):" else "Aspect Ratio:",
                selectedTitle = resizeOpts.find { it.first == playerResizeMode.toString() }?.second ?: resizeOpts.first().second,
                options = resizeOpts,
                theme = theme,
                onSelect = { onPlayerResizeChange(it.toIntOrNull() ?: 0) }
            )

            val bufOpts = listOf(
                "0" to (if (S.isRu) "🚀 Быстрый старт (Буфер 2 сек)" else "🚀 Fast Start (2s Buffer)"),
                "1" to (if (S.isRu) "⚖️ Сбалансированный (Буфер 5 сек)" else "⚖️ Balanced (5s Buffer)"),
                "2" to (if (S.isRu) "🛡 Макс. стабильность (Буфер 12 сек — от зависаний)" else "🛡 Max Stability (12s Buffer)")
            )
            SourceDropdownSelector(
                label = if (S.isRu) "Размер буфера видеоплеера:" else "Video Buffer Size:",
                selectedTitle = bufOpts.find { it.first == playerBufferMode.toString() }?.second ?: bufOpts[1].second,
                options = bufOpts,
                theme = theme,
                onSelect = { onPlayerBufferChange(it.toIntOrNull() ?: 1) }
            )

            val uaOpts = listOf(
                "Lavf/60.3.100" to "Lavf / FFmpeg (По умолчанию)",
                "Mozilla/5.0 (SmartHub; SMART-TV) OttPlayer/2.0" to "OttPlayer SmartTV",
                "VLC/3.0.20 LibVLC/3.0.20" to "VLC Media Player",
                "TiviMate/4.7.0 (Android TV)" to "TiviMate Android TV",
                "Mozilla/5.0 (QtEmbedded; U; Linux; C) MAG200" to "MAG250 Set-Top Box"
            )
            SourceDropdownSelector(
                label = if (S.isRu) "Представляться серверу как (User-Agent):" else "Default User-Agent:",
                selectedTitle = uaOpts.find { it.first == playerDefaultUa }?.second ?: playerDefaultUa,
                options = uaOpts,
                theme = theme,
                onSelect = { onPlayerUaChange(it) }
            )

            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (S.isRu) "Открывать канал сразу на весь экран при нажатии" else "Open channel in fullscreen on tap", fontWeight = FontWeight.Bold, color = theme.text, modifier = Modifier.weight(1f))
                Switch(checked = openFullscreenOnTap, onCheckedChange = onAutoFsChange)
            }
        }

        // 2. ПЛЕЙЛИСТЫ M3U
        ExpandableSectionRow("${S.catPlaylists} (${playlists.size})", expandedRow == 2, theme, { toggleRow(2) }) {
            if (playlists.isNotEmpty()) {
                val curName = playlists.find { it.id == selectedPlaylistId }?.name ?: playlists.first().name
                SourceDropdownSelector(S.activePlaylistLabel, "📺 $curName", playlists.map { it.id to it.name }, theme, onSelectPlaylist)
            }
            OutlinedTextField(value = newPlName, onValueChange = { newPlName = it }, label = { Text(S.addSourceName) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = newPlUrl,
                onValueChange = { newPlUrl = it },
                label = { Text(S.addSourceUrl) },
                trailingIcon = { if (newPlUrl.isNotEmpty()) IconButton(onClick = { newPlUrl = "" }) { Icon(Icons.Default.Clear, null) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (newPlUrl.isNotBlank()) {
                        onAddPlaylist(newPlName.trim().ifEmpty { "Плейлист ${playlists.size + 1}" }, newPlUrl.trim())
                        newPlName = ""; newPlUrl = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(S.addBtn, fontWeight = FontWeight.Bold) }

            playlists.forEach { pl ->
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(pl.name, fontWeight = FontWeight.Bold, color = theme.text)
                        Text(pl.url, fontSize = 12.sp, color = theme.subtext, maxLines = 1)
                    }
                    IconButton(onClick = { onDeletePlaylist(pl.id) }) { Icon(Icons.Default.Delete, null, tint = Color(0xFFEF4444)) }
                }
            }
        }

        // 3. ТЕЛЕПРОГРАММА EPG
        ExpandableSectionRow(S.catEpg, expandedRow == 3, theme, { toggleRow(3) }) {
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(theme.bg).border(1.dp, theme.primary.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (S.isRu) "Состояние телепрограммы (EPG):" else "EPG Status:", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = theme.primary)
                if (isEpgLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp), color = theme.primary)
                Text(epgStatusText, fontSize = 13.sp, color = theme.text)
                Button(onClick = onRefreshEpgNow, enabled = !isEpgLoading, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (S.isRu) "🔄 Обновить телепрограмму сейчас" else "🔄 Refresh EPG Now", fontWeight = FontWeight.Bold)
                }
            }
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(S.usePlaylistEpgSwitch, fontWeight = FontWeight.Bold, color = theme.text)
                    if (!detectedPlaylistEpgUrl.isNullOrBlank()) Text("EPG: $detectedPlaylistEpgUrl", fontSize = 12.sp, color = theme.primary)
                }
                Switch(checked = usePlaylistEpg, onCheckedChange = onToggleUsePlaylistEpg)
            }
            val epgOpts = listOf("AUTO_PLAYLIST" to S.epgFromPlaylistOption) + epgSources.map { it.id to it.name }
            val curEpg = epgOpts.find { it.first == selectedEpgId }?.second ?: S.epgFromPlaylistOption
            SourceDropdownSelector(S.activeEpgLabel, curEpg, epgOpts, theme, onSelectEpg)

            OutlinedTextField(value = newEpgName, onValueChange = { newEpgName = it }, label = { Text(S.addSourceName) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = newEpgUrl,
                onValueChange = { newEpgUrl = it },
                label = { Text(S.addSourceUrl) },
                trailingIcon = { if (newEpgUrl.isNotEmpty()) IconButton(onClick = { newEpgUrl = "" }) { Icon(Icons.Default.Clear, null) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (newEpgUrl.isNotBlank()) {
                        onAddEpg(newEpgName.trim().ifEmpty { "EPG ${epgSources.size + 1}" }, newEpgUrl.trim())
                        newEpgName = ""; newEpgUrl = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(S.addBtn, fontWeight = FontWeight.Bold) }

            epgSources.forEach { epg ->
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(epg.name, fontWeight = FontWeight.Bold, color = theme.text)
                        Text(epg.url, fontSize = 12.sp, color = theme.subtext, maxLines = 1)
                    }
                    IconButton(onClick = { onDeleteEpg(epg.id) }) { Icon(Icons.Default.Delete, null, tint = Color(0xFFEF4444)) }
                }
            }
        }

        // 4. ПОРТАЛЫ (VPORTAL И STALKER)
        ExpandableSectionRow(S.catPortals, expandedRow == 4, theme, { toggleRow(4) }) {
            Text("🎬 VPortal (TVlider / iLook)", fontWeight = FontWeight.Bold, color = theme.primary)
            OutlinedTextField(
                value = vportalUrl,
                onValueChange = onVportalChange,
                label = { Text("portal::[key:...]http://.../api/v1/") },
                trailingIcon = { if (vportalUrl.isNotEmpty()) IconButton(onClick = { onVportalChange("") }) { Icon(Icons.Default.Clear, null) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("📡 Stalker Portal", fontWeight = FontWeight.Bold, color = theme.primary)
            OutlinedTextField(
                value = stalkerUrl,
                onValueChange = onStalkerUrlChange,
                label = { Text("http://.../stalker_portal/c/") },
                trailingIcon = { if (stalkerUrl.isNotEmpty()) IconButton(onClick = { onStalkerUrlChange("") }) { Icon(Icons.Default.Clear, null) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(value = stalkerMac, onValueChange = onStalkerMacChange, label = { Text("MAC (00:1A:79:XX:XX:XX)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = onSaveAndReload, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text(S.saveAndReloadBtn, fontWeight = FontWeight.Bold) }

            if (vportalDiagnostic.isNotBlank()) {
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Диагностика VPortal:", fontWeight = FontWeight.Bold, color = theme.primary)
                    Text(vportalDiagnostic, fontSize = 12.sp, color = theme.text)
                    OutlinedButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("VPortalLog", vportalDiagnostic))
                    }) {
                        Icon(Icons.Default.ContentCopy, null)
                        Spacer(Modifier.width(6.dp))
                        Text(S.copyLogBtn)
                    }
                }
            }
        }

        // 5. QR-ПУЛЬТ ДЛЯ ТЕЛЕФОНА
        ExpandableSectionRow(S.catQr, expandedRow == 5, theme, { toggleRow(5) }) {
            val localIp = remember { getDeviceLocalIp() }
            val remoteUrl = "http://$localIp:8989"
            val qrBitmap = remember(remoteUrl) { generateQrBitmap(remoteUrl, 360) }
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (qrBitmap != null) {
                    Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "QR", modifier = Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text("🌐 $remoteUrl", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = theme.primary)
            }
        }

        // 6. ИМПОРТ И ЭКСПОРТ НАСТРОЕК
        ExpandableSectionRow(S.catBackup, expandedRow == 6, theme, { toggleRow(6) }) {
            Button(onClick = { exportLauncher.launch("CCTV-Plus-Backup.json") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.UploadFile, null)
                Spacer(Modifier.width(8.dp))
                Text(if (S.isRu) "📤 Экспортировать настройки в файл (.json)" else "📤 Export Settings to File (.json)")
            }
            Button(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text(if (S.isRu) "📥 Импортировать настройки из файла (.json)" else "📥 Import Settings from File (.json)")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("CCTVBackup", exportAllSettingsToJson(prefs)))
                        backupStatus = if (S.isRu) "✅ Скопировано в буфер обмена!" else "✅ Copied to clipboard!"
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (S.isRu) "📋 Копировать" else "📋 Copy") }

                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        if (importAllSettingsFromJson(prefs, clip)) {
                            backupStatus = if (S.isRu) "✅ Восстановлено из буфера!" else "✅ Restored from clipboard!"
                            onSettingsImported()
                        } else backupStatus = "❌ В буфере нет настроек CCTV Plus"
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (S.isRu) "📋 Вставить" else "📋 Paste") }
            }
            if (backupStatus.isNotBlank()) {
                Text(backupStatus, fontSize = 13.sp, color = theme.primary, fontWeight = FontWeight.Bold)
            }
        }

        // 7. СПИСОК ИЗМЕНЕНИЙ (CHANGELOG)
        ExpandableSectionRow(S.catChangelog, expandedRow == 7, theme, { toggleRow(7) }) {
            AppChangelogHistory.forEachIndexed { idx, rel ->
                val verTitle = if (idx == 0 || rel.version == "CURRENT") {
                    "v$installedVerName (Build #$installedVerCode • Текущая)"
                } else {
                    rel.version
                }
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(verTitle, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = theme.primary)
                        Text(rel.date, fontSize = 12.sp, color = theme.subtext)
                    }
                    val items = if (S.isRu) rel.changesRu else rel.changesEn
                    items.forEach { line ->
                        Text(line, fontSize = 13.sp, color = theme.text)
                    }
                }
            }
        }

        // 8. ЛИЦЕНЗИЯ И УСЛОВИЯ ИСПОЛЬЗОВАНИЯ
        ExpandableSectionRow(S.catLicense, expandedRow == 8, theme, { toggleRow(8) }) {
            val items = if (S.isRu) LicenseItemsRu else LicenseItemsEn
            items.forEach { paragraph ->
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(12.dp)) {
                    Text(paragraph, fontSize = 13.sp, color = theme.text)
                }
            }
        }

        // 9. ПОЛИТИКА КОНФИДЕНЦИАЛЬНОСТИ
        ExpandableSectionRow(S.catPrivacy, expandedRow == 9, theme, { toggleRow(9) }) {
            val items = if (S.isRu) PrivacyItemsRu else PrivacyItemsEn
            items.forEach { paragraph ->
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(12.dp)) {
                    Text(paragraph, fontSize = 13.sp, color = theme.text)
                }
            }
        }

        // 10. СООБЩИТЬ О ПРОБЛЕМЕ (ОБРАТНАЯ СВЯЗЬ)
        ExpandableSectionRow(S.catReportIssue, expandedRow == 10, theme, { toggleRow(10) }) {
            Text(
                text = if (S.isRu) "Опишите проблему, и приложение сформирует безопасный технический отчет (ваши ключи подписки автоматически скрываются звездочками ***):"
                else "Describe the issue below to generate a safe diagnostic report (private keys are automatically masked):",
                fontSize = 13.sp,
                color = theme.subtext
            )
            OutlinedTextField(
                value = issueDescription,
                onValueChange = { issueDescription = it },
                label = { Text(if (S.isRu) "Что именно пошло не так?" else "Describe the problem...") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val reportText = buildString {
                        appendLine("=== CCTV Plus Bug Report ===")
                        appendLine("App Version: v$installedVerName (Build #$installedVerCode)")
                        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
                        appendLine("Theme: ${theme.id} | Resize: $playerResizeMode | Buffer: $playerBufferMode")
                        appendLine("Playlists count: ${playlists.size} | EPG Status: $epgStatusText")
                        appendLine("VPortal Diag: ${maskSensitiveKeys(vportalDiagnostic)}")
                        appendLine("User Comment: ${issueDescription.ifBlank { "No comment" }}")
                    }
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("CCTVBugReport", reportText))
                    reportStatus = if (S.isRu) "✅ Тех. отчет скопирован в буфер! Нажмите кнопку GitHub ниже, чтобы отправить его." else "✅ Report copied to clipboard!"
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.BugReport, null)
                Spacer(Modifier.width(8.dp))
                Text(if (S.isRu) "📋 Скопировать тех. отчет для разработчика" else "📋 Copy Safe Diagnostic Report", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = {
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/cakecock")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.OpenInNew, null)
                Spacer(Modifier.width(8.dp))
                Text(if (S.isRu) "🌐 Открыть GitHub разработчика (cakecock)" else "🌐 Open Developer GitHub (cakecock)")
            }
            if (reportStatus.isNotBlank()) {
                Text(reportStatus, fontSize = 13.sp, color = theme.primary, fontWeight = FontWeight.Bold)
            }
        }

        // 11. ОБНОВЛЕНИЕ ПРИЛОЖЕНИЯ
        ExpandableSectionRow(if (S.isRu) "🔄 Обновление приложения (GitHub)" else "🔄 App Update (GitHub)", expandedRow == 11, theme, { toggleRow(11) }) {
            Text(
                text = if (S.isRu) "Установленная версия: v$installedVerName (Build #$installedVerCode)" else "Installed version: v$installedVerName (Build #$installedVerCode)",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = theme.subtext
            )
            Button(
                onClick = {
                    scope.launch {
                        isUpdating = true
                        downloadPercent = -1
                        updateStatus = if (S.isRu) "🔍 Проверяем свежую сборку на GitHub..." else "🔍 Checking GitHub..."
                        val res = GitHubUpdater.checkLatestRelease()
                        isUpdating = false
                        res.onSuccess { info ->
                            latestRelease = info
                            updateStatus = if (S.isRu) "✅ На GitHub доступна сборка: ${info.versionTag}" else "✅ Available on GitHub: ${info.versionTag}"
                        }.onFailure { err ->
                            updateStatus = "❌ ${err.localizedMessage}"
                        }
                    }
                },
                enabled = !isUpdating,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.SystemUpdate, null)
                Spacer(Modifier.width(8.dp))
                Text(if (S.isRu) "Проверить наличие обновлений" else "Check for Updates")
            }

            if (isUpdating) {
                if (downloadPercent in 0..100) {
                    LinearProgressIndicator(
                        progress = { downloadPercent / 100f },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = theme.primary
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp), color = theme.primary)
                }
            }

            if (updateStatus.isNotBlank()) {
                Text(updateStatus, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = theme.text)
            }

            if (latestRelease != null) {
                Button(
                    onClick = {
                        val info = latestRelease ?: return@Button
                        scope.launch {
                            isUpdating = true
                            downloadPercent = 0
                            updateStatus = if (S.isRu) "⏳ Подготовка к скачиванию ${info.versionTag}..." else "⏳ Starting download..."
                            val dl = GitHubUpdater.downloadAndInstall(context, info.downloadUrl) { pct, dlMb, totMb ->
                                downloadPercent = pct
                                updateStatus = if (S.isRu) {
                                    "⏳ Скачивание обновления: $pct% ($dlMb МБ из $totMb МБ)"
                                } else {
                                    "⏳ Downloading update: $pct% ($dlMb MB / $totMb MB)"
                                }
                            }
                            isUpdating = false
                            dl.onSuccess {
                                updateStatus = if (S.isRu) "✅ Файл скачан (100%)! Открыто окно установки." else "✅ Downloaded (100%)! Installer opened."
                            }.onFailure { err ->
                                updateStatus = "❌ Ошибка установки: ${err.localizedMessage}"
                            }
                        }
                    },
                    enabled = !isUpdating,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudDownload, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (S.isRu) "⬇️ Скачать и установить ${latestRelease!!.versionTag}" else "⬇️ Download & Install ${latestRelease!!.versionTag}", fontWeight = FontWeight.Bold)
                }
            }
        }

        // ПОДВАЛ НАСТРОЕК: ПОДПИСЬ РАЗРАБОТЧИКА И НОМЕР ВЕРСИИ
        Spacer(Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(theme.card)
                .border(1.dp, theme.primary.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                .padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "📺 CCTV Plus • v$installedVerName (Build #$installedVerCode)",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp,
                color = theme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (S.isRu) "👨‍💻 Разработчик: cakecock © 2026" else "👨‍💻 Developer: cakecock © 2026",
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = theme.subtext
            )
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
