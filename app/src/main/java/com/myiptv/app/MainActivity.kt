@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.myiptv.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val FAV_GROUP_TAG = "__FAV_CATEGORY__"
private const val EPG_URL_TVLIDER = "http://epg.it999.ru/edem.xml.gz"
private const val EPG_URL_IPTVX_LITE = "https://iptvx.one/epg/epg_lite.xml.gz"
private const val EPG_URL_IPTVX_FULL = "https://iptvx.one/epg/epg.xml.gz"
private const val REMINDER_CHANNEL_ID = "cctv_epg_reminders"

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val channelId = intent.getStringExtra("channel_id").orEmpty()
        val channelName = intent.getStringExtra("channel_name").orEmpty().ifEmpty { "CCTV Plus" }
        val programTitle = intent.getStringExtra("program_title").orEmpty().ifEmpty { "Program starting" }
        val notifId = (channelId.hashCode() * 31 + programTitle.hashCode())

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(REMINDER_CHANNEL_ID, "EPG Reminders", NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(ch)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("open_channel_id", channelId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = PendingIntent.getActivity(
            context,
            notifId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("📺 $channelName")
            .setContentText(programTitle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        runCatching { nm.notify(notifId, notif) }
    }
}

private fun reminderRequestCode(channelId: String, startMs: Long): Int =
    channelId.hashCode() * 31 + (startMs / 1000L).toInt()

private fun scheduleReminderAlarm(context: Context, rem: ProgramReminder) {
    if (rem.startMs <= System.currentTimeMillis()) return
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, ReminderReceiver::class.java).apply {
        putExtra("channel_id", rem.channelId)
        putExtra("channel_name", rem.channelName)
        putExtra("program_title", rem.programTitle)
    }
    val pi = PendingIntent.getBroadcast(
        context,
        reminderRequestCode(rem.channelId, rem.startMs),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, rem.startMs, pi) }
}

private fun cancelReminderAlarm(context: Context, rem: ProgramReminder) {
    val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(context, ReminderReceiver::class.java)
    val pi = PendingIntent.getBroadcast(
        context,
        reminderRequestCode(rem.channelId, rem.startMs),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    runCatching { am.cancel(pi) }
}

fun findChannelSchedule(ch: Channel, epgMap: Map<String, List<EpgProgramItem>>): List<EpgProgramItem> {
    if (epgMap.isEmpty()) return emptyList()
    ch.epgId?.takeIf { it.isNotBlank() }?.let { id ->
        epgMap[id]?.let { return it }
        epgMap[id.lowercase()]?.let { return it }
        val normId = normalizeChannelKey(id)
        if (normId.isNotEmpty()) epgMap[normId]?.let { return it }
    }
    val lowName = ch.name.trim().lowercase()
    epgMap[lowName]?.let { return it }
    val normName = normalizeChannelKey(ch.name)
    if (normName.isNotEmpty()) epgMap[normName]?.let { return it }
    return emptyList()
}

private fun loadSavedReminders(prefs: SharedPreferences): List<ProgramReminder> {
    val raw = prefs.getString("reminders_json", null) ?: return emptyList()
    val now = System.currentTimeMillis()
    val list = mutableListOf<ProgramReminder>()
    runCatching {
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val startMs = o.optLong("startMs", 0L)
            if (startMs > now - 10L * 60_000L) {
                list.add(
                    ProgramReminder(
                        id = o.optString("id"),
                        channelId = o.optString("channelId"),
                        channelName = o.optString("channelName"),
                        programTitle = o.optString("programTitle"),
                        startMs = startMs
                    )
                )
            }
        }
    }
    return list
}

private fun saveRemindersToPrefs(prefs: SharedPreferences, list: List<ProgramReminder>) {
    val arr = JSONArray()
    list.forEach { r ->
        val o = JSONObject()
        o.put("id", r.id)
        o.put("channelId", r.channelId)
        o.put("channelName", r.channelName)
        o.put("programTitle", r.programTitle)
        o.put("startMs", r.startMs)
        arr.put(o)
    }
    prefs.edit().putString("reminders_json", arr.toString()).apply()
}

