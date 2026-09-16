package com.yehenowo.gitmind.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import com.yehenowo.gitmind.AppViewModel
import com.yehenowo.gitmind.ui.THEME_AUTO
import kotlinx.coroutines.launch
import android.os.Build
import android.provider.DocumentsContract

// 各类弹窗:新建工程 / 设置(含 SAF 导入导出) / 首启引导 / Git 历史+Diff。

/** 新建工程弹窗 */
@Composable
fun NewProjectDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建工程") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("工程名称 *") }, placeholder = { Text("例如:雾海之城") })
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("一句话描述") }, placeholder = { Text("这个世界讲的是什么?") })
                OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("作者(写入 git 提交记录)") }, placeholder = { Text("你的名字/笔名") })
                if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && name.isNotBlank(),
                onClick = {
                    busy = true; err = ""
                    vm.createProject(name.trim(), desc.trim(), author.trim())
                        .onSuccess { onDismiss() }
                        .onFailure {
                            android.util.Log.e("GitMindCreate", "创建工程失败", it)
                            err = buildString {
                                append(it::class.java.simpleName)
                                append(": ")
                                append(it.message ?: "(无异常信息)")
                                it.cause?.let { c -> append(" <- ").append(c::class.java.simpleName).append(": ").append(c.message ?: "") }
                            }
                        }
                    busy = false
                },
            ) { Text(if (busy) "创建中…" else "创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 设置弹窗:LLM 配置 + 测试连接 + 主题模式 + 工程迁移(SAF 导入/导出) + 远程仓库 */
@Composable
fun SettingsDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val s = vm.settings
    var baseUrl by remember { mutableStateOf(s.llm.base_url) }
    var apiKey by remember { mutableStateOf(s.llm.api_key) }
    var model by remember { mutableStateOf(s.llm.model) }
    var ctxWindow by remember { mutableStateOf(s.llm.context_window.toString()) }
    var mode by remember { mutableStateOf(s.theme.mode) }
    var seedColor by remember { mutableStateOf(s.theme.seed_color) }
    var remote by remember { mutableStateOf(vm.project?.info?.remote_url ?: "") }
    var testing by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.importFromSaf(ctx, uri) { vm.toast = "已导入工程: $it" }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.exportToSaf(ctx, uri)
    }
    // 项目文件夹:SAF 选中后取真实文件系统路径存入 workspace_dir
    var workspaceDir by remember { mutableStateOf(s.workspace_dir) }
    val pickWorkspaceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val doc = DocumentFile.fromTreeUri(ctx, uri)
            val path = com.yehenowo.gitmind.data.safToRealPath(ctx, uri, doc?.uri)
            if (path != null) {
                workspaceDir = path
                vm.toast = "已选择项目文件夹,保存后生效: $path"
            } else {
                vm.toast = "该位置无法作为项目文件夹(需要本地存储目录)"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("大模型(OpenAI 兼容接口)", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("API 地址") }, placeholder = { Text("https://api.example.com/v1") }, modifier = Modifier.fillMaxWidth())
                SecretField(value = apiKey, onValueChange = { apiKey = it }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = ctxWindow, onValueChange = { ctxWindow = it.filter { c -> c.isDigit() } }, label = { Text("上下文窗口(token,滚动阈值=70%)") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    TextButton(enabled = !testing, onClick = {
                        testing = true
                        vm.scope.launch {  // ponytail: 复用 VM scope
                            val r = com.yehenowo.gitmind.core.testConnection(baseUrl.trim(), apiKey.trim(), model.trim())
                            testMsg = "${if (r.ok) "✓" else "✗"} ${r.message} (${r.latency_ms}ms)"
                            testing = false
                        }
                    }) { Text(if (testing) "测试中…" else "测试连接") }
                    Text(testMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(12.dp))
                Text("主题模式", style = MaterialTheme.typography.titleSmall)
                Row {
                    listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (m, label) ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(label) }, modifier = Modifier.padding(end = 8.dp))
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("主题颜色", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 跟随系统 Monet 动态取色(Android 12+)
                    FilterChip(
                        selected = seedColor == THEME_AUTO,
                        onClick = { seedColor = THEME_AUTO },
                        label = { Text("自动") },
                        leadingIcon = { MsIcon("palette", filled = seedColor == THEME_AUTO) },
                    )
                    Spacer(Modifier.width(10.dp))
                    // 常用颜色色板
                    listOf(
                        "#6750A4" to "紫", "#006A60" to "青", "#0061A4" to "蓝",
                        "#8F4C38" to "棕", "#984061" to "玫红", "#4C662B" to "绿",
                        "#7D5260" to "藕", "#5F5D5C" to "灰",
                    ).forEach { (hex, name) ->
                        val isSel = seedColor.equals(hex, ignoreCase = true)
                        Box(
                            Modifier
                                .padding(end = 8.dp)
                                .size(32.dp)
                                .border(
                                    width = if (isSel) 3.dp else 1.dp,
                                    color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape,
                                )
                                .clip(CircleShape)
                                .background(runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF6750A4)))
                                .clickable { seedColor = hex }
                                .semantics { contentDescription = name },
                        )
                    }
                }
                if (seedColor != THEME_AUTO && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    Text("此设备低于 Android 12,自定义颜色由应用内生成 Material 配色。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(12.dp))
                Text("项目文件夹", style = MaterialTheme.typography.titleSmall)
                Text("当前:${workspaceDir ?: "默认(应用私有目录)"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row {
                    TextButton(onClick = { pickWorkspaceLauncher.launch(null) }) { MsIcon("folder_open"); Spacer(Modifier.width(6.dp)); Text("切换文件夹") }
                }
                Text(
                    "切换后列表只显示新文件夹里的工程;原文件夹不变,可随时切回。已打开的工程不在新文件夹内时会回到未选择状态。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))
                Text("工程迁移", style = MaterialTheme.typography.titleSmall)
                Row {
                    TextButton(onClick = { importLauncher.launch(null) }) { MsIcon("file_upload"); Spacer(Modifier.width(6.dp)); Text("导入工程") }
                    TextButton(onClick = { exportLauncher.launch(null) }) { MsIcon("file_download"); Spacer(Modifier.width(6.dp)); Text("导出当前工程") }
                }
                Text("导入:选中含 project.json 的工程文件夹(或其父目录);导出:选中目标文件夹。整个目录(含 git 历史)原样迁移。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (vm.project != null) {
                    Spacer(Modifier.height(12.dp))
                    Text("远程仓库(GitHub)", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(value = remote, onValueChange = { remote = it }, label = { Text("remote_url") }, modifier = Modifier.fillMaxWidth())
                }

                Text("\n对话内容将发送到你填写的服务商处理,请自行确认其隐私政策与数据使用条款。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    vm.saveSettings(s.copy(
                        llm = s.llm.copy(base_url = baseUrl.trim(), api_key = apiKey.trim(), model = model.trim(), context_window = ctxWindow.toLongOrNull() ?: 131072L),
                        theme = s.theme.copy(mode = mode, seed_color = seedColor),
                        workspace_dir = workspaceDir?.trim()?.takeIf { it.isNotEmpty() },
                    ))
                    // workspace 变了:按新根目录刷新工程状态(不在新目录则回空态)
                    if (workspaceDir?.trim().takeIf { !it.isNullOrEmpty() } != s.workspace_dir) {
                        vm.refreshAfterWorkspaceChange()
                    }
                    if (remote != (vm.project?.info?.remote_url ?: "")) vm.setRemote(remote.trim())
                    onDismiss()
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 首启引导:第二步工程信息 + 第三步 API(移动端 workspace 用默认应用目录) */
@Composable
fun Bootstrap(vm: AppViewModel, onDone: () -> Unit) {
    var step by remember { mutableStateOf(1) }
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().imePadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Text("GitMind", style = MaterialTheme.typography.displaySmall)
        Text("用 git 管理你的想法脑洞 · AI 驱动", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Text(if (step == 1) "① 创建第一个工程" else "② 接入大模型", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (step == 1) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("工程名称 *") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("一句话描述") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = author, onValueChange = { author = it }, label = { Text("作者(写入 git 提交记录)") }, modifier = Modifier.fillMaxWidth())
                Text("\n工程文件将存放在应用私有目录,可随时在设置中通过 SAF 导出/迁移。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("API 地址") }, placeholder = { Text("https://api.example.com/v1") }, modifier = Modifier.fillMaxWidth())
                SecretField(value = apiKey, onValueChange = { apiKey = it }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("模型名称") }, modifier = Modifier.fillMaxWidth())
                Text("\n以服务商官方文档为准。对话内容将发送到你填写的服务商处理。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (step > 1) TextButton(onClick = { step-- }) { Text("上一步") } else Spacer(Modifier.width(8.dp))
            Button(
                enabled = !busy && (if (step == 1) name.isNotBlank() else baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()),
                onClick = {
                    if (step == 1) {
                        step = 2
                    } else {
                        busy = true; err = ""
                        runCatching {
                            vm.saveSettings(vm.settings.copy(
                                onboarded = true,
                                llm = vm.settings.llm.copy(base_url = baseUrl.trim(), api_key = apiKey.trim(), model = model.trim()),
                            ))
                            vm.createProject(name.trim(), desc.trim(), author.trim().ifBlank { "匿名" })
                        }.onSuccess { onDone() }.onFailure { err = it.message ?: "创建失败" }
                        busy = false
                    }
                },
            ) { Text(if (step == 1) "下一步" else "开始创作") }
        }
    }
}