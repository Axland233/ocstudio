package com.yehenowo.gitmind.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import com.yehenowo.gitmind.data.JGitRepo
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// Agent 循环:用户消息 -> 流式调 LLM -> 若模型要工具则执行 -> 循环,直到模型不再要工具。
// 全程通过 AgentEvent 流式推给 UI。与 legacy agent/mod.rs 行为一一对应。

/** 推给 UI 的事件(打字机/工具状态/用量) */
sealed class AgentEvent {
    data class Token(val text: String) : AgentEvent()
    data class ToolStart(val name: String) : AgentEvent()
    data class ToolDone(val name: String, val summary: String) : AgentEvent()
    data class Done(val content: String) : AgentEvent()
    data class TurnUsage(val turnTokens: Long, val totalTokens: Long) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
}

/** 一次对话需要的全部上下文 */
class AgentCtx(
    val llmBaseUrl: String,
    val llmApiKey: String,
    val llmModel: String,
    val projectName: String,
    val projectDesc: String,
    val author: String,
    val projectDir: File,
    /** 当前 session 的摘要(滚动产生;注入 system 作记忆锚点) */
    val summary: String?,
    /** 历史累计消耗 token(跨 session;来自 total.json) */
    val totalSoFar: Long,
    /** 历史 session 检索根目录 */
    val sessionsRoot: File,
)

/** 一轮对话的用量统计(供调用方决定是否滚动) */
data class TurnStats(
    val turnTotalTokens: Long = 0,
    val windowPromptTokens: Long = 0,
)

/** 单轮对话最多工具调用轮数(防死循环) */
const val MAX_TURNS = 10

/** 运行一轮对话(用户一条消息)。history 为可变历史,运行后已更新。 */
suspend fun runConversation(
    ctx: AgentCtx,
    history: MutableList<JsonObject>,
    userText: String,
    stop: AtomicBoolean,
    git: JGitRepo,
    onEvent: (AgentEvent) -> Unit,
): TurnStats {
    val system = buildJsonObject {
        put("role", "system")
        put("content", buildSystemPrompt(ctx.projectName, ctx.projectDesc, ctx.author, ctx.summary))
    }
    history.add(buildJsonObject {
        put("role", "user"); put("content", userText)
    })
    val toolCtx = ToolCtx(ctx.projectDir, ctx.author, ctx.sessionsRoot)
    var stats = TurnStats()

    for (turn in 0 until MAX_TURNS) {
        // 停止信号:本轮已在安全点,直接结束(不再发起新请求)
        if (stop.get()) { onEvent(AgentEvent.Done("")); break }
        // system 每轮都带上(实现最简单,与 legacy 一致)
        val messages = listOf(system) + history
        val result = chatStream(ctx.llmBaseUrl, ctx.llmApiKey, ctx.llmModel, messages, toolSpecs(), stop) {
            onEvent(AgentEvent.Token(it))
        }
        result.usage?.let { u ->
            stats = stats.copy(
                turnTotalTokens = stats.turnTotalTokens + u.totalTokens,
                windowPromptTokens = u.promptTokens, // 最后一次请求即当前窗口占用
            )
        }
        // 本轮没有工具调用 -> 对话结束
        if (result.toolCalls.isEmpty()) {
            history.add(buildJsonObject { put("role", "assistant"); put("content", result.content) })
            onEvent(AgentEvent.Done(result.content))
            break
        }
        // 模型要调工具:记录 assistant 消息(tool_calls 原样回传)
        val tcJson = buildJsonArray {
            result.toolCalls.forEach { tc ->
                add(buildJsonObject {
                    put("id", tc.id)
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", tc.name)
                        put("arguments", tc.arguments.ifEmpty { "{}" })
                    })
                })
            }
        }
        history.add(buildJsonObject {
            put("role", "assistant"); put("content", result.content); put("tool_calls", tcJson)
        })
        // 逐个执行工具,结果作为 tool 消息回传
        var stopped = false
        for (tc in result.toolCalls) {
            if (stop.get()) { onEvent(AgentEvent.Done("")); stopped = true; break }
            onEvent(AgentEvent.ToolStart(tc.name))
            // 解析参数 JSON(模型可能给不完整 JSON,容错)
            val args = runCatching {
                APP_JSON.parseToJsonElement(tc.arguments.ifEmpty { "{}" }) as JsonObject
            }.getOrDefault(buildJsonObject { })
            val output = try {
                executeTool(toolCtx, tc.name, args, git)
            } catch (e: Exception) {
                // 工具失败也回传,让模型看到错误自行处理
                "工具执行失败: ${e.message}"
            }
            onEvent(AgentEvent.ToolDone(tc.name, output))
            history.add(buildJsonObject {
                put("role", "tool"); put("tool_call_id", tc.id); put("content", output)
            })
        }
        if (stopped) return stats
        // 循环下一轮,让模型基于工具结果继续
    }

    // 用量事件(bubble 下方灰字);usage 缺失时本地估算兜底
    if (stats.turnTotalTokens == 0L) {
        val est = estimateMessages(history).toLong()
        stats = stats.copy(turnTotalTokens = est, windowPromptTokens = est)
    }
    onEvent(AgentEvent.TurnUsage(stats.turnTotalTokens, ctx.totalSoFar + stats.turnTotalTokens))
    return stats
}

/** 静默滚动:总结当前 session -> 建新 session(摘要 meta + 尾部 3 条原文 seed)。返回 (新 seq, 摘要) */
suspend fun rollSession(ctx: AgentCtx, store: SessionStore, messages: List<JsonObject>): Pair<Long, String> {
    val summarySystem = buildJsonObject {
        put("role", "system")
        put("content", "你是创作助理。总结下面这段创作对话,用要点列出:1)已确定的设定 2)讨论中未定型的想法 3)用户的偏好与行文风格 4)遗留待办/悬而未决的问题。中文,控制在 400 字内,只输出总结本身,不要客套。")
    }
    val (summary, usage) = chatSimple(
        ctx.llmBaseUrl, ctx.llmApiKey, ctx.llmModel,
        listOf(summarySystem) + messages, 800,
    )
    val s = summary.trim()
    // 尾部 3 条原文作 seed(保持语气无缝)
    val seed = messages.takeLast(3)
    val newSeq = store.startNewSession(s, seed)
    // 总结请求的消耗计入总账
    val cost = usage?.totalTokens ?: estimateMessages(messages).toLong()
    if (cost > 0) runCatching { store.addTokens(cost) }
    return newSeq to s
}