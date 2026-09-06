package com.yehenowo.gitmind

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yehenowo.gitmind.ui.*

// 主装配:对应 legacy App.tsx 的状态机。
// 引导 -> (无工程空态 | 聊天);浮层:抽屉/设置/新建/Git 记录(返回键依优先级关闭)。

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: AppViewModel) {
    var drawerOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var newOpen by remember { mutableStateOf(false) }
    var gitOpen by remember { mutableStateOf(false) }

    // Android 返回键:浮层依优先级关闭,主页则交给系统(退到后台)
    BackHandler(enabled = settingsOpen || newOpen || drawerOpen || gitOpen) {
        when {
            settingsOpen -> settingsOpen = false
            newOpen -> newOpen = false
            gitOpen -> gitOpen = false
            drawerOpen -> drawerOpen = false
        }
    }

    GitMindTheme(vm.settings.theme.mode, vm.settings.theme.seed_color) {
        if (!vm.settings.onboarded) {
            Bootstrap(vm) { }
            return@GitMindTheme
        }

        // 工程抽屉内容(延迟打开:drawerState 变化时再拉列表)
        ModalNavigationDrawer(
            drawerState = rememberDrawerState(if (drawerOpen) DrawerValue.Open else DrawerValue.Closed),
            drawerContent = {
                ModalDrawerSheet {
                    Text("我的工程", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                    val projects = remember(drawerOpen) { vm.listProjects() }
                    if (projects.isEmpty()) {
                        Text("还没有工程,点下方新建一个", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    projects.forEach { pr ->
                        NavigationDrawerItem(
                            label = { Column { Text(pr.name); Text(pr.desc.ifBlank { "无描述" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                            selected = pr.name == vm.project?.info?.name,
                            onClick = { vm.openProject(pr.name); drawerOpen = false },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    NavigationDrawerItem(
                        label = { Text("＋ 新建工程") },
                        selected = false,
                        onClick = { drawerOpen = false; newOpen = true },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("设置 / 工程迁移") },
                        selected = false,
                        onClick = { drawerOpen = false; settingsOpen = true },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            },
        ) {
            Scaffold(
                topBar = {
                    CenterAlignedTopAppBar(
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(vm.project?.info?.name ?: "未选择工程", style = MaterialTheme.typography.titleMedium)
                                vm.project?.info?.desc?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { drawerOpen = true }) { Text("☰") }
                        },
                        actions = {
                            if (vm.project != null) {
                                IconButton(onClick = { gitOpen = true }) { Text("⌥") }
                            }
                            IconButton(onClick = { settingsOpen = true }) { Text("⚙") }
                        },
                    )
                },
            ) { padding ->
                val p = vm.project
                if (p == null) {
                    Column(
                        Modifier.fillMaxSize().padding(padding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("GitMind", style = MaterialTheme.typography.headlineMedium)
                        Text("选择一个工程开始创作", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { drawerOpen = true }) { Text("打开工程列表") }
                        OutlinedButton(onClick = { newOpen = true }) { Text("新建工程") }
                    }
                } else {
                    ChatPanel(vm, p.info.name, Modifier.fillMaxSize().padding(padding))
                }
            }
        }

        // Git 历史面板(全屏对话框)
        if (gitOpen && vm.project != null) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { gitOpen = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(Modifier.fillMaxSize().padding(16.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    GitPanel(vm, Modifier.fillMaxSize())
                }
            }
        }

        if (newOpen) NewProjectDialog(vm) { newOpen = false }
        if (settingsOpen) SettingsDialog(vm) { settingsOpen = false }

        // toast
        vm.toast?.let { msg ->
            LaunchedEffect(msg) {
                ToastProxy.show(msg)
                vm.clearToast()
            }
        }
    }
}

/** 简易 toast 桥(避免在 composable 里直接拿 context) */
object ToastProxy {
    var show: (String) -> Unit = { }
}