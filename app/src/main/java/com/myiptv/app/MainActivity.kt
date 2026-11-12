@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.myiptv.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val FAV_GROUP_TAG = "__FAV_CATEGORY__"

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

            var playlists by remember {
                mutableStateOf(loadNamedList(prefs, "playlists_json", "m3u_url", "Основной плейлист"))
            }
            var selectedPlaylistId by remember {
                mutableStateOf(prefs.getString("selected_playlist_id", playlists.firstOrNull()?.id ?: "") ?: "")
            }

            var epgSources by remember {
                mutableStateOf(loadNamedList(prefs, "epgs_json", "epg_url", "Моя программа EPG"))
            }
            var usePlaylistEpg by remember { mutableStateOf(prefs.getBoolean("use_playlist_epg", true)) }
            var selectedEpgId by remember {
                mutableStateOf(prefs.getString("selected_epg_id", "AUTO_PLAYLIST") ?: "AUTO_PLAYLIST")
            }

            var vportalUrl by remember { mutableStateOf(prefs.getString("vportal_url", "") ?: "") }
            var stalkerUrl by remember { mutableStateOf(prefs.getString("stalker_url", "") ?: "") }
            var stalkerMac by remember { mutableStateOf(prefs.getString("stalker_mac", "") ?: "") }

            var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
            var vodItems by remember { mutableStateOf<List<VodMovie>>(emptyList()) }
            var vodFolderStack by remember { mutableStateOf<List<Pair<String, List<VodMovie>>>>(emptyList()) }
            var currentVodFolderTitle by remember { mutableStateOf("Кинозал VPortal") }

            var epgMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
            var detectedPlaylistEpgUrl by remember { mutableStateOf<String?>(null) }
            var favoriteIds by remember {
                mutableStateOf(prefs.getStringSet("favs", emptySet())?.toSet() ?: emptySet())
            }

            var currentTab by remember { mutableStateOf(0) }
            var isLoading by remember { mutableStateOf(false) }
            var vportalDiagnostic by remember { mutableStateOf("") }

            var currentChannel by remember { mutableStateOf<Channel?>(null) }
            var previousChannel by remember { mutableStateOf<Channel?>(null) }
            var currentVodStream by remember { mutableStateOf<Pair<String, String>?>(null) }
            var isFullscreen by remember { mutableStateOf(false) }

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
                    .apply()
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
                        val stChannels = NetworkLoader.loadStalker(stalkerUrl.trim(), stalkerMac.trim())
                        loadedChannels.addAll(stChannels)
                    }

                    channels = loadedChannels
                    vodItems = loadedVod
                    if (currentChannel == null && loadedChannels.isNotEmpty()) {
                        currentChannel = loadedChannels.first()
                    }
                    isLoading = false

                    val epgUrlToLoad = when {
                        usePlaylistEpg && !embeddedEpg.isNullOrBlank() -> embeddedEpg
                        selectedEpgId == "AUTO_PLAYLIST" && !embeddedEpg.isNullOrBlank() -> embeddedEpg
                        else -> epgSources.find { it.id == selectedEpgId }?.url ?: epgSources.firstOrNull()?.url
                    }

                    if (!epgUrlToLoad.isNullOrBlank()) {
                        epgMap = NetworkLoader.loadEpgCurrentPrograms(epgUrlToLoad.trim())
                    }
                }
            }

            fun openVodNode(item: VodMovie) {
                if (!item.isFolder && item.streamUrl.isNotBlank()) {
                    currentVodStream = item.title to item.streamUrl
                    isFullscreen = true
                    return
                }
                if (vportalUrl.isBlank()) return
                scope.launch {
                    isLoading = true
                    val res = NetworkLoader.loadVPortalNode(
                        rawPortalUrl = vportalUrl.trim(),
                        customTargetUrl = item.targetApiUrl,
                        customRequestJson = item.requestPayloadJson
                    )
                    vportalDiagnostic = res.second
                    val children = res.first
                    isLoading = false

                    if (children.size == 1 && !children.first().isFolder && children.first().streamUrl.isNotBlank()) {
                        currentVodStream = children.first().title to children.first().streamUrl
                        isFullscreen = true
                    } else if (children.isNotEmpty()) {
                        vodFolderStack = vodFolderStack + (currentVodFolderTitle to vodItems)
                        currentVodFolderTitle = item.title
                        vodItems = children
                    }
                }
            }

            fun searchInVPortal(query: String) {
                if (vportalUrl.isBlank() || query.isBlank()) return
                scope.launch {
                    isLoading = true
                    val res = NetworkLoader.loadVPortalNode(
                        rawPortalUrl = vportalUrl.trim(),
                        searchQuery = query.trim()
                    )
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
                darkColorScheme(
                    primary = themePalette.primary,
                    onPrimary = themePalette.onPrimary,
                    background = themePalette.bg,
                    surface = themePalette.card,
                    onBackground = themePalette.text,
                    onSurface = themePalette.text
                )
            } else {
                lightColorScheme(
                    primary = themePalette.primary,
                    onPrimary = themePalette.onPrimary,
                    background = themePalette.bg,
                    surface = themePalette.card,
                    onBackground = themePalette.text,
                    onSurface = themePalette.text
                )
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = themePalette.bg) {
                    if (isFirstLaunch) {
                        FirstLaunchLanguageScreen(themePalette) { selectedRu ->
                            isRu = selectedRu
                            prefs.edit()
                                .putBoolean("lang_selected", true)
                                .putString("lang", if (selectedRu) "ru" else "en")
                                .apply()
                            isFirstLaunch = false
                            currentTab = 3
                        }
                    } else if (isFullscreen && (currentChannel != null || currentVodStream != null)) {
                        BackHandler { isFullscreen = false }
                        val playUrl = currentVodStream?.second ?: currentChannel?.streamUrl ?: ""
                        val playTitle = currentVodStream?.first ?: currentChannel?.name ?: ""
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                            SmartVideoPlayer(
                                streamUrl = playUrl,
                                userAgent = currentChannel?.userAgent,
                                reconnectingText = S.reconnecting,
                                modifier = Modifier.fillMaxSize()
                            )
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(16.dp)
                                    .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(playTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Spacer(Modifier.width(16.dp))
                                Button(onClick = { isFullscreen = false }) {
                                    Text(if (isRu) "Свернуть" else "Exit")
                                }
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            NavigationBar(containerColor = themePalette.card, modifier = Modifier.height(64.dp)) {
                                NavigationBarItem(
                                    selected = currentTab == 0,
                                    onClick = { currentTab = 0; currentVodStream = null },
                                    icon = { Icon(Icons.Default.Tv, contentDescription = null) },
                                    label = { Text(S.liveTv) }
                                )
                                NavigationBarItem(
                                    selected = currentTab == 1,
                                    onClick = { currentTab = 1 },
                                    icon = { Icon(Icons.Default.Movie, contentDescription = null) },
                                    label = { Text("${S.vod} (${vodItems.size})") }
                                )
                                NavigationBarItem(
                                    selected = currentTab == 2,
                                    onClick = { currentTab = 2; currentVodStream = null },
                                    icon = { Icon(Icons.Default.Star, contentDescription = null) },
                                    label = { Text(S.favorites) }
                                )
                                NavigationBarItem(
                                    selected = currentTab == 3,
                                    onClick = { currentTab = 3 },
                                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    label = { Text(S.settings) }
                                )
                            }

                            if (isLoading) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = themePalette.primary)
                            }

                            when (currentTab) {
                                0, 2 -> LiveTvScreen(
                                    S = S,
                                    theme = themePalette,
                                    playlists = playlists,
                                    selectedPlaylistId = selectedPlaylistId,
                                    onSelectPlaylistId = { newId ->
                                        selectedPlaylistId = newId
                                        reloadAllData()
                                    },
                                    channels = if (currentTab == 2) channels.filter { favoriteIds.contains(it.id) } else channels,
                                    epgMap = epgMap,
                                    favoriteIds = favoriteIds,
                                    currentChannel = currentChannel,
                                    previousChannel = previousChannel,
                                    onSelectChannel = { ch ->
                                        if (currentChannel != null && currentChannel?.id != ch.id) {
                                            previousChannel = currentChannel
                                        }
                                        currentVodStream = null
                                        currentChannel = ch
                                    },
                                    onToggleFavorite = { ch ->
                                        val next = favoriteIds.toMutableSet()
                                        if (!next.add(ch.id)) next.remove(ch.id)
                                        favoriteIds = next
                                        prefs.edit().putStringSet("favs", next).apply()
                                    },
                                    onFullscreen = { isFullscreen = true },
                                    onOpenSettings = { currentTab = 3 }
                                )

                                1 -> VodScreen(
                                    S = S,
                                    theme = themePalette,
                                    movies = vodItems,
                                    folderTitle = currentVodFolderTitle,
                                    canGoBack = vodFolderStack.isNotEmpty(),
                                    diagnosticLog = vportalDiagnostic,
                                    onBackFolder = { goBackInVod() },
                                    onClickItem = { item -> openVodNode(item) },
                                    onServerSearch = { q -> searchInVPortal(q) },
                                    onOpenSettings = { currentTab = 3 }
                                )

                                3 -> AccordionSettingsScreen(
                                    S = S,
                                    theme = themePalette,
                                    onSelectTheme = { newTheme ->
                                        themePalette = newTheme
                                        prefs.edit().putString("theme_id", newTheme.id).apply()
                                    },
                                    onToggleLanguage = {
                                        isRu = !isRu
                                        prefs.edit().putString("lang", if (isRu) "ru" else "en").apply()
                                    },
                                    playlists = playlists,
                                    selectedPlaylistId = selectedPlaylistId,
                                    onSelectPlaylist = { id ->
                                        selectedPlaylistId = id
                                        reloadAllData()
                                    },
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
                                    onToggleUsePlaylistEpg = { enabled ->
                                        usePlaylistEpg = enabled
                                        reloadAllData()
                                    },
                                    onSelectEpg = { id ->
                                        selectedEpgId = id
                                        reloadAllData()
                                    },
                                    onAddEpg = { name, url ->
                                        val newId = "epg_${System.currentTimeMillis()}"
                                        epgSources = epgSources + NamedSource(newId, name, url)
                                        selectedEpgId = newId
                                        reloadAllData()
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
                                    onSaveAndReload = { reloadAllData() }
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
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(theme.bg)
                .border(1.dp, theme.primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 11.sp, color = theme.subtext)
                Text(selectedTitle, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = theme.text, maxLines = 1)
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = theme.primary)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(theme.card)
        ) {
            options.forEach { (id, title) ->
                DropdownMenuItem(
                    text = { Text(title, color = theme.text, fontWeight = FontWeight.Medium) },
                    onClick = {
                        expanded = false
                        onSelect(id)
                    }
                )
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
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isExpanded) 1.5.dp else 0.dp,
                color = if (isExpanded) theme.primary else Color.Transparent,
                shape = RoundedCornerShape(14.dp)
            )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = if (isExpanded) theme.primary else theme.text
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = theme.primary
                )
            }
            if (isExpanded) {
                HorizontalDivider(color = theme.subtext.copy(alpha = 0.2f))
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
fun FirstLaunchLanguageScreen(theme: AppThemePalette, onSelectLanguage: (Boolean) -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = theme.card),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.padding(24.dp).widthIn(max = 460.dp)
        ) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Language, contentDescription = null, tint = theme.primary, modifier = Modifier.size(60.dp))
                Spacer(Modifier.height(14.dp))
                Text("CCTV Plus", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = theme.primary)
                Spacer(Modifier.height(6.dp))
                Text("Выберите язык / Choose Language", fontSize = 17.sp, color = theme.text)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { onSelectLanguage(true) },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("🇷🇺  Русский", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = { onSelectLanguage(false) },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("🇬🇧  English", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = theme.text)
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
    epgMap: Map<String, String>,
    favoriteIds: Set<String>,
    currentChannel: Channel?,
    previousChannel: Channel?,
    onSelectChannel: (Channel) -> Unit,
    onToggleFavorite: (Channel) -> Unit,
    onFullscreen: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        if (playlists.isNotEmpty()) {
            val activeName = playlists.find { it.id == selectedPlaylistId }?.name ?: playlists.first().name
            SourceDropdownSelector(
                label = S.activePlaylistLabel,
                selectedTitle = "📺 $activeName",
                options = playlists.map { it.id to it.name },
                theme = theme,
                onSelect = onSelectPlaylistId,
                modifier = Modifier.padding(bottom = 10.dp)
            )
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

        val favChannelsCount = remember(channels, favoriteIds) {
            channels.count { favoriteIds.contains(it.id) }
        }

        // Если есть хотя бы 1 избранный канал — добавляем категорию "⭐ Избранное" сразу после "Все категории"
        val groups = remember(channels, favChannelsCount, S) {
            val baseList = mutableListOf(S.allGroups)
            if (favChannelsCount > 0) {
                baseList.add(FAV_GROUP_TAG)
            }
            baseList.addAll(channels.map { it.group }.distinct())
            baseList
        }

        var selectedGroup by remember { mutableStateOf(S.allGroups) }
        var searchQuery by remember { mutableStateOf("") }

        // Если пользователь убрал последний канал из избранного, возвращаем фильтр на "Все категории"
        LaunchedEffect(favChannelsCount) {
            if (favChannelsCount == 0 && selectedGroup == FAV_GROUP_TAG) {
                selectedGroup = S.allGroups
            }
        }

        val filteredChannels = remember(channels, selectedGroup, searchQuery, favoriteIds, S) {
            channels.filter { ch ->
                val matchGroup = when (selectedGroup) {
                    S.allGroups -> true
                    FAV_GROUP_TAG -> favoriteIds.contains(ch.id)
                    else -> ch.group == selectedGroup
                }
                val matchSearch = searchQuery.isBlank() || ch.name.contains(searchQuery, ignoreCase = true)
                matchGroup && matchSearch
            }
        }

        if (currentChannel != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(205.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
            ) {
                Box(modifier = Modifier.weight(1.35f).fillMaxHeight()) {
                    SmartVideoPlayer(
                        streamUrl = currentChannel.streamUrl,
                        userAgent = currentChannel.userAgent,
                        reconnectingText = S.reconnecting,
                        modifier = Modifier.fillMaxSize().clickable { onFullscreen() }
                    )
                }
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().background(theme.card).padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "${currentChannel.number}. ${currentChannel.name}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = theme.primary
                        )
                        Text(currentChannel.group, fontSize = 12.sp, color = theme.subtext)
                        Spacer(Modifier.height(6.dp))
                        val nowPlaying = currentChannel.epgId?.let { epgMap[it] } ?: epgMap[currentChannel.name]
                        if (!nowPlaying.isNullOrBlank()) {
                            Text("📺 $nowPlaying", fontSize = 13.sp, color = theme.text, maxLines = 2)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onFullscreen, modifier = Modifier.weight(1f), contentPadding = PaddingValues(6.dp)) {
                            Icon(Icons.Default.Fullscreen, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(S.fullscreen, fontSize = 12.sp, maxLines = 1)
                        }
                        if (previousChannel != null) {
                            OutlinedButton(onClick = { onSelectChannel(previousChannel) }, contentPadding = PaddingValues(6.dp)) {
                                Icon(Icons.Default.History, contentDescription = null)
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
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = theme.primary) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = null, tint = theme.subtext)
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        )

        Spacer(Modifier.height(8.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups) { grp ->
                val chipTitle = if (grp == FAV_GROUP_TAG) {
                    "⭐ ${S.favorites} ($favChannelsCount)"
                } else {
                    grp
                }
                FilterChip(
                    selected = grp == selectedGroup,
                    onClick = { selectedGroup = grp },
                    label = { Text(chipTitle) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
            items(filteredChannels, key = { it.id }) { ch ->
                val isPlaying = currentChannel?.id == ch.id
                val isFav = favoriteIds.contains(ch.id)
                val epgNow = ch.epgId?.let { epgMap[it] } ?: epgMap[ch.name]

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isPlaying) theme.selectedCard else theme.card)
                        .border(if (isPlaying) 2.dp else 0.dp, if (isPlaying) theme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable { if (isPlaying) onFullscreen() else onSelectChannel(ch) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${ch.number}", color = theme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.width(42.dp))
                    if (!ch.logoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = ch.logoUrl,
                            contentDescription = ch.name,
                            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(ch.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = theme.text)
                        if (!epgNow.isNullOrBlank()) {
                            Text(epgNow, fontSize = 13.sp, color = theme.primary, maxLines = 1)
                        } else {
                            Text(ch.group, fontSize = 12.sp, color = theme.subtext, maxLines = 1)
                        }
                    }
                    IconButton(onClick = { onToggleFavorite(ch) }) {
                        Icon(
                            imageVector = if (isFav) Icons.Default.Star else Icons.Default.StarBorder,
                            tint = if (isFav) Color(0xFFF59E0B) else theme.subtext,
                            contentDescription = null
                        )
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
    diagnosticLog: String,
    onBackFolder: () -> Unit,
    onClickItem: (VodMovie) -> Unit,
    onServerSearch: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    if (canGoBack) {
        BackHandler { onBackFolder() }
    }

    val filtered = remember(movies, searchQuery) {
        if (searchQuery.isBlank()) movies
        else movies.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (canGoBack) {
                    Button(
                        onClick = onBackFolder,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 10.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (S.isRu) "Назад" else "Back")
                    }
                }
                Text(
                    text = folderTitle,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = theme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (movies.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    Text(
                        if (S.isRu) "В этом разделе пока пусто" else "No items loaded",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.text
                    )
                    if (diagnosticLog.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = diagnosticLog,
                            fontSize = 12.sp,
                            color = theme.subtext,
                            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(theme.card).padding(12.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onOpenSettings) { Text(S.goToSettings) }
                }
            }
            return
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(S.searchHint, color = theme.subtext) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = theme.primary) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = null, tint = theme.subtext)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.weight(1f).height(52.dp)
            )
            Button(
                onClick = { onServerSearch(searchQuery) },
                modifier = Modifier.height(52.dp)
            ) {
                Text(if (S.isRu) "Найти" else "Find")
            }
        }

        Spacer(Modifier.height(10.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 155.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filtered, key = { it.id }) { movie ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = theme.card),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onClickItem(movie) }
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (movie.isFolder && movie.posterUrl.isNullOrBlank()) 90.dp else 180.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(theme.bg),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!movie.posterUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = movie.posterUrl,
                                    contentDescription = movie.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = if (movie.isFolder) Icons.Default.Folder else Icons.Default.PlayCircleFilled,
                                    contentDescription = null,
                                    tint = theme.primary,
                                    modifier = Modifier.size(46.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            movie.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = theme.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (movie.isFolder) (if (S.isRu) "📁 Открыть" else "📁 Open") else "▶ Смотреть",
                            fontSize = 12.sp,
                            color = theme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AccordionSettingsScreen(
    S: Strings,
    theme: AppThemePalette,
    onSelectTheme: (AppThemePalette) -> Unit,
    onToggleLanguage: () -> Unit,
    playlists: List<NamedSource>,
    selectedPlaylistId: String,
    onSelectPlaylist: (String) -> Unit,
    onAddPlaylist: (String, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    epgSources: List<NamedSource>,
    selectedEpgId: String,
    usePlaylistEpg: Boolean,
    detectedPlaylistEpgUrl: String?,
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
    onSaveAndReload: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var expandedRow by remember { mutableStateOf(0) }
    fun toggleRow(idx: Int) {
        expandedRow = if (expandedRow == idx) -1 else idx
    }

    var newPlaylistName by remember { mutableStateOf("") }
    var newPlaylistUrl by remember { mutableStateOf("") }
    var newEpgName by remember { mutableStateOf("") }
    var newEpgUrl by remember { mutableStateOf("") }

    var updateStatus by remember { mutableStateOf("") }
    var latestRelease by remember { mutableStateOf<ReleaseInfo?>(null) }
    var isUpdating by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ExpandableSectionRow(
            title = S.catAppearance,
            isExpanded = expandedRow == 0,
            theme = theme,
            onToggle = { toggleRow(0) }
        ) {
            AppThemePalette.entries.forEach { item ->
                val isChosen = item == theme
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(item.bg)
                        .border(if (isChosen) 2.5.dp else 1.dp, if (isChosen) item.primary else item.subtext, RoundedCornerShape(12.dp))
                        .clickable { onSelectTheme(item) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(24.dp).clip(CircleShape).background(item.primary))
                        Spacer(Modifier.width(12.dp))
                        Text(if (S.isRu) item.titleRu else item.titleEn, fontWeight = FontWeight.Bold, color = item.text)
                    }
                    if (isChosen) Icon(Icons.Default.Check, contentDescription = null, tint = item.primary)
                }
            }
            Button(onClick = onToggleLanguage, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Icon(Icons.Default.Language, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (S.isRu) "Язык: Русский 🇷🇺 (Сменить на EN)" else "Language: English 🇬🇧 (Switch to RU)")
            }
        }

        ExpandableSectionRow(
            title = "${S.catPlaylists} (${playlists.size})",
            isExpanded = expandedRow == 1,
            theme = theme,
            onToggle = { toggleRow(1) }
        ) {
            if (playlists.isNotEmpty()) {
                val currentPlName = playlists.find { it.id == selectedPlaylistId }?.name ?: playlists.first().name
                SourceDropdownSelector(
                    label = S.activePlaylistLabel,
                    selectedTitle = "📺 $currentPlName",
                    options = playlists.map { it.id to it.name },
                    theme = theme,
                    onSelect = onSelectPlaylist
                )
            }
            OutlinedTextField(value = newPlaylistName, onValueChange = { newPlaylistName = it }, label = { Text(S.addSourceName) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = newPlaylistUrl,
                onValueChange = { newPlaylistUrl = it },
                label = { Text(S.addSourceUrl) },
                trailingIcon = {
                    if (newPlaylistUrl.isNotEmpty()) {
                        IconButton(onClick = { newPlaylistUrl = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (newPlaylistUrl.isNotBlank()) {
                        onAddPlaylist(newPlaylistName.trim().ifEmpty { "Плейлист ${playlists.size + 1}" }, newPlaylistUrl.trim())
                        newPlaylistName = ""
                        newPlaylistUrl = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(S.addBtn, fontWeight = FontWeight.Bold) }

            playlists.forEach { pl ->
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(pl.name, fontWeight = FontWeight.Bold, color = theme.text)
                        Text(pl.url, fontSize = 12.sp, color = theme.subtext, maxLines = 1)
                    }
                    IconButton(onClick = { onDeletePlaylist(pl.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF4444))
                    }
                }
            }
        }

        ExpandableSectionRow(
            title = S.catEpg,
            isExpanded = expandedRow == 2,
            theme = theme,
            onToggle = { toggleRow(2) }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(S.usePlaylistEpgSwitch, fontWeight = FontWeight.Bold, color = theme.text)
                    if (!detectedPlaylistEpgUrl.isNullOrBlank()) {
                        Text("EPG: $detectedPlaylistEpgUrl", fontSize = 12.sp, color = theme.primary)
                    }
                }
                Switch(checked = usePlaylistEpg, onCheckedChange = onToggleUsePlaylistEpg)
            }

            val epgOptions = listOf("AUTO_PLAYLIST" to S.epgFromPlaylistOption) + epgSources.map { it.id to it.name }
            val curEpgTitle = epgOptions.find { it.first == selectedEpgId }?.second ?: S.epgFromPlaylistOption
            SourceDropdownSelector(label = S.activeEpgLabel, selectedTitle = curEpgTitle, options = epgOptions, theme = theme, onSelect = onSelectEpg)

            OutlinedTextField(value = newEpgName, onValueChange = { newEpgName = it }, label = { Text(S.addSourceName) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = newEpgUrl,
                onValueChange = { newEpgUrl = it },
                label = { Text(S.addSourceUrl) },
                trailingIcon = {
                    if (newEpgUrl.isNotEmpty()) {
                        IconButton(onClick = { newEpgUrl = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (newEpgUrl.isNotBlank()) {
                        onAddEpg(newEpgName.trim().ifEmpty { "EPG ${epgSources.size + 1}" }, newEpgUrl.trim())
                        newEpgName = ""
                        newEpgUrl = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(S.addBtn, fontWeight = FontWeight.Bold) }

            epgSources.forEach { epg ->
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(epg.name, fontWeight = FontWeight.Bold, color = theme.text)
                        Text(epg.url, fontSize = 12.sp, color = theme.subtext, maxLines = 1)
                    }
                    IconButton(onClick = { onDeleteEpg(epg.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF4444))
                    }
                }
            }
        }

        ExpandableSectionRow(
            title = S.catPortals,
            isExpanded = expandedRow == 3,
            theme = theme,
            onToggle = { toggleRow(3) }
        ) {
            Text("🎬 VPortal (TVlider / iLook)", fontWeight = FontWeight.Bold, color = theme.primary)
            OutlinedTextField(
                value = vportalUrl,
                onValueChange = onVportalChange,
                label = { Text("portal::[key:...]http://.../api/v1/") },
                trailingIcon = {
                    if (vportalUrl.isNotEmpty()) {
                        IconButton(onClick = { onVportalChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("📡 Stalker Portal", fontWeight = FontWeight.Bold, color = theme.primary)
            OutlinedTextField(
                value = stalkerUrl,
                onValueChange = onStalkerUrlChange,
                label = { Text("http://.../stalker_portal/c/") },
                trailingIcon = {
                    if (stalkerUrl.isNotEmpty()) {
                        IconButton(onClick = { onStalkerUrlChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(value = stalkerMac, onValueChange = onStalkerMacChange, label = { Text("MAC (00:1A:79:XX:XX:XX)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = onSaveAndReload, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text(S.saveAndReloadBtn, fontWeight = FontWeight.Bold)
            }

            if (vportalDiagnostic.isNotBlank()) {
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Диагностика VPortal:", fontWeight = FontWeight.Bold, color = theme.primary)
                    Text(vportalDiagnostic, fontSize = 12.sp, color = theme.text)
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("VPortalLog", vportalDiagnostic))
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(S.copyLogBtn)
                    }
                }
            }
        }

        ExpandableSectionRow(
            title = S.catQr,
            isExpanded = expandedRow == 4,
            theme = theme,
            onToggle = { toggleRow(4) }
        ) {
            val localIp = remember { getDeviceLocalIp() }
            val remoteUrl = "http://$localIp:8989"
            val qrBitmap = remember(remoteUrl) { generateQrBitmap(remoteUrl, 360) }
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR",
                        modifier = Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("🌐 $remoteUrl", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = theme.primary)
            }
        }

        ExpandableSectionRow(
            title = if (S.isRu) "🔄 Обновление приложения (GitHub)" else "🔄 App Update (GitHub)",
            isExpanded = expandedRow == 5,
            theme = theme,
            onToggle = { toggleRow(5) }
        ) {
            Button(
                onClick = {
                    scope.launch {
                        isUpdating = true
                        updateStatus = if (S.isRu) "Проверяем свежую сборку на GitHub..." else "Checking GitHub..."
                        val res = GitHubUpdater.checkLatestRelease()
                        isUpdating = false
                        res.onSuccess { info ->
                            latestRelease = info
                            updateStatus = if (S.isRu) "Найдена сборка: ${info.versionTag}" else "Found: ${info.versionTag}"
                        }.onFailure { err ->
                            updateStatus = if (S.isRu) "Ошибка: ${err.localizedMessage}" else "Error: ${err.localizedMessage}"
                        }
                    }
                },
                enabled = !isUpdating,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (S.isRu) "Проверить наличие обновлений" else "Check for Updates")
            }

            if (isUpdating) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = theme.primary)
            }
            if (updateStatus.isNotBlank()) {
                Text(updateStatus, fontSize = 13.sp, color = theme.text)
            }
            if (latestRelease != null) {
                Button(
                    onClick = {
                        val info = latestRelease ?: return@Button
                        scope.launch {
                            isUpdating = true
                            updateStatus = if (S.isRu) "Скачиваем ${info.versionTag}..." else "Downloading ${info.versionTag}..."
                            val dl = GitHubUpdater.downloadAndInstall(context, info.downloadUrl)
                            isUpdating = false
                            dl.onSuccess {
                                updateStatus = if (S.isRu) "Окно установки открыто!" else "Installer opened!"
                            }.onFailure { err ->
                                updateStatus = if (S.isRu) "Ошибка установки: ${err.localizedMessage}" else "Error: ${err.localizedMessage}"
                            }
                        }
                    },
                    enabled = !isUpdating,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (S.isRu) "⬇️ Скачать и установить ${latestRelease!!.versionTag}"
                        else "⬇️ Download & Install ${latestRelease!!.versionTag}",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun SmartVideoPlayer(
    streamUrl: String,
    userAgent: String?,
    reconnectingText: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isReconnecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val exoPlayer = remember { ExoPlayer.Builder(context).build().apply { playWhenReady = true } }

    LaunchedEffect(streamUrl, userAgent) {
        if (streamUrl.isBlank()) return@LaunchedEffect
        isReconnecting = false
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent ?: "Lavf/60.3.100")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(12000)

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
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (isReconnecting) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(reconnectingText, color = Color(0xFFF59E0B), fontSize = 13.sp)
            }
        }
    }
}
