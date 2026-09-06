package com.yehenowo.gitmind.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yehenowo.gitmind.AppViewModel
import com.yehenowo.gitmind.ChatItem

// 聊天主区:消息气泡 + 工具状态条 + 输入行。
// 流式输出本身就是逐 token 到达(打字机),新气泡入场加"模糊+上浮飞入"的 M3 表达动画。

@Composable
fun ChatPanel(vm: AppViewModel, projectName: String, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    // 新消息/流式更新时滚到底部
    LaunchedEffect(vm.chatItems.size, vm.chatItems.lastOrNull()?.let { (it as? ChatItem.Assistant)?.text?.length }) {
        if (vm.chatItems.isNotEmpty()) listState.animateScrollToItem(vm.chatItems.size - 1)
    }

    Column(modifier) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(12.dp)) {
            if (vm.chatItems.isEmpty()) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 80.dp)) {
                        Text(projectName, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "和 AI 一起打磨你的设定集吧 —— 聊人设、世界观、剧情脑洞,\nAI 会把值得固化的内容写进设定文件并自动 git 提交。",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(vm.chatItems) { item -> MessageBubble(item) }
        }

        // 本轮用量灰字
        vm.usage?.let { (turn, total) ->
            if (!vm.busy) {
                Text(
                    "本轮 $turn tokens · 累计 $total",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
        }

        // 输入行
        var input by remember { mutableStateOf("") }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("聊聊你的脑洞…") },
                maxLines = 8,
                enabled = !vm.busy,
                shape = RoundedCornerShape(24.dp),
            )
            Spacer(Modifier.width(8.dp))
            if (vm.busy) {
                FilledIconButton(onClick = { vm.stopGen() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Text("■")
                }
            } else {
                FilledIconButton(onClick = { vm.send(input.trim()); input = "" }, enabled = input.isNotBlank()) {
                    Text("➤")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(item: ChatItem) {
    // 入场动画:透明+下偏移+模糊 -> 归位(非线性 FastOutSlowInEasing)
    var entered by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "enter",
    )
    LaunchedEffect(Unit) { entered = true }

    when (item) {
        is ChatItem.User -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp),
                modifier = Modifier
                    .graphicsLayer { alpha = progress; translationY = (1 - progress) * 40f }
                    .blur(4.dp * (1 - progress)),
            ) {
                Text(item.text, modifier = Modifier.padding(12.dp))
            }
        }
        is ChatItem.Assistant -> if (item.text.isNotBlank()) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .graphicsLayer { alpha = progress; translationY = (1 - progress) * 40f }
                    .blur(4.dp * (1 - progress)),
            ) {
                Text(item.text, modifier = Modifier.padding(12.dp))
            }
        }
        is ChatItem.Tool -> Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = { },
                label = { Text(toolLabel(item.name), style = MaterialTheme.typography.labelSmall) },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                item.summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        is ChatItem.Error -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
            Text(
                item.text,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

fun toolLabel(name: String): String = when (name) {
    "write_project_file" -> "已固化并提交"
    "read_project_file" -> "读取设定"
    "search_history" -> "检索历史"
    else -> name
}