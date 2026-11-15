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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URI
import java.util.Locale
import kotlin.math.roundToInt

data class AudioTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val isSelected: Boolean
)

data class VideoQualityOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val height: Int,
    val bitrate: Int,
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
    onPlaybackProgress: (posMs: Long, durMs: Long) -> Unit = { _, _ -> },
    referrer: String? = null,
    cookie: String? = null,
    isRu: Boolean = true,
    isPipMode: Boolean = false,
    onNextChannel: (() -> Unit)? = null,
    showQualitySelectorDialog: Boolean = false,
    onDismissQualitySelector: () -> Unit = {},
    enableDoubleTapSeek: Boolean = false,
    onDoubleTapSeekDelta: ((Long) -> Unit)? = null
) {
    if (playerEngine == 1) {
        NativeAndroidVideoPlayer(
            streamUrl = streamUrl,
            useNativeController = useNativeController && !isPipMode,
            isMuted = isMuted,
            reconnectingText = reconnectingText,
            modifier = modifier,
            isRu = isRu,
            isPipMode = isPipMode,
            onNextChannel = onNextChannel
        )
    } else {
        ExoVideoPlayer(
            streamUrl = streamUrl,
            userAgent = userAgent,
            resizeMode = resizeMode,
            bufferMode = bufferMode,
            useNativeController = useNativeController && !isPipMode,
            reconnectingText = reconnectingText,
            modifier = modifier,
            isMuted = isMuted,
            enableSwipeGestures = enableSwipeGestures && !isPipMode,
            playbackSpeed = playbackSpeed,
            initialSeekMs = initialSeekMs,
            seekCommand = seekCommand,
            showAudioSelectorDialog = showAudioSelectorDialog,
            onDismissAudioSelector = onDismissAudioSelector,
            onVideoQualityDetected = onVideoQualityDetected,
            onPlaybackProgress = onPlaybackProgress,
            referrer = referrer,
            cookie = cookie,
            isRu = isRu,
            isPipMode = isPipMode,
            onNextChannel = onNextChannel,
            showQualitySelectorDialog = showQualitySelectorDialog,
            onDismissQualitySelector = onDismissQualitySelector,
            enableDoubleTapSeek = enableDoubleTapSeek && !isPipMode,
            onDoubleTapSeekDelta = onDoubleTapSeekDelta
        )
    }
}