@Composable
fun PlayerClockBadge(clockPosition: Int, accentColor: Color, modifier: Modifier = Modifier) {
    if (clockPosition !in 1..2) return
    val clockText by produceState(initialValue = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) {
        while (true) {
            value = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            delay(15_000L)
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.align(if (clockPosition == 1) Alignment.TopStart else Alignment.TopEnd)
                .padding(horizontal = 10.dp, vertical = 8.dp).clip(RoundedCornerShape(8.dp))
                .background(Color(0xAA000000)).border(1.dp, accentColor.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) { Text(clockText, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
    }
}

@Composable
fun PlayerActionPill(text: String, accentColor: Color = Color.White, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x55222834))
            .border(1.dp, accentColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .clickable { onClick() }.padding(horizontal = 11.dp, vertical = 6.dp)
    ) { Text(text, color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

class MainActivity : ComponentActivity() {
    private var isInPipModeState by mutableStateOf(false)
    private var autoPipAllowed = false
    private var pendingOpenChannelId by mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingOpenChannelId = intent.getStringExtra("open_channel_id")
    }

    fun triggerPipMode() {
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipModeState = isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (autoPipAllowed && !isInPictureInPictureMode) {
            triggerPipMode()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingOpenChannelId = intent?.getStringExtra("open_channel_id")
        val prefs = getSharedPreferences("cctv_plus_prefs", Context.MODE_PRIVATE)

        runCatching {
            val legacyPin = prefs.getString("parental_pin", null)
            if (!legacyPin.isNullOrBlank() && prefs.getString("parental_pin_hash", null).isNullOrBlank()) {
                setParentalPin(prefs, legacyPin)
            }
        }

        setContent {
            val lifecycleOwner = LocalLifecycleOwner.current
            var isFirstLaunch by remember { mutableStateOf(!prefs.contains("lang_selected")) }
            var isRu by remember { mutableStateOf(prefs.getString("lang", "ru") == "ru") }
            var deviceType by remember { mutableStateOf(prefs.getString("device_type", "mobile") ?: "mobile") }
            var controlMode by remember { mutableStateOf(prefs.getString("control_mode", "touch") ?: "touch") }
            var clockPosition by remember { mutableStateOf(prefs.getInt("clock_pos", 0)) }
            var playerEngine by remember { mutableStateOf(prefs.getInt("player_engine", 0)) }
            var archiveDays by remember { mutableStateOf(prefs.getInt("archive_days", 3)) }
            var epgOffsetHours by remember { mutableStateOf(prefs.getInt("epg_offset_hours", 0)) }
            var parentalEnabled by remember { mutableStateOf(prefs.getBoolean("parental_enabled", false)) }
            var parentalPin by remember { mutableStateOf("") }
            var adultUnlockedSession by remember { mutableStateOf(false) }
            var pendingPinAction by remember { mutableStateOf<(() -> Unit)?>(null) }

            var themePalette by remember { mutableStateOf(AppThemePalette.fromId(prefs.getString("theme_id", AppThemePalette.DARK_EMERALD.id))) }
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
            var hiddenGroups by remember { mutableStateOf(loadHiddenGroups(prefs)) }
            var vodItems by remember { mutableStateOf<List<VodMovie>>(emptyList()) }
            var vodHistory by remember { mutableStateOf(loadVodHistory(prefs)) }
            var vodFolderStack by remember { mutableStateOf<List<Pair<String, List<VodMovie>>>>(emptyList()) }
            var currentVodFolderTitle by remember { mutableStateOf(if (isRu) "Кинозал VPortal" else "VPortal VOD") }
            var isLoadingMoreVod by remember { mutableStateOf(false) }
            var vodNoticeMessage by remember { mutableStateOf("") }

            var epgScheduleMap by remember { mutableStateOf<Map<String, List<EpgProgramItem>>>(emptyMap()) }
            var detectedPlaylistEpgUrl by remember { mutableStateOf<String?>(null) }
            var isEpgLoading by remember { mutableStateOf(false) }
            var epgStatusText by remember { mutableStateOf(if (isRu) "Ожидание EPG..." else "Waiting for EPG...") }

            var favoriteIds by remember { mutableStateOf(prefs.getStringSet("favs", emptySet())?.toSet() ?: emptySet()) }
            var currentTab by remember { mutableStateOf(0) }
            var isLoading by remember { mutableStateOf(false) }
            var playlistErrorText by remember { mutableStateOf<String?>(null) }
            var vportalDiagnostic by remember { mutableStateOf("") }
            val notifPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

            var currentChannel by remember { mutableStateOf<Channel?>(null) }
            var previousChannel by remember { mutableStateOf<Channel?>(null) }
            var activeArchiveProgram by remember { mutableStateOf<EpgProgramItem?>(null) }
            var archiveSeekOffsetSec by remember { mutableStateOf(0L) }

            var currentVodPlay by remember { mutableStateOf<VodProgressItem?>(null) }
            var vodInitialSeekMs by remember { mutableStateOf(0L) }
            var selectedVodForDetails by remember { mutableStateOf<VodMovie?>(null) }

            var isFullscreen by remember { mutableStateOf(false) }
            var isMultiViewMode by remember { mutableStateOf(false) }
            var showTiviMateOverlay by remember { mutableStateOf(false) }
            var showAudioTracksDialog by remember { mutableStateOf(false) }
            var showQualityDialog by remember { mutableStateOf(false) }
            var showHiddenGroupsDialog by remember { mutableStateOf(false) }
            var showSleepDialog by remember { mutableStateOf(false) }
            var showPlayerQuickMenu by remember { mutableStateOf(false) }
            var epgDialogChannel by remember { mutableStateOf<Channel?>(null) }
            var showQuickChannelPicker by remember { mutableStateOf(false) }

            var videoQualityLabel by remember { mutableStateOf("") }
            var playbackSpeed by remember { mutableStateOf(1f) }
            var seekCounter by remember { mutableStateOf(0) }
            var seekCommand by remember { mutableStateOf<Pair<Int, Long>?>(null) }
            var digitBuffer by remember { mutableStateOf("") }

            var sleepSecondsLeft by remember { mutableStateOf(0) }
            var showSleepTriggeredNotice by remember { mutableStateOf(false) }
            var reminders by remember { mutableStateOf(loadSavedReminders(prefs)) }
            var triggeredReminder by remember { mutableStateOf<ProgramReminder?>(null) }

            var epgMinuteTickMs by remember { mutableStateOf(System.currentTimeMillis()) }
            var lastVodSavedAtMs by remember { mutableStateOf(0L) }
            var epgLoadJob by remember { mutableStateOf<Job?>(null) }

            val scope = rememberCoroutineScope()
            val visibleChannels = remember(channels, hiddenGroups) { channels.filterNot { hiddenGroups.contains(it.group) } }
            val allGroupsList = remember(channels) { channels.map { it.group }.distinct() }

            LaunchedEffect(deviceType, isFullscreen, isMultiViewMode, currentChannel, currentVodPlay, playerEngine) {
                autoPipAllowed = deviceType == "mobile" && isFullscreen && !isMultiViewMode &&
                    playerEngine != 2 && (currentChannel != null || currentVodPlay != null)
            }

            val rootView = LocalView.current
            LaunchedEffect(isFullscreen, isMultiViewMode, isInPipModeState) {
                val ctrl = WindowCompat.getInsetsController(window, rootView)
                ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (isFullscreen || isMultiViewMode || isInPipModeState) ctrl.hide(WindowInsetsCompat.Type.systemBars())
                else ctrl.show(WindowInsetsCompat.Type.systemBars())
            }

            LaunchedEffect(Unit) {
                while (true) {
                    delay(30_000L)
                    epgMinuteTickMs = System.currentTimeMillis()
                }
            }

            LaunchedEffect(sleepSecondsLeft > 0) {
                while (sleepSecondsLeft > 0) {
                    delay(1000L)
                    sleepSecondsLeft = (sleepSecondsLeft - 1).coerceAtLeast(0)
                    if (sleepSecondsLeft == 0) {
                        isFullscreen = false; isMultiViewMode = false
                        currentVodPlay = null; activeArchiveProgram = null; currentChannel = null
                        showSleepTriggeredNotice = true
                    }
                }
            }

            LaunchedEffect(reminders.size) {
                while (reminders.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val due = reminders.firstOrNull { now >= it.startMs && (now - it.startMs) < 10L * 60_000L }
                    if (due != null) {
                        val updated = reminders.filterNot { it.id == due.id }
                        reminders = updated
                        saveRemindersToPrefs(prefs, updated)
                        triggeredReminder = due
                    }
                    delay(15_000L)
                }
            }

            fun persistLists() {
                saveNamedList(prefs, "playlists_json", playlists)
                saveNamedList(prefs, "epgs_json", epgSources)
                saveHiddenGroups(prefs, hiddenGroups)
                prefs.edit()
                    .putString("device_type", deviceType).putString("control_mode", controlMode)
                    .putInt("clock_pos", clockPosition).putInt("player_engine", playerEngine)
                    .putInt("archive_days", archiveDays).putInt("epg_offset_hours", epgOffsetHours)
                    .putBoolean("parental_enabled", parentalEnabled)
                    .putString("selected_playlist_id", selectedPlaylistId).putString("selected_epg_id", selectedEpgId)
                    .putBoolean("use_playlist_epg", usePlaylistEpg).putString("vportal_url", vportalUrl.trim())
                    .putString("stalker_url", stalkerUrl.trim()).putString("stalker_mac", stalkerMac.trim())
                    .putInt("player_resize", playerResizeMode).putInt("player_buffer", playerBufferMode)
                    .putString("player_ua", playerDefaultUa).putBoolean("player_auto_fs", openFullscreenOnTap)
                    .apply()
            }

            fun loadEpgWithStatus(
                embeddedOverride: String? = detectedPlaylistEpgUrl,
                forceRefresh: Boolean = false,
                channelsSnapshot: List<Channel> = channels
            ) {
                val customSelected = epgSources.find { it.id == selectedEpgId }?.url
                val effectiveEmbedded = if (usePlaylistEpg) embeddedOverride?.takeIf { it.isNotBlank() } else null
                val candidateUrls = when (selectedEpgId) {
                    "BUILTIN_TVLIDER" -> listOf(EPG_URL_TVLIDER, EPG_URL_IPTVX_LITE)
                    "BUILTIN_IPTVX_LITE" -> listOf(EPG_URL_IPTVX_LITE, EPG_URL_TVLIDER)
                    "BUILTIN_IPTVX_FULL" -> listOf(EPG_URL_IPTVX_FULL, EPG_URL_IPTVX_LITE)
                    "AUTO_PLAYLIST" -> listOfNotNull(effectiveEmbedded, EPG_URL_IPTVX_LITE, EPG_URL_TVLIDER)
                    else -> listOfNotNull(customSelected?.takeIf { it.isNotBlank() }, effectiveEmbedded, EPG_URL_IPTVX_LITE, EPG_URL_TVLIDER)
                }.distinct()

                val allowedKeys = channelsSnapshot.flatMap { ch ->
                    buildList {
                        ch.epgId?.takeIf { it.isNotBlank() }?.let { id ->
                            add(id.lowercase())
                            normalizeChannelKey(id).takeIf { it.isNotEmpty() }?.let(::add)
                        }
                        add(ch.name.trim().lowercase())
                        normalizeChannelKey(ch.name).takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }.toSet()

                epgLoadJob?.cancel()
                epgLoadJob = scope.launch {
                    isEpgLoading = true
                    var finalSchedule = emptyMap<String, List<EpgProgramItem>>()
                    var usedUrl = ""
                    for (testUrl in candidateUrls) {
                        usedUrl = maskUrlForDisplay(testUrl)
                        epgStatusText = "⏳ $usedUrl ..."
                        val loaded = NetworkLoader.loadFullEpgSchedule(
                            testUrl.trim(), archiveDays, epgOffsetHours, this@MainActivity, forceRefresh, allowedKeys
                        )
                        if (loaded.isNotEmpty()) { finalSchedule = loaded; break }
                    }
                    epgScheduleMap = finalSchedule; isEpgLoading = false
                    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    val matchedCount = channelsSnapshot.count { findChannelSchedule(it, finalSchedule).isNotEmpty() }
                    epgStatusText = if (finalSchedule.isNotEmpty()) {
                        if (isRu) "✅ $timeStr ($usedUrl)\nКаналов с EPG: $matchedCount / ${channelsSnapshot.size} (${archiveDays}д, сдвиг ${epgOffsetHours}ч)"
                        else "✅ $timeStr ($usedUrl)\nMatched EPG: $matchedCount / ${channelsSnapshot.size} (${archiveDays}d, shift ${epgOffsetHours}h)"
                    } else {
                        if (isRu) "⚠️ EPG не загружен ($timeStr)" else "⚠️ EPG not loaded ($timeStr)"
                    }
                }
            }
            fun reloadAllData(forceRefresh: Boolean = false) {
                persistLists()
                scope.launch {
                    isLoading = true
                    val loadedChannels = mutableListOf<Channel>()
                    val loadedVod = mutableListOf<VodMovie>()
                    var embeddedEpg: String? = null

                    val activePlaylist = playlists.find { it.id == selectedPlaylistId } ?: playlists.firstOrNull()
                    if (activePlaylist != null && activePlaylist.url.isNotBlank()) {
                        val m3uRes = NetworkLoader.loadM3u(activePlaylist.url.trim(), this@MainActivity, forceRefresh)
                        loadedChannels.addAll(m3uRes.channels); loadedVod.addAll(m3uRes.movies)
                        embeddedEpg = m3uRes.embeddedEpgUrl; detectedPlaylistEpgUrl = embeddedEpg
                    } else {
                        detectedPlaylistEpgUrl = null
                    }
                    if (vportalUrl.isNotBlank()) {
                        val vpRes = NetworkLoader.loadVPortalNode(vportalUrl.trim())
                        loadedVod.addAll(vpRes.first); vportalDiagnostic = vpRes.second
                        vodFolderStack = emptyList(); currentVodFolderTitle = if (isRu) "Кинозал VPortal" else "VPortal Root"
                    }
                    if (stalkerUrl.isNotBlank() && stalkerMac.isNotBlank()) {
                        val (stCh, stVod) = NetworkLoader.loadStalkerFull(stalkerUrl.trim(), stalkerMac.trim())
                        loadedChannels.addAll(stCh); loadedVod.addAll(stVod)
                    }
                    channels = loadedChannels; vodItems = loadedVod
                    playlistErrorText = if (loadedChannels.isEmpty() && activePlaylist != null) NetworkLoader.lastM3uError else null
                    val stillValid = currentChannel?.let { cur -> loadedChannels.find { it.id == cur.id || it.streamUrl == cur.streamUrl } }
                    currentChannel = stillValid ?: loadedChannels.firstOrNull { isChannelAllowed(it, parentalEnabled, adultUnlockedSession) }
                    isLoading = false
                    loadEpgWithStatus(embeddedEpg, forceRefresh, loadedChannels)
                }
            }

            fun refreshStateFromPrefs() {
                isRu = prefs.getString("lang", "ru") == "ru"
                deviceType = prefs.getString("device_type", "mobile") ?: "mobile"
                controlMode = prefs.getString("control_mode", "touch") ?: "touch"
                clockPosition = prefs.getInt("clock_pos", 0)
                playerEngine = prefs.getInt("player_engine", 0)
                archiveDays = prefs.getInt("archive_days", 3)
                epgOffsetHours = prefs.getInt("epg_offset_hours", 0)
                parentalEnabled = prefs.getBoolean("parental_enabled", false)
                adultUnlockedSession = false
                themePalette = AppThemePalette.fromId(prefs.getString("theme_id", AppThemePalette.DARK_EMERALD.id))
                playlists = loadNamedList(prefs, "playlists_json", "m3u_url", "Основной плейлист")
                selectedPlaylistId = prefs.getString("selected_playlist_id", playlists.firstOrNull()?.id ?: "") ?: ""
                epgSources = loadNamedList(prefs, "epgs_json", "epg_url", "Моя программа EPG")
                usePlaylistEpg = prefs.getBoolean("use_playlist_epg", true)
                selectedEpgId = prefs.getString("selected_epg_id", "AUTO_PLAYLIST") ?: "AUTO_PLAYLIST"
                vportalUrl = prefs.getString("vportal_url", "") ?: ""
                stalkerUrl = prefs.getString("stalker_url", "") ?: ""
                stalkerMac = prefs.getString("stalker_mac", "") ?: ""
                playerResizeMode = prefs.getInt("player_resize", 0); playerBufferMode = prefs.getInt("player_buffer", 1)
                playerDefaultUa = prefs.getString("player_ua", "Lavf/60.3.100") ?: "Lavf/60.3.100"
                openFullscreenOnTap = prefs.getBoolean("player_auto_fs", false)
                favoriteIds = prefs.getStringSet("favs", emptySet())?.toSet() ?: emptySet()
                hiddenGroups = loadHiddenGroups(prefs)
                reloadAllData(forceRefresh = true)
            }

            fun openExternalSafe(rawUrl: String, title: String) {
                scope.launch {
                    val real = NetworkLoader.resolveStalkerStreamIfNeeded(rawUrl)
                    launchExternalVideoPlayer(this@MainActivity, real, title)
                }
            }

            fun runWithPinCheck(ch: Channel, action: () -> Unit) {
                if (!isChannelAllowed(ch, parentalEnabled, adultUnlockedSession)) {
                    pendingPinAction = action
                } else {
                    action()
                }
            }

            fun selectChannelWithPinCheck(ch: Channel) {
                runWithPinCheck(ch) {
                    if (currentChannel != null && currentChannel?.id != ch.id) previousChannel = currentChannel
                    currentVodPlay = null; activeArchiveProgram = null; archiveSeekOffsetSec = 0L; playbackSpeed = 1f
                    currentChannel = ch
                    if (openFullscreenOnTap) {
                        if (playerEngine == 2) openExternalSafe(ch.streamUrl, ch.name) else isFullscreen = true
                    }
                }
            }

            LaunchedEffect(digitBuffer) {
                if (digitBuffer.isNotEmpty()) {
                    delay(1400L)
                    val targetNum = digitBuffer.toIntOrNull(); digitBuffer = ""
                    if (targetNum != null) channels.find { it.number == targetNum }?.let { ch ->
                        selectChannelWithPinCheck(ch)
                    }
                }
            }

            fun zapChannelStep(step: Int) {
                val baseList = visibleChannels.ifEmpty { channels }
                val allowedList = baseList.filter { isChannelAllowed(it, parentalEnabled, adultUnlockedSession) }
                val list = allowedList.ifEmpty { baseList }
                if (list.isEmpty()) return
                val curIdx = list.indexOfFirst { it.id == currentChannel?.id }.let { if (it < 0) 0 else it }
                selectChannelWithPinCheck(list[(curIdx + step + list.size) % list.size])
            }

           fun toggleReminderFor(ch: Channel, prog: EpgProgramItem) {
                val existing = reminders.find { it.channelId == ch.id && it.startMs == prog.startMs }
                val updated = if (existing != null) {
                    cancelReminderAlarm(this@MainActivity, existing)
                    reminders.filterNot { it.channelId == ch.id && it.startMs == prog.startMs }
                } else {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    val newRem = ProgramReminder("${ch.id}_${prog.startMs}", ch.id, ch.name, prog.title, prog.startMs)
                    scheduleReminderAlarm(this@MainActivity, newRem)
                    reminders + newRem
                }
                reminders = updated
                saveRemindersToPrefs(prefs, updated)
            }

            fun startVodPlayback(key: String, title: String, poster: String?, streamUrl: String, seekMs: Long) {
                if (playerEngine == 2) {
                    openExternalSafe(streamUrl, title)
                } else {
                    val item = VodProgressItem(key, title, poster, streamUrl, seekMs, 0L, System.currentTimeMillis())
                    scope.launch(Dispatchers.IO) {
                        val updated = saveVodProgressItem(prefs, item)
                        launch(Dispatchers.Main) { vodHistory = updated }
                    }
                    currentVodPlay = item; vodInitialSeekMs = seekMs; playbackSpeed = 1f; isFullscreen = true
                }
            }

            fun openVodNode(item: VodMovie) {
                vodNoticeMessage = ""
                if (item.id.startsWith("vp_next_")) {
                    if (isLoadingMoreVod || vportalUrl.isBlank()) return
                    scope.launch {
                        isLoadingMoreVod = true
                        val res = NetworkLoader.loadVPortalNode(vportalUrl.trim(), item.targetApiUrl, item.requestPayloadJson)
                        vportalDiagnostic = res.second
                        vodItems = vodItems.filterNot { it.id.startsWith("vp_next_") } + res.first
                        isLoadingMoreVod = false
                    }
                    return
                }
                if (!item.isFolder && item.streamUrl.isNotBlank()) { selectedVodForDetails = item; return }
                if (vportalUrl.isBlank()) return
                scope.launch {
                    isLoading = true
                    val res = NetworkLoader.loadVPortalNode(vportalUrl.trim(), item.targetApiUrl, item.requestPayloadJson)
                    vportalDiagnostic = res.second; val children = res.first; isLoading = false
                    if (children.size == 1 && !children.first().isFolder && children.first().streamUrl.isNotBlank()) {
                        selectedVodForDetails = item.copy(streamUrl = children.first().streamUrl, isFolder = false)
                    } else if (children.isNotEmpty()) {
                        vodFolderStack = vodFolderStack + (currentVodFolderTitle to vodItems)
                        currentVodFolderTitle = item.title; vodItems = children
                    } else vodNoticeMessage = (if (isRu) "Ответ сервера: " else "Server response: ") + maskSensitiveKeys(vportalDiagnostic)
                }
            }

            val qrServer = remember(isRu) {
                SimpleQrWebServer(8989, isRu) { payload ->
                    scope.launch(Dispatchers.Main) {
                        val m3u = payload["m3u"].orEmpty()
                        val m3uName = payload["m3u_name"].orEmpty().ifEmpty { "Плейлист ${playlists.size + 1}" }
                        if (m3u.isNotBlank()) {
                            val id = "pl_${System.currentTimeMillis()}"
                            playlists = playlists + NamedSource(id, m3uName, m3u)
                            selectedPlaylistId = id
                        }
                        val epg = payload["epg"].orEmpty()
                        val epgName = payload["epg_name"].orEmpty().ifEmpty { "EPG ${epgSources.size + 1}" }
                        if (epg.isNotBlank()) {
                            val id = "epg_${System.currentTimeMillis()}"
                            epgSources = epgSources + NamedSource(id, epgName, epg)
                            selectedEpgId = id
                        }
                        if (payload["vportal"]?.isNotBlank() == true) vportalUrl = payload["vportal"]!!
                        if (payload["stalker_url"]?.isNotBlank() == true) stalkerUrl = payload["stalker_url"]!!
                        if (payload["stalker_mac"]?.isNotBlank() == true) stalkerMac = payload["stalker_mac"]!!
                        reloadAllData(forceRefresh = true)
                        currentTab = 0
                    }
                }
            }

            DisposableEffect(lifecycleOwner, qrServer) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP) {
                        qrServer.stop()
                        if (!isInPictureInPictureMode) {
                            adultUnlockedSession = false
                        }
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    qrServer.stop()
                }
            }

            LaunchedEffect(Unit) {
                if (!isFirstLaunch && (playlists.isNotEmpty() || vportalUrl.isNotBlank() || stalkerUrl.isNotBlank())) {
                    reloadAllData(forceRefresh = false)
                }
            }

            LaunchedEffect(channels, pendingOpenChannelId) {
                val targetId = pendingOpenChannelId ?: return@LaunchedEffect
                val targetCh = channels.find { it.id == targetId } ?: return@LaunchedEffect
                pendingOpenChannelId = null
                currentTab = 0
                selectChannelWithPinCheck(targetCh)
            }

            val colorScheme = if (themePalette.isDark) {
                darkColorScheme(primary = themePalette.primary, onPrimary = themePalette.onPrimary, background = themePalette.bg, surface = themePalette.card, onBackground = themePalette.text, onSurface = themePalette.text)
            } else {
                lightColorScheme(primary = themePalette.primary, onPrimary = themePalette.onPrimary, background = themePalette.bg, surface = themePalette.card, onBackground = themePalette.text, onSurface = themePalette.text)
            }

            val activeArchiveUrl = remember(currentChannel, activeArchiveProgram, archiveSeekOffsetSec) {
                val ch = currentChannel; val prog = activeArchiveProgram
                if (ch != null && prog != null) buildArchiveStreamUrl(ch, (prog.startMs + archiveSeekOffsetSec * 1000L).coerceAtMost(prog.stopMs - 10000L), prog.stopMs)
                else null
            }

            MaterialTheme(colorScheme = colorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = themePalette.bg) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (showSleepTriggeredNotice) {
                            AlertDialog(
                                onDismissRequest = { showSleepTriggeredNotice = false },
                                title = { Text(if (S.isRu) "😴 Таймер сна" else "😴 Sleep Timer") },
                                text = { Text(if (S.isRu) "Воспроизведение остановлено по таймеру." else "Playback stopped by sleep timer.") },
                                confirmButton = { Button(onClick = { showSleepTriggeredNotice = false }) { Text("OK") } }
                            )
                        }
                        if (triggeredReminder != null) {
                            val rem = triggeredReminder!!
                            AlertDialog(
                                onDismissRequest = { triggeredReminder = null },
                                title = { Text("🔔 ${rem.programTitle}") },
                                text = { Text(if (S.isRu) "Начинается на канале «${rem.channelName}». Переключить?" else "Starting on ${rem.channelName}. Switch?") },
                                confirmButton = {
                                    Button(onClick = {
                                        channels.find { it.id == rem.channelId }?.let { selectChannelWithPinCheck(it) }
                                        triggeredReminder = null
                                    }) { Text(if (S.isRu) "▶ Переключить" else "▶ Switch") }
                                },
                                dismissButton = { OutlinedButton(onClick = { triggeredReminder = null }) { Text(if (S.isRu) "Позже" else "Dismiss") } }
                            )
                        }
                        if (pendingPinAction != null) {
                            ParentalPinDialog(S, themePalette, parentalPin, onSuccess = { adultUnlockedSession = true; val act = pendingPinAction; pendingPinAction = null; act?.invoke() }, onDismiss = { pendingPinAction = null })
                        }
                        if (showHiddenGroupsDialog) {
                            HiddenCategoriesDialog(S, themePalette, allGroupsList, hiddenGroups, onToggleGroup = { grp -> hiddenGroups = if (hiddenGroups.contains(grp)) hiddenGroups - grp else hiddenGroups + grp; saveHiddenGroups(prefs, hiddenGroups) }, onShowAll = { hiddenGroups = emptySet(); saveHiddenGroups(prefs, hiddenGroups) }, onDismiss = { showHiddenGroupsDialog = false })
                        }
                        if (showSleepDialog) {
                            SleepTimerDialog(S, themePalette, (sleepSecondsLeft + 59) / 60, onSelectMinutes = { sleepSecondsLeft = it * 60 }, onDismiss = { showSleepDialog = false })
                        }
                        if (selectedVodForDetails != null) {
                            val m = selectedVodForDetails!!; val saved = vodHistory.find { it.key == m.id || it.streamUrl == m.streamUrl }
                            VodDetailsDialog(S, themePalette, m, saved, onPlayFromStart = { selectedVodForDetails = null; startVodPlayback(m.id, m.title, m.posterUrl, m.streamUrl, 0L) }, onResume = { pos -> selectedVodForDetails = null; startVodPlayback(m.id, m.title, m.posterUrl, m.streamUrl, pos) }, onDismiss = { selectedVodForDetails = null })
                        }
                        if (epgDialogChannel != null) {
                            val ch = epgDialogChannel!!; val schedule = remember(ch, epgScheduleMap) { findChannelSchedule(ch, epgScheduleMap) }
                            EpgAndArchiveDialog(S, themePalette, ch, schedule, reminders, archiveDays, onDismiss = { epgDialogChannel = null }, onToggleReminder = { toggleReminderFor(ch, it) },
                                onPlayArchive = { prog ->
                                    runWithPinCheck(ch) {
                                        currentChannel = ch; currentVodPlay = null; activeArchiveProgram = prog; archiveSeekOffsetSec = 0L; epgDialogChannel = null
                                        if (playerEngine == 2) openExternalSafe(buildArchiveStreamUrl(ch, prog.startMs, prog.stopMs), "${ch.name}: ${prog.title}")
                                    }
                                },
                                onPlayLive = {
                                    runWithPinCheck(ch) {
                                        currentChannel = ch; currentVodPlay = null; activeArchiveProgram = null; archiveSeekOffsetSec = 0L; epgDialogChannel = null
                                        if (playerEngine == 2) openExternalSafe(ch.streamUrl, ch.name)
                                    }
                                }
                            )
                        }
                        if (showQuickChannelPicker && visibleChannels.isNotEmpty()) {
                            QuickChannelPickerDialog(S, themePalette, visibleChannels, currentChannel, onDismiss = { showQuickChannelPicker = false }) { ch -> selectChannelWithPinCheck(ch); showQuickChannelPicker = false }
                        }

                        if (isFirstLaunch) {
                            FirstLaunchSetupScreen(themePalette, isRu, deviceType, controlMode) { selRu, selDevice, selControl ->
                                isRu = selRu; deviceType = selDevice; controlMode = selControl
                                prefs.edit().putBoolean("lang_selected", true).putString("lang", if (selRu) "ru" else "en").putString("device_type", selDevice).putString("control_mode", selControl).apply()
                                isFirstLaunch = false; currentTab = 2
                            }
                        } else if (isMultiViewMode && currentChannel != null) {
                            BackHandler { isMultiViewMode = false }
                            MultiViewScreenOverlay(
                                S, themePalette, visibleChannels.ifEmpty { channels }, currentChannel!!, playerBufferMode, playerDefaultUa,
                                onExitMultiView = { chosen ->
                                    currentChannel = chosen; isMultiViewMode = false; isFullscreen = true
                                },
                                parentalEnabled = parentalEnabled,
                                adultUnlockedSession = adultUnlockedSession
                            )
                        } else if (isFullscreen && (currentChannel != null || currentVodPlay != null)) {
                            BackHandler { if (showTiviMateOverlay) showTiviMateOverlay = false else isFullscreen = false }
                            val isVod = currentVodPlay != null; val isArchive = !isVod && activeArchiveProgram != null
                            val playUrl = currentVodPlay?.streamUrl ?: activeArchiveUrl ?: currentChannel?.streamUrl ?: ""
                            val playTitle = currentVodPlay?.title ?: activeArchiveProgram?.let { "⏪ ${it.title}" } ?: currentChannel?.name ?: ""
                            val resizeLabels = if (isRu) listOf("По размеру", "16:9", "Zoom") else listOf("Fit", "16:9", "Zoom")
                            val curSchedule = remember(currentChannel, epgScheduleMap) { currentChannel?.let { findChannelSchedule(it, epgScheduleMap) } ?: emptyList() }
                            val nowProg = remember(curSchedule, epgMinuteTickMs) { curSchedule.find { epgMinuteTickMs in it.startMs..it.stopMs } }
                            val isSmartphone = deviceType == "mobile"
                            val fsFocusRequester = remember { FocusRequester() }
                            val anyOverlayOpen = showTiviMateOverlay || showPlayerQuickMenu || showQuickChannelPicker || showSleepDialog || showAudioTracksDialog || showQualityDialog

                            LaunchedEffect(isFullscreen, anyOverlayOpen) {
                                if (isFullscreen && !anyOverlayOpen) {
                                    runCatching { fsFocusRequester.requestFocus() }
                                }
                            }

                            if (showPlayerQuickMenu) {
                                PlayerQuickMenuDialog(
                                    S, themePalette, isVod, isArchive, playerEngine, resizeLabels[playerResizeMode], playbackSpeed, (sleepSecondsLeft + 59) / 60, isSmartphone,
                                    onCycleResize = { playerResizeMode = (playerResizeMode + 1) % 3; prefs.edit().putInt("player_resize", playerResizeMode).apply() },
                                    onOpenAudio = { showPlayerQuickMenu = false; showAudioTracksDialog = true },
                                    onOpenQuality = { showPlayerQuickMenu = false; showQualityDialog = true },
                                    onCycleSpeed = { playbackSpeed = when (playbackSpeed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 1f } },
                                    onSeekBack10 = { if (isVod) { seekCounter++; seekCommand = seekCounter to -10000L } else archiveSeekOffsetSec = (archiveSeekOffsetSec - 10L).coerceAtLeast(0L) },
                                    onSeekFwd10 = { if (isVod) { seekCounter++; seekCommand = seekCounter to 10000L } else archiveSeekOffsetSec += 10L },
                                    onOpenSleep = { showPlayerQuickMenu = false; showSleepDialog = true },
                                    onOpenChannels = { showPlayerQuickMenu = false; showQuickChannelPicker = true },
                                    onOpenTvGuide = { showPlayerQuickMenu = false; showTiviMateOverlay = true },
                                    onOpenMultiView = { showPlayerQuickMenu = false; isMultiViewMode = true },
                                    onDismiss = { showPlayerQuickMenu = false }
                                )
                            }

                            Box(
                                modifier = Modifier.fillMaxSize().background(Color.Black).focusRequester(fsFocusRequester).focusable()
                                    .onPreviewKeyEvent { ev ->
                                        if (ev.type == KeyEventType.KeyDown && !showTiviMateOverlay && !isVod) {
                                            val code = ev.nativeKeyEvent.keyCode
                                            if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { digitBuffer += (code - KeyEvent.KEYCODE_0).toString(); return@onPreviewKeyEvent true }
                                            if (code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_MEDIA_NEXT) { zapChannelStep(1); return@onPreviewKeyEvent true }
                                            if (code == KeyEvent.KEYCODE_CHANNEL_DOWN || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) { zapChannelStep(-1); return@onPreviewKeyEvent true }
                                            if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { showPlayerQuickMenu = !showPlayerQuickMenu; return@onPreviewKeyEvent true }
                                        }
                                        false
                                    }
                                    .onKeyEvent { ev ->
                                        if (ev.type == KeyEventType.KeyDown && !showTiviMateOverlay && !isVod) {
                                            when (ev.nativeKeyEvent.keyCode) {
                                                KeyEvent.KEYCODE_DPAD_UP -> { zapChannelStep(1); return@onKeyEvent true }
                                                KeyEvent.KEYCODE_DPAD_DOWN -> { zapChannelStep(-1); return@onKeyEvent true }
                                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { showTiviMateOverlay = true; return@onKeyEvent true }
                                            }
                                        }
                                        false
                                    }
                            ) {
                                SmartVideoPlayer(
                                    streamUrl = playUrl,
                                    userAgent = currentChannel?.userAgent ?: playerDefaultUa,
                                    resizeMode = playerResizeMode,
                                    bufferMode = playerBufferMode,
                                    useNativeController = (isVod || playerEngine == 1) && !isInPipModeState,
                                    reconnectingText = S.reconnecting,
                                    playerEngine = playerEngine,
                                    enableSwipeGestures = controlMode == "touch" && !isInPipModeState,
                                    playbackSpeed = if (isVod || isArchive) playbackSpeed else 1f,
                                    initialSeekMs = if (isVod) vodInitialSeekMs else 0L,
                                    seekCommand = seekCommand,
                                    showAudioSelectorDialog = showAudioTracksDialog,
                                    onDismissAudioSelector = { showAudioTracksDialog = false },
                                    onVideoQualityDetected = { videoQualityLabel = it },
                                    onPlaybackProgress = { pos, dur ->
                                        val vp = currentVodPlay
                                        val now = System.currentTimeMillis()
                                        if (isVod && vp != null && (now - lastVodSavedAtMs) >= 12_000L) {
                                            lastVodSavedAtMs = now
                                            val updatedItem = vp.copy(positionMs = pos, durationMs = dur, updatedAt = now)
                                            scope.launch(Dispatchers.IO) {
                                                val updatedList = saveVodProgressItem(prefs, updatedItem)
                                                launch(Dispatchers.Main) { vodHistory = updatedList }
                                            }
                                        }
                                    },
                                    referrer = currentChannel?.referrer,
                                    cookie = currentChannel?.cookie,
                                    isRu = isRu,
                                    isPipMode = isInPipModeState,
                                    onNextChannel = if (!isVod) { { zapChannelStep(1) } } else null,
                                    showQualitySelectorDialog = showQualityDialog,
                                    onDismissQualitySelector = { showQualityDialog = false },
                                    enableDoubleTapSeek = (isVod || isArchive) && controlMode == "touch",
                                    onDoubleTapSeekDelta = if (isArchive) {
                                        { deltaMs -> archiveSeekOffsetSec = (archiveSeekOffsetSec + deltaMs / 1000L).coerceAtLeast(0L) }
                                    } else null,
                                    modifier = Modifier.fillMaxSize()
                                )

                                if (!isInPipModeState) {
                                    PlayerClockBadge(clockPosition, themePalette.primary)

                                    if (digitBuffer.isNotEmpty()) {
                                        Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xDD000000)).border(2.dp, themePalette.primary, RoundedCornerShape(14.dp)).padding(horizontal = 24.dp, vertical = 10.dp)) {
                                            Text("📺 $digitBuffer", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp)
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                                            .padding(top = if (clockPosition != 0) 40.dp else 10.dp, start = 10.dp, end = 10.dp)
                                            .clip(RoundedCornerShape(14.dp)).background(Color(0xBB000000)).padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(playTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (videoQualityLabel.isNotBlank()) Text(videoQualityLabel, color = themePalette.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                        }
                                        if (isSmartphone) {
                                            IconButton(onClick = { triggerPipMode() }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.PictureInPictureAlt, null, tint = Color.White) }
                                        }
                                        IconButton(onClick = { showPlayerQuickMenu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Tune, null, tint = themePalette.primary) }
                                        IconButton(onClick = { isFullscreen = false }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.FullscreenExit, null, tint = Color.White) }
                                    }

                                    if (!isVod && currentChannel != null && !showTiviMateOverlay) {
                                        Column(
                                            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)
                                                .clip(RoundedCornerShape(16.dp)).background(Color(0xCC121417)).padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (playerEngine == 0) {
                                                if (isArchive && activeArchiveProgram != null) {
                                                    val prog = activeArchiveProgram!!; val totalSec = ((prog.stopMs - prog.startMs) / 1000L).coerceAtLeast(60L)
                                                    var sliderPos by remember(archiveSeekOffsetSec) { mutableStateOf(archiveSeekOffsetSec.toFloat()) }
                                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                        Text("⏪ ${prog.title}", color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                                        TextButton(onClick = { activeArchiveProgram = null; archiveSeekOffsetSec = 0L; playbackSpeed = 1f }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                                            Text(if (S.isRu) "🔴 Эфир" else "🔴 Live", color = Color(0xFFEF4444), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    }
                                                    Slider(value = sliderPos.coerceIn(0f, totalSec.toFloat()), onValueChange = { sliderPos = it }, onValueChangeFinished = { archiveSeekOffsetSec = sliderPos.toLong() }, valueRange = 0f..totalSec.toFloat(), modifier = Modifier.fillMaxWidth().height(26.dp))
                                                } else if (nowProg != null) {
                                                    val totalMs = (nowProg.stopMs - nowProg.startMs).coerceAtLeast(1L); val elapsedMs = (epgMinuteTickMs - nowProg.startMs).coerceIn(0L, totalMs)
                                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                                        Text("🟢 ${nowProg.title}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                                                        if (currentChannel!!.catchupDays > 0) {
                                                            Text(if (S.isRu) "⏪ С начала" else "⏪ Restart", color = Color(0xFFF59E0B), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { activeArchiveProgram = nowProg; archiveSeekOffsetSec = 0L })
                                                        }
                                                    }
                                                    LinearProgressIndicator(progress = { (elapsedMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = themePalette.primary)
                                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                        val minU = if (S.isRu) "мин" else "min"
                                                        val leftLbl = if (S.isRu) "Ост:" else "Left:"
                                                        Text("${elapsedMs / 60000L} $minU (${nowProg.timeRangeText})", color = Color.LightGray, fontSize = 11.sp)
                                                        Text("$leftLbl ${(totalMs - elapsedMs) / 60000L} $minU", color = Color.LightGray, fontSize = 11.sp)
                                                    }
                                                }
                                            }
                                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                if (isSmartphone) item { PlayerActionPill(if (S.isRu) "📋 Каналы" else "📋 Channels", Color(0xFF38BDF8)) { showQuickChannelPicker = true } }
                                                item { PlayerActionPill(if (S.isRu) "🗂 ТВ-Гид" else "🗂 Guide", Color(0xFF10B981)) { showTiviMateOverlay = true } }
                                                if (playerEngine == 0) {
                                                    item { PlayerActionPill("📐 ${resizeLabels[playerResizeMode]}") { playerResizeMode = (playerResizeMode + 1) % 3; prefs.edit().putInt("player_resize", playerResizeMode).apply() } }
                                                    item { PlayerActionPill(if (S.isRu) "🔊 Звук" else "🔊 Audio") { showAudioTracksDialog = true } }
                                                    item { PlayerActionPill(if (S.isRu) "🎞 Качество" else "🎞 Quality") { showQualityDialog = true } }
                                                    if (isArchive) {
                                                        item { PlayerActionPill("${playbackSpeed}x", Color(0xFF38BDF8)) { playbackSpeed = when (playbackSpeed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 1f } } }
                                                        item { PlayerActionPill(if (S.isRu) "-10с" else "-10s") { archiveSeekOffsetSec = (archiveSeekOffsetSec - 10L).coerceAtLeast(0L) } }
                                                        item { PlayerActionPill(if (S.isRu) "+10с" else "+10s") { archiveSeekOffsetSec += 10L } }
                                                    }
                                                    item { PlayerActionPill(if (S.isRu) "⊞ 4 Экран" else "⊞ Multi", Color(0xFFF59E0B)) { isMultiViewMode = true } }
                                                }
                                                item {
                                                    val mUnit = if (S.isRu) "м" else "m"
                                                    val sLabel = if (sleepSecondsLeft > 0) "⏱ ${(sleepSecondsLeft + 59) / 60}$mUnit" else (if (S.isRu) "⏱ Сон" else "⏱ Sleep")
                                                    PlayerActionPill(sLabel, if (sleepSecondsLeft > 0) Color(0xFFF59E0B) else Color.White) { showSleepDialog = true }
                                                }
                                            }
                                        }
                                    }

                                    if (showTiviMateOverlay) {
                                        TiviMateTvOverlay(
                                            S, themePalette, channels, hiddenGroups, epgScheduleMap, reminders, currentChannel,
                                            onSelectChannel = { selectChannelWithPinCheck(it) },
                                            onPlayArchive = { ch, prog ->
                                                runWithPinCheck(ch) {
                                                    currentChannel = ch; activeArchiveProgram = prog; archiveSeekOffsetSec = 0L
                                                }
                                            },
                                            onToggleReminder = { ch, prog -> toggleReminderFor(ch, prog) },
                                            onClose = { showTiviMateOverlay = false },
                                            parentalEnabled = parentalEnabled,
                                            adultUnlockedSession = adultUnlockedSession
                                        )
                                    }
                                }
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                NavigationBar(containerColor = themePalette.card, modifier = Modifier.defaultMinSize(minHeight = 64.dp)) {
                                    NavigationBarItem(selected = currentTab == 0, onClick = { currentTab = 0; currentVodPlay = null }, icon = { Icon(Icons.Default.Tv, null) }, label = { Text(S.liveTv) })
                                    NavigationBarItem(selected = currentTab == 1, onClick = { currentTab = 1 }, icon = { Icon(Icons.Default.Movie, null) }, label = { Text("${S.vod} (${vodItems.count { !it.id.startsWith("vp_next_") }})") })
                                    NavigationBarItem(selected = currentTab == 2, onClick = { currentTab = 2 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text(S.settings) })
                                }
                                if (isLoading || isLoadingMoreVod) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = themePalette.primary)

                                when (currentTab) {
                                    0 -> LiveTvScreen(
                                        S, themePalette, playlists, selectedPlaylistId, onSelectPlaylistId = { selectedPlaylistId = it; reloadAllData(forceRefresh = false) },
                                        visibleChannels, epgScheduleMap, favoriteIds, currentChannel, previousChannel, activeArchiveProgram, activeArchiveUrl,
                                        epgMinuteTickMs, clockPosition, playerEngine, playerResizeMode, playerBufferMode, playerDefaultUa,
                                        onSelectChannel = { selectChannelWithPinCheck(it) }, onOpenEpgDialog = { epgDialogChannel = it }, onOpenHiddenGroups = { showHiddenGroupsDialog = true },
                                        onResetToLive = { activeArchiveProgram = null; archiveSeekOffsetSec = 0L; playbackSpeed = 1f },
                                        onToggleFavorite = { ch -> val next = favoriteIds.toMutableSet(); if (!next.add(ch.id)) next.remove(ch.id); favoriteIds = next; prefs.edit().putStringSet("favs", next).apply() },
                                        onFullscreen = { val ch = currentChannel ?: return@LiveTvScreen; val url = activeArchiveUrl ?: ch.streamUrl; if (playerEngine == 2) openExternalSafe(url, ch.name) else isFullscreen = true },
                                        onOpenSettings = { currentTab = 2 },
                                        onNextChannel = { zapChannelStep(1) }
                                    )
                                    1 -> VodScreen(
                                        S, themePalette, vodItems, vodHistory, currentVodFolderTitle, vodFolderStack.isNotEmpty(), isLoadingMoreVod, vodNoticeMessage, maskSensitiveKeys(vportalDiagnostic),
                                        hasVPortal = vportalUrl.isNotBlank(),
                                        onBackFolder = { vodNoticeMessage = ""; if (vodFolderStack.isNotEmpty()) { val last = vodFolderStack.last(); currentVodFolderTitle = last.first; vodItems = last.second; vodFolderStack = vodFolderStack.dropLast(1) } },
                                        onClickItem = { openVodNode(it) }, onClickHistoryItem = { h -> startVodPlayback(h.key, h.title, h.posterUrl, h.streamUrl, h.positionMs) },
                                        onAutoLoadMore = { openVodNode(it) },
                                        onServerSearch = { q ->
                                            if (vportalUrl.isNotBlank() && q.isNotBlank()) scope.launch {
                                                isLoading = true; val res = NetworkLoader.loadVPortalNode(vportalUrl.trim(), searchQuery = q.trim()); vportalDiagnostic = res.second; isLoading = false
                                                if (res.first.isNotEmpty()) { vodFolderStack = vodFolderStack + (currentVodFolderTitle to vodItems); currentVodFolderTitle = "🔍 $q"; vodItems = res.first }
                                            }
                                        },
                                        onOpenSettings = { currentTab = 2 }
                                    )
                                    2 -> AccordionSettingsScreen(
                                        S, prefs, themePalette, deviceType, controlMode, clockPosition, playerEngine, archiveDays, epgOffsetHours, (sleepSecondsLeft + 59) / 60, hiddenGroups.size,
                                        parentalEnabled, parentalPin,
                                        onDeviceTypeChange = { deviceType = it; persistLists() }, onControlModeChange = { controlMode = it; persistLists() },
                                        onClockPositionChange = { clockPosition = it; persistLists() }, onPlayerEngineChange = { playerEngine = it; persistLists() },
                                        onArchiveDaysChange = { archiveDays = it; persistLists(); loadEpgWithStatus(forceRefresh = false) },
                                        onEpgOffsetChange = { epgOffsetHours = it; persistLists(); loadEpgWithStatus(forceRefresh = false) },
                                        onSleepTimerChange = { sleepSecondsLeft = it * 60 }, onOpenHiddenGroups = { showHiddenGroupsDialog = true },
                                        onParentalEnabledChange = { parentalEnabled = it; adultUnlockedSession = false; persistLists() }, onParentalPinChange = { parentalPin = it; persistLists() },
                                        onSelectTheme = { themePalette = it; prefs.edit().putString("theme_id", it.id).apply() },
                                        onToggleLanguage = { isRu = !isRu; prefs.edit().putString("lang", if (isRu) "ru" else "en").apply() },
                                        playerResizeMode, playerBufferMode, playerDefaultUa, openFullscreenOnTap,
                                        onPlayerResizeChange = { playerResizeMode = it; persistLists() }, onPlayerBufferChange = { playerBufferMode = it; persistLists() },
                                        onPlayerUaChange = { playerDefaultUa = it; persistLists() }, onAutoFsChange = { openFullscreenOnTap = it; persistLists() },
                                        playlists, selectedPlaylistId, onSelectPlaylist = { selectedPlaylistId = it; reloadAllData(forceRefresh = false) },
                                        onAddPlaylist = { name, url -> val id = "pl_${System.currentTimeMillis()}"; playlists = playlists + NamedSource(id, name, url); selectedPlaylistId = id; reloadAllData(forceRefresh = true) },
                                        onDeletePlaylist = { id ->
                                            val deleted = playlists.find { it.id == id }
                                            if (deleted != null) NetworkLoader.clearPlaylistCache(this@MainActivity, deleted.url)
                                            playlists = playlists.filterNot { it.id == id }
                                            if (selectedPlaylistId == id) selectedPlaylistId = playlists.firstOrNull()?.id ?: ""
                                            reloadAllData(forceRefresh = false)
                                        },
                                        epgSources, selectedEpgId, usePlaylistEpg, detectedPlaylistEpgUrl, isEpgLoading, epgStatusText,
                                        onRefreshEpgNow = { persistLists(); loadEpgWithStatus(forceRefresh = true) },
                                        onToggleUsePlaylistEpg = { usePlaylistEpg = it; persistLists(); loadEpgWithStatus(forceRefresh = false) },
                                        onSelectEpg = { selectedEpgId = it; persistLists(); loadEpgWithStatus(forceRefresh = false) },
                                        onAddEpg = { name, url -> val id = "epg_${System.currentTimeMillis()}"; epgSources = epgSources + NamedSource(id, name, url); selectedEpgId = id; persistLists(); loadEpgWithStatus(forceRefresh = true) },
                                        onDeleteEpg = { id ->
                                            val deleted = epgSources.find { it.id == id }
                                            if (deleted != null) NetworkLoader.clearEpgCache(this@MainActivity, deleted.url)
                                            epgSources = epgSources.filterNot { it.id == id }
                                            if (selectedEpgId == id) selectedEpgId = "AUTO_PLAYLIST"
                                            persistLists()
                                            loadEpgWithStatus(forceRefresh = false)
                                        },
                                        vportalUrl, stalkerUrl, stalkerMac, vportalDiagnostic,
                                        onVportalChange = { vportalUrl = it }, onStalkerUrlChange = { stalkerUrl = it }, onStalkerMacChange = { stalkerMac = it },
                                        onSaveAndReload = { reloadAllData(forceRefresh = true) }, onSettingsImported = { refreshStateFromPrefs() },
                                        qrServer = qrServer
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

@Composable
fun PlayerQuickMenuDialog(
    S: Strings, theme: AppThemePalette, isVod: Boolean, isArchive: Boolean, playerEngine: Int,
    resizeLabel: String, playbackSpeed: Float, sleepMinutesLeft: Int, isSmartphone: Boolean,
    onCycleResize: () -> Unit, onOpenAudio: () -> Unit, onOpenQuality: () -> Unit = {}, onCycleSpeed: () -> Unit,
    onSeekBack10: () -> Unit, onSeekFwd10: () -> Unit, onOpenSleep: () -> Unit,
    onOpenChannels: () -> Unit, onOpenTvGuide: () -> Unit, onOpenMultiView: () -> Unit, onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = theme.card), shape = RoundedCornerShape(18.dp)) {
            Column(modifier = Modifier.padding(18.dp).widthIn(min = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (S.isRu) "⚙️ Опции плеера" else "⚙️ Player Options", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = theme.primary)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, null, tint = theme.text) }
                }
                if (playerEngine == 0) {
                    OutlinedButton(onClick = onCycleResize, modifier = Modifier.fillMaxWidth()) {
                        Text(if (S.isRu) "📐 Формат: $resizeLabel" else "📐 Aspect: $resizeLabel", color = theme.text)
                    }
                    OutlinedButton(onClick = onOpenAudio, modifier = Modifier.fillMaxWidth()) {
                        Text(if (S.isRu) "🔊 Звуковая дорожка" else "🔊 Audio Track", color = theme.text)
                    }
                    OutlinedButton(onClick = onOpenQuality, modifier = Modifier.fillMaxWidth()) {
                        Text(if (S.isRu) "🎞 Качество видео" else "🎞 Video Quality", color = theme.text)
                    }
                    if (isVod || isArchive) {
                        OutlinedButton(onClick = onCycleSpeed, modifier = Modifier.fillMaxWidth()) {
                            Text(if (S.isRu) "⚡ Скорость: ${playbackSpeed}x" else "⚡ Speed: ${playbackSpeed}x", color = theme.text)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = onSeekBack10, modifier = Modifier.weight(1f)) { Text(if (S.isRu) "⏪ -10 сек" else "⏪ -10 sec", color = theme.text) }
                            OutlinedButton(onClick = onSeekFwd10, modifier = Modifier.weight(1f)) { Text(if (S.isRu) "⏩ +10 сек" else "⏩ +10 sec", color = theme.text) }
                        }
                    }
                }
                OutlinedButton(onClick = onOpenSleep, modifier = Modifier.fillMaxWidth()) {
                    val text = if (sleepMinutesLeft > 0) {
                        if (S.isRu) "⏱ Таймер сна: $sleepMinutesLeft мин" else "⏱ Sleep Timer: $sleepMinutesLeft min"
                    } else {
                        if (S.isRu) "⏱ Таймер сна: Выкл" else "⏱ Sleep Timer: Off"
                    }
                    Text(text, color = theme.text)
                }
                if (!isVod) {
                    if (isSmartphone) OutlinedButton(onClick = onOpenChannels, modifier = Modifier.fillMaxWidth()) {
                        Text(if (S.isRu) "📋 Список каналов" else "📋 Channel List", color = theme.text)
                    }
                    OutlinedButton(onClick = onOpenTvGuide, modifier = Modifier.fillMaxWidth()) {
                        Text(if (S.isRu) "🗂 Прозрачный ТВ-Гид" else "🗂 Overlay TV Guide", color = theme.text)
                    }
                    if (playerEngine == 0) OutlinedButton(onClick = onOpenMultiView, modifier = Modifier.fillMaxWidth()) {
                        Text(if (S.isRu) "⊞ Мультиэкран (4 канала)" else "⊞ Multi-View (4 channels)", color = theme.text)
                    }
                }
            }
        }
    }
}

