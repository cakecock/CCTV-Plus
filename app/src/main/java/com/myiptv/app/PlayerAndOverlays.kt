@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.myiptv.app

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class AudioTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val isSelected: Boolean
)

// ================= ВИДЕОПЛЕЕР С ЖЕСТАМИ ЯРКОСТИ/ГРОМКОСТИ И ВЫБОРОМ ОЗВУЧКИ =================

@Composable
fun SmartVideoPlayer(
    streamUrl: String,
    userAgent: String?,
    resizeMode: Int,
    bufferMode: Int,
    useNativeController: Boolean,
    reconnectingText: String,
    modifier: Modifier = Modifier,
    isMuted: Boolean = false,
    enableSwipeGestures: Boolean = false,
    showAudioSelectorDialog: Boolean = false,
    onDismissAudioSelector: () -> Unit = {}
) {
    val context = LocalContext.current
    var isReconnecting by remember { mutableStateOf(false) }
    var gestureHudText by remember { mutableStateOf("") }
    var audioTracks by remember { mutableStateOf<List<AudioTrackOption>>(emptyList()) }
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

    LaunchedEffect(isMuted, exoPlayer) {
        exoPlayer.volume = if (isMuted) 0f else 1f
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
                    TextButton(onClick = onDismissAudioSelector, modifier = Modifier.align(Alignment.End)) {
                        Text("Закрыть", color = Color.White)
                    }
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
                            val lp = win.attributes
                            lp.screenBrightness = next
                            win.attributes = lp
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
                    player = exoPlayer
                    useController = useNativeController
                    setShowSubtitleButton(true)
                    this.resizeMode = aspectConst
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            update = { view ->
                view.player = exoPlayer
                view.useController = useNativeController
                view.resizeMode = aspectConst
            },
            modifier = Modifier.fillMaxSize()
        )

        if (gestureHudText.isNotEmpty()) {
            Box(
                modifier = Modifier.align(Alignment.Center).background(Color(0xDD000000), RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
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

// ================= ПРОЗРАЧНЫЙ 3-КОЛОНОЧНЫЙ ТВ-ГИД ПОВЕРХ ВИДЕО (TIVIMATE СТИЛЬ) =================

@Composable
fun TiviMateTvOverlay(
    S: Strings,
    theme: AppThemePalette,
    channels: List<Channel>,
    epgScheduleMap: Map<String, List<EpgProgramItem>>,
    favoriteIds: Set<String>,
    currentChannel: Channel?,
    onSelectChannel: (Channel) -> Unit,
    onPlayArchive: (Channel, EpgProgramItem) -> Unit,
    onClose: () -> Unit
) {
    val groups = remember(channels, S) { listOf(S.allGroups) + channels.map { it.group }.distinct() }
    var selGroup by remember { mutableStateOf(currentChannel?.group ?: S.allGroups) }
    val groupChannels = remember(channels, selGroup, S) {
        if (selGroup == S.allGroups) channels else channels.filter { it.group == selGroup }
    }
    var previewChannel by remember(currentChannel) { mutableStateOf(currentChannel ?: channels.firstOrNull()) }
    val previewSchedule = remember(previewChannel, epgScheduleMap) {
        previewChannel?.let { findChannelSchedule(it, epgScheduleMap) } ?: emptyList()
    }
    val now = System.currentTimeMillis()

    Box(modifier = Modifier.fillMaxSize().background(Color(0xBB090B0E)).padding(14.dp)) {
        Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Колонка 1: Категории
            Column(modifier = Modifier.weight(0.85f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Color(0xDD141820)).padding(8.dp)) {
                Text(if (S.isRu) "1. Категории" else "1. Categories", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = theme.primary, modifier = Modifier.padding(6.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(groups) { grp ->
                        val isSel = grp == selGroup
                        Box(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) theme.primary else Color.Transparent)
                                .clickable { selGroup = grp }.padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(grp, color = if (isSel) theme.onPrimary else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            }

            // Колонка 2: Каналы категории
            Column(modifier = Modifier.weight(1.15f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Color(0xDD141820)).padding(8.dp)) {
                Text(if (S.isRu) "2. Каналы (${groupChannels.size})" else "2. Channels", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = theme.primary, modifier = Modifier.padding(6.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(groupChannels, key = { it.id }) { ch ->
                        val isPreview = previewChannel?.id == ch.id
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(if (isPreview) theme.selectedCard else Color(0x55000000))
                                .border(if (isPreview) 1.5.dp else 0.dp, if (isPreview) theme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable {
                                    if (isPreview) { onSelectChannel(ch); onClose() } else previewChannel = ch
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${ch.number}", color = theme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(34.dp))
                            Text(ch.name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            // Колонка 3: Программа и Архив выбранного канала
            Column(modifier = Modifier.weight(1.3f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Color(0xDD141820)).padding(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("3. ${previewChannel?.name ?: "Гид"}", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = theme.primary, maxLines = 1, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, null, tint = Color.White) }
                }
                if (previewSchedule.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Button(onClick = { previewChannel?.let { onSelectChannel(it); onClose() } }) {
                            Text(if (S.isRu) "▶ Включить этот канал" else "▶ Watch Channel")
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(previewSchedule) { prog ->
                            val isPast = prog.stopMs < now
                            val isNow = now in prog.startMs..prog.stopMs
                            val canArchive = isPast && (previewChannel?.catchupDays ?: 0) > 0
                            Column(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .background(if (isNow) theme.selectedCard else Color(0x66000000))
                                    .clickable(enabled = canArchive || isNow) {
                                        val ch = previewChannel ?: return@clickable
                                        if (canArchive) { onPlayArchive(ch, prog); onClose() }
                                        else if (isNow) { onSelectChannel(ch); onClose() }
                                    }
                                    .padding(8.dp)
                            ) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(prog.timeRangeText, fontSize = 11.sp, color = Color.LightGray)
                                    if (isNow) Text("🟢 ЭФИР", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                                    else if (canArchive) Text("⏪ АРХИВ", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
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

// ================= МУЛЬТИЭКРАН (MULTI-VIEW НА 2 ИЛИ 4 КАНАЛА ОДНОВРЕМЕННО) =================

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
            S = S,
            theme = theme,
            channels = allChannels,
            currentChannel = slots[pickingSlotIndex!!],
            onDismiss = { pickingSlotIndex = null },
            onSelect = { chosen ->
                val mutable = slots.toMutableList()
                mutable[pickingSlotIndex!!] = chosen
                slots = mutable
                pickingSlotIndex = null
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
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { activeAudioSlot = slotIdx }
                    ) {
                        SmartVideoPlayer(
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
                            Text(
                                text = if (S.isRu) "🔄 Канал" else "🔄 Change",
                                color = theme.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { pickingSlotIndex = slotIdx }
                            )
                            Text(
                                text = "⛶",
                                color = Color(0xFFF59E0B),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { onExitMultiView(ch) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ================= ОКНО ВВОДА PIN-КОДА РОДИТЕЛЬСКОГО КОНТРОЛЯ (18+) =================

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
                        entered = it.filter { c -> c.isDigit() }.take(4)
                        error = false
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
