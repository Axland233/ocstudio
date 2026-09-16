package com.yehenowo.gitmind

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yehenowo.gitmind.ui.*
import kotlinx.coroutines.launch

// 主装配:对应 legacy App.tsx 的状态机。
// 引导 -> (无工程空态 | 聊天);浮层:抽屉/设置/新建/Git 记录(返回键依优先级关闭)。
// 抽屉状态唯一真源是 DrawerState(手势滑动也会同步),开关一律走协程,
// 不再维护独立布尔(曾因布尔与手势状态脱节导致"选中工程后抽屉不收")。
// Scaffold 整体 imePadding():窗口 edge-to-edge 后系统不再自动避让,
// 由组合自己消费 IME insets,键盘弹出时整个界面抬到键盘上方,输入框不被遮挡。

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: AppViewModel) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val collectionState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var settingsOpen by remember { mutableStateOf(false) }
    var newOpen by remember { mutableStateOf(false) }

    // Android 返回键:浮层依优先级关闭,主页则交给系统(退到后台)
    BackHandler(enabled = settingsOpen || newOpen || collectionState.isOpen || drawerState.isOpen) {
        when {
            settingsOpen -> settingsOpen = false
            newOpen -> newOpen = false
            collectionState.isOpen -> scope.launch { collectionState.close() }
            drawerState.isOpen -> scope.launch { drawerState.close() }
        }
    }

    GitMindTheme(vm.settings.theme.mode, vm.settings.theme.seed_color) {
        if (!vm.settings.onboarded) {
            Bootstrap(vm) { }
            return@GitMindTheme
        }

        // 工程抽屉内容(延迟打开:drawerState.isOpen 变化时再拉列表)
        // 宽度:屏幕宽的 75%,但不超过 MD3 最大抽屉宽度
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerState = drawerState,
                    modifier = Modifier.widthIn(max = DrawerDefaults.MaximumDrawerWidth).fillMaxWidth(0.75f),
                ) {
                    Text("我的工程", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                    val projects = remember(drawerState.isOpen) { vm.listProjects() }
                    if (projects.isEmpty()) {
                        Text("还没有工程,点下方新建一个", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    projects.forEach { pr ->
                        NavigationDrawerItem(
                            label = {
                                Column {
                                    Text(pr.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        pr.desc.ifBlank { "无描述" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            selected = pr.name == vm.project?.info?.name,
                            onClick = {
                                vm.openProject(pr.name)
                                scope.launch { drawerState.close() }   // 选中即收起,等手势/动画完成
                            },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    NavigationDrawerItem(
                        label = { Row(verticalAlignment = Alignment.CenterVertically) { MsIcon("add"); Spacer(Modifier.width(8.dp)); Text("新建工程") } },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            newOpen = true
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("设置 / 工程迁移") },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            settingsOpen = true
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            },
        ) {
            CollectionPanel(vm, collectionState) {
            Scaffold(
                // 键盘弹出时整体抬升(edge-to-edge 下必须自己消费 ime insets)
                modifier = Modifier.fillMaxSize().imePadding(),
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
                            IconButton(onClick = { scope.launch { drawerState.open() } }) { MsIcon("menu") }
                        },
                        actions = {
                            if (vm.project != null) {
                                IconButton(onClick = { scope.launch { collectionState.open() } }) { MsIcon("history") }
                            }
                            IconButton(onClick = { settingsOpen = true }) { MsIcon("settings") }
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
                        Button(onClick = { scope.launch { drawerState.open() } }) { Text("打开工程列表") }
                        OutlinedButton(onClick = { newOpen = true }) { Text("新建工程") }
                    }
                } else {
                    ChatPanel(vm, p.info.name, Modifier.fillMaxSize().padding(padding))
                }
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
