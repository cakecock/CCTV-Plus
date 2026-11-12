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
                Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (isExpanded) theme.primary else theme.text)
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
                Text(if (selRu) "Настройка перед первым запуском" else "First Launch Setup", fontSize = 15.sp, color = theme.subtext)

                Text(if (selRu) "1. Выберите язык / Language:" else "1. Choose Language:", fontWeight = FontWeight.Bold, color = theme.text)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(selected = selRu, onClick = { selRu = true }, label = { Text("🇷🇺 Русский") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = !selRu, onClick = { selRu = false }, label = { Text("🇬🇧 English") }, modifier = Modifier.weight(1f))
                }

                Text(if (selRu) "2. Ваше устройство:" else "2. Your Device:", fontWeight = FontWeight.Bold, color = theme.text)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(selected = selDevice == "mobile", onClick = { selDevice = "mobile"; selControl = "touch" }, label = { Text(if (selRu) "📱 Смартфон" else "📱 Smartphone") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = selDevice == "tv", onClick = { selDevice = "tv"; selControl = "remote" }, label = { Text(if (selRu) "📺 Телевизор (ТВ)" else "📺 Android TV") }, modifier = Modifier.weight(1f))
                }

                Text(if (selRu) "3. Способ управления:" else "3. Control Mode:", fontWeight = FontWeight.Bold, color = theme.text)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(selected = selControl == "touch", onClick = { selControl = "touch" }, label = { Text(if (selRu) "👆 Сенсор" else "👆 Touch") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = selControl == "remote", onClick = { selControl = "remote" }, label = { Text(if (selRu) "🎮 Пульт ДУ" else "🎮 TV Remote") }, modifier = Modifier.weight(1f))
                }

                Spacer(Modifier.height(6.dp))
                Button(onClick = { onFinishSetup(selRu, selDevice, selControl) }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp)) {
                    Text(if (selRu) "🚀 Продолжить" else "🚀 Continue", fontSize = 17.sp, fontWeight = FontWeight.Bold)
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
    onDeviceTypeChange: (String) -> Unit,
    onControlModeChange: (String) -> Unit,
    onClockPositionChange: (Int) -> Unit,
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
                backupStatus = if (S.isRu) "✅ Сохранено в файл!" else "✅ Exported!"
            }.onFailure { backupStatus = "❌ ${it.localizedMessage}" }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val jsonText = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
                if (importAllSettingsFromJson(prefs, jsonText)) {
                    backupStatus = if (S.isRu) "✅ Настройки импортированы!" else "✅ Imported!"
                    onSettingsImported()
                } else backupStatus = "❌ Неверный формат файла"
            }.onFailure { backupStatus = "❌ ${it.localizedMessage}" }
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 1. ИСТОЧНИКИ И КОНТЕНТ
        ExpandableSectionRow(S.catSourcesGroup, expandedRow == 0, theme, { toggleRow(0) }) {
            val subOpts = listOf(
                "playlists" to "${S.catPlaylists} (${playlists.size})",
                "epg" to S.catEpg,
                "portals" to S.catPortals,
                "qr" to S.catQr
            )
            val curSubTitle = subOpts.find { it.first == selectedSourceSubMenu }?.second ?: subOpts.first().second
            SourceDropdownSelector(
                label = if (S.isRu) "Выберите раздел источников:" else "Select Source Section:",
                selectedTitle = curSubTitle,
                options = subOpts,
                theme = theme,
                onSelect = { selectedSourceSubMenu = it }
            )
            HorizontalDivider(color = theme.subtext.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 4.dp))

            when (selectedSourceSubMenu) {
                "playlists" -> {
                    if (playlists.isNotEmpty()) {
                        val curName = playlists.find { it.id == selectedPlaylistId }?.name ?: playlists.first().name
                        SourceDropdownSelector(S.activePlaylistLabel, "📺 $curName", playlists.map { it.id to it.name }, theme, onSelectPlaylist)
                    }
                    OutlinedTextField(value = newPlName, onValueChange = { newPlName = it }, label = { Text(S.addSourceName) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = newPlUrl, onValueChange = { newPlUrl = it }, label = { Text(S.addSourceUrl) }, trailingIcon = { if (newPlUrl.isNotEmpty()) IconButton(onClick = { newPlUrl = "" }) { Icon(Icons.Default.Clear, null) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { if (newPlUrl.isNotBlank()) { onAddPlaylist(newPlName.trim().ifEmpty { "Плейлист ${playlists.size + 1}" }, newPlUrl.trim()); newPlName = ""; newPlUrl = "" } }, modifier = Modifier.fillMaxWidth()) { Text(S.addBtn, fontWeight = FontWeight.Bold) }
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
                "epg" -> {
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(theme.bg).border(1.dp, theme.primary.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (S.isRu) "Состояние телепрограммы (EPG):" else "EPG Status:", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = theme.primary)
                        if (isEpgLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp), color = theme.primary)
                        Text(epgStatusText, fontSize = 13.sp, color = theme.text)
                        Button(onClick = onRefreshEpgNow, enabled = !isEpgLoading, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp))
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
                    OutlinedTextField(value = newEpgUrl, onValueChange = { newEpgUrl = it }, label = { Text(S.addSourceUrl) }, trailingIcon = { if (newEpgUrl.isNotEmpty()) IconButton(onClick = { newEpgUrl = "" }) { Icon(Icons.Default.Clear, null) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { if (newEpgUrl.isNotBlank()) { onAddEpg(newEpgName.trim().ifEmpty { "EPG ${epgSources.size + 1}" }, newEpgUrl.trim()); newEpgName = ""; newEpgUrl = "" } }, modifier = Modifier.fillMaxWidth()) { Text(S.addBtn, fontWeight = FontWeight.Bold) }
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
                "portals" -> {
                    Text("🎬 VPortal (TVlider / iLook)", fontWeight = FontWeight.Bold, color = theme.primary)
                    OutlinedTextField(value = vportalUrl, onValueChange = onVportalChange, label = { Text("portal::[key:...]http://.../api/v1/") }, trailingIcon = { if (vportalUrl.isNotEmpty()) IconButton(onClick = { onVportalChange("") }) { Icon(Icons.Default.Clear, null) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("📡 Stalker Portal", fontWeight = FontWeight.Bold, color = theme.primary)
                    OutlinedTextField(value = stalkerUrl, onValueChange = onStalkerUrlChange, label = { Text("http://.../stalker_portal/c/") }, trailingIcon = { if (stalkerUrl.isNotEmpty()) IconButton(onClick = { onStalkerUrlChange("") }) { Icon(Icons.Default.Clear, null) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = stalkerMac, onValueChange = onStalkerMacChange, label = { Text("MAC (00:1A:79:XX:XX:XX)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = onSaveAndReload, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text(S.saveAndReloadBtn, fontWeight = FontWeight.Bold) }
                    if (vportalDiagnostic.isNotBlank()) {
                        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Диагностика VPortal:", fontWeight = FontWeight.Bold, color = theme.primary)
                            Text(vportalDiagnostic, fontSize = 12.sp, color = theme.text)
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
                        if (qrBitmap != null) Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "QR", modifier = Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("🌐 $remoteUrl", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = theme.primary)
                    }
                }
            }
        }

        // 2. ПЛЕЕР, ЧАСЫ, УСТРОЙСТВО И УПРАВЛЕНИЕ
        ExpandableSectionRow(S.catPlayer, expandedRow == 1, theme, { toggleRow(1) }) {
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (S.isRu) "Открывать канал сразу на полный экран при выборе" else "Open channel in fullscreen on tap", fontWeight = FontWeight.Bold, color = theme.text, modifier = Modifier.weight(1f))
                Switch(checked = openFullscreenOnTap, onCheckedChange = onAutoFsChange)
            }

            val clockOpts = listOf(
                "0" to (if (S.isRu) "🚫 Выключены" else "🚫 Off"),
                "1" to (if (S.isRu) "↖️ В левом верхнем углу экрана" else "↖️ Top-Left Corner"),
                "2" to (if (S.isRu) "↗️ В правом верхнем углу экрана" else "↗️ Top-Right Corner")
            )
            SourceDropdownSelector(
                label = if (S.isRu) "🕒 Часы реального времени на экране:" else "🕒 Real-Time Screen Clock:",
                selectedTitle = clockOpts.find { it.first == clockPosition.toString() }?.second ?: clockOpts.first().second,
                options = clockOpts,
                theme = theme,
                onSelect = { onClockPositionChange(it.toIntOrNull() ?: 0) }
            )

            val devOpts = listOf(
                "mobile" to (if (S.isRu) "📱 Смартфон / Планшет (кнопки Каналов и Архива в плеере)" else "📱 Smartphone / Tablet"),
                "tv" to (if (S.isRu) "📺 Телевизор / ТВ-приставка (без лишних кнопок на экране)" else "📺 Android TV / TV Box")
            )
            SourceDropdownSelector(if (S.isRu) "Тип устройства:" else "Device Type:", devOpts.find { it.first == deviceType }?.second ?: devOpts.first().second, devOpts, theme, onDeviceTypeChange)

            val ctrlOpts = listOf(
                "touch" to (if (S.isRu) "👆 Сенсорное управление (Касания)" else "👆 Touch Screen"),
                "remote" to (if (S.isRu) "🎮 Пульт ДУ (Кнопки навигации)" else "🎮 TV Remote")
            )
            SourceDropdownSelector(if (S.isRu) "Режим управления:" else "Control Mode:", ctrlOpts.find { it.first == controlMode }?.second ?: ctrlOpts.first().second, ctrlOpts, theme, onControlModeChange)

            val resizeOpts = listOf(
                "0" to (if (S.isRu) "📐 По размеру экрана (Fit)" else "📐 Fit Screen"),
                "1" to (if (S.isRu) "📐 Растянуть 16:9 (Fill)" else "📐 Stretch 16:9"),
                "2" to (if (S.isRu) "📐 Увеличить с обрезкой (Zoom)" else "📐 Zoom Crop")
            )
            SourceDropdownSelector(
                label = if (S.isRu) "Формат изображения:" else "Aspect Ratio:",
                selectedTitle = resizeOpts.find { it.first == playerResizeMode.toString() }?.second ?: resizeOpts.first().second,
                options = resizeOpts,
                theme = theme,
                onSelect = { onPlayerResizeChange(it.toIntOrNull() ?: 0) }
            )

            val bufOpts = listOf(
                "0" to (if (S.isRu) "🚀 Быстрый старт (2 сек)" else "🚀 Fast (2s)"),
                "1" to (if (S.isRu) "⚖️ Сбалансированный (5 сек)" else "⚖️ Balanced (5s)"),
                "2" to (if (S.isRu) "🛡 Макс. стабильность (12 сек)" else "🛡 Max Stability (12s)")
            )
            SourceDropdownSelector(
                label = if (S.isRu) "Буфер видеоплеера:" else "Buffer Size:",
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
            SourceDropdownSelector("User-Agent:", uaOpts.find { it.first == playerDefaultUa }?.second ?: playerDefaultUa, uaOpts, theme, onPlayerUaChange)
        }

        // 3. ВНЕШНИЙ ВИД, ТЕМЫ И ЯЗЫК
        ExpandableSectionRow(S.catAppearance, expandedRow == 2, theme, { toggleRow(2) }) {
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
                Icon(Icons.Default.Language, null); Spacer(Modifier.width(8.dp))
                Text(if (S.isRu) "Язык: Русский 🇷🇺 (Сменить на EN)" else "Language: English 🇬🇧 (Switch to RU)")
            }
        }

        // 4. ИМПОРТ И ЭКСПОРТ НАСТРОЕК
        ExpandableSectionRow(S.catBackup, expandedRow == 3, theme, { toggleRow(3) }) {
            Button(onClick = { exportLauncher.launch("CCTV-Plus-Backup.json") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp))
                Text(if (S.isRu) "📤 Экспортировать настройки в файл (.json)" else "📤 Export to File (.json)")
            }
            Button(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Download, null); Spacer(Modifier.width(8.dp))
                Text(if (S.isRu) "📥 Импортировать настройки из файла (.json)" else "📥 Import from File (.json)")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("CCTVBackup", exportAllSettingsToJson(prefs)))
                    backupStatus = if (S.isRu) "✅ Скопировано в буфер!" else "✅ Copied!"
                }, modifier = Modifier.weight(1f)) { Text(if (S.isRu) "📋 Копировать" else "📋 Copy") }

                OutlinedButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    if (importAllSettingsFromJson(prefs, clip)) {
                        backupStatus = if (S.isRu) "✅ Восстановлено!" else "✅ Restored!"
                        onSettingsImported()
                    } else backupStatus = "❌ Ошибка буфера"
                }, modifier = Modifier.weight(1f)) { Text(if (S.isRu) "📋 Вставить" else "📋 Paste") }
            }
            if (backupStatus.isNotBlank()) Text(backupStatus, fontSize = 13.sp, color = theme.primary, fontWeight = FontWeight.Bold)
        }

        // 5. ОБНОВЛЕНИЕ ПРИЛОЖЕНИЯ
        ExpandableSectionRow(if (S.isRu) "🔄 Обновление приложения (GitHub)" else "🔄 App Update (GitHub)", expandedRow == 4, theme, { toggleRow(4) }) {
            Text(if (S.isRu) "Установленная версия: v$installedVerName (Build #$installedVerCode)" else "Installed: v$installedVerName (Build #$installedVerCode)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = theme.subtext)
            Button(
                onClick = {
                    scope.launch {
                        isUpdating = true; downloadPercent = -1
                        updateStatus = if (S.isRu) "🔍 Проверяем GitHub..." else "🔍 Checking GitHub..."
                        val res = GitHubUpdater.checkLatestRelease()
                        isUpdating = false
                        res.onSuccess { latestRelease = it; updateStatus = "✅ Доступна сборка: ${it.versionTag}" }
                            .onFailure { updateStatus = "❌ ${it.localizedMessage}" }
                    }
                },
                enabled = !isUpdating,
                modifier = Modifier.fillMaxWidth()
            ) { Icon(Icons.Default.SystemUpdate, null); Spacer(Modifier.width(8.dp)); Text(if (S.isRu) "Проверить наличие обновлений" else "Check for Updates") }

            if (isUpdating) {
                if (downloadPercent in 0..100) LinearProgressIndicator(progress = { downloadPercent / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp), color = theme.primary)
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp), color = theme.primary)
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
                                updateStatus = "⏳ Скачивание: $pct% ($dlMb МБ из $totMb МБ)"
                            }
                            isUpdating = false
                            dl.onSuccess { updateStatus = "✅ Скачано (100%)! Открыто окно установки." }
                                .onFailure { updateStatus = "❌ ${it.localizedMessage}" }
                        }
                    },
                    enabled = !isUpdating,
                    modifier = Modifier.fillMaxWidth()
                ) { Icon(Icons.Default.CloudDownload, null); Spacer(Modifier.width(8.dp)); Text("⬇️ Скачать и установить ${latestRelease!!.versionTag}", fontWeight = FontWeight.Bold) }
            }
        }

        // 6. СПИСОК ИЗМЕНЕНИЙ (CHANGELOG)
        ExpandableSectionRow(S.catChangelog, expandedRow == 5, theme, { toggleRow(5) }) {
            AppChangelogHistory.forEachIndexed { idx, rel ->
                val verTitle = if (idx == 0 || rel.version == "CURRENT") "v$installedVerName (Build #$installedVerCode • Текущая)" else rel.version
                Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(verTitle, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = theme.primary)
                        Text(rel.date, fontSize = 12.sp, color = theme.subtext)
                    }
                    (if (S.isRu) rel.changesRu else rel.changesEn).forEach { Text(it, fontSize = 13.sp, color = theme.text) }
                }
            }
        }

        // 7. СООБЩИТЬ О ПРОБЛЕМЕ
        ExpandableSectionRow(S.catReportIssue, expandedRow == 6, theme, { toggleRow(6) }) {
            OutlinedTextField(value = issueDescription, onValueChange = { issueDescription = it }, label = { Text(if (S.isRu) "Опишите проблему..." else "Describe the issue...") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    val reportText = "=== CCTV Plus Report ===\nVer: v$installedVerName (#$installedVerCode)\nDevice: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})\nMode: $deviceType/$controlMode | Clock: $clockPosition | Theme: ${theme.id}\nEPG: $epgStatusText\nVPortal: ${maskSensitiveKeys(vportalDiagnostic)}\nComment: $issueDescription"
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("CCTVBugReport", reportText))
                    reportStatus = if (S.isRu) "✅ Отчет скопирован в буфер!" else "✅ Report copied!"
                },
                modifier = Modifier.fillMaxWidth()
            ) { Icon(Icons.Default.BugReport, null); Spacer(Modifier.width(8.dp)); Text(if (S.isRu) "📋 Скопировать тех. отчет" else "📋 Copy Diagnostic Report", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/cakecock")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("🌐 GitHub (cakecock)") }
            if (reportStatus.isNotBlank()) Text(reportStatus, fontSize = 13.sp, color = theme.primary, fontWeight = FontWeight.Bold)
        }

        // 8. ПОЛИТИКА КОНФИДЕНЦИАЛЬНОСТИ
        ExpandableSectionRow(S.catPrivacy, expandedRow == 7, theme, { toggleRow(7) }) {
            (if (S.isRu) PrivacyItemsRu else PrivacyItemsEn).forEach { p ->
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(12.dp)) { Text(p, fontSize = 13.sp, color = theme.text) }
            }
        }

        // 9. ЛИЦЕНЗИЯ И УСЛОВИЯ
        ExpandableSectionRow(S.catLicense, expandedRow == 8, theme, { toggleRow(8) }) {
            (if (S.isRu) LicenseItemsRu else LicenseItemsEn).forEach { p ->
                Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.bg).padding(12.dp)) { Text(p, fontSize = 13.sp, color = theme.text) }
            }
        }

        // ПОДВАЛ НАСТРОЕК: РАЗРАБОТЧИК И ВЕРСИЯ
        Spacer(Modifier.height(6.dp))
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(theme.card).border(1.dp, theme.primary.copy(alpha = 0.3f), RoundedCornerShape(14.dp)).padding(vertical = 16.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("📺 CCTV Plus • v$installedVerName (Build #$installedVerCode)", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = theme.primary)
            Spacer(Modifier.height(4.dp))
            Text(if (S.isRu) "👨‍💻 Разработчик: cakecock © 2026" else "👨‍💻 Developer: cakecock © 2026", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = theme.subtext)
        }
    }
}
