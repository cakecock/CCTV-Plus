@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.myiptv.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class AudioTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val isSelected: Boolean
)

fun launchExternalVideoPlayer(context: Context, streamUrl: String, title: String) {
    if (streamUrl.isBlank()) return
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(streamUrl), "video/*")
            putExtra("title", title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun SmartVideoPlayer(
    streamUrl: String,
    userAgent: String?,
    resizeMode: Int,
    bufferMode: Int,
    useNativeController: Boolean,
    reconnectingText: String,
    modifier: Modifier = Modifier,
    playerEngine: Int = 0,
    isMuted: Boolean = false,
    enableSwipeGestures: Boolean = false,
    playbackSpeed: Float = 1f,
    initialSeekMs: Long = 0L,
    seekCommand: Pair<Int, Long>? = null,
    showAudioSelectorDialog: Boolean = false,
    onDismissAudioSelector: () -> Unit = {},
    onVideoQualityDetected: (String) -> Unit = {},
    onPlaybackProgress: (posMs: Long, durMs: Long) -> Unit = { _, _ -> }
) {
    if (playerEngine == 1) {
        NativeAndroidVideoPlayer(
            streamUrl = streamUrl,
            useNativeController = useNativeController,
            isMuted = isMuted,
            reconnectingText = reconnectingText,
            modifier = modifier
        )
    } else {
        ExoVideoPlayer(
            streamUrl = streamUrl,
            userAgent = userAgent,
            resizeMode = resizeMode,
            bufferMode = bufferMode,
            useNativeController = useNativeController,
            reconnectingText = reconnectingText,
            modifier = modifier,
            isMuted = isMuted,
            enableSwipeGestures = enableSwipeGestures,
            playbackSpeed = playbackSpeed,
            initialSeekMs = initialSeekMs,
            seekCommand = seekCommand,
            showAudioSelectorDialog = showAudioSelectorDialog,
            onDismissAudioSelector = onDismissAudioSelector,
            onVideoQualityDetected = onVideoQualityDetected,
            onPlaybackProgress = onPlaybackProgress
        )
    }
}

@Composable
fun NativeAndroidVideoPlayer(
    streamUrl: String,
    useNativeController: Boolean,
    isMuted: Boolean,
    reconnectingText: String,
    modifier: Modifier = Modifier
) {
    var hasError by remember(streamUrl) { mutableStateOf(false) }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    if (useNativeController) {
                        val mc = MediaController(ctx)
                        mc.setAnchorView(this)
                        setMediaController(mc)
                    }
                }
            },
            update = { view ->
                if (streamUrl.isNotBlank() && view.tag != streamUrl) {
                    view.tag = streamUrl
                    hasError = false
                    view.setOnPreparedListener { mp ->
                        val vol = if (isMuted) 0f else 1f
                        mp.setVolume(vol, vol)
                        mp.start()
                    }
                    view.setOnErrorListener { _, _, _ -> hasError = true; true }
                    view.setVideoURI(Uri.parse(streamUrl))
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (hasError) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(reconnectingText, color = Color(0xFFF59E0B), fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun ExoVideoPlayer(
    streamUrl: String,
    userAgent: String?,
    resizeMode: Int,
    bufferMode: Int,
    useNativeController: Boolean,
    reconnectingText: String,
    modifier: Modifier = Modifier,
    isMuted: Boolean = false,
    enableSwipeGestures: Boolean = false,
    playbackSpeed: Float = 1f,
    initialSeekMs: Long = 0L,
    seekCommand: Pair<Int, Long>? = null,
    showAudioSelectorDialog: Boolean = false,
    onDismissAudioSelector: () -> Unit = {},
    onVideoQualityDetected: (String) -> Unit = {},
    onPlaybackProgress: (posMs: Long, durMs: Long) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var isReconnecting by remember { mutableStateOf(false) }
    var gestureHudText by remember { mutableStateOf("") }
    var audioTracks by remember { mutableStateOf<List<AudioTrackOption>>(emptyList()) }
    var didInitialSeek by remember(streamUrl, initialSeekMs) { mutableStateOf(false) }
    var detectedRes by remember(streamUrl) { mutableStateOf("") }
    var detectedFps by remember(streamUrl) { mutableStateOf("") }
    val curOnQuality by rememberUpdatedState(onVideoQualityDetected)
    val curOnProgress by rememberUpdatedState(onPlaybackProgress)
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

    LaunchedEffect(isMuted, exoPlayer) { exoPlayer.volume = if (isMuted) 0f else 1f }
    LaunchedEffect(playbackSpeed, exoPlayer) { exoPlayer.setPlaybackSpeed(playbackSpeed) }
    LaunchedEffect(seekCommand) {
        if (seekCommand != null) {
            exoPlayer.seekTo((exoPlayer.currentPosition + seekCommand.second).coerceAtLeast(0L))
        }
    }

    LaunchedEffect(streamUrl, userAgent, exoPlayer) {
        if (streamUrl.isBlank()) return@LaunchedEffect
        isReconnecting = false
        curOnQuality("")
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

    LaunchedEffect(exoPlayer, streamUrl) {
        while (true) {
            delay(4000L)
            val dur = exoPlayer.duration
            val pos = exoPlayer.currentPosition
            if (dur > 30000L && pos > 5000L) {
                curOnProgress(pos, dur)
            }
        }
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
                if (playbackState == Player.STATE_READY) {
                    isReconnecting = false
                    if (!didInitialSeek && initialSeekMs > 5000L) {
                        didInitialSeek = true
                        exoPlayer.seekTo(initialSeekMs)
                    }
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val h = videoSize.height
                if (h > 0) {
                    detectedRes = when {
                        h >= 2100 -> "4K"
                        h >= 1050 -> "1080p"
                        h >= 700 -> "720p"
                        else -> "${h}p"
                    }
                    val combined = listOf(detectedRes, detectedFps).filter { it.isNotBlank() }.joinToString(" • ")
                    curOnQuality(combined)
                }
            }
            override fun onTracksChanged(tracks: Tracks) {
                val list = mutableListOf<AudioTrackOption>()
                tracks.groups.forEachIndexed { gIdx, group ->
                    if (group.type == C.TRACK_TYPE_AUDIO) {
                        for (tIdx in 0 until group.length) {
                            val fmt = group.getTrackFormat(tIdx)
                            val lang = fmt.language?.uppercase() ?: ""
                            val lbl = fmt.label ?: ""
                            val chs = if (fmt.channelCount > 0) "${fmt.channelCount}ch" else ""
                            val title = listOf(lbl, lang, chs).filter { it.isNotBlank() }.joinToString(" • ")
                                .ifEmpty { "Дорожка #${list.size + 1}" }
                            list.add(AudioTrackOption(gIdx, tIdx, title, group.isTrackSelected(tIdx)))
                        }
                    } else if (group.type == C.TRACK_TYPE_VIDEO && group.length > 0) {
                        val fps = group.getTrackFormat(0).frameRate
                        if (fps > 1f) {
                            detectedFps = "${fps.roundToInt()}fps"
                            val combined = listOf(detectedRes, detectedFps).filter { it.isNotBlank() }.joinToString(" • ")
                            if (combined.isNotBlank()) curOnQuality(combined)
                        }
                    }
                }
                audioTracks = list
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener); exoPlayer.release() }
    }

    if (showAudioSelectorDialog) {
        Dialog(onDismissRequest = onDismissAudioSelector) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1C2026)), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp).widthIn(min = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🔊 Выбор звуковой дорожки", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color(0xFF10B981))
                    if (audioTracks.isEmpty()) {
                        Text("Дополнительные звуковые дорожки не найдены", fontSize = 13.sp, color = Color.LightGray)
                    } else {
                        audioTracks.forEach { opt ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .background(if (opt.isSelected) Color(0xFF19322B) else Color(0xFF121417))
                                    .clickable {
                                        val grp = exoPlayer.currentTracks.groups.getOrNull(opt.groupIndex)
                                        if (grp != null) {
                                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                                .buildUpon()
                                                .setOverrideForType(TrackSelectionOverride(grp.mediaTrackGroup, opt.trackIndex))
                                                .build()
                                        }
                                        onDismissAudioSelector()
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(opt.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                if (opt.isSelected) Icon(Icons.Default.Check, null, tint = Color(0xFF10B981))
                            }
                        }
                    }
                    TextButton(onClick = onDismissAudioSelector, modifier = Modifier.align(Alignment.End)) { Text("Закрыть", color = Color.White) }
                }
            }
        }
    }

    val aspectConst = when (resizeMode) {
        1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    Box(
        modifier = modifier.pointerInput(enableSwipeGestures) {
            if (!enableSwipeGestures) return@pointerInput
            detectVerticalDragGestures(
                onDragEnd = { gestureHudText = "" },
                onDragCancel = { gestureHudText = "" },
                onVerticalDrag = { change, dragAmount ->
                    val w = size.width.coerceAtLeast(1)
                    val isLeft = change.position.x < w / 2f
                    val delta = -dragAmount / 550f
                    if (isLeft) {
                        val win = (context as? Activity)?.window
                        if (win != null) {
                            val cur = win.attributes.screenBrightness.let { if (it < 0f) 0.5f else it }
                            val next = (cur + delta).coerceIn(0.05f, 1.0f)
                            val lp = win.attributes; lp.screenBrightness = next; win.attributes = lp
                            gestureHudText = "☀️ Яркость: ${(next * 100).toInt()}%"
                        }
                    } else {
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val maxV = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val curV = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val nextFloat = ((curV.toFloat() / maxV.toFloat()) + delta).coerceIn(0f, 1f)
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, (nextFloat * maxV).toInt().coerceIn(0, maxV), 0)
                        gestureHudText = "🔊 Громкость: ${(nextFloat * 100).toInt()}%"
                    }
                }
            )
        }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer; useController = useNativeController; setShowSubtitleButton(true)
                    this.resizeMode = aspectConst
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            update = { view ->
                view.player = exoPlayer; view.useController = useNativeController; view.resizeMode = aspectConst
            },
            modifier = Modifier.fillMaxSize()
        )
        if (gestureHudText.isNotEmpty()) {
            Box(modifier = Modifier.align(Alignment.Center).background(Color(0xDD000000), RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(gestureHudText, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            }
        }
        if (isReconnecting) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(reconnectingText, color = Color(0xFFF59E0B), fontSize = 13.sp)
            }
        }
    }
}

// ================= КАРТОЧКА ФИЛЬМА (VOD) С ОПИСАНИЕМ И ПРОДОЛЖЕНИЕМ ПРОСМОТРА =================

@Composable
fun VodDetailsDialog(
    S: Strings,
    theme: AppThemePalette,
    movie: VodMovie,
    savedProgress: VodProgressItem?,
    onPlayFromStart: () -> Unit,
    onResume: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = theme.card), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 580.dp)) {
            Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(modifier = Modifier.width(105.dp).height(150.dp).clip(RoundedCornerShape(12.dp)).background(theme.bg), contentAlignment = Alignment.Center) {
                        if (!movie.posterUrl.isNullOrBlank()) {
                            AsyncImage(model = movie.posterUrl, contentDescription = movie.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        } else {
                            Icon(Icons.Default.Movie, null, tint = theme.primary, modifier = Modifier.size(44.dp))
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(movie.title, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = theme.text)
                        if (movie.year.isNotBlank() || movie.rating.isNotBlank()) {
                            Text(listOfNotNull(movie.year.takeIf { it.isNotBlank() }, movie.rating.takeIf { it.isNotBlank() }?.let { "⭐ $it" }).joinToString("  •  "), fontSize = 13.sp, color = theme.primary, fontWeight = FontWeight.Bold)
                        }
                        Text(movie.category, fontSize = 12.sp, color = theme.subtext)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, null, tint = theme.text) }
                }

                if (movie.description.isNotBlank()) {
                    Text(movie.description, fontSize = 13.sp, color = theme.text)
                }

                if (savedProgress != null && savedProgress.positionMs > 10000L) {
                    val posStr = formatTimeDuration(savedProgress.positionMs)
                    Button(onClick = { onResume(savedProgress.positionMs) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (S.isRu) "▶ Продолжить с $posStr" else "▶ Resume from $posStr", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = onPlayFromStart, modifier = Modifier.fillMaxWidth()) {
                        Text(if (S.isRu) "🔄 Смотреть сначала" else "🔄 Watch from start")
                    }
                } else {
                    Button(onClick = onPlayFromStart, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (S.isRu) "▶ Смотреть" else "▶ Play", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ================= ОКНО СКРЫТИЯ КАТЕГОРИЙ ПЛЕЙЛИСТА =================

@Composable
fun HiddenCategoriesDialog(
    S: Strings,
    theme: AppThemePalette,
    allGroups: List<String>,
    hiddenGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    onShowAll: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = theme.card), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (S.isRu) "👁 Видимые категории" else "👁 Visible Categories", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = theme.primary)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null, tint = theme.text) }
                }
                Text(if (S.isRu) "Снимите галочки с ненужных категорий, чтобы скрыть их:" else "Uncheck groups to hide them:", fontSize = 12.sp, color = theme.subtext, modifier = Modifier.padding(bottom = 8.dp))
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(allGroups) { grp ->
                        val isVisible = !hiddenGroups.contains(grp)
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg)
                                .clickable { onToggleGroup(grp) }.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(grp, color = if (isVisible) theme.text else theme.subtext, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            Checkbox(checked = isVisible, onCheckedChange = { onToggleGroup(grp) })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onShowAll, modifier = Modifier.weight(1f)) { Text(if (S.isRu) "Показать все" else "Show all") }
                    Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(if (S.isRu) "Готово" else "Done") }
                }
            }
        }
    }
}