@Composable
fun QuickChannelPickerDialog(S: Strings, theme: AppThemePalette, channels: List<Channel>, currentChannel: Channel?, onDismiss: () -> Unit, onSelect: (Channel) -> Unit) {
    var q by remember { mutableStateOf("") }
    val filtered = remember(channels, q) { if (q.isBlank()) channels else channels.filter { it.name.contains(q, ignoreCase = true) } }
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = theme.card), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(if (S.isRu) "📋 Выбор канала" else "📋 Channels", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = theme.primary)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null, tint = theme.text) }
                }
                OutlinedTextField(value = q, onValueChange = { q = it }, placeholder = { Text(S.searchHint, color = theme.subtext) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.id }) { ch ->
                        val isCur = currentChannel?.id == ch.id
                        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (isCur) theme.selectedCard else theme.bg).tvFocusHighlight(theme.primary, 10.dp).clickable { onSelect(ch) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
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
    S: Strings, theme: AppThemePalette, channel: Channel, schedule: List<EpgProgramItem>, reminders: List<ProgramReminder>,
    archiveDays: Int = 3,
    onDismiss: () -> Unit, onToggleReminder: (EpgProgramItem) -> Unit, onPlayArchive: (EpgProgramItem) -> Unit, onPlayLive: () -> Unit
) {
    val now = System.currentTimeMillis()
    val channelHasArchive = channel.catchupDays > 0
    val listState = rememberLazyListState()
    LaunchedEffect(schedule) { val liveIdx = schedule.indexOfFirst { now in it.startMs..it.stopMs }; if (liveIdx > 0) listState.scrollToItem((liveIdx - 1).coerceAtLeast(0)) }

    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = theme.card), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("📅 ${channel.name}", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = theme.primary, maxLines = 1)
                        Text(if (S.isRu) "Архив ⏪, Эфир 🟢 и Напоминания 🔔" else "Archive ⏪, Live 🟢 & Reminders 🔔", fontSize = 12.sp, color = theme.subtext)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null, tint = theme.text) }
                }
                Spacer(Modifier.height(10.dp))
                if (schedule.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (S.isRu) "Для «${channel.name}» программа не найдена." else "No TV guide found for ${channel.name}.", fontSize = 14.sp, color = theme.subtext)
                    }
                } else {
                    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                        items(schedule) { prog ->
                            val isPast = prog.stopMs < now; val isNow = now in prog.startMs..prog.stopMs; val isFuture = prog.startMs > now
                            val canPlayThisPast = isPast && channelHasArchive && isArchiveProgramAvailable(channel, prog, archiveDays, now)
                            val hasRem = isFuture && reminders.any { it.channelId == channel.id && it.startMs == prog.startMs }
                            val badgeText = when {
                                isNow -> if (S.isRu) "🟢 В ЭФИРЕ" else "🟢 LIVE"
                                canPlayThisPast -> if (S.isRu) "⏪ АРХИВ" else "⏪ CATCHUP"
                                hasRem -> if (S.isRu) "🔔 НАПОМНЮ" else "🔔 REMINDED"
                                isFuture -> if (S.isRu) "🔔 НАПОМНИТЬ" else "🔔 REMIND"
                                else -> if (S.isRu) "ЗАВЕРШЕНО" else "ENDED"
                            }
                            val badgeColor = when { isNow -> Color(0xFF10B981); canPlayThisPast -> Color(0xFFF59E0B); hasRem -> Color(0xFF38BDF8); else -> theme.subtext }
                            Column(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (isNow) theme.selectedCard else theme.bg)
                                    .tvFocusHighlight(theme.primary, 12.dp).border(if (isNow) 1.5.dp else 0.dp, if (isNow) theme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                                    .clickable(enabled = canPlayThisPast || isNow || isFuture) { if (canPlayThisPast) onPlayArchive(prog) else if (isNow) onPlayLive() else if (isFuture) onToggleReminder(prog) }.padding(12.dp)
                            ) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(prog.timeRangeText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = theme.subtext); Text(badgeText, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = badgeColor) }
                                Spacer(Modifier.height(4.dp)); Text(prog.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = theme.text)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LiveTvScreen(
    S: Strings, theme: AppThemePalette, playlists: List<NamedSource>, selectedPlaylistId: String, onSelectPlaylistId: (String) -> Unit,
    channels: List<Channel>, epgScheduleMap: Map<String, List<EpgProgramItem>>, favoriteIds: Set<String>,
    currentChannel: Channel?, previousChannel: Channel?, activeArchiveProgram: EpgProgramItem?, activeArchiveUrl: String?,
    currentTimeMs: Long, clockPosition: Int, playerEngine: Int, playerResizeMode: Int, playerBufferMode: Int, playerDefaultUa: String,
    onSelectChannel: (Channel) -> Unit, onOpenEpgDialog: (Channel) -> Unit, onOpenHiddenGroups: () -> Unit,
    onResetToLive: () -> Unit, onToggleFavorite: (Channel) -> Unit, onFullscreen: () -> Unit, onOpenSettings: () -> Unit,
    onNextChannel: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        if (playlists.isNotEmpty()) {
            val activeName = playlists.find { it.id == selectedPlaylistId }?.name ?: playlists.first().name
            SourceDropdownSelector(S.activePlaylistLabel, "📺 $activeName", playlists.map { it.id to it.name }, theme, onSelectPlaylistId, Modifier.padding(bottom = 10.dp))
        }
        if (channels.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(S.noChannels, fontSize = 19.sp, color = theme.subtext); Spacer(Modifier.height(16.dp)); Button(onClick = onOpenSettings) { Text(S.goToSettings) } }
            }
            return
        }

        val favCount = remember(channels, favoriteIds) { channels.count { favoriteIds.contains(it.id) } }
        val groups = remember(channels, favCount, S) { val list = mutableListOf(S.allGroups); if (favCount > 0) list.add(FAV_GROUP_TAG); list.addAll(channels.map { it.group }.distinct()); list }
        var selectedGroup by remember { mutableStateOf(S.allGroups) }
        var searchQuery by remember { mutableStateOf("") }
        LaunchedEffect(favCount) { if (favCount == 0 && selectedGroup == FAV_GROUP_TAG) selectedGroup = S.allGroups }
        LaunchedEffect(groups) { if (!groups.contains(selectedGroup)) selectedGroup = S.allGroups }

        val filtered = remember(channels, selectedGroup, searchQuery, favoriteIds, S) {
            channels.filter { ch ->
                val matchG = when (selectedGroup) { S.allGroups -> true; FAV_GROUP_TAG -> favoriteIds.contains(ch.id); else -> ch.group == selectedGroup }
                matchG && (searchQuery.isBlank() || ch.name.contains(searchQuery, ignoreCase = true))
            }
        }

        if (currentChannel != null) {
            val playUrl = activeArchiveUrl ?: currentChannel.streamUrl
            val curSchedule = remember(currentChannel, epgScheduleMap) { findChannelSchedule(currentChannel, epgScheduleMap) }
            val nowProg = remember(curSchedule, currentTimeMs) { curSchedule.find { currentTimeMs in it.startMs..it.stopMs } }

            Row(modifier = Modifier.fillMaxWidth().height(215.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                Box(modifier = Modifier.weight(1.3f).fillMaxHeight()) {
                    SmartVideoPlayer(
                        streamUrl = playUrl,
                        userAgent = currentChannel.userAgent ?: playerDefaultUa,
                        resizeMode = playerResizeMode,
                        bufferMode = playerBufferMode,
                        useNativeController = false,
                        reconnectingText = S.reconnecting,
                        playerEngine = if (playerEngine == 2) 0 else playerEngine,
                        referrer = currentChannel.referrer,
                        cookie = currentChannel.cookie,
                        isRu = S.isRu,
                        onNextChannel = onNextChannel,
                        modifier = Modifier.fillMaxSize().clickable { onFullscreen() }
                    )
                    PlayerClockBadge(clockPosition, theme.primary)
                }
                Column(modifier = Modifier.weight(1f).fillMaxHeight().background(theme.card).padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("${currentChannel.number}. ${currentChannel.name}", fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = theme.primary)
                        Text(currentChannel.group, fontSize = 11.sp, color = theme.subtext, maxLines = 1)
                    }
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).tvFocusHighlight(theme.primary, 10.dp).border(1.dp, theme.primary.copy(alpha = 0.45f), RoundedCornerShape(10.dp)).clickable { onOpenEpgDialog(currentChannel) }.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (activeArchiveProgram != null) {
                            Text((if (S.isRu) "⏪ Архив: " else "⏪ Catchup: ") + activeArchiveProgram.title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B), maxLines = 2)
                            Text(activeArchiveProgram.timeRangeText, fontSize = 10.sp, color = theme.subtext)
                        } else if (nowProg != null) {
                            val totalMs = (nowProg.stopMs - nowProg.startMs).coerceAtLeast(1L); val elapsedMs = (currentTimeMs - nowProg.startMs).coerceIn(0L, totalMs)
                            Text("📺 ${nowProg.title}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = theme.text, maxLines = 2)
                            LinearProgressIndicator(progress = { (elapsedMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = theme.primary)
                            Text("🕒 ${nowProg.timeRangeText} • " + (if (S.isRu) "⏪ Архив" else "⏪ Archive"), fontSize = 10.sp, color = theme.primary, maxLines = 1)
                        } else if (curSchedule.isNotEmpty()) {
                            Text(if (S.isRu) "📅 Расписание (${curSchedule.size} пер.)" else "📅 Schedule (${curSchedule.size})", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = theme.primary)
                        } else Text(if (S.isRu) "📅 Программа и Архив" else "📅 EPG & Archive", fontSize = 11.sp, color = theme.subtext)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = onFullscreen, modifier = Modifier.weight(1f).height(36.dp), contentPadding = PaddingValues(4.dp)) {
                            Icon(Icons.Default.Fullscreen, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                            Text(if (playerEngine == 2) (if (S.isRu) "Внеш." else "Ext.") else S.fullscreen, fontSize = 11.sp, maxLines = 1)
                        }
                        if (activeArchiveProgram != null) {
                            OutlinedButton(onClick = onResetToLive, modifier = Modifier.height(36.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text(if (S.isRu) "🔴 Эфир" else "🔴 Live", fontSize = 11.sp, color = Color(0xFFEF4444))
                            }
                        } else if (previousChannel != null) {
                            OutlinedButton(onClick = { onSelectChannel(previousChannel) }, modifier = Modifier.height(36.dp), contentPadding = PaddingValues(6.dp)) { Icon(Icons.Default.History, null, modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text(S.searchHint, color = theme.subtext) }, leadingIcon = { Icon(Icons.Default.Search, null, tint = theme.primary) }, trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, null) } }, singleLine = true, modifier = Modifier.weight(1f).height(52.dp))
            IconButton(onClick = onOpenHiddenGroups, modifier = Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)).background(theme.card).tvFocusHighlight(theme.primary, 12.dp)) { Icon(Icons.Default.Tune, null, tint = theme.primary) }
        }
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
                val isPlaying = currentChannel?.id == ch.id; val isFav = favoriteIds.contains(ch.id)
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (isPlaying) theme.selectedCard else theme.card)
                        .tvFocusHighlight(theme.primary, 12.dp).border(if (isPlaying) 2.dp else 0.dp, if (isPlaying) theme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                        .clickable { if (isPlaying) onFullscreen() else onSelectChannel(ch) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${ch.number}", color = theme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
                    if (!ch.logoUrl.isNullOrBlank()) { AsyncImage(model = ch.logoUrl, contentDescription = ch.name, modifier = Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Fit); Spacer(Modifier.width(10.dp)) }
                    Column(modifier = Modifier.weight(1f)) { Text(ch.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = theme.text); Text(ch.group, fontSize = 12.sp, color = theme.subtext, maxLines = 1) }
                    IconButton(onClick = { onToggleFavorite(ch) }) { Icon(if (isFav) Icons.Default.Star else Icons.Default.StarBorder, null, tint = if (isFav) Color(0xFFF59E0B) else theme.subtext) }
                }
            }
        }
    }
}

