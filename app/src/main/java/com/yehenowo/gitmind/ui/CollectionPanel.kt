package com.yehenowo.gitmind.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yehenowo.gitmind.AppViewModel
import com.yehenowo.gitmind.core.CommitInfo
import com.yehenowo.gitmind.core.SETTING_FILES
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 设定卡右抽屉:使用 Material3 自带 DrawerState/拖拽动画,
// 与左侧工程抽屉同样可跟手滑动,不再用阈值手势突然弹出全屏 Dialog。

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionPanel(vm: AppViewModel, drawerState: DrawerState, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = vm.project != null,
            drawerContent = {
                ModalDrawerSheet(
                    drawerState = drawerState,
                    modifier = Modifier.widthIn(max = DrawerDefaults.MaximumDrawerWidth).fillMaxWidth(0.9f),
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        CollectionContent(vm) { scope.launch { drawerState.close() } }
                    }
                }
            },
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                content()
            }
        }
    }
}

@Composable
private fun CollectionContent(vm: AppViewModel, onClose: () -> Unit) {
    val p = vm.project ?: return
    var showGit by rememberSaveable(p.info.name) { mutableStateOf(false) }
    if (showGit) GitLogPage(vm) { showGit = false }
    else FilesPage(vm, onOpenGit = { showGit = true }, onClose = onClose)
}

@Composable
private fun FilesPage(vm: AppViewModel, onOpenGit: () -> Unit, onClose: () -> Unit) {
    val p = vm.project ?: return
    var fileIdx by rememberSaveable(p.info.name) { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        // 顶栏:标题 + Git 入口 + 关闭
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MsIcon("auto_stories", tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("设定集 · ${p.info.name}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("点右上角翻 Git 记录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onOpenGit) { MsIcon("history") }
            IconButton(onClick = onClose) { MsIcon("close") }
        }

        // 五大模块横排切换
        ScrollableTabRow(
            selectedTabIndex = fileIdx.coerceIn(0, SETTING_FILES.lastIndex),
            edgePadding = 16.dp,
        ) {
            SETTING_FILES.forEachIndexed { i, f ->
                Tab(selected = i == fileIdx, onClick = { fileIdx = i }, text = { Text(f.removeSuffix(".md"), maxLines = 1) })
            }
        }

        // 正文阅读
        val content = p.files.getOrNull(fileIdx)?.second.orEmpty()
        SelectionContainer(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.padding(16.dp)) {
                if (content.isBlank()) {
                    Text("(空)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    content.lines().forEach { line -> MdLine(line) }
                }
            }
        }
    }
}

@Composable
private fun GitLogPage(vm: AppViewModel, onBack: () -> Unit) {
    var log by remember { mutableStateOf(listOf<CommitInfo>()) }
    var dlg by remember { mutableStateOf<CommitInfo?>(null) }
    var loading by remember { mutableStateOf(true) }

    // 进页即拉取;工程更新(固化/切工程)后刷新
    LaunchedEffect(vm.project?.info?.name, vm.project?.info?.updated_at) {
        log = vm.gitLog()
        loading = false
    }
    BackHandler(enabled = dlg == null) { onBack() }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { MsIcon("arrow_back") }
            MsIcon("commit", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("Git 记录", style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider()

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            log.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无提交", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(log) { i, c ->
                    Column(
                        Modifier.fillMaxWidth().clickable { dlg = c }.padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.id.take(7), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text(c.message, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                        Text(
                            "${c.author} · ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(c.time_secs * 1000))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (i < log.lastIndex) HorizontalDivider()
                }
            }
        }
    }

    dlg?.let { commit ->
        val olderId = log.getOrNull(log.indexOf(commit) + 1)?.id
        DiffDialog(vm, commit, olderId) { dlg = null }
    }
}

/** 轻量 markdown 行渲染:标题分级加粗放大,其余正文。已知的刻意简化(不做完整 markdown)。 */
@Composable
private fun MdLine(line: String) {
    val t = line.trimEnd()
    when {
        t.startsWith("### ") -> Text(t.removePrefix("### "), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        t.startsWith("## ") -> Text(t.removePrefix("## "), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        t.startsWith("# ") -> Text(t.removePrefix("# "), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        t.startsWith("---") -> HorizontalDivider(Modifier.padding(vertical = 6.dp))
        t.isBlank() -> Spacer(Modifier.height(4.dp))
        else -> Text(t, style = MaterialTheme.typography.bodyMedium)
    }
}
