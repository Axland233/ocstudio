package com.yehenowo.gitmind.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// LLM 网络层:调用 OpenAI 兼容的 /chat/completions。
// - chatStream: 流式(SSE),带 usage(stream_options.include_usage)
// - chatSimple: 非流式,用于"总结本会话"等后台任务
// usage 是会话滚动与 token 展示的主计量来源。

/** 累积中的一条 tool_call(stream 模式 delta 按 index 分片到达) */
data class PendingToolCall(
    val id: String = "",
    val name: String = "",
    val arguments: String = "",
)

/** 一轮请求的结果 */
data class TurnResult(
    val content: String,
    val toolCalls: List<PendingToolCall>,
    val usage: Usage?,
)

/** 全局 HTTP 客户端(连接测试要单独超时,临时 clone) */
val HTTP: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(300, TimeUnit.SECONDS)   // LLM 长回复,读要给足
    .build()

/** 修正 base_url:允许用户填带或不带 /v1 的地址 */
fun chatUrl(base: String): String {
    val b = base.trim().trimEnd('/')
    return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
}

/**
 * 发起一轮流式请求。
 * messages: OpenAI 格式历史;tools: 工具定义。
 * 每个增量文本通过 onToken 回调推送;返回完整结果(含 usage)。
 * stop: 置 true 时尽快中断(停止按钮),返回已收到的部分内容。
 */
suspend fun chatStream(
    baseUrl: String,
    apiKey: String,
    model: String,
    messages: List<JsonObject>,
    tools: List<JsonObject>,
    stop: AtomicBoolean,
    onToken: (String) -> Unit,
): TurnResult = withContext(Dispatchers.IO) {
    val body = buildJsonObject {
        put("model", model)
        put("messages", JsonArray(messages))
        put("stream", true)
        // 流式响应默认不带 usage,必须显式请求(最后多一个 usage chunk)
        putJsonObject("stream_options") { put("include_usage", true) }
        if (tools.isNotEmpty()) put("tools", JsonArray(tools))
    }

    val req = okhttp3.Request.Builder()
        .url(chatUrl(baseUrl))
        .header("Authorization", "Bearer $apiKey")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

    val call = HTTP.newCall(req)
    val resp = try {
        call.execute()
    } catch (e: Exception) {
        throw RuntimeException("请求失败: ${e.message}", e)
    }
    resp.use { r ->
        if (!r.isSuccessful) {
            val text = r.body.string()
            throw RuntimeException("API 错误 ${r.code}: ${truncate(text, 300)}")
        }
        var content = ""
        val toolCalls = mutableListOf<PendingToolCall>()
        var finishReason: String? = null
        var usage: Usage? = null

        // SSE 按行读;reader.readLine 阻塞在 IO 线程,停止时靠 call.cancel() 打断
        val reader = r.body.charStream().buffered()
        while (true) {
            if (stop.get()) break
            val line = reader.readLine() ?: break
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("data:")) {
                val data = t.removePrefix("data:").trim()
                if (data == "[DONE]") { finishReason = "stop"; break }
                val v = runCatching { APP_JSON.parseToJsonElement(data) as JsonObject }.getOrNull() ?: continue
                if (usage == null) {
                    v["usage"]?.let { u -> runCatching { APP_JSON.decodeFromJsonElement(Usage.serializer(), u) }.getOrNull()?.let { usage = it } }
                }
                parseDelta(v) { delta -> content += delta; onToken(delta) }?.let { fr -> finishReason = fr }
                // tool_calls 增量累积
                accumulateToolCalls(v, toolCalls)
            }
        }
        TurnResult(content, toolCalls, usage)
    }
}