@Composable
fun NativeAndroidVideoPlayer(
    streamUrl: String,
    useNativeController: Boolean,
    isMuted: Boolean,
    reconnectingText: String,
    modifier: Modifier = Modifier,
    isRu: Boolean = true,
    isPipMode: Boolean = false,
    onNextChannel: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasError by remember(streamUrl) { mutableStateOf(false) }
    var resolvedUrl by remember(streamUrl) { mutableStateOf("") }
    var reloadTrigger by remember { mutableIntStateOf(0) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }

    LaunchedEffect(streamUrl, reloadTrigger) {
        hasError = false
        resolvedUrl = if (streamUrl.isNotBlank()) NetworkLoader.resolveStalkerStreamIfNeeded(streamUrl) else ""
    }

    DisposableEffect(lifecycleOwner, videoViewRef) {
        val observer = LifecycleEventObserver { _, event ->
            val vv = videoViewRef ?: return@LifecycleEventObserver
            val inPip = (context as? Activity)?.isInPictureInPictureMode == true
            when (event) {
                Lifecycle.Event.ON_STOP -> if (!inPip && vv.isPlaying) vv.pause()
                Lifecycle.Event.ON_START -> if (!vv.isPlaying && resolvedUrl.isNotBlank() && !hasError) runCatching { vv.start() }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                VideoView(ctx).apply {
                    keepScreenOn = true
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    videoViewRef = this
                }
            },
            update = { view ->
                videoViewRef = view
                view.keepScreenOn = true
                if (useNativeController && view.tag != "mc_$resolvedUrl") {
                    val mc = MediaController(context)
                    mc.setAnchorView(view)
                    view.setMediaController(mc)
                } else if (!useNativeController) {
                    view.setMediaController(null)
                }
                val expectedKey = "$resolvedUrl#$reloadTrigger"
                if (resolvedUrl.isNotBlank() && view.tag != expectedKey) {
                    view.tag = expectedKey
                    hasError = false
                    view.setOnPreparedListener { mp ->
                        val vol = if (isMuted) 0f else 1f
                        mp.setVolume(vol, vol)
                        mp.start()
                    }
                    view.setOnErrorListener { _, _, _ -> hasError = true; true }
                    view.setVideoURI(Uri.parse(resolvedUrl))
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        if (hasError && !isPipMode) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xDD121417), RoundedCornerShape(14.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(if (isRu) "⚠️ Ошибка воспроизведения потока" else "⚠️ Stream playback error", color = Color(0xFFF59E0B), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { reloadTrigger++ }) {
                        Text(if (isRu) "Повторить" else "Retry")
                    }
                    if (onNextChannel != null) {
                        OutlinedButton(onClick = onNextChannel) {
                            Text(if (isRu) "След. канал" else "Next", color = Color.White)
                        }
                    }
                }
            }
        } else if (hasError) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(reconnectingText, color = Color(0xFFF59E0B), fontSize = 11.sp)
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
    onPlaybackProgress: (posMs: Long, durMs: Long) -> Unit = { _, _ -> },
    referrer: String? = null,
    cookie: String? = null,
    isRu: Boolean = true,
    isPipMode: Boolean = false,
    onNextChannel: (() -> Unit)? = null,
    showQualitySelectorDialog: Boolean = false,
    onDismissQualitySelector: () -> Unit = {},
    enableDoubleTapSeek: Boolean = false,
    onDoubleTapSeekDelta: ((Long) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isReconnecting by remember(streamUrl) { mutableStateOf(false) }
    var fatalErrorMsg by remember(streamUrl) { mutableStateOf<String?>(null) }
    var retryAttempt by remember(streamUrl) { mutableIntStateOf(0) }
    var manualRetryTrigger by remember { mutableIntStateOf(0) }
    var gestureHudText by remember { mutableStateOf("") }
    var doubleTapHudText by remember { mutableStateOf("") }
    var doubleTapHudCounter by remember { mutableIntStateOf(0) }
    var audioTracks by remember { mutableStateOf<List<AudioTrackOption>>(emptyList()) }
    var videoQualities by remember(streamUrl) { mutableStateOf<List<VideoQualityOption>>(emptyList()) }
    var isAutoQuality by remember(streamUrl) { mutableStateOf(true) }
    var didInitialSeek by remember(streamUrl, initialSeekMs) { mutableStateOf(false) }
    var pendingReconnectSeekMs by remember(streamUrl) { mutableLongStateOf(0L) }
    var detectedRes by remember(streamUrl) { mutableStateOf("") }
    var detectedFps by remember(streamUrl) { mutableStateOf("") }
    var wasPlayingBeforeStop by remember { mutableStateOf(false) }
    var retryJob by remember { mutableStateOf<Job?>(null) }

    val curOnQuality by rememberUpdatedState(onVideoQualityDetected)
    val curOnProgress by rememberUpdatedState(onPlaybackProgress)
    val curOnDoubleTapSeek by rememberUpdatedState(onDoubleTapSeekDelta)
    val scope = rememberCoroutineScope()

    val mediaAudioAttributes = remember {
        AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
    }

    val exoPlayer = remember(bufferMode) {
        val (minBuf, maxBuf, playBuf) = when (bufferMode) {
            0 -> Triple(2000, 15000, 1000)
            2 -> Triple(12000, 60000, 4000)
            else -> Triple(5000, 30000, 2000)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuf, maxBuf, playBuf, 2000)
            .build()
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setHandleAudioBecomingNoisy(true)
                playWhenReady = true
            }
    }

    LaunchedEffect(doubleTapHudCounter) {
        if (doubleTapHudText.isNotEmpty()) {
            delay(900L)
            doubleTapHudText = ""
        }
    }

    DisposableEffect(enableSwipeGestures, context) {
        val win = if (enableSwipeGestures) (context as? Activity)?.window else null
        val savedBrightness = win?.attributes?.screenBrightness ?: -1f
        onDispose {
            if (win != null) {
                val lp = win.attributes
                lp.screenBrightness = savedBrightness
                win.attributes = lp
            }
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            val inPip = (context as? Activity)?.isInPictureInPictureMode == true
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (!inPip) {
                        wasPlayingBeforeStop = exoPlayer.playWhenReady
                        exoPlayer.pause()
                    }
                }
                Lifecycle.Event.ON_START -> {
                    if (wasPlayingBeforeStop) {
                        wasPlayingBeforeStop = false
                        exoPlayer.playWhenReady = true
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isMuted, exoPlayer) {
        exoPlayer.volume = if (isMuted) 0f else 1f
        exoPlayer.setAudioAttributes(mediaAudioAttributes, !isMuted)
    }
    LaunchedEffect(playbackSpeed, exoPlayer) { exoPlayer.setPlaybackSpeed(playbackSpeed) }
    LaunchedEffect(seekCommand) {
        if (seekCommand != null) {
            exoPlayer.seekTo((exoPlayer.currentPosition + seekCommand.second).coerceAtLeast(0L))
        }
    }

    LaunchedEffect(streamUrl, userAgent, referrer, cookie, exoPlayer, manualRetryTrigger) {
        retryJob?.cancel()
        retryJob = null
        if (streamUrl.isBlank()) return@LaunchedEffect
        isReconnecting = false
        fatalErrorMsg = null
        retryAttempt = 0
        isAutoQuality = true
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .build()
        curOnQuality("")
        val realUrl = NetworkLoader.resolveStalkerStreamIfNeeded(streamUrl)
        if (realUrl.isBlank()) {
            fatalErrorMsg = if (isRu) "Не удалось получить ссылку на поток" else "Failed to resolve stream URL"
            return@LaunchedEffect
        }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent?.takeIf { it.isNotBlank() } ?: "Lavf/60.3.100")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12000)
            .setReadTimeoutMs(15000)

        val headers = mutableMapOf<String, String>()
        if (!referrer.isNullOrBlank()) {
            headers["Referer"] = referrer
            runCatching {
                val u = URI(referrer)
                if (!u.scheme.isNullOrBlank() && !u.host.isNullOrBlank()) {
                    headers["Origin"] = "${u.scheme}://${u.host}"
                }
            }
        }
        if (!cookie.isNullOrBlank()) {
            headers["Cookie"] = cookie
        }
        if (headers.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(headers)
        }

        val mediaSource = DefaultMediaSourceFactory(httpFactory).createMediaSource(MediaItem.fromUri(realUrl))
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    LaunchedEffect(exoPlayer, streamUrl) {
        while (true) {
            delay(4000L)
            val dur = exoPlayer.duration
            val pos = exoPlayer.currentPosition
            if (dur > 30000L && pos > 5000L) curOnProgress(pos, dur)
        }
    }

    DisposableEffect(exoPlayer) {
        val maxRetries = 3
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val curDur = exoPlayer.duration
                val curPos = exoPlayer.currentPosition
                if (curDur > 30000L && curPos > 5000L) {
                    pendingReconnectSeekMs = curPos
                }
                if (retryAttempt < maxRetries) {
                    retryAttempt += 1
                    isReconnecting = true
                    fatalErrorMsg = null
                    val delayMs = retryAttempt * 2000L
                    retryJob?.cancel()
                    retryJob = scope.launch {
                        delay(delayMs)
                        exoPlayer.prepare()
                        exoPlayer.playWhenReady = true
                    }
                } else {
                    isReconnecting = false
                    fatalErrorMsg = error.localizedMessage ?: (if (isRu) "Поток недоступен" else "Stream unavailable")
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    isReconnecting = false
                    fatalErrorMsg = null
                    retryAttempt = 0
                    if (pendingReconnectSeekMs > 5000L) {
                        val target = pendingReconnectSeekMs
                        pendingReconnectSeekMs = 0L
                        didInitialSeek = true
                        exoPlayer.seekTo(target)
                    } else if (!didInitialSeek && initialSeekMs > 5000L) {
                        didInitialSeek = true
                        exoPlayer.seekTo(initialSeekMs)
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val h = videoSize.height
                if (h > 0) {
                    detectedRes = when { h >= 2100 -> "4K"; h >= 1050 -> "1080p"; h >= 700 -> "720p"; else -> "${h}p" }
                    curOnQuality(listOf(detectedRes, detectedFps).filter { it.isNotBlank() }.joinToString(" • "))
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val aList = mutableListOf<AudioTrackOption>()
                val vList = mutableListOf<VideoQualityOption>()
                val hasVideoOverride = exoPlayer.trackSelectionParameters.overrides.values.any { it.type == C.TRACK_TYPE_VIDEO }
                isAutoQuality = !hasVideoOverride

                tracks.groups.forEachIndexed { gIdx, group ->
                    if (group.type == C.TRACK_TYPE_AUDIO) {
                        for (tIdx in 0 until group.length) {
                            val fmt = group.getTrackFormat(tIdx)
                            val fallbackLabel = if (isRu) "Дорожка #${aList.size + 1}" else "Track #${aList.size + 1}"
                            val title = listOf(fmt.label ?: "", fmt.language?.uppercase() ?: "", if (fmt.channelCount > 0) "${fmt.channelCount}ch" else "")
                                .filter { it.isNotBlank() }.joinToString(" • ").ifEmpty { fallbackLabel }
                            aList.add(AudioTrackOption(gIdx, tIdx, title, group.isTrackSelected(tIdx)))
                        }
                    } else if (group.type == C.TRACK_TYPE_VIDEO) {
                        for (tIdx in 0 until group.length) {
                            if (!group.isTrackSupported(tIdx)) continue
                            val fmt = group.getTrackFormat(tIdx)
                            val h = fmt.height
                            if (h > 0) {
                                val resTag = when {
                                    h >= 2100 -> "4K (${h}p)"
                                    else -> "${h}p"
                                }
                                val fpsTag = if (fmt.frameRate > 1f) "${fmt.frameRate.roundToInt()}fps" else ""
                                val brTag = if (fmt.bitrate > 0) String.format(Locale.US, "%.1f Mbps", fmt.bitrate / 1_000_000f) else ""
                                val lbl = listOf(resTag, fpsTag, brTag).filter { it.isNotBlank() }.joinToString(" • ")
                                vList.add(VideoQualityOption(gIdx, tIdx, lbl, h, fmt.bitrate, hasVideoOverride && group.isTrackSelected(tIdx)))
                            }
                        }
                        if (group.length > 0) {
                            val fps = group.getTrackFormat(0).frameRate
                            if (fps > 1f) {
                                detectedFps = "${fps.roundToInt()}fps"
                                val combined = listOf(detectedRes, detectedFps).filter { it.isNotBlank() }.joinToString(" • ")
                                if (combined.isNotBlank()) curOnQuality(combined)
                            }
                        }
                    }
                }
                audioTracks = aList
                videoQualities = vList
                    .distinctBy { it.label }
                    .sortedWith(compareByDescending<VideoQualityOption> { it.height }.thenByDescending { it.bitrate })
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            retryJob?.cancel()
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    if (showAudioSelectorDialog) {
        Dialog(onDismissRequest = onDismissAudioSelector) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1C2026)), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp).widthIn(min = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (isRu) "🔊 Выбор звуковой дорожки" else "🔊 Audio Track", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color(0xFF10B981))
                    if (audioTracks.isEmpty()) {
                        Text(if (isRu) "Дополнительные звуковые дорожки не найдены" else "No additional audio tracks found", fontSize = 13.sp, color = Color.LightGray)
                    } else {
                        audioTracks.forEach { opt ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .background(if (opt.isSelected) Color(0xFF19322B) else Color(0xFF121417))
                                    .clickable {
                                        exoPlayer.currentTracks.groups.getOrNull(opt.groupIndex)?.let { grp ->
                                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                                .buildUpon().setOverrideForType(TrackSelectionOverride(grp.mediaTrackGroup, opt.trackIndex)).build()
                                        }
                                        onDismissAudioSelector()
                                    }.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(opt.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                if (opt.isSelected) Icon(Icons.Default.Check, null, tint = Color(0xFF10B981))
                            }
                        }
                    }
                    TextButton(onClick = onDismissAudioSelector, modifier = Modifier.align(Alignment.End)) {
                        Text(if (isRu) "Закрыть" else "Close", color = Color.White)
                    }
                }
            }
        }
    }

    if (showQualitySelectorDialog) {
        Dialog(onDismissRequest = onDismissQualitySelector) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1C2026)), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp).widthIn(min = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (isRu) "🎞 Качество видео" else "🎞 Video Quality", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, color = Color(0xFF10B981))
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (isAutoQuality) Color(0xFF19322B) else Color(0xFF121417))
                            .clickable {
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                    .buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                    .build()
                                isAutoQuality = true
                                onDismissQualitySelector()
                            }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(if (isRu) "✨ Авто (Адаптивно)" else "✨ Auto (Adaptive)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        if (isAutoQuality) Icon(Icons.Default.Check, null, tint = Color(0xFF10B981))
                    }
                    if (videoQualities.size <= 1) {
                        val curText = videoQualities.firstOrNull()?.label ?: detectedRes.ifEmpty { if (isRu) "Фиксированный поток" else "Fixed stream" }
                        Text(
                            if (isRu) "Текущий поток: $curText (без адаптивных вариантов)" else "Current stream: $curText (single quality)",
                            fontSize = 12.sp, color = Color.LightGray
                        )
                    } else {
                        videoQualities.forEach { opt ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .background(if (opt.isSelected) Color(0xFF19322B) else Color(0xFF121417))
                                    .clickable {
                                        exoPlayer.currentTracks.groups.getOrNull(opt.groupIndex)?.let { grp ->
                                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                                .buildUpon()
                                                .setOverrideForType(TrackSelectionOverride(grp.mediaTrackGroup, opt.trackIndex))
                                                .build()
                                            isAutoQuality = false
                                        }
                                        onDismissQualitySelector()
                                    }.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(opt.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                if (opt.isSelected) Icon(Icons.Default.Check, null, tint = Color(0xFF10B981))
                            }
                        }
                    }
                    TextButton(onClick = onDismissQualitySelector, modifier = Modifier.align(Alignment.End)) {
                        Text(if (isRu) "Закрыть" else "Close", color = Color.White)
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
        modifier = modifier
            .pointerInput(enableSwipeGestures, isRu) {
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
                                gestureHudText = if (isRu) "☀️ Яркость: ${(next * 100).toInt()}%" else "☀️ Brightness: ${(next * 100).toInt()}%"
                            }
                        } else {
                            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            val maxV = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val curV = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val nextFloat = ((curV.toFloat() / maxV.toFloat()) + delta).coerceIn(0f, 1f)
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, (nextFloat * maxV).toInt().coerceIn(0, maxV), 0)
                            gestureHudText = if (isRu) "🔊 Громкость: ${(nextFloat * 100).toInt()}%" else "🔊 Volume: ${(nextFloat * 100).toInt()}%"
                        }
                    }
                )
            }
            .pointerInput(enableDoubleTapSeek, isRu) {
                if (!enableDoubleTapSeek) return@pointerInput
                detectTapGestures(
                    onDoubleTap = { offset ->
                        val w = size.width.coerceAtLeast(1)
                        val deltaMs = if (offset.x < w / 2f) -10_000L else 10_000L
                        val cb = curOnDoubleTapSeek
                        if (cb != null) {
                            cb(deltaMs)
                        } else {
                            exoPlayer.seekTo((exoPlayer.currentPosition + deltaMs).coerceAtLeast(0L))
                        }
                        doubleTapHudText = if (deltaMs < 0L) {
                            if (isRu) "⏪ -10 сек" else "⏪ -10 sec"
                        } else {
                            if (isRu) "⏩ +10 сек" else "⏩ +10 sec"
                        }
                        doubleTapHudCounter++
                    }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer; useController = useNativeController; setShowSubtitleButton(true)
                    keepScreenOn = true; this.resizeMode = aspectConst
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
            },
            update = { view ->
                view.player = exoPlayer; view.useController = useNativeController
                view.keepScreenOn = true; view.resizeMode = aspectConst
            },
            modifier = Modifier.fillMaxSize()
        )
        val activeHud = gestureHudText.ifEmpty { doubleTapHudText }
        if (activeHud.isNotEmpty() && !isPipMode) {
            Box(modifier = Modifier.align(Alignment.Center).background(Color(0xDD000000), RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(activeHud, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            }
        }
        if (isReconnecting && !isPipMode) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("$reconnectingText ($retryAttempt/3)", color = Color(0xFFF59E0B), fontSize = 13.sp)
            }
        }
        val errMsg = fatalErrorMsg
        if (errMsg != null && !isPipMode) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
                    .background(Color(0xE6121417), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    if (isRu) "⚠️ Не удалось подключиться" else "⚠️ Connection failed",
                    color = Color(0xFFF59E0B), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp
                )
                Text(errMsg, color = Color.LightGray, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { manualRetryTrigger++ }) {
                        Text(if (isRu) "🔄 Повторить" else "🔄 Retry", fontWeight = FontWeight.Bold)
                    }
                    if (onNextChannel != null) {
                        OutlinedButton(onClick = onNextChannel) {
                            Text(if (isRu) "⏭ След. канал" else "⏭ Next Channel", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

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
                        if (!movie.posterUrl.isNullOrBlank()) AsyncImage(model = movie.posterUrl, contentDescription = movie.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Icon(Icons.Default.Movie, null, tint = theme.primary, modifier = Modifier.size(44.dp))
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
                if (movie.description.isNotBlank()) Text(movie.description, fontSize = 13.sp, color = theme.text)
                if (savedProgress != null && savedProgress.positionMs > 10000L) {
                    val posStr = formatTimeDuration(savedProgress.positionMs)
                    Button(onClick = { onResume(savedProgress.positionMs) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp))
                        Text(if (S.isRu) "▶ Продолжить с $posStr" else "▶ Resume from $posStr", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = onPlayFromStart, modifier = Modifier.fillMaxWidth()) { Text(if (S.isRu) "🔄 Смотреть сначала" else "🔄 Watch from start") }
                } else {
                    Button(onClick = onPlayFromStart, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp))
                        Text(if (S.isRu) "▶ Смотреть" else "▶ Play", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun HiddenCategoriesDialog(
    S: Strings, theme: AppThemePalette, allGroups: List<String>, hiddenGroups: Set<String>,
    onToggleGroup: (String) -> Unit, onShowAll: () -> Unit, onDismiss: () -> Unit,
    lockedGroups: Set<String> = emptySet(),
    onToggleLockedGroup: ((String) -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = theme.card), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (S.isRu) "👁 Видимые категории" else "👁 Visible Categories", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = theme.primary)
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null, tint = theme.text) }
                }
                Text(
                    if (onToggleLockedGroup != null) {
                        if (S.isRu) "Галочка — видимость, 🔒 — защита категории PIN-кодом:" else "Checkbox — visibility, 🔒 — lock category with PIN:"
                    } else {
                        if (S.isRu) "Снимите галочки с ненужных категорий:" else "Uncheck groups to hide:"
                    },
                    fontSize = 12.sp, color = theme.subtext, modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(allGroups) { grp ->
                        val isVisible = !hiddenGroups.contains(grp)
                        val isLocked = lockedGroups.contains(grp)
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg)
                                .tvFocusHighlight(theme.primary, 10.dp).clickable { onToggleGroup(grp) }.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(grp, color = if (isVisible) theme.text else theme.subtext, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            if (onToggleLockedGroup != null) {
                                IconButton(onClick = { onToggleLockedGroup(grp) }, modifier = Modifier.size(36.dp)) {
                                    Icon(
                                        if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                        contentDescription = null,
                                        tint = if (isLocked) Color(0xFFF59E0B) else theme.subtext.copy(alpha = 0.6f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
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

@Composable
fun SleepTimerDialog(S: Strings, theme: AppThemePalette, currentMinutesLeft: Int, onSelectMinutes: (Int) -> Unit, onDismiss: () -> Unit) {
    val minLabel = if (S.isRu) "мин" else "min"
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = theme.card), shape = RoundedCornerShape(18.dp)) {
            Column(modifier = Modifier.padding(18.dp).widthIn(min = 250.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (S.isRu) "⏱ Таймер сна" else "⏱ Sleep Timer", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = theme.primary)
                if (currentMinutesLeft > 0) Text(if (S.isRu) "Выключение через: $currentMinutesLeft мин" else "Stopping in: ${currentMinutesLeft}m", fontSize = 13.sp, color = theme.text)
                listOf(0, 15, 30, 60, 90).forEach { mins ->
                    val label = if (mins == 0) (if (S.isRu) "🚫 Отключить таймер" else "🚫 Turn Off") else "⏱ $mins $minLabel"
                    OutlinedButton(onClick = { onSelectMinutes(mins); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text(label, color = theme.text, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
fun TiviMateTvOverlay(
    S: Strings, theme: AppThemePalette, channels: List<Channel>, hiddenGroups: Set<String>,
    epgScheduleMap: Map<String, List<EpgProgramItem>>, reminders: List<ProgramReminder>,
    currentChannel: Channel?, onSelectChannel: (Channel) -> Unit,
    onPlayArchive: (Channel, EpgProgramItem) -> Unit, onToggleReminder: (Channel, EpgProgramItem) -> Unit, onClose: () -> Unit,
    parentalEnabled: Boolean = false, adultUnlockedSession: Boolean = false,
    lockedGroups: Set<String> = emptySet()
) {
    val visibleChannels = remember(channels, hiddenGroups, parentalEnabled, adultUnlockedSession, lockedGroups) {
        channels.filter { !hiddenGroups.contains(it.group) && isChannelAllowed(it, parentalEnabled, adultUnlockedSession, lockedGroups) }
    }
    val groups = remember(visibleChannels, S) { listOf(S.allGroups) + visibleChannels.map { it.group }.distinct() }
    var selGroup by remember { mutableStateOf(currentChannel?.group?.takeIf { !hiddenGroups.contains(it) } ?: S.allGroups) }
    val groupChannels = remember(visibleChannels, selGroup, S) { if (selGroup == S.allGroups) visibleChannels else visibleChannels.filter { it.group == selGroup } }
    var previewChannel by remember(currentChannel, visibleChannels, lockedGroups) {
        mutableStateOf(currentChannel?.takeIf { isChannelAllowed(it, parentalEnabled, adultUnlockedSession, lockedGroups) } ?: visibleChannels.firstOrNull())
    }
    val previewSchedule = remember(previewChannel, epgScheduleMap) { previewChannel?.let { findChannelSchedule(it, epgScheduleMap) } ?: emptyList() }
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
                        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (isSel) theme.primary else Color.Transparent).tvFocusHighlight(Color.White, 8.dp).clickable { selGroup = grp }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                            Text(grp, color = if (isSel) theme.onPrimary else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                }
            }
            Column(modifier = Modifier.weight(1.15f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Color(0xDD141820)).padding(8.dp)) {
                Text(if (S.isRu) "2. Каналы (${groupChannels.size})" else "2. Channels (${groupChannels.size})", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = theme.primary, modifier = Modifier.padding(6.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(groupChannels, key = { it.id }) { ch ->
                        val isPreview = previewChannel?.id == ch.id
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (isPreview) theme.selectedCard else Color(0x55000000))
                                .tvFocusHighlight(Color.White, 8.dp).border(if (isPreview) 1.5.dp else 0.dp, if (isPreview) theme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { if (isPreview) { onSelectChannel(ch); onClose() } else previewChannel = ch }.padding(horizontal = 10.dp, vertical = 8.dp),
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
                    val defaultGuide = if (S.isRu) "Гид" else "Guide"
                    Text("3. ${previewChannel?.name ?: defaultGuide}", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = theme.primary, maxLines = 1, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, null, tint = Color.White) }
                }
                if (previewSchedule.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Button(onClick = { previewChannel?.let { onSelectChannel(it); onClose() } }) { Text(if (S.isRu) "▶ Включить" else "▶ Watch") }
                    }
                } else {
                    LazyColumn(state = epgListState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(previewSchedule) { prog ->
                            val isPast = prog.stopMs < now; val isNow = now in prog.startMs..prog.stopMs; val isFuture = prog.startMs > now
                            val canArchive = isPast && (previewChannel?.catchupDays ?: 0) > 0
                            val hasReminder = isFuture && reminders.any { it.channelId == previewChannel?.id && it.startMs == prog.startMs }
                            Column(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (isNow) theme.selectedCard else Color(0x66000000))
                                    .tvFocusHighlight(Color.White, 8.dp).clickable(enabled = canArchive || isNow || isFuture) {
                                        val ch = previewChannel ?: return@clickable
                                        if (canArchive) { onPlayArchive(ch, prog); onClose() } else if (isNow) { onSelectChannel(ch); onClose() } else if (isFuture) onToggleReminder(ch, prog)
                                    }.padding(8.dp)
                            ) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(prog.timeRangeText, fontSize = 11.sp, color = Color.LightGray)
                                    when {
                                        isNow -> Text(if (S.isRu) "🟢 ЭФИР" else "🟢 LIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                                        canArchive -> Text(if (S.isRu) "⏪ АРХИВ" else "⏪ CATCHUP", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
                                        hasReminder -> Text(if (S.isRu) "🔔 НАПОМНЮ" else "🔔 REMIND", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
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
    onExitMultiView: (Channel) -> Unit,
    parentalEnabled: Boolean = false,
    adultUnlockedSession: Boolean = false,
    lockedGroups: Set<String> = emptySet()
) {
    val allowedChannels = remember(allChannels, parentalEnabled, adultUnlockedSession, lockedGroups) {
        allChannels.filter { isChannelAllowed(it, parentalEnabled, adultUnlockedSession, lockedGroups) }
    }
    val safeInitial = remember(initialChannel, allowedChannels, parentalEnabled, adultUnlockedSession, lockedGroups) {
        if (isChannelAllowed(initialChannel, parentalEnabled, adultUnlockedSession, lockedGroups)) initialChannel
        else allowedChannels.firstOrNull() ?: initialChannel
    }
    var slots by remember(safeInitial, allowedChannels) {
        mutableStateOf(
            listOf(
                safeInitial,
                allowedChannels.getOrNull(1) ?: safeInitial,
                allowedChannels.getOrNull(2) ?: safeInitial,
                allowedChannels.getOrNull(3) ?: safeInitial
            )
        )
    }
    var activeAudioSlot by remember { mutableStateOf(0) }
    var pickingSlotIndex by remember { mutableStateOf<Int?>(null) }

    if (pickingSlotIndex != null) {
        QuickChannelPickerDialog(S, theme, allowedChannels, slots[pickingSlotIndex!!], onDismiss = { pickingSlotIndex = null }) { chosen ->
            val mutable = slots.toMutableList(); mutable[pickingSlotIndex!!] = chosen; slots = mutable; pickingSlotIndex = null
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (rowIdx in 0..1) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (colIdx in 0..1) {
                    val slotIdx = rowIdx * 2 + colIdx; val ch = slots[slotIdx]; val isAudioActive = activeAudioSlot == slotIdx
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().border(if (isAudioActive) 3.dp else 1.dp, if (isAudioActive) theme.primary else Color.DarkGray, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)).clickable { activeAudioSlot = slotIdx }) {
                        ExoVideoPlayer(
                            streamUrl = ch.streamUrl,
                            userAgent = ch.userAgent ?: playerDefaultUa,
                            resizeMode = 0,
                            bufferMode = playerBufferMode,
                            useNativeController = false,
                            reconnectingText = S.reconnecting,
                            modifier = Modifier.fillMaxSize(),
                            isMuted = !isAudioActive,
                            referrer = ch.referrer,
                            cookie = ch.cookie,
                            isRu = S.isRu
                        )
                        Row(modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0xBB000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
fun ParentalPinDialog(S: Strings, theme: AppThemePalette, expectedPin: String, onSuccess: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cctv_plus_prefs", Context.MODE_PRIVATE) }
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var failedAttempts by remember { mutableIntStateOf(0) }
    var lockedUntilMs by remember { mutableLongStateOf(0L) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(lockedUntilMs) {
        while (lockedUntilMs > System.currentTimeMillis()) {
            nowMs = System.currentTimeMillis()
            delay(500L)
        }
        nowMs = System.currentTimeMillis()
    }

    val secondsLeft = ((lockedUntilMs - nowMs) / 1000L).coerceAtLeast(0L)

    fun submit() {
        if (secondsLeft > 0L) return
        val ok = verifyParentalPin(prefs, entered) || (expectedPin.isNotBlank() && !hasCustomParentalPin(prefs) && entered == expectedPin)
        if (ok) {
            onSuccess()
        } else {
            error = true
            failedAttempts += 1
            entered = ""
            if (failedAttempts >= 5) {
                lockedUntilMs = System.currentTimeMillis() + 15_000L
                failedAttempts = 0
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = theme.card), shape = RoundedCornerShape(18.dp)) {
            Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Default.Lock, null, tint = theme.primary, modifier = Modifier.size(44.dp))
                Text(if (S.isRu) "🔒 Родительский контроль (18+)" else "🔒 Parental Control (18+)", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = theme.text)
                OutlinedTextField(
                    value = entered,
                    onValueChange = { entered = it.filter { c -> c.isDigit() }.take(8); error = false },
                    label = { Text(if (S.isRu) "Введите PIN" else "Enter PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = secondsLeft == 0L,
                    modifier = Modifier.fillMaxWidth()
                )
                if (secondsLeft > 0L) {
                    Text(
                        if (S.isRu) "⏳ Слишком много попыток. Ждите ${secondsLeft}с" else "⏳ Too many attempts. Wait ${secondsLeft}s",
                        color = Color(0xFFF59E0B), fontSize = 13.sp
                    )
                } else if (error) {
                    Text(if (S.isRu) "❌ Неверный PIN-код" else "❌ Wrong PIN", color = Color(0xFFEF4444), fontSize = 13.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onDismiss) { Text(if (S.isRu) "Отмена" else "Cancel") }
                    Button(onClick = { submit() }, enabled = secondsLeft == 0L && entered.isNotEmpty()) { Text("OK") }
                }
            }
        }
    }
}