@Composable
fun VodScreen(
    S: Strings, theme: AppThemePalette, movies: List<VodMovie>, vodHistory: List<VodProgressItem>,
    folderTitle: String, canGoBack: Boolean, isLoadingMore: Boolean, noticeMessage: String, diagnosticLog: String,
    hasVPortal: Boolean = false,
    onBackFolder: () -> Unit, onClickItem: (VodMovie) -> Unit, onClickHistoryItem: (VodProgressItem) -> Unit,
    onAutoLoadMore: (VodMovie) -> Unit, onServerSearch: (String) -> Unit, onOpenSettings: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    if (canGoBack) BackHandler { onBackFolder() }
    val filtered = remember(movies, searchQuery) { if (searchQuery.isBlank()) movies else movies.filter { it.title.contains(searchQuery, ignoreCase = true) } }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        if (vodHistory.isNotEmpty() && !canGoBack && searchQuery.isBlank()) {
            Text(if (S.isRu) "🕒 Продолжить просмотр:" else "🕒 Continue Watching:", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = theme.primary, modifier = Modifier.padding(bottom = 6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                items(vodHistory, key = { it.key + it.streamUrl }) { h ->
                    Card(colors = CardDefaults.cardColors(containerColor = theme.card), shape = RoundedCornerShape(10.dp), modifier = Modifier.width(155.dp).tvFocusHighlight(theme.primary, 10.dp).clickable { onClickHistoryItem(h) }) {
                        Column(modifier = Modifier.padding(8.dp)) { Text(h.title, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = theme.text, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("▶ ${formatTimeDuration(h.positionMs)}", fontSize = 11.sp, color = theme.primary, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canGoBack) Button(onClick = onBackFolder, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), modifier = Modifier.padding(end = 10.dp)) { Icon(Icons.Default.ArrowBack, null); Spacer(Modifier.width(4.dp)); Text(if (S.isRu) "Назад" else "Back") }
            Text(folderTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = theme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (noticeMessage.isNotBlank()) Text(noticeMessage, fontSize = 12.sp, color = Color(0xFFF59E0B), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(8.dp)).background(theme.card).padding(10.dp))
        if (movies.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    Text(if (S.isRu) "В этом разделе пока пусто" else "No items loaded", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = theme.text)
                    if (diagnosticLog.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(diagnosticLog, fontSize = 12.sp, color = theme.subtext, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(theme.card).padding(12.dp)) }
                    Spacer(Modifier.height(16.dp)); Button(onClick = onOpenSettings) { Text(S.goToSettings) }
                }
            }
            return
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text(S.searchHint, color = theme.subtext) }, leadingIcon = { Icon(Icons.Default.Search, null, tint = theme.primary) }, trailingIcon = { if (searchQuery.isNotEmpty()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, null) } }, singleLine = true, modifier = Modifier.weight(1f).height(52.dp))
            if (hasVPortal) {
                Button(onClick = { onServerSearch(searchQuery) }, modifier = Modifier.height(52.dp)) { Text(if (S.isRu) "Найти" else "Find") }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 150.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { it.id }) { movie ->
                if (movie.id.startsWith("vp_next_")) LaunchedEffect(movie.id) { if (searchQuery.isBlank() && !isLoadingMore) onAutoLoadMore(movie) }
                Card(colors = CardDefaults.cardColors(containerColor = theme.card), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().tvFocusHighlight(theme.primary, 14.dp).clickable { onClickItem(movie) }) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Box(modifier = Modifier.fillMaxWidth().height(if (movie.isFolder && movie.posterUrl.isNullOrBlank()) 85.dp else 175.dp).clip(RoundedCornerShape(10.dp)).background(theme.bg), contentAlignment = Alignment.Center) {
                            if (!movie.posterUrl.isNullOrBlank()) AsyncImage(model = movie.posterUrl, contentDescription = movie.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Icon(if (movie.id.startsWith("vp_next_")) Icons.Default.Sync else if (movie.isFolder) Icons.Default.Folder else Icons.Default.PlayCircleFilled, null, tint = theme.primary, modifier = Modifier.size(44.dp))
                        }
                        Spacer(Modifier.height(8.dp))
                        val nextLabel = if (S.isRu) "⏳ Подгрузка..." else "⏳ Loading more..."
                        Text(if (movie.id.startsWith("vp_next_")) nextLabel else movie.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = theme.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (movie.isFolder) (if (S.isRu) "📁 Открыть" else "📁 Open") else (if (S.isRu) "▶ Смотреть" else "▶ Watch"),
                            fontSize = 12.sp, color = theme.primary
                        )
                    }
                }
            }
        }
    }
}
