package app.bilimusic.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bilimusic.data.Plan

private fun fmtBytes(b: Long): String =
    if (b < 1024 * 1024) "${b / 1024} KB" else "%.1f MB".format(b / 1024.0 / 1024.0)

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 4.dp))
}

@Composable
private fun GroupLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val ap by vm.appearance.collectAsStateWithLifecycle()
    val count by vm.downloadedCount.collectAsStateWithLifecycle()
    val bytes by vm.storageBytes.collectAsStateWithLifecycle()
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle()
    val linkProgress by vm.linkImport.collectAsStateWithLifecycle()
    var linkDialog by remember { mutableStateOf(false) }
    val dark = resolveDark(ap.mode)
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(count) { vm.refreshStorage() }

    val exportAll = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportTo(uri, null)
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.prepareImport(uri)
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                Text("设置", style = MaterialTheme.typography.titleLarge)
            }
        }

        // ---------------- 外观 ----------------
        item { SectionTitle("外观") }
        item { GroupLabel("明暗") }
        item { RadioRow("跟随系统", ap.mode == 0) { vm.setAppearance { it.copy(mode = 0) } } }
        item { RadioRow("浅色", ap.mode == 1) { vm.setAppearance { it.copy(mode = 1) } } }
        item { RadioRow("深色", ap.mode == 2) { vm.setAppearance { it.copy(mode = 2) } } }

        item { GroupLabel("强调色") }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                for (i in 0..CUSTOM_ACCENT) {
                    val selected = ap.accent == i
                    val color = accentFor(ap.copy(accent = i), dark)
                    Column(
                        Modifier.weight(1f).clip(MaterialTheme.shapes.medium)
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { vm.setAppearance { it.copy(accent = i) } })
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                            // 选中态用对勾标记，不只靠颜色
                            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = onAccent(color))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(if (i == CUSTOM_ACCENT) "自定义" else AccentPresets[i].name, style = MaterialTheme.typography.bodySmall,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (ap.accent == CUSTOM_ACCENT) item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("色相", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = ap.hue, onValueChange = { h -> vm.setAppearance { it.copy(hue = h) } }, valueRange = 0f..360f,
                    colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
                )
                Text("颜色会自动调整到足够清晰：过浅的颜色会被加深（深色模式下则提亮），保证文字和按钮看得清。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item { GroupLabel("标题字体") }
        item { RadioRow("衬线（更有书卷气）", ap.serifTitles) { vm.setAppearance { it.copy(serifTitles = true) } } }
        item { RadioRow("无衬线（更简洁）", !ap.serifTitles) { vm.setAppearance { it.copy(serifTitles = false) } } }

        item { GroupLabel("圆角") }
        item { RadioRow("紧凑", ap.corners == 0) { vm.setAppearance { it.copy(corners = 0) } } }
        item { RadioRow("标准", ap.corners == 1) { vm.setAppearance { it.copy(corners = 1) } } }
        item { RadioRow("圆润", ap.corners == 2) { vm.setAppearance { it.copy(corners = 2) } } }

        // ---------------- 下载 ----------------
        item { SectionTitle("下载") }
        item {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .toggleable(value = wifiOnly, role = Role.Switch, onValueChange = vm::setWifiOnly)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("仅在 Wi-Fi 下下载", style = MaterialTheme.typography.bodyLarge)
                    Text("关闭后，使用移动数据也会下载。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = wifiOnly, onCheckedChange = null)
            }
        }
        item { Hint("已下载 $count 首，占用 ${fmtBytes(bytes)}。已下载的歌曲没有网络也能播放。") }
        item {
            OutlinedButton(onClick = { confirmClear = true }, enabled = count > 0,
                modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 44.dp)) { Text("清除全部下载") }
        }

        // ---------------- 数据 ----------------
        item { SectionTitle("数据") }
        item { Hint("导出的歌单文件可以备份，也可以导入到另一台设备。导出包含全部歌单和收藏。") }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportAll.launch("BiliMusic-backup.json") }, modifier = Modifier.heightIn(min = 44.dp)) { Text("导出全部") }
                OutlinedButton(onClick = { import.launch(arrayOf("*/*")) }, modifier = Modifier.heightIn(min = 44.dp)) { Text("导入歌单") }
            }
        }
        item {
            OutlinedButton(onClick = { linkDialog = true }, enabled = linkProgress == null,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp).heightIn(min = 44.dp)) { Text("从链接或 BV 号导入") }
        }
        linkProgress?.let { msg -> item { Hint(msg) } }
        item { Spacer(Modifier.height(32.dp)) }
    }

    if (linkDialog) LinkImportDialog(onDismiss = { linkDialog = false }) { name, text ->
        linkDialog = false
        vm.importLinks(name, text)
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("清除全部下载？", style = MaterialTheme.typography.titleLarge) },
        text = { Text("会删除 $count 首歌曲的本地文件（${fmtBytes(bytes)}）。歌单和收藏不受影响，之后仍可在线播放或重新下载。") },
        confirmButton = {
            TextButton(onClick = { vm.clearDownloads(); confirmClear = false },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("清除") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
    )
}

@Composable
fun LinkImportDialog(onDismiss: () -> Unit, onConfirm: (name: String, text: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从链接导入", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("歌单名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("BV 号或视频链接，每行一个") },
                    minLines = 4, maxLines = 8, modifier = Modifier.fillMaxWidth())
                Text("最多一次导入 200 个。暂不支持 b23.tv 短链接。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, text) }, enabled = text.isNotBlank()) { Text("开始导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 导入前的预览：写入数据库之前，让用户先看到会发生什么。 */
@Composable
fun ImportPreviewDialog(plan: Plan, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入这个备份？", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("新建 ${plan.newLists} 个歌单")
                if (plan.mergedLists > 0) Text("${plan.mergedLists} 个歌单与已有歌单同名，会并入已有歌单")
                Text("新增 ${plan.newSongs} 首歌曲" + if (plan.knownSongs > 0) "（${plan.knownSongs} 首已在本机，会跳过）" else "")
                if (plan.newFavorites > 0) Text("新增 ${plan.newFavorites} 首收藏")
                Spacer(Modifier.height(4.dp))
                Text("现有的歌单和收藏不会被删除。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("导入") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