// ================= ОКНО ТАЙМЕРА СНА =================

@Composable
fun SleepTimerDialog(
    S: Strings,
    theme: AppThemePalette,
    currentMinutesLeft: Int,
    onSelectMinutes: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = theme.card), shape = RoundedCornerShape(18.dp)) {
            Column(modifier = Modifier.padding(18.dp).widthIn(min = 250.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (S.isRu) "⏱ Таймер сна" else "⏱ Sleep Timer", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = theme.primary)
                if (currentMinutesLeft > 0) {
                    Text(if (S.isRu) "Выключение через: $currentMinutesLeft мин" else "Stopping in: ${currentMinutesLeft}m", fontSize = 13.sp, color = theme.text)
                }
                listOf(0, 15, 30, 60, 90).forEach { mins ->
                    val label = if (mins == 0) (if (S.isRu) "🚫 Отключить таймер" else "🚫 Turn Off") else "⏱ $mins мин"
                    OutlinedButton(onClick = { onSelectMinutes(mins); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text(label, color = theme.text, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ================= 3-КОЛОНОЧНЫЙ ПРОЗРАЧНЫЙ ТВ-ГИД С НАПОМИНАНИЯМИ =================

@Composable
fun TiviMateTvOverlay(
    S: Strings,
    theme: AppThemePalette,
    channels: List<Channel>,
    hiddenGroups: Set<String>,
    epgScheduleMap: Map<String, List<EpgProgramItem>>,
    reminders: List<ProgramReminder>,
    currentChannel: Channel?,
    onSelectChannel: (Channel) -> Unit,
    onPlayArchive: (Channel, EpgProgramItem) -> Unit,
    onToggleReminder: (Channel, EpgProgramItem) -> Unit,
    onClose: () -> Unit
) {
    val visibleChannels = remember(channels, hiddenGroups) { channels.filterNot { hiddenGroups.contains(it.group) } }
    val groups = remember(visibleChannels, S) { listOf(S.allGroups) + visibleChannels.map { it.group }.distinct() }
    var selGroup by remember { mutableStateOf(currentChannel?.group?.takeIf { !hiddenGroups.contains(it) } ?: S.allGroups) }
    val groupChannels = remember(visibleChannels, selGroup, S) {
        if (selGroup == S.allGroups) visibleChannels else visibleChannels.filter { it.group == selGroup }
    }
    var previewChannel by remember(currentChannel) { mutableStateOf(currentChannel ?: visibleChannels.firstOrNull()) }
    val previewSchedule = remember(previewChannel, epgScheduleMap) {
        previewChannel?.let { findChannelSchedule(it, epgScheduleMap) } ?: emptyList()
    }
    val now = System.currentTimeMillis()
    val epgListState = rememberLazyListState()

    LaunchedEffect(previewChannel, previewSchedule) {
        val liveIdx = previewSchedule.indexOfFirst { now in it.startMs..it.stopMs }
        if (liveIdx > 0) epgListState.scrollToItem((liveIdx - 1).coerceAtLeast(0))
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xBB090B0E)).padding(14.dp)) {
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(0.85f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Color(0xDD141820)).padding(8.dp)) {
                Text(if (S.isRu) "1. Категории" else "1. Categories", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = theme.primary, modifier = Modifier.padding(6.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(groups) { grp ->
                        val isSel = grp == selGroup
                        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (isSel) theme.primary else Color.Transparent).clickable { selGroup = grp }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(grp, color = if (isSel) theme.onPrimary else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            }

            Column(modifier = Modifier.weight(1.15f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Color(0xDD141820)).padding(8.dp)) {
                Text(if (S.isRu) "2. Каналы (${groupChannels.size})" else "2. Channels", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = theme.primary, modifier = Modifier.padding(6.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(groupChannels, key = { it.id }) { ch ->
                        val isPreview = previewChannel?.id == ch.id
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (isPreview) theme.selectedCard else Color(0x55000000))
                                .border(if (isPreview) 1.5.dp else 0.dp, if (isPreview) theme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { if (isPreview) { onSelectChannel(ch); onClose() } else previewChannel = ch }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${ch.number}", color = theme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(34.dp))
                            Text(ch.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Column(modifier = Modifier.weight(1.3f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Color(0xDD141820)).padding(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("3. ${previewChannel?.name ?: "Гид"}", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = theme.primary, maxLines = 1, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, null, tint = Color.White) }
                }
                if (previewSchedule.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Button(onClick = { previewChannel?.let { onSelectChannel(it); onClose() } }) {
                            Text(if (S.isRu) "▶ Включить" else "▶ Watch")
                        }
                    }
                } else {
                    LazyColumn(state = epgListState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(previewSchedule) { prog ->
                            val isPast = prog.stopMs < now
                            val isNow = now in prog.startMs..prog.stopMs
                            val isFuture = prog.startMs > now
                            val canArchive = isPast && (previewChannel?.catchupDays ?: 0) > 0
                            val hasReminder = isFuture && reminders.any { it.channelId == previewChannel?.id && it.startMs == prog.startMs }
                            Column(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .background(if (isNow) theme.selectedCard else Color(0x66000000))
                                    .clickable(enabled = canArchive || isNow || isFuture) {
                                        val ch = previewChannel ?: return@clickable
                                        if (canArchive) { onPlayArchive(ch, prog); onClose() }
                                        else if (isNow) { onSelectChannel(ch); onClose() }
                                        else if (isFuture) onToggleReminder(ch, prog)
                                    }
                                    .padding(8.dp)
                            ) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(prog.timeRangeText, fontSize = 11.sp, color = Color.LightGray)
                                    when {
                                        isNow -> Text("🟢 ЭФИР", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                                        canArchive -> Text("⏪ АРХИВ", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
                                        hasReminder -> Text("🔔 НАПОМНЮ", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                                        isFuture -> Text("🕒", fontSize = 10.sp, color = Color.Gray)
                                    }
                                }
                                Text(prog.title, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 2)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MultiViewScreenOverlay(
    S: Strings,
    theme: AppThemePalette,
    allChannels: List<Channel>,
    initialChannel: Channel,
    playerBufferMode: Int,
    playerDefaultUa: String,
    onExitMultiView: (Channel) -> Unit
) {
    var slots by remember {
        val c2 = allChannels.getOrNull(1) ?: initialChannel
        val c3 = allChannels.getOrNull(2) ?: initialChannel
        val c4 = allChannels.getOrNull(3) ?: initialChannel
        mutableStateOf(listOf(initialChannel, c2, c3, c4))
    }
    var activeAudioSlot by remember { mutableStateOf(0) }
    var pickingSlotIndex by remember { mutableStateOf<Int?>(null) }

    if (pickingSlotIndex != null) {
        QuickChannelPickerDialog(
            S = S, theme = theme, channels = allChannels, currentChannel = slots[pickingSlotIndex!!],
            onDismiss = { pickingSlotIndex = null },
            onSelect = { chosen ->
                val mutable = slots.toMutableList(); mutable[pickingSlotIndex!!] = chosen; slots = mutable; pickingSlotIndex = null
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (rowIdx in 0..1) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (colIdx in 0..1) {
                    val slotIdx = rowIdx * 2 + colIdx
                    val ch = slots[slotIdx]
                    val isAudioActive = activeAudioSlot == slotIdx

                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .border(if (isAudioActive) 3.dp else 1.dp, if (isAudioActive) theme.primary else Color.DarkGray, RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp)).clickable { activeAudioSlot = slotIdx }
                    ) {
                        ExoVideoPlayer(
                            streamUrl = ch.streamUrl,
                            userAgent = ch.userAgent ?: playerDefaultUa,
                            resizeMode = 0,
                            bufferMode = playerBufferMode,
                            useNativeController = false,
                            reconnectingText = S.reconnecting,
                            isMuted = !isAudioActive,
                            modifier = Modifier.fillMaxSize()
                        )
                        Row(
                            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0xBB000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(if (isAudioActive) "🔊 ${ch.name}" else "🔇 ${ch.name}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.widthIn(max = 110.dp))
                            Text(if (S.isRu) "🔄 Канал" else "🔄 Change", color = theme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { pickingSlotIndex = slotIdx })
                            Text("⛶", color = Color(0xFFF59E0B), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onExitMultiView(ch) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ParentalPinDialog(
    S: Strings,
    theme: AppThemePalette,
    expectedPin: String,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = theme.card), shape = RoundedCornerShape(18.dp)) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Lock, null, tint = theme.primary, modifier = Modifier.size(44.dp))
                Text(if (S.isRu) "🔒 Родительский контроль (18+)" else "🔒 Parental Control (18+)", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = theme.text)
                OutlinedTextField(
                    value = entered,
                    onValueChange = {
                        entered = it.filter { c -> c.isDigit() }.take(4); error = false
                        if (entered == expectedPin) onSuccess()
                    },
                    label = { Text(if (S.isRu) "Введите 4-значный PIN-код" else "Enter 4-digit PIN") },
                    singleLine = true
                )
                if (error) Text(if (S.isRu) "❌ Неверный PIN-код" else "❌ Wrong PIN", color = Color(0xFFEF4444), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onDismiss) { Text(if (S.isRu) "Отмена" else "Cancel") }
                    Button(onClick = { if (entered == expectedPin) onSuccess() else error = true }) { Text("OK") }
                }
            }
        }
    }
}
