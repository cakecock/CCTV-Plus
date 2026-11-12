@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.myiptv.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

val BuiltInEpgPresets = listOf(
    "AUTO_PLAYLIST" to "🔄 Авто (Плейлист + TVLider + IPTVx)",
    "BUILTIN_TVLIDER" to "📡 TVLider / Edem (epg.it999.ru)",
    "BUILTIN_IPTVX_LITE" to "⚡ IPTVx Lite (СНГ / РФ)",
    "BUILTIN_IPTVX_FULL" to "🌍 IPTVx Full (Полная база)"
)

fun getAppVersionInfo(context: Context): Pair<String, Long> = runCatching {
    val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    (pInfo.versionName ?: "1.0") to pInfo.longVersionCode
}.getOrDefault("1.0" to 1L)

@Composable
fun SourceDropdownSelector(
    label: String,
    selectedTitle: String,
    options: List<Pair<String, String>>,
    theme: AppThemePalette,
    onSelect: (String) -> Unit
) = SourceDropdownSelector(label, selectedTitle, options, theme, onSelect, Modifier)

@Composable
fun SourceDropdownSelector(
    label: String,
    selectedTitle: String,
    options: List<Pair<String, String>>,
    theme: AppThemePalette,
    onSelect: (String) -> Unit,
    modifier: Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(theme.bg)
                .border(1.dp, theme.primary.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                .clickable { expanded = true }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 11.sp, color = theme.subtext)
                Text(selectedTitle, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = theme.text, maxLines = 1)
            }
            Icon(Icons.Default.ArrowDropDown, null, tint = theme.primary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(theme.card)) {
            options.forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title, color = theme.text, fontSize = 14.sp) }, onClick = { expanded = false; onSelect(id) })
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
                modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (isExpanded) theme.primary else theme.text)
                Icon(if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = theme.primary)
            }
            if (isExpanded) {
                HorizontalDivider(color = theme.subtext.copy(alpha = 0.2f))
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
            }
        }
    }
}