/** 解析一个 SSE data 的 JSON delta:返回 finish_reason(若有) */
private fun parseDelta(v: JsonObject, onText: (String) -> Unit): String? {
    val choice = (v["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
    var finish: String? = null
    (choice.str("finish_reason"))?.takeIf { it.isNotEmpty() && it != "null" }?.let { finish = it }
    val delta = choice["delta"] as? JsonObject ?: return finish
    delta.str("content")?.takeIf { it.isNotEmpty() }?.let { onText(it) }
    return finish
}

/** 工具调用增量(按 index 累积) */
private fun accumulateToolCalls(v: JsonObject, acc: MutableList<PendingToolCall>) {
    val choice = (v["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
    val delta = choice["delta"] as? JsonObject ?: return
    val calls = delta["tool_calls"] as? JsonArray ?: return
    for (c in calls) {
        val obj = c as? JsonObject ?: continue
        val index = (obj["index"])?.let { runCatching { it.toString().toInt() }.getOrNull() } ?: 0
        while (acc.size <= index) acc.add(PendingToolCall())
        val slot = acc[index]
        val id = obj.str("id")
        val fn = obj["function"] as? JsonObject
        val name = fn?.str("name")
        val args = fn?.str("arguments")
        acc[index] = slot.copy(
            id = slot.id.ifEmpty { id ?: "" },
            name = slot.name.ifEmpty { name ?: "" },
            arguments = slot.arguments + (args ?: ""),
        )
    }
}

/** 非流式调用(后台任务:如"总结本会话")。不做工具、不流式,返回文本 + usage。 */
suspend fun chatSimple(
    baseUrl: String,
    apiKey: String,
    model: String,
    messages: List<JsonObject>,
    maxTokens: Long? = null,
): Pair<String, Usage?> = withContext(Dispatchers.IO) {
    val body = buildJsonObject {
        put("model", model)
        put("messages", JsonArray(messages))
        if (maxTokens != null) put("max_tokens", maxTokens)
    }
    val req = okhttp3.Request.Builder()
        .url(chatUrl(baseUrl))
        .header("Authorization", "Bearer $apiKey")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()
    val resp = HTTP.newCall(req).execute()
    resp.use { r ->
        if (!r.isSuccessful) throw RuntimeException("API 错误 ${r.code}: ${truncate(r.body.string(), 300)}")
        val v = APP_JSON.parseToJsonElement(r.body.string()) as JsonObject
        val content = ((((v["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
            ?.get("message") as? JsonObject)?.str("content")) ?: ""
        val usage = v["usage"]?.let { u -> runCatching { APP_JSON.decodeFromJsonElement(Usage.serializer(), u) }.getOrNull() }
        content to usage
    }
}

/** 测试连接:极小非流式请求,验证 地址/密钥/模型 全链路,错误按类别归因 */
suspend fun testConnection(baseUrl: String, apiKey: String, model: String): TestResult {
    if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
        return TestResult(false, "config", "请先完整填写 API 地址、密钥与模型名称")
    }
    val start = System.currentTimeMillis()
    val client = HTTP.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val body = buildJsonObject {
        put("model", model)
        put("messages", JsonArray(listOf(buildJsonObject {
            put("role", "user"); put("content", "回复\"连接成功\"四个字")
        })))
        put("max_tokens", 16)
        put("stream", false)
    }
    val req = okhttp3.Request.Builder()
        .url(chatUrl(baseUrl))
        .header("Authorization", "Bearer $apiKey")
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

    val resp = try { client.newCall(req).execute() } catch (e: Exception) {
        val kind = when (e) {
            is SocketTimeoutException -> "timeout"
            is UnknownHostException -> "dns"
            else -> "network"
        }
        val hint = when (kind) {
            "timeout" -> "请求超时:服务商无响应或网络不通/需要代理"
            "dns" -> "域名无法解析:检查 API 地址是否拼写正确"
            else -> "无法建立连接:检查网络、API 地址,以及系统是否放行该应用联网"
        }
        return TestResult(false, kind, "${e.message} —— $hint", latency_ms = System.currentTimeMillis() - start)
    }
    resp.use { r ->
        val latency = System.currentTimeMillis() - start
        if (!r.isSuccessful) {
            val text = r.body.string()
            val kind = when (r.code) {
                401, 403 -> "auth"
                404 -> "not_found"
                400 -> "bad_request"
                429 -> "rate_limit"
                else -> if (r.code >= 500) "server" else "http"
            }
            val hint = when (kind) {
                "auth" -> "密钥无效或没有权限:检查 API Key 是否正确、账户是否有该模型的访问权限"
                "not_found" -> "接口或模型不存在:检查 API 地址(通常以 /v1 结尾)与模型名称拼写"
                "bad_request" -> "请求被拒绝:通常是模型名称不正确,请按服务商文档核对"
                "rate_limit" -> "触发限流:请求过于频繁或余额不足,稍后再试"
                "server" -> "服务商服务器错误:稍后再试"
                else -> "请求失败"
            }
            return TestResult(false, kind, "HTTP ${r.code}: ${truncate(text, 200)} —— $hint", latency_ms = latency)
        }
        return try {
            val v = APP_JSON.parseToJsonElement(r.body.string()) as JsonObject
            val reply = ((((v["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)
                ?.get("message") as? JsonObject)?.str("content")) ?: ""
            if (reply.isNotEmpty()) TestResult(true, "ok", "连接成功", reply, latency)
            else TestResult(false, "empty", "连接成功但模型回复为空,请核对模型名称", latency_ms = latency)
        } catch (e: Exception) {
            TestResult(false, "parse", "响应不是有效的 OpenAI 兼容格式: ${e.message} —— 确认 API 地址是否为 /v1/chat/completions 兼容接口", latency_ms = latency)
        }
    }
}

fun truncate(s: String, n: Int): String =
    if (s.length <= n) s else s.take(n) + "…"