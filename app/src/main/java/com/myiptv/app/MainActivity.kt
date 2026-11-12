@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.myiptv.app

import android.content.Context
import android.graphics.Bitmap
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
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
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

// ================= МОДЕЛИ ДАННЫХ =================

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
    val streamUrl: String
)

// ================= ЛОКАЛИЗАЦИЯ (RU / EN) =================

data class Strings(
    val isRu: Boolean,
    val chooseLangTitle: String,
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
    val qrTitle: String,
    val qrSubtitle: String,
    val m3uLabel: String,
    val epgLabel: String,
    val vportalLabel: String,
    val stalkerUrlLabel: String,
    val stalkerMacLabel: String,
    val saveAndLoad: String,
    val loading: String,
    val langSwitch: String,
    val vportalStatus: String,
    val reconnecting: String
)

val RuStrings = Strings(
    isRu = true,
    chooseLangTitle = "Выберите язык / Choose Language",
    liveTv = "Телеканалы",
    vod = "Кинозал (VOD)",
    favorites = "Избранное",
    settings = "Настройки",
    allGroups = "Все каналы",
    searchHint = "Поиск канала или фильма...",
    prevChannel = "Пред. канал",
    fullscreen = "На весь экран",
    noChannels = "Плейлист еще не загружен",
    goToSettings = "Перейти в Настройки / Сканировать QR",
    qrTitle = "Быстрая загрузка с телефона по QR-коду",
    qrSubtitle = "Наведите камеру телефона на QR-код (телефон и ТВ должны быть в одной сети Wi-Fi), чтобы вставить ссылки на M3U, VPortal и EPG:",
    m3uLabel = "Ссылка на плейлист M3U / M3U8",
    epgLabel = "Ссылка на свою телепрограмму (EPG .xml или .xml.gz)",
    vportalLabel = "Ссылка VPortal (portal::[key:...]http://...)",
    stalkerUrlLabel = "Ссылка Stalker Portal",
    stalkerMacLabel = "MAC-адрес Stalker (00:1A:79:...)",
    saveAndLoad = "Сохранить и загрузить всё",
    loading = "Загрузка данных...",
    langSwitch = "Язык интерфейса: Русский (Нажмите для смены на EN)",
    vportalStatus = "Диагностика VPortal / Статус:",
    reconnecting = "Поток прервался, переподключаемся..."
)

val EnStrings = Strings(
    isRu = false,
    chooseLangTitle = "Choose Language / Выберите язык",
    liveTv = "Live TV",
    vod = "Movies (VOD)",
    favorites = "Favorites",
    settings = "Settings",
    allGroups = "All Channels",
    searchHint = "Search channels or movies...",
    prevChannel = "Recall",
    fullscreen = "Fullscreen",
    noChannels = "No playlist loaded yet",
    goToSettings = "Open Settings / Scan QR",
    qrTitle = "Quick Setup via Phone QR Code",
    qrSubtitle = "Scan this QR code with your phone camera (same Wi-Fi) to paste M3U, VPortal, and EPG links:",
    m3uLabel = "M3U / M3U8 Playlist URL",
    epgLabel = "Custom EPG TV Guide URL (.xml or .xml.gz)",
    vportalLabel = "VPortal Link (portal::[key:...]http://...)",
    stalkerUrlLabel = "Stalker Portal URL",
    stalkerMacLabel = "Stalker MAC Address (00:1A:79:...)",
    saveAndLoad = "Save & Load All",
    loading = "Loading data...",
    langSwitch = "Language: English (Tap to switch to RU)",
    vportalStatus = "VPortal Status / Log:",
    reconnecting = "Stream dropped, reconnecting..."
)

// ================= ГЛАВНЫЙ ЭКРАН ПРИЛОЖЕНИЯ =================

class MainActivity : ComponentActivity() {
    private var webServer: SimpleQrWebServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("myiptv_prefs", Context.MODE_PRIVATE)

        setContent {
            var isFirstLaunch by remember { mutableStateOf(!prefs.contains("lang_selected")) }
            var isRu by remember { mutableStateOf(prefs.getString("lang", "ru") == "ru") }
            val S = if (isRu) RuStrings else EnStrings

            // Настройки пользователя
            var m3uUrl by remember { mutableStateOf(prefs.getString("m3u_url", "") ?: "") }
            var epgUrl by remember { mutableStateOf(prefs.getString("epg_url", "") ?: "") }
            var vportalUrl by remember { mutableStateOf(prefs.getString("vportal_url", "") ?: "") }
            var stalkerUrl by remember { mutableStateOf(prefs.getString("stalker_url", "") ?: "") }
            var stalkerMac by remember { mutableStateOf(prefs.getString("stalker_mac", "") ?: "") }

            // Состояние загруженных данных
            var channels by remember { mutableStateOf<List<Channel>>(emptyList()) }
            var vodMovies by remember { mutableStateOf<List<VodMovie>>(emptyList()) }
            var epgMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
            var favoriteIds by remember {
                mutableStateOf(prefs.getStringSet("favs", emptySet())?.toSet() ?: emptySet())
            }

            var currentTab by remember { mutableStateOf(0) } // 0=TV, 1=VOD, 2=Favs, 3=Settings
            var isLoading by remember { mutableStateOf(false) }
            var statusMessage by remember { mutableStateOf("") }

            // Плеер и переключение каналов
            var currentChannel by remember { mutableStateOf<Channel?>(null) }
            var previousChannel by remember { mutableStateOf<Channel?>(null) }
            var currentVodStream by remember { mutableStateOf<Pair<String, String>?>(null) }
            var isFullscreen by remember { mutableStateOf(false) }

            val scope = rememberCoroutineScope()

            fun triggerFullLoad(
                newM3u: String,
                newEpg: String,
                newVportal: String,
                newStalker: String,
                newMac: String
            ) {
                prefs.edit()
                    .putString("m3u_url", newM3u.trim())
                    .putString("epg_url", newEpg.trim())
                    .putString("vportal_url", newVportal.trim())
                    .putString("stalker_url", newStalker.trim())
                    .putString("stalker_mac", newMac.trim())
                    .apply()

                scope.launch {
                    isLoading = true
                    statusMessage = S.loading
                    val loadedChannels = mutableListOf<Channel>()
                    val loadedVod = mutableListOf<VodMovie>()

                    // 1. Загрузка M3U / M3U8
                    if (newM3u.isNotBlank()) {
                        val res = NetworkLoader.loadM3u(newM3u.trim())
                        loadedChannels.addAll(res.first)
                        loadedVod.addAll(res.second)
                    }

                    // 2. Загрузка VPortal (TVlider / iLook)
                    if (newVportal.isNotBlank()) {
                        val vpRes = NetworkLoader.loadVPortal(newVportal.trim())
                        loadedVod.addAll(vpRes.first)
                        statusMessage = vpRes.second
                    }

                    // 3. Загрузка Stalker Portal
                    if (newStalker.isNotBlank() && newMac.isNotBlank()) {
                        val stChannels = NetworkLoader.loadStalker(newStalker.trim(), newMac.trim())
                        loadedChannels.addAll(stChannels)
                    }

                    channels = loadedChannels
                    vodMovies = loadedVod
                    if (currentChannel == null && loadedChannels.isNotEmpty()) {
                        currentChannel = loadedChannels.first()
                    }
                    isLoading = false

                    // 4. Фоновая загрузка телепрограммы EPG
                    if (newEpg.isNotBlank()) {
                        epgMap = NetworkLoader.loadEpgCurrentPrograms(newEpg.trim())
                    }
                }
            }

            // Запуск встроенного QR-сервера для отправки ссылок с телефона
            DisposableEffect(isRu) {
                webServer?.stop()
                webServer = SimpleQrWebServer(8989, isRu) { payload ->
                    scope.launch(Dispatchers.Main) {
                        if (payload["m3u"]?.isNotBlank() == true) m3uUrl = payload["m3u"]!!
                        if (payload["epg"]?.isNotBlank() == true) epgUrl = payload["epg"]!!
                        if (payload["vportal"]?.isNotBlank() == true) vportalUrl = payload["vportal"]!!
                        if (payload["stalker"]?.isNotBlank() == true) stalkerUrl = payload["stalker"]!!
                        if (payload["mac"]?.isNotBlank() == true) stalkerMac = payload["mac"]!!
                        triggerFullLoad(m3uUrl, epgUrl, vportalUrl, stalkerUrl, stalkerMac)
                        currentTab = 0
                    }
                }.also { it.start() }
                onDispose { webServer?.stop() }
            }

            // Автозагрузка при старте, если ссылки уже сохранены
            LaunchedEffect(Unit) {
                if (!isFirstLaunch && (m3uUrl.isNotBlank() || vportalUrl.isNotBlank() || stalkerUrl.isNotBlank())) {
                    triggerFullLoad(m3uUrl, epgUrl, vportalUrl, stalkerUrl, stalkerMac)
                }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E5FF),
                    background = Color(0xFF0F111A),
                    surface = Color(0xFF181B28),
                    onPrimary = Color.Black,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F111A)) {
                    // ЭКРАН 1: Выбор языка при самом первом запуске
                    if (isFirstLaunch) {
                        FirstLaunchLanguageScreen(
                            onSelectLanguage = { selectedRu ->
                                isRu = selectedRu
                                prefs.edit()
                                    .putBoolean("lang_selected", true)
                                    .putString("lang", if (selectedRu) "ru" else "en")
                                    .apply()
                                isFirstLaunch = false
                                currentTab = 3 // Сразу открываем настройки с QR-кодом
                            }
                        )
                    } else if (isFullscreen && (currentChannel != null || currentVodStream != null)) {
                        // ЭКРАН 2: Полноэкранный плеер
                        BackHandler { isFullscreen = false }
                        val playUrl = currentVodStream?.second ?: currentChannel?.streamUrl ?: ""
                        val playTitle = currentVodStream?.first ?: currentChannel?.name ?: ""
                        Box(modifier = Modifier.fillMaxSize()) {
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
                                    .background(Color(0xAA0F111A), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(playTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Spacer(Modifier.width(16.dp))
                                Button(onClick = { isFullscreen = false }) {
                                    Text(if (isRu) "Свернуть" else "Exit Fullscreen")
                                }
                            }
                        }
                    } else {
                        // ЭКРАН 3: Основной интерфейс приложения
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Верхняя панель навигации (удобно и для пульта ТВ, и для пальца)
                            NavigationBar(
                                containerColor = Color(0xFF181B28),
                                modifier = Modifier.height(64.dp)
                            ) {
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
                                    label = { Text("${S.vod} (${vodMovies.size})") }
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
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color(0xFF00E5FF)
                                )
                            }

                            when (currentTab) {
                                0, 2 -> LiveTvScreen(
                                    S = S,
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
                                    movies = vodMovies,
                                    statusMessage = statusMessage,
                                    onPlayMovie = { movie ->
                                        currentVodStream = movie.title to movie.streamUrl
                                        isFullscreen = true
                                    },
                                    onOpenSettings = { currentTab = 3 }
                                )

                                3 -> SettingsAndQrScreen(
                                    S = S,
                                    m3uUrl = m3uUrl,
                                    epgUrl = epgUrl,
                                    vportalUrl = vportalUrl,
                                    stalkerUrl = stalkerUrl,
                                    stalkerMac = stalkerMac,
                                    statusMessage = statusMessage,
                                    onM3uChange = { m3uUrl = it },
                                    onEpgChange = { epgUrl = it },
                                    onVportalChange = { vportalUrl = it },
                                    onStalkerUrlChange = { stalkerUrl = it },
                                    onStalkerMacChange = { stalkerMac = it },
                                    onToggleLanguage = {
                                        isRu = !isRu
                                        prefs.edit().putString("lang", if (isRu) "ru" else "en").apply()
                                    },
                                    onSave = {
                                        triggerFullLoad(m3uUrl, epgUrl, vportalUrl, stalkerUrl, stalkerMac)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ================= ЭКРАН ВЫБОРА ЯЗЫКА ПРИ ПЕРВОМ ЗАПУСКЕ =================

@Composable
fun FirstLaunchLanguageScreen(onSelectLanguage: (Boolean) -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF181B28)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.padding(24.dp).widthIn(max = 460.dp)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Language,
                    contentDescription = null,
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Выберите язык / Choose Language",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.height(28.dp))
                Button(
                    onClick = { onSelectLanguage(true) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("🇷🇺  Русский", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { onSelectLanguage(false) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("🇬🇧  English", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}

// ================= ЭКРАН ТЕЛЕКАНАЛОВ + ПЛЕЕР + EPG =================

@Composable
fun LiveTvScreen(
    S: Strings,
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
    if (channels.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(S.noChannels, fontSize = 20.sp, color = Color.LightGray)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenSettings) {
                    Icon(Icons.Default.QrCode, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(S.goToSettings)
                }
            }
        }
        return
    }

    val groups = remember(channels, S) {
        listOf(S.allGroups) + channels.map { it.group }.distinct()
    }
    var selectedGroup by remember { mutableStateOf(S.allGroups) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredChannels = remember(channels, selectedGroup, searchQuery, S) {
        channels.filter { ch ->
            val matchGroup = selectedGroup == S.allGroups || ch.group == selectedGroup
            val matchQuery = searchQuery.isBlank() || ch.name.contains(searchQuery, ignoreCase = true)
            matchGroup && matchQuery
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // Верхний блок: Видеоплеер + управление текущим каналом
        if (currentChannel != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
            ) {
                Box(modifier = Modifier.weight(1.4f).fillMaxHeight()) {
                    SmartVideoPlayer(
                        streamUrl = currentChannel.streamUrl,
                        userAgent = currentChannel.userAgent,
                        reconnectingText = S.reconnecting,
                        modifier = Modifier.fillMaxSize().clickable { onFullscreen() }
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color(0xFF181B28))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "${currentChannel.number}. ${currentChannel.name}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color(0xFF00E5FF)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = currentChannel.group,
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
                        Spacer(Modifier.height(8.dp))
                        val nowPlaying = currentChannel.epgId?.let { epgMap[it] } ?: epgMap[currentChannel.name]
                        if (!nowPlaying.isNullOrBlank()) {
                            Text(
                                text = "📺 $nowPlaying",
                                fontSize = 14.sp,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onFullscreen,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(8.dp)
                        ) {
                            Icon(Icons.Default.Fullscreen, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(S.fullscreen, fontSize = 12.sp, maxLines = 1)
                        }
                        if (previousChannel != null) {
                            OutlinedButton(
                                onClick = { onSelectChannel(previousChannel) },
                                contentPadding = PaddingValues(8.dp)
                            ) {
                                Icon(Icons.Default.History, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(S.prevChannel, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // Строка поиска
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(S.searchHint) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        )

        Spacer(Modifier.height(8.dp))

        // Горизонтальный список групп (категорий)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups) { grp ->
                val isSelected = grp == selectedGroup
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedGroup = grp },
                    label = { Text(grp) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Список каналов
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filteredChannels, key = { it.id }) { ch ->
                val isPlaying = currentChannel?.id == ch.id
                val isFav = favoriteIds.contains(ch.id)
                val epgNow = ch.epgId?.let { epgMap[it] } ?: epgMap[ch.name]

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isPlaying) Color(0xFF22304A) else Color(0xFF181B28))
                        .border(
                            width = if (isPlaying) 2.dp else 0.dp,
                            color = if (isPlaying) Color(0xFF00E5FF) else Color.Transparent,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable {
                            if (isPlaying) onFullscreen() else onSelectChannel(ch)
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${ch.number}",
                        color = Color(0xFF00E5FF),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(42.dp)
                    )
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
                        Text(ch.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color.White)
                        if (!epgNow.isNullOrBlank()) {
                            Text(epgNow, fontSize = 13.sp, color = Color(0xFFA0A8C0), maxLines = 1)
                        } else {
                            Text(ch.group, fontSize = 12.sp, color = Color.Gray, maxLines = 1)
                        }
                    }
                    IconButton(onClick = { onToggleFavorite(ch) }) {
                        Icon(
                            imageVector = if (isFav) Icons.Default.Star else Icons.Default.StarBorder,
                            tint = if (isFav) Color(0xFFFFD54F) else Color.Gray,
                            contentDescription = null
                        )
                    }
                }
            }
        }
    }
}

// ================= ЭКРАН КИНОЗАЛА (VOD / VPORTAL) =================

@Composable
fun VodScreen(
    S: Strings,
    movies: List<VodMovie>,
    statusMessage: String,
    onPlayMovie: (VodMovie) -> Unit,
    onOpenSettings: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val categories = remember(movies, S) {
        listOf(S.allGroups) + movies.map { it.category }.distinct()
    }
    var selectedCat by remember { mutableStateOf(S.allGroups) }

    val filtered = remember(movies, selectedCat, searchQuery, S) {
        movies.filter { m ->
            val matchCat = selectedCat == S.allGroups || m.category == selectedCat
            val matchQ = searchQuery.isBlank() || m.title.contains(searchQuery, ignoreCase = true)
            matchCat && matchQ
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        if (statusMessage.isNotBlank()) {
            Text(
                text = "${S.vportalStatus} $statusMessage",
                fontSize = 12.sp,
                color = Color(0xFF00E5FF),
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        if (movies.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (S.isRu) "Фильмы и сериалы еще не загружены" else "No VOD movies loaded yet",
                        fontSize = 18.sp,
                        color = Color.LightGray
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onOpenSettings) {
                        Text(S.goToSettings)
                    }
                }
            }
            return
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(S.searchHint) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        )

        Spacer(Modifier.height(8.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(categories) { cat ->
                FilterChip(
                    selected = selectedCat == cat,
                    onClick = { selectedCat = cat },
                    label = { Text(cat) }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filtered, key = { it.id }) { movie ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF181B28)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlayMovie(movie) }
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(190.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF232738)),
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
                                Icon(Icons.Default.Movie, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(48.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            movie.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (movie.year.isNotBlank() || movie.rating.isNotBlank()) {
                            Text(
                                "${movie.year}  ⭐ ${movie.rating}",
                                fontSize = 12.sp,
                                color = Color(0xFF00E5FF)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ================= ЭКРАН НАСТРОЕК И QR-ПУЛЬТА =================

@Composable
fun SettingsAndQrScreen(
    S: Strings,
    m3uUrl: String,
    epgUrl: String,
    vportalUrl: String,
    stalkerUrl: String,
    stalkerMac: String,
    statusMessage: String,
    onM3uChange: (String) -> Unit,
    onEpgChange: (String) -> Unit,
    onVportalChange: (String) -> Unit,
    onStalkerUrlChange: (String) -> Unit,
    onStalkerMacChange: (String) -> Unit,
    onToggleLanguage: () -> Unit,
    onSave: () -> Unit
) {
    val localIp = remember { getDeviceLocalIp() }
    val remoteUrl = "http://$localIp:8989"
    val qrBitmap = remember(remoteUrl) { generateQrBitmap(remoteUrl, 340) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Кнопка переключения языка RU / EN
        OutlinedButton(
            onClick = onToggleLanguage,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Language, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(S.langSwitch, color = Color(0xFF00E5FF))
        }

        // Карточка с QR-кодом для удобного ввода с телефона
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF181B28)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier
                            .size(140.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(6.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(S.qrTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF00E5FF))
                    Spacer(Modifier.height(6.dp))
                    Text(S.qrSubtitle, fontSize = 13.sp, color = Color.LightGray)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "🌐 $remoteUrl",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color(0xFFFFD54F)
                    )
                }
            }
        }

        // Поля ручного ввода (если открыто на самом смартфоне)
        OutlinedTextField(
            value = m3uUrl,
            onValueChange = onM3uChange,
            label = { Text(S.m3uLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = epgUrl,
            onValueChange = onEpgChange,
            label = { Text(S.epgLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = vportalUrl,
            onValueChange = onVportalChange,
            label = { Text(S.vportalLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = stalkerUrl,
                onValueChange = onStalkerUrlChange,
                label = { Text(S.stalkerUrlLabel) },
                singleLine = true,
                modifier = Modifier.weight(1.3f)
            )
            OutlinedTextField(
                value = stalkerMac,
                onValueChange = onStalkerMacChange,
                label = { Text(S.stalkerMacLabel) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        Button(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.CloudDownload, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(S.saveAndLoad, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }

        if (statusMessage.isNotBlank()) {
            Text(statusMessage, fontSize = 13.sp, color = Color(0xFF00E5FF))
        }
    }
}

// ================= ВИДЕОПЛЕЕР MEDIA3 С УМНЫМ РЕКОННЕКТОМ =================

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

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }

    LaunchedEffect(streamUrl, userAgent) {
        if (streamUrl.isBlank()) return@LaunchedEffect
        isReconnecting = false
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent ?: "Lavf/60.3.100")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(12000)

        val mediaSource = DefaultMediaSourceFactory(httpFactory)
            .createMediaSource(MediaItem.fromUri(streamUrl))

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Умный авто-реконнект при обрыве потока
                isReconnecting = true
                scope.launch {
                    delay(2500)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    isReconnecting = false
                }
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
                    .background(Color(0xCC0F111A), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(reconnectingText, color = Color(0xFFFFD54F), fontSize = 13.sp)
            }
        }
    }
}

// ================= СЕТЕВОЙ МОДУЛЬ (M3U, EPG, VPORTAL, STALKER) =================

object NetworkLoader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val attrRegex = Regex("""([a-zA-Z0-9_-]+)="([^"]*)"""")
    private val vportalRegex = Regex("""^portal::\[key:([^\]]+)\](https?://.+)$""", RegexOption.IGNORE_CASE)

    // 1. Загрузка и парсинг M3U / M3U8 (Разделяет ТВ-каналы и Фильмы VOD)
    suspend fun loadM3u(url: String): Pair<List<Channel>, List<VodMovie>> = withContext(Dispatchers.IO) {
        val channels = mutableListOf<Channel>()
        val movies = mutableListOf<VodMovie>()
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
                                val isVodFile = t.endsWith(".mp4", true) || t.endsWith(".mkv", true) || t.endsWith(".avi", true)
                                if (isVodFile) {
                                    movies.add(
                                        VodMovie(
                                            id = "m3u_vod_$num",
                                            title = name,
                                            category = group,
                                            posterUrl = logo,
                                            description = "",
                                            year = "",
                                            rating = "",
                                            streamUrl = t
                                        )
                                    )
                                } else {
                                    channels.add(
                                        Channel(
                                            id = "ch_$num",
                                            number = num,
                                            name = name,
                                            group = group,
                                            logoUrl = logo,
                                            streamUrl = t,
                                            epgId = epgId,
                                            userAgent = ua
                                        )
                                    )
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
        channels to movies
    }

    // 2. Загрузка VPortal от TVlider: portal::[key:...]http://.../api/v1/
    suspend fun loadVPortal(rawUrl: String): Pair<List<VodMovie>, String> = withContext(Dispatchers.IO) {
        val movies = mutableListOf<VodMovie>()
        var diagnostic = "OK"
        runCatching {
            val match = vportalRegex.find(rawUrl.trim())
            val key = match?.groupValues?.get(1)?.trim() ?: ""
            var baseUrl = match?.groupValues?.get(2)?.trim() ?: rawUrl.trim()
            if (!baseUrl.endsWith("/")) baseUrl += "/"

            // Пробуем стандартные точки входа VPortal / OttPlayer API v1
            val candidateUrls = listOf(
                "${baseUrl}?key=$key",
                "${baseUrl}items?key=$key",
                "${baseUrl}playlist?key=$key",
                "${baseUrl}categories?key=$key"
            )

            for (testUrl in candidateUrls) {
                val req = Request.Builder()
                    .url(testUrl)
                    .header("User-Agent", "Mozilla/5.0 (SmartHub; SMART-TV; U; Linux/SmartTV) AppleWebKit/531.2+ OttPlayer/2.0")
                    .header("X-Api-Key", key)
                    .header("Authorization", "Bearer $key")
                    .build()

                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string()?.trim() ?: ""
                    if (body.isNotEmpty()) {
                        diagnostic = "HTTP ${resp.code}: " + body.take(120).replace("\n", " ")
                        parseVPortalPayload(body, movies)
                    }
                }
                if (movies.isNotEmpty()) break
            }
        }.onFailure { e ->
            diagnostic = "Ошибка: ${e.localizedMessage}"
        }
        movies to diagnostic
    }

    private fun parseVPortalPayload(body: String, out: MutableList<VodMovie>) {
        // Если сервер вернул M3U-список внутри VPortal
        if (body.startsWith("#EXTM3U")) {
            val lines = body.lines()
            var title = ""
            var group = "VPortal"
            var poster: String? = null
            var idx = 1
            for (l in lines) {
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

        // Если сервер вернул JSON-каталог
        runCatching {
            val arr = if (body.startsWith("[")) {
                JSONArray(body)
            } else {
                val obj = JSONObject(body)
                obj.optJSONArray("data")
                    ?: obj.optJSONArray("items")
                    ?: obj.optJSONArray("movies")
                    ?: obj.optJSONArray("channels")
                    ?: obj.optJSONArray("playlist")
                    ?: JSONArray()
            }
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val stream = item.optString("url")
                    .ifEmpty { item.optString("stream_url") }
                    .ifEmpty { item.optString("file") }
                    .ifEmpty { item.optString("cmd") }
                val title = item.optString("name")
                    .ifEmpty { item.optString("title") }
                    .ifEmpty { "Видео #${i + 1}" }
                if (stream.isNotEmpty()) {
                    out.add(
                        VodMovie(
                            id = "vp_json_$i",
                            title = title,
                            category = item.optString("category", item.optString("genre", "Кинозал")),
                            posterUrl = item.optString("poster", item.optString("logo", item.optString("cover"))),
                            description = item.optString("description", item.optString("plot", "")),
                            year = item.optString("year", ""),
                            rating = item.optString("rating", item.optString("rating_kp", "")),
                            streamUrl = stream
                        )
                    )
                }
            }
        }
    }

    // 3. Загрузка Stalker Portal
    suspend fun loadStalker(portalUrl: String, mac: String): List<Channel> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Channel>()
        runCatching {
            var base = portalUrl.trim()
            if (base.endsWith("/c/")) base = base.removeSuffix("c/")
            if (!base.endsWith("/")) base += "/"
            val cookie = "mac=${ java.net.URLEncoder.encode(mac, "UTF-8") }; stb_lang=ru; timezone=Europe/Moscow"

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

    // 4. Загрузка пользовательской телепрограммы EPG (.xml и сжатого .xml.gz)
    suspend fun loadEpgCurrentPrograms(epgUrl: String): Map<String, String> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, String>()
        runCatching {
            val req = Request.Builder().url(epgUrl).build()
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
                var insideTitle = false
                var count = 0

                while (eventType != XmlPullParser.END_DOCUMENT && count < 4000) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            if (parser.name == "programme") {
                                currentChannelId = parser.getAttributeValue(null, "channel")
                            } else if (parser.name == "title" && currentChannelId != null) {
                                insideTitle = true
                            }
                        }
                        XmlPullParser.TEXT -> {
                            if (insideTitle && currentChannelId != null) {
                                if (!result.containsKey(currentChannelId!!)) {
                                    result[currentChannelId!!] = parser.text
                                    count++
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

// ================= QR-ГЕНЕРАТОР И ЛОКАЛЬНЫЙ СЕРВЕР ДЛЯ ТЕЛЕФОНА =================

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
                val ok = if (isRu) "✅ Успешно отправлено в приложение!" else "✅ Sent to TV App!"
                sendHtml(out, "<html><body style='background:#0F111A;color:#00E5FF;font-family:sans-serif;text-align:center;padding:40px;'><h2>$ok</h2></body></html>")
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
        val title = if (isRu) "Пульт загрузки MyIPTV" else "MyIPTV Remote Setup"
        val btn = if (isRu) "🚀 Отправить в телевизор" else "🚀 Send to TV"
        return """
        <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>
            body{background:#0F111A;color:#fff;font-family:sans-serif;padding:18px;}
            .box{background:#181B28;padding:20px;border-radius:16px;max-width:460px;margin:auto;}
            input{width:100%;padding:12px;margin:8px 0 16px 0;border-radius:8px;border:1px solid #333;background:#0F111A;color:#fff;box-sizing:border-box;}
            button{width:100%;padding:14px;background:#00E5FF;color:#000;font-weight:bold;border:none;border-radius:10px;font-size:16px;}
        </style></head><body><div class="box">
        <h2 style="color:#00E5FF;margin-top:0;">📺 $title</h2>
        <form method="POST" action="/">
            <label>📺 M3U / M3U8 URL:</label><input name="m3u" placeholder="http://.../playlist.m3u8"/>
            <label>📅 EPG (Телепрограмма .xml / .xml.gz):</label><input name="epg" placeholder="http://.../epg.xml.gz"/>
            <label>🎬 VPortal (portal::[key:...]http://...):</label><input name="vportal" placeholder="portal::[key:...]http://..."/>
            <label>📡 Stalker Portal URL:</label><input name="stalker" placeholder="http://.../stalker_portal/c/"/>
            <label>🔑 Stalker MAC:</label><input name="mac" placeholder="00:1A:79:XX:XX:XX"/>
            <button type="submit">$btn</button>
        </form></div></body></html>
        """.trimIndent()
    }
}