@Composable
fun FirstLaunchSetupScreen(
    theme: AppThemePalette,
    initialRu: Boolean,
    initialDevice: String,
    initialControl: String,
    onFinishSetup: (Boolean, String, String) -> Unit
) {
    var selRu by remember { mutableStateOf(initialRu) }
    var selDevice by remember { mutableStateOf(initialDevice) }
    var selControl by remember { mutableStateOf(initialControl) }

    Box(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Card(colors = CardDefaults.cardColors(containerColor = theme.card), shape = RoundedCornerShape(24.dp), modifier = Modifier.padding(20.dp).widthIn(max = 480.dp)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("📺 CCTV Plus", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = theme.primary)
                Text(if (selRu) "Быстрая настройка" else "Quick Setup", fontSize = 15.sp, color = theme.subtext)

                Text(if (selRu) "Язык / Language" else "Language", fontWeight = FontWeight.Bold, color = theme.text)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(selected = selRu, onClick = { selRu = true }, label = { Text("🇷🇺 Русский") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = !selRu, onClick = { selRu = false }, label = { Text("🇬🇧 English") }, modifier = Modifier.weight(1f))
                }

                Text(if (selRu) "Устройство" else "Device", fontWeight = FontWeight.Bold, color = theme.text)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(selected = selDevice == "mobile", onClick = { selDevice = "mobile"; selControl = "touch" }, label = { Text(if (selRu) "📱 Смартфон" else "📱 Phone") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = selDevice == "tv", onClick = { selDevice = "tv"; selControl = "remote" }, label = { Text(if (selRu) "📺 Телевизор" else "📺 Android TV") }, modifier = Modifier.weight(1f))
                }

                Text(if (selRu) "Управление" else "Controls", fontWeight = FontWeight.Bold, color = theme.text)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(selected = selControl == "touch", onClick = { selControl = "touch" }, label = { Text(if (selRu) "👆 Сенсор" else "👆 Touch") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = selControl == "remote", onClick = { selControl = "remote" }, label = { Text(if (selRu) "🎮 Пульт" else "🎮 Remote") }, modifier = Modifier.weight(1f))
                }

                Spacer(Modifier.height(6.dp))
                Button(onClick = { onFinishSetup(selRu, selDevice, selControl) }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                    Text(if (selRu) "🚀 Готово" else "🚀 Start", fontSize = 17.sp, fontWeight = FontWeight.Bold)
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
    deviceType: String,
    controlMode: String,
    clockPosition: Int,
    playerEngine: Int,
    archiveDays: Int,
    sleepTimerMinutes: Int,
    hiddenGroupsCount: Int,
    parentalEnabled: Boolean,
    parentalPin: String,
    onDeviceTypeChange: (String) -> Unit,
    onControlModeChange: (String) -> Unit,
    onClockPositionChange: (Int) -> Unit,
    onPlayerEngineChange: (Int) -> Unit,
    onArchiveDaysChange: (Int) -> Unit,
    onSleepTimerChange: (Int) -> Unit,
    onOpenHiddenGroups: () -> Unit,
    onParentalEnabledChange: (Boolean) -> Unit,
    onParentalPinChange: (String) -> Unit,
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

    var expandedRow by remember { mutableStateOf(-1) }
    fun toggleRow(idx: Int) { expandedRow = if (expandedRow == idx) -1 else idx }
    var selectedSourceSubMenu by remember { mutableStateOf("playlists") }

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
                context.contentResolver.openOutputStream(uri)?.use { it.write(exportAllSettingsToJson(prefs).toByteArray(Charsets.UTF_8)) }
                backupStatus = if (S.isRu) "✅ Сохранено" else "✅ Exported"
            }.onFailure { backupStatus = "❌ ${it.localizedMessage}" }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val jsonText = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
                if (importAllSettingsFromJson(prefs, jsonText)) {
                    backupStatus = if (S.isRu) "✅ Загружено" else "✅ Imported"
                    onSettingsImported()
                } else backupStatus = "❌ Ошибка формата"
            }.onFailure { backupStatus = "❌ ${it.localizedMessage}" }
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

        // 1. ИСТОЧНИКИ
        ExpandableSectionRow(S.catSourcesGroup, expandedRow == 0, theme, { toggleRow(0) }) {
            val subOpts = listOf(
                "playlists" to "${S.catPlaylists} (${playlists.size})",
                "epg" to S.catEpg,
                "portals" to S.catPortals,
                "qr" to S.catQr
            )
            val curSubTitle = subOpts.find { it.first == selectedSourceSubMenu }?.second ?: subOpts.first().second
            SourceDropdownSelector(if (S.isRu) "Раздел:" else "Section:", curSubTitle, subOpts, theme) { selectedSourceSubMenu = it }
            HorizontalDivider(color = theme.subtext.copy(alpha = 0.2f))

            when (selectedSourceSubMenu) {
                "playlists" -> {
                    if (playlists.isNotEmpty()) {
                        val curName = playlists.find { it.id == selectedPlaylistId }?.name ?: playlists.first().name
                        SourceDropdownSelector(S.activePlaylistLabel, "📺 $curName", playlists.map { it.id to it.name }, theme, onSelectPlaylist)
                    }
                    OutlinedButton(onClick = onOpenHiddenGroups, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.VisibilityOff, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (S.isRu) "Скрыть категории (скрыто: $hiddenGroupsCount)" else "Hide Categories ($hiddenGroupsCount)")
                    }
                    OutlinedTextField(value = newPlName, onValueChange = { newPlName = it }, label = { Text(S.addSourceName) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newPlUrl, onValueChange = { newPlUrl = it }, label = { Text(S.addSourceUrl) }, trailingIcon = { if (newPlUrl.isNotEmpty()) IconButton(onClick = { newPlUrl = "" }) { Icon(Icons.Default.Clear, null) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { if (newPlUrl.isNotBlank()) { onAddPlaylist(newPlName.trim().ifEmpty { "Плейлист ${playlists.size + 1}" }, newPlUrl.trim()); newPlName = ""; newPlUrl = "" } }, modifier = Modifier.fillMaxWidth()) { Text(S.addBtn, fontWeight = FontWeight.Bold) }
                    playlists.forEach { pl ->
                        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(pl.name, fontWeight = FontWeight.Bold, color = theme.text, fontSize = 14.sp)
                                Text(pl.url, fontSize = 11.sp, color = theme.subtext, maxLines = 1)
                            }
                            IconButton(onClick = { onDeletePlaylist(pl.id) }) { Icon(Icons.Default.Delete, null, tint = Color(0xFFEF4444)) }
                        }
                    }
                }
                "epg" -> {
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (isEpgLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp), color = theme.primary)
                        Text(epgStatusText, fontSize = 12.sp, color = theme.text)
                        Button(onClick = onRefreshEpgNow, enabled = !isEpgLoading, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp))
                            Text(if (S.isRu) "Обновить EPG" else "Refresh EPG", fontWeight = FontWeight.Bold)
                        }
                    }
                    val epgOpts = BuiltInEpgPresets + epgSources.map { it.id to "📌 ${it.name}" }
                    val curEpg = epgOpts.find { it.first == selectedEpgId }?.second ?: BuiltInEpgPresets.first().second
                    SourceDropdownSelector(S.activeEpgLabel, curEpg, epgOpts, theme, onSelectEpg)

                    val archiveOpts = listOf("1" to "1 день", "3" to "3 дня", "5" to "5 дней")
                    SourceDropdownSelector(if (S.isRu) "Глубина архива:" else "Archive:", archiveOpts.find { it.first == archiveDays.toString() }?.second ?: "3 дня", archiveOpts, theme) { onArchiveDaysChange(it.toIntOrNull() ?: 3) }

                    OutlinedTextField(value = newEpgName, onValueChange = { newEpgName = it }, label = { Text(S.addSourceName) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newEpgUrl, onValueChange = { newEpgUrl = it }, label = { Text(S.addSourceUrl) }, trailingIcon = { if (newEpgUrl.isNotEmpty()) IconButton(onClick = { newEpgUrl = "" }) { Icon(Icons.Default.Clear, null) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { if (newEpgUrl.isNotBlank()) { onAddEpg(newEpgName.trim().ifEmpty { "EPG ${epgSources.size + 1}" }, newEpgUrl.trim()); newEpgName = ""; newEpgUrl = "" } }, modifier = Modifier.fillMaxWidth()) { Text(S.addBtn, fontWeight = FontWeight.Bold) }
                    epgSources.forEach { epg ->
                        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(epg.name, fontWeight = FontWeight.Bold, color = theme.text, fontSize = 14.sp)
                                Text(epg.url, fontSize = 11.sp, color = theme.subtext, maxLines = 1)
                            }
                            IconButton(onClick = { onDeleteEpg(epg.id) }) { Icon(Icons.Default.Delete, null, tint = Color(0xFFEF4444)) }
                        }
                    }
                }
                "portals" -> {
                    OutlinedTextField(value = vportalUrl, onValueChange = onVportalChange, label = { Text("VPortal (portal::[key:...]http://...)") }, trailingIcon = { if (vportalUrl.isNotEmpty()) IconButton(onClick = { onVportalChange("") }) { Icon(Icons.Default.Clear, null) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = stalkerUrl, onValueChange = onStalkerUrlChange, label = { Text("Stalker URL") }, trailingIcon = { if (stalkerUrl.isNotEmpty()) IconButton(onClick = { onStalkerUrlChange("") }) { Icon(Icons.Default.Clear, null) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = stalkerMac, onValueChange = onStalkerMacChange, label = { Text("Stalker MAC") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = onSaveAndReload, modifier = Modifier.fillMaxWidth()) { Text(S.saveAndReloadBtn, fontWeight = FontWeight.Bold) }
                    if (vportalDiagnostic.isNotBlank()) {
                        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(vportalDiagnostic, fontSize = 11.sp, color = theme.subtext)
                            OutlinedButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("VPortalLog", vportalDiagnostic))
                            }) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text(S.copyLogBtn) }
                        }
                    }
                }
                "qr" -> {
                    val localIp = remember { getDeviceLocalIp() }
                    val remoteUrl = "http://$localIp:8989"
                    val qrBitmap = remember(remoteUrl) { generateQrBitmap(remoteUrl, 360) }
                    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (qrBitmap != null) Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "QR", modifier = Modifier.size(170.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("🌐 $remoteUrl", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = theme.primary)
                    }
                }
            }
        }

        // 2. ВИДЕОПЛЕЕР
        ExpandableSectionRow(S.catPlayer, expandedRow == 1, theme, { toggleRow(1) }) {
            val engineOpts = listOf(
                "0" to (if (S.isRu) "Встроенный (ExoPlayer)" else "Built-in (ExoPlayer)"),
                "1" to (if (S.isRu) "Системный (Android)" else "System (Android)"),
                "2" to (if (S.isRu) "Внешний (VLC / MX)" else "External (VLC / MX)")
            )
            SourceDropdownSelector(if (S.isRu) "Плеер:" else "Player:", engineOpts.find { it.first == playerEngine.toString() }?.second ?: engineOpts.first().second, engineOpts, theme) { onPlayerEngineChange(it.toIntOrNull() ?: 0) }

            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(theme.bg).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (S.isRu) "Сразу на весь экран" else "Auto Fullscreen", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = theme.text)
                Switch(checked = openFullscreenOnTap, onCheckedChange = onAutoFsChange)
            }

            val sleepOpts = listOf("0" to (if (S.isRu) "Выкл" else "Off"), "15" to "15 мин", "30" to "30 мин", "60" to "60 мин", "90" to "90 мин")
            val curSleepTitle = if (sleepTimerMinutes > 0) "⏱ $sleepTimerMinutes мин" else (if (S.isRu) "Выкл" else "Off")
            SourceDropdownSelector(if (S.isRu) "Таймер сна:" else "Sleep Timer:", curSleepTitle, sleepOpts, theme) { onSleepTimerChange(it.toIntOrNull() ?: 0) }

            val resizeOpts = listOf("0" to "По размеру", "1" to "На весь экран (16:9)", "2" to "Обрезка (Zoom)")
            SourceDropdownSelector(if (S.isRu) "Формат:" else "Aspect:", resizeOpts.find { it.first == playerResizeMode.toString() }?.second ?: resizeOpts.first().second, resizeOpts, theme) { onPlayerResizeChange(it.toIntOrNull() ?: 0) }

            val clockOpts = listOf("0" to "Выкл", "1" to "Слева вверху", "2" to "Справа вверху")
            SourceDropdownSelector(if (S.isRu) "Часы в плеере:" else "Clock:", clockOpts.find { it.first == clockPosition.toString() }?.second ?: "Выкл", clockOpts, theme) { onClockPositionChange(it.toIntOrNull() ?: 0) }

            val archiveOpts = listOf("1" to "1 день", "3" to "3 дня", "5" to "5 дней")
            SourceDropdownSelector(if (S.isRu) "Архив:" else "Archive:", archiveOpts.find { it.first == archiveDays.toString() }?.second ?: "3 дня", archiveOpts, theme) { onArchiveDaysChange(it.toIntOrNull() ?: 3) }

            val bufOpts = listOf("0" to "Быстрый (2 сек)", "1" to "Обычный (5 сек)", "2" to "Большой (12 сек)")
            SourceDropdownSelector(if (S.isRu) "Буфер:" else "Buffer:", bufOpts.find { it.first == playerBufferMode.toString() }?.second ?: bufOpts[1].second, bufOpts, theme) { onPlayerBufferChange(it.toIntOrNull() ?: 1) }

            val uaOpts = listOf(
                "Lavf/60.3.100" to "Lavf / FFmpeg",
                "Mozilla/5.0 (SmartHub; SMART-TV) OttPlayer/2.0" to "OttPlayer",
                "VLC/3.0.20 LibVLC/3.0.20" to "VLC",
                "TiviMate/4.7.0 (Android TV)" to "TiviMate",
                "Mozilla/5.0 (QtEmbedded; U; Linux; C) MAG200" to "MAG250"
            )
            SourceDropdownSelector("User-Agent:", uaOpts.find { it.first == playerDefaultUa }?.second ?: playerDefaultUa, uaOpts, theme, onPlayerUaChange)
        }

        // 3. УСТРОЙСТВО 
        ExpandableSectionRow(if (S.isRu) "🎮 Устройство" else "🎮 Device", expandedRow == 2, theme, { toggleRow(2) }) {
            val devOpts = listOf("mobile" to (if (S.isRu) "📱 Смартфон" else "📱 Phone"), "tv" to (if (S.isRu) "📺 Телевизор (ТВ)" else "📺 Android TV"))
            SourceDropdownSelector(if (S.isRu) "Устройство:" else "Device:", devOpts.find { it.first == deviceType }?.second ?: devOpts.first().second, devOpts, theme, onDeviceTypeChange)

            val ctrlOpts = listOf("touch" to (if (S.isRu) "👆 Сенсор" else "👆 Touch"), "remote" to (if (S.isRu) "🎮 Пульт ДУ" else "🎮 Remote"))
            SourceDropdownSelector(if (S.isRu) "Управление:" else "Controls:", ctrlOpts.find { it.first == controlMode }?.second ?: ctrlOpts.first().second, ctrlOpts, theme, onControlModeChange)

            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(theme.bg).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (S.isRu) "🔒 Защита 18+ (PIN-код)" else "🔒 18+ PIN Lock", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = theme.text)
                Switch(checked = parentalEnabled, onCheckedChange = onParentalEnabledChange)
            }
            if (parentalEnabled) {
                OutlinedTextField(
                    value = parentalPin,
                    onValueChange = { if (it.length <= 4) onParentalPinChange(it.filter { c -> c.isDigit() }) },
                    label = { Text(if (S.isRu) "PIN-код (4 цифры)" else "4-digit PIN") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 4. ТЕМА И ЯЗЫК
        ExpandableSectionRow(S.catAppearance, expandedRow == 3, theme, { toggleRow(3) }) {
            Text(if (S.isRu) "Тёмные" else "Dark", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = theme.subtext)
            AppThemePalette.entries.filter { it.isDark }.forEach { item ->
                val isChosen = item == theme
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(item.bg)
                        .border(if (isChosen) 2.dp else 1.dp, if (isChosen) item.primary else item.subtext.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .clickable { onSelectTheme(item) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(20.dp).clip(CircleShape).background(item.primary))
                        Spacer(Modifier.width(10.dp))
                        Text(if (S.isRu) item.titleRu else item.titleEn, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = item.text)
                    }
                    if (isChosen) Icon(Icons.Default.Check, null, tint = item.primary)
                }
            }

            Text(if (S.isRu) "Светлые" else "Light", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = theme.subtext, modifier = Modifier.padding(top = 4.dp))
            AppThemePalette.entries.filter { !it.isDark }.forEach { item ->
                val isChosen = item == theme
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(item.bg)
                        .border(if (isChosen) 2.dp else 1.dp, if (isChosen) item.primary else item.subtext.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .clickable { onSelectTheme(item) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(20.dp).clip(CircleShape).background(item.primary))
                        Spacer(Modifier.width(10.dp))
                        Text(if (S.isRu) item.titleRu else item.titleEn, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = item.text)
                    }
                    if (isChosen) Icon(Icons.Default.Check, null, tint = item.primary)
                }
            }

            Button(onClick = onToggleLanguage, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Language, null); Spacer(Modifier.width(8.dp))
                Text(if (S.isRu) "Язык: Русский 🇷🇺" else "Language: English 🇬🇧", fontWeight = FontWeight.Bold)
            }
        }

        // 5. РЕЗЕРВНАЯ КОПИЯ
        ExpandableSectionRow(S.catBackup, expandedRow == 4, theme, { toggleRow(4) }) {
            Button(onClick = { exportLauncher.launch("CCTV-Plus-Backup.json") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp))
                Text(if (S.isRu) "Сохранить в файл" else "Export to File")
            }
            Button(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp))
                Text(if (S.isRu) "Загрузить из файла" else "Import from File")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("CCTVBackup", exportAllSettingsToJson(prefs)))
                    backupStatus = if (S.isRu) "✅ Скопировано" else "✅ Copied"
                }, modifier = Modifier.weight(1f)) { Text(if (S.isRu) "Копировать" else "Copy") }

                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    if (importAllSettingsFromJson(prefs, clip)) {
                        backupStatus = if (S.isRu) "✅ Загружено" else "✅ Restored"
                        onSettingsImported()
                    } else backupStatus = "❌ Ошибка"
                }, modifier = Modifier.weight(1f)) { Text(if (S.isRu) "Вставить" else "Paste") }
            }
            if (backupStatus.isNotBlank()) Text(backupStatus, fontSize = 13.sp, color = theme.primary, fontWeight = FontWeight.Bold)
        }

        // 6. ЧТО НОВОГО
        ExpandableSectionRow(S.catChangelog, expandedRow == 5, theme, { toggleRow(5) }) {
            AppChangelogHistory.forEachIndexed { idx, rel ->
                val verTitle = if (idx == 0 || rel.version == "CURRENT") "v$installedVerName (#$installedVerCode)" else rel.version
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(verTitle, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = theme.primary)
                        Text(rel.date, fontSize = 11.sp, color = theme.subtext)
                    }
                    (if (S.isRu) rel.changesRu else rel.changesEn).forEach { Text(it, fontSize = 12.sp, color = theme.text) }
                }
            }
        }

        // 7. ПОДДЕРЖКА
        ExpandableSectionRow(S.catReportIssue, expandedRow == 6, theme, { toggleRow(6) }) {
            OutlinedTextField(value = issueDescription, onValueChange = { issueDescription = it }, label = { Text(if (S.isRu) "Опишите проблему" else "Describe issue") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    val reportText = "=== CCTV Plus Report ===\nVer: v$installedVerName (#$installedVerCode)\nDevice: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})\nMode: $deviceType/$controlMode | Engine: $playerEngine | Archive: ${archiveDays}d\nEPG: $epgStatusText\nVPortal: ${maskSensitiveKeys(vportalDiagnostic)}\nComment: $issueDescription"
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("CCTVBugReport", reportText))
                    reportStatus = if (S.isRu) "✅ Отчет скопирован" else "✅ Report copied"
                },
                modifier = Modifier.fillMaxWidth()
            ) { Icon(Icons.Default.BugReport, null); Spacer(Modifier.width(8.dp)); Text(if (S.isRu) "Скопировать отчет" else "Copy Report", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/cakecock")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("GitHub (cakecock)") }
            if (reportStatus.isNotBlank()) Text(reportStatus, fontSize = 13.sp, color = theme.primary, fontWeight = FontWeight.Bold)
        }

        // 8. КОНФИДЕНЦИАЛЬНОСТЬ
        ExpandableSectionRow(S.catPrivacy, expandedRow == 7, theme, { toggleRow(7) }) {
            (if (S.isRu) PrivacyItemsRu else PrivacyItemsEn).forEach { p ->
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(10.dp)) { Text(p, fontSize = 12.sp, color = theme.text) }
            }
        }

        // 9. ЛИЦЕНЗИЯ
        ExpandableSectionRow(S.catLicense, expandedRow == 8, theme, { toggleRow(8) }) {
            (if (S.isRu) LicenseItemsRu else LicenseItemsEn).forEach { p ->
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(10.dp)) { Text(p, fontSize = 12.sp, color = theme.text) }
            }
        }

        // ОБНОВЛЕНИЕ + РАЗРАБОТЧИК И ВЕРСИЯ
        Spacer(Modifier.height(2.dp))
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(theme.card).border(1.dp, theme.primary.copy(alpha = 0.3f), RoundedCornerShape(14.dp)).padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    scope.launch {
                        isUpdating = true; downloadPercent = -1
                        updateStatus = if (S.isRu) "Проверка..." else "Checking..."
                        val res = GitHubUpdater.checkLatestRelease()
                        isUpdating = false
                        res.onSuccess { info ->
                            val remoteBuild = info.versionTag.substringAfterLast(".").filter { it.isDigit() }.toLongOrNull() ?: 0L
                            if ((remoteBuild in 1..installedVerCode) || info.versionTag == "v$installedVerName") {
                                latestRelease = null
                                updateStatus = if (S.isRu) "✅ Обновлений нет" else "✅ Up to date"
                            } else {
                                latestRelease = info
                                updateStatus = if (S.isRu) "🔥 Доступна ${info.versionTag}" else "🔥 Available ${info.versionTag}"
                            }
                        }.onFailure { updateStatus = "❌ ${it.localizedMessage}" }
                    }
                },
                enabled = !isUpdating,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.SystemUpdate, null)
                Spacer(Modifier.width(8.dp))
                Text(if (S.isRu) "Проверить обновления" else "Check for Updates", fontWeight = FontWeight.Bold)
            }

            if (isUpdating) {
                if (downloadPercent in 0..100) LinearProgressIndicator(progress = { downloadPercent / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp), color = theme.primary)
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp), color = theme.primary)
            }
            if (updateStatus.isNotBlank()) Text(updateStatus, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = theme.text)
            if (latestRelease != null) {
                Button(
                    onClick = {
                        val info = latestRelease ?: return@Button
                        scope.launch {
                            isUpdating = true; downloadPercent = 0
                            val dl = GitHubUpdater.downloadAndInstall(context, info.downloadUrl) { pct, dlMb, totMb ->
                                downloadPercent = pct
                                updateStatus = "⏳ $pct% ($dlMb / $totMb МБ)"
                            }
                            isUpdating = false
                            dl.onSuccess { updateStatus = "✅ Скачано (100%)" }
                                .onFailure { updateStatus = "❌ ${it.localizedMessage}" }
                        }
                    },
                    enabled = !isUpdating,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudDownload, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Установить ${latestRelease!!.versionTag}", fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = theme.subtext.copy(alpha = 0.2f))
            Text("CCTV Plus • v$installedVerName (Build #$installedVerCode)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = theme.primary)
            Text(if (S.isRu) "Разработчик: cakecock © 2026" else "Developer: cakecock © 2026", fontSize = 12.sp, color = theme.subtext)
        }
    }
}
