package com.yehenowo.gitmind

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import com.yehenowo.gitmind.core.*
import com.yehenowo.gitmind.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// UI 状态模型(对应 legacy 前端的 ChatItem/App 状态)
sealed class ChatItem {
    data class User(val text: String) : ChatItem()
    data class Assistant(val text: String, val closed: Boolean = false) : ChatItem()
    data class Tool(val name: String, val summary: String) : ChatItem()
    data class Error(val text: String) : ChatItem()
}

data class ProjectView(val info: ProjectInfo, val files: List<Pair<String, String>>)

/**
 * 全局 VM:对应 legacy lib.rs 的 AppState + 前端 App.tsx 的状态编排。
 * 所有业务都在 suspend 里做,UI 只消费状态。
 */
class AppViewModel(private val filesDir: File) {

    val settingsStore = SettingsStore(filesDir)
    val git = JGitRepo()
    val scope = CoroutineScope(Dispatchers.Default)

    var settings by mutableStateOf(settingsStore.load())
        private set
    var project by mutableStateOf<ProjectView?>(null)
        private set
    var chatItems by mutableStateOf<List<ChatItem>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var usage by mutableStateOf<Pair<Long, Long>?>(null)   // (本轮, 累计)
        private set
    var toast by mutableStateOf<String?>(null)

    // ---- 会话状态(内存 + JSONL 落盘,与 legacy lib.rs ActiveProject 对应) ----
    private var history = mutableListOf<JsonObject>()
    private var sessionSeq = 1L
    private var summary: String? = null
    private val stopFlag = AtomicBoolean(false)
    private var chatJob: Job? = null

    /** 与 legacy 一致:重启后不自动恢复工程,由用户从工程列表打开(会话已持久化,打开即恢复) */
    fun bootstrap() { }

    fun saveSettings(s: AppSettings) {
        settings = s
        settingsStore.save(s)
    }

    // ---- 工程 ----

    fun workspaceRoot(): File = settingsStore.workspaceRoot(settings)

    fun listProjects(): List<ProjectInfo> = listProjects(workspaceRoot())

    fun createProject(name: String, desc: String, author: String): Result<ProjectInfo> = runCatching {
        createProject(workspaceRoot(), name, desc, author, git)
    }.onSuccess {
        openProject(name)
    }

    fun openProject(name: String): Result<Unit> = runCatching {
        val info = openProject(workspaceRoot(), name)
        val files = readFiles(workspaceRoot(), name)
        project = ProjectView(info, files)
        // 恢复该工程最新 session(退出重开不丢)
        val store = SessionStore(sessionsRoot(name))
        store.ensureFirst()
        val (seq, sum, msgs) = store.loadLatest() ?: Triple(1L, null, emptyList())
        sessionSeq = seq; summary = sum
        history = msgs.toMutableList()
        chatItems = historyToItems(msgs)
        usage = null
    }

    /** 设置页改 workspace 后调用:当前工程若不在新目录则回空态 */
    fun refreshAfterWorkspaceChange() {
        settings = settingsStore.load()
        val p = project?.info
        if (p == null || !File(File(workspaceRoot(), p.name), PROJECT_META).exists()) {
            project = null; chatItems = emptyList()
        } else {
            openProject(p.name)
        }
    }

    fun importFromSaf(context: android.content.Context, uri: android.net.Uri, onDone: (String) -> Unit) {
        scope.launch {
            runCatching { importProject(context, uri, workspaceRoot()) }
                .onSuccess { name -> openProject(name); onDone(name) }
                .onFailure { toast = "导入失败: ${it.message}" }
        }
    }

    fun exportToSaf(context: android.content.Context, uri: android.net.Uri) {
        val dir = projectDir() ?: run { toast = "没有打开的工程"; return }
        scope.launch {
            runCatching { exportProject(context, uri, dir) }
                .onSuccess { toast = it }
                .onFailure { toast = "导出失败: ${it.message}" }
        }
    }

    fun gitLog(): List<CommitInfo> =
        projectDir()?.let { runCatching { git.log(it) }.getOrDefault(emptyList()) } ?: emptyList()

    fun gitDiff(old: String?, new: String): List<FileDiff> =
        projectDir()?.let { runCatching { git.diff(it, old, new) }.getOrDefault(emptyList()) } ?: emptyList()

    fun setRemote(url: String) {
        val p = project ?: return
        runCatching { setRemote(workspaceRoot(), p.info.name, url, p.info.author, git) }
            .onSuccess { info -> project = ProjectView(info, p.files) }
            .onFailure { toast = "设置失败: ${it.message}" }
    }

    // ---- 对话 ----

    fun send(text: String) {
        if (busy || text.isBlank()) return
        val p = project ?: return
        val s = settings
        if (s.llm.base_url.isBlank() || s.llm.api_key.isBlank()) {
            toast = "请先在设置中填写 API 地址与密钥"
            return
        }
        busy = true
        usage = null
        stopFlag.set(false)
        chatItems = chatItems + ChatItem.User(text)
        val lenBefore = history.size
        val store = SessionStore(sessionsRoot(p.info.name))

        chatJob = scope.launch {
            try {
                val ctx = AgentCtx(
                    llmBaseUrl = s.llm.base_url, llmApiKey = s.llm.api_key, llmModel = s.llm.model,
                    projectName = p.info.name, projectDesc = p.info.desc, author = p.info.author,
                    projectDir = File(workspaceRoot(), p.info.name),
                    summary = summary, totalSoFar = store.totalTokens(),
                    sessionsRoot = sessionsRoot(p.info.name),
                )
                val stats = runConversation(ctx, history, text, stopFlag, git) { ev ->
                    when (ev) {
                        is AgentEvent.Token -> appendToken(ev.text)
                        is AgentEvent.ToolStart -> closeAssistant(removeIfEmpty = true)
                        is AgentEvent.ToolDone -> {
                            chatItems = chatItems + ChatItem.Tool(ev.name, ev.summary.lines().first().take(120))
                            refreshProjectFiles()   // 有固化动作,刷新设定集
                        }
                        is AgentEvent.Done -> closeAssistant(removeIfEmpty = true)
                        is AgentEvent.TurnUsage -> usage = ev.turnTokens to ev.totalTokens
                        is AgentEvent.Error -> chatItems = chatItems + ChatItem.Error(ev.message)
                    }
                }
                // 落盘 + 累计(后端完成,UI 无感)
                store.appendMessages(sessionSeq, history.drop(lenBefore))
                if (stats.turnTotalTokens > 0) runCatching { store.addTokens(stats.turnTotalTokens) }
                // 滚动判断:窗口占用 >= 70% × context_window
                val threshold = maxOf(s.llm.context_window, 4096) * 7 / 10
                if (stats.windowPromptTokens >= threshold) {
                    runCatching { rollSession(ctx, store, history) }.onSuccess { (seq, sum) ->
                        sessionSeq = seq; summary = sum
                        store.loadLatest()?.let { (_, _, msgs) -> history = msgs.toMutableList() }
                    }
                }
            } catch (e: Exception) {
                chatItems = chatItems + ChatItem.Error(e.message ?: e.toString())
            } finally {
                busy = false
            }
        }
    }

    fun stopGen() { stopFlag.set(true) }

    fun clearToast() { toast = null }

    // ---- 内部 ----

    private fun sessionsRoot(name: String) = File(File(filesDir, "sessions"), name)
    private fun projectDir(): File? = project?.let { File(workspaceRoot(), it.info.name) }

    private fun refreshProjectFiles() {
        val p = project ?: return
        val info = runCatching { openProject(workspaceRoot(), p.info.name) }.getOrNull() ?: return
        project = ProjectView(info, readFiles(workspaceRoot(), p.info.name))
    }

    private fun appendToken(text: String) {
        val last = chatItems.lastOrNull()
        chatItems = if (last is ChatItem.Assistant && !last.closed) {
            chatItems.dropLast(1) + last.copy(text = last.text + text)
        } else {
            chatItems + ChatItem.Assistant(text)
        }
    }

    private fun closeAssistant(removeIfEmpty: Boolean) {
        val last = chatItems.lastOrNull() ?: return
        if (last is ChatItem.Assistant && !last.closed) {
            if (removeIfEmpty && last.text.isBlank()) {
                chatItems = chatItems.dropLast(1)
            } else {
                chatItems = chatItems.dropLast(1) + last.copy(closed = true)
            }
        }
    }

    /** 把后端历史消息转成 UI 渲染项(tool 消息只留一行摘要) — 与 legacy historyToItems 一致 */
    private fun historyToItems(messages: List<JsonObject>): List<ChatItem> = messages.mapNotNull { m ->
        val role = m.str("role") ?: return@mapNotNull null
        val content = m.str("content") ?: ""
        when (role) {
            "user" -> ChatItem.User(content)
            "tool" -> ChatItem.Tool("历史工具", content.lines().first().take(120))
            "assistant" -> if (content.isBlank()) null else ChatItem.Assistant(content, closed = true)
            else -> null
        }
    }
}