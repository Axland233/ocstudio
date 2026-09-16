package com.yehenowo.gitmind.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.yehenowo.gitmind.AppViewModel
import com.yehenowo.gitmind.core.CommitInfo
import com.yehenowo.gitmind.core.FileDiff
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Git 历史 + Diff 阅读弹窗。
// 顶层 Git 历史列表已并入 CollectionPanel 右栏;这里保留 DiffDialog 供其复用:
// 提交列表 -> 点开独立弹窗看逐行 diff。

/** Diff 阅读弹窗(大空间,列表选文件,逐行展示) */
@Composable
fun DiffDialog(vm: AppViewModel, commit: CommitInfo, olderId: String?, onDismiss: () -> Unit) {
    var diffs by remember { mutableStateOf<List<FileDiff>?>(null) }
    var openPath by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf("") }

    LaunchedEffect(commit.id) {
        runCatching { vm.gitDiff(olderId, commit.id) }
            .onSuccess { diffs = it; openPath = it.firstOrNull()?.path }
            .onFailure { err = it.message ?: "diff 失败" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${commit.id} · ${commit.message}", maxLines = 2) },
        text = {
            Column {
                Text("${commit.author} · ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(commit.time_secs * 1000))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                when {
                    err.isNotEmpty() -> Text(err, color = MaterialTheme.colorScheme.error)
                    diffs == null -> Text("加载中…")
                    diffs!!.isEmpty() -> Text("该提交无文件变更")
                    else -> Column {
                        // 文件列表(类型徽标 + 增删统计)
                        diffs!!.forEach { d ->
                            Row(
                                Modifier.fillMaxWidth().clickable { openPath = d.path }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    d.kind,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(d.path, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1)
                                val add = d.lines.count { it.kind == "add" }
                                val del = d.lines.count { it.kind == "del" }
                                Text("+$add -$del", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        // 逐行 diff
                        val open = diffs!!.firstOrNull { it.path == openPath }
                        if (open != null) {
                            Text("${open.path}(${kindLabel(open.kind)})", style = MaterialTheme.typography.titleSmall)
                            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                                itemsIndexed(open.lines) { _, l ->
                                    val bg = when (l.kind) {
                                        "add" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                        "del" -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                                        else -> MaterialTheme.colorScheme.surface
                                    }
                                    Text(
                                        "${(if (l.kind == "add") "+" else if (l.kind == "del") "-" else " ")} ${l.text}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        modifier = Modifier.fillMaxWidth().background(bg).padding(horizontal = 8.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

fun kindLabel(k: String) = when (k) {
    "A" -> "新增"; "M" -> "修改"; "D" -> "删除"; "R" -> "重命名"; else -> k
}