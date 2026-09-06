package com.yehenowo.gitmind.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// ============ 数据模型:字段名与 legacy Tauri 版(serde snake_case)逐一对齐 ============
// JSONL 会话 / project.json / settings.json 格式保持原样,老数据直接可读。

@Serializable
data class LlmConfig(
    val base_url: String = "",
    val api_key: String = "",
    val model: String = "",
    /** 模型上下文窗口(token);会话滚动阈值 = 70% × 此值 */
    val context_window: Long = 131_072L,
)

@Serializable
data class ThemeConfig(
    val mode: String = "system",          // system | light | dark
    val seed_color: String = "#6750A4",
)

@Serializable
data class GitHubConfig(
    val remote_url: String = "",
    val token: String = "",
)

@Serializable
data class AppSettings(
    val onboarded: Boolean = false,
    val llm: LlmConfig = LlmConfig(),
    val theme: ThemeConfig = ThemeConfig(),
    val github: GitHubConfig = GitHubConfig(),
    val workspace_dir: String? = null,
)

@Serializable
data class ProjectInfo(
    val name: String,
    val desc: String,
    val author: String,
    val created_at: String,
    val updated_at: String,
    val path: String,
    val remote_url: String,
)

@Serializable
data class ProjectMeta(
    val app: String = "mindoc",
    val schema_version: Int = 1,
    val name: String = "",
    val desc: String = "",
    val author: String = "",
    val created_at: String = "",
    val updated_at: String = "",
    val github: ProjectGitHub = ProjectGitHub(),
)

@Serializable
data class ProjectGitHub(val remote_url: String = "")

@Serializable
data class CommitInfo(
    val id: String,
    val message: String,
    val author: String,
    val time_secs: Long,
)

@Serializable
data class DiffLine(
    val kind: String,          // add | del | ctx
    val old_no: Int? = null,
    val new_no: Int? = null,
    val text: String,
)

@Serializable
data class FileDiff(
    val path: String,
    val kind: String,          // A=新增 M=修改 D=删除 R=重命名
    val lines: List<DiffLine>,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Long = 0,
    @SerialName("completion_tokens") val completionTokens: Long = 0,
    @SerialName("total_tokens") val totalTokens: Long = 0,
)

@Serializable
data class TestResult(
    val ok: Boolean,
    val kind: String,          // ok|config|auth|not_found|bad_request|rate_limit|timeout|dns|network|server|http|parse|empty
    val message: String,
    val reply: String? = null,
    val latency_ms: Long = 0,
)

// ============ OpenAI 消息工具(JSONObject 直用,兼容 tool_calls 等动态字段) ============

/** 统一 JSON 配置:忽略未知字段(向前兼容),写出含默认值 */
val APP_JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** 取 JsonObject 字符串字段的小工具 */
fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull