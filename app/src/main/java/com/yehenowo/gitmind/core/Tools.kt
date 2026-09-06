package com.yehenowo.gitmind.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import com.yehenowo.gitmind.data.JGitRepo
import com.yehenowo.gitmind.data.touchUpdatedAt
import com.yehenowo.gitmind.data.collectGitFiles
import java.io.File

// Agent 工具:模型通过 function calling 调用这些工具完成"固化"。
// 安全边界:文件路径是强白名单(SETTING_FILES),模型无法读写工程目录之外或非设定文件。

const val TOOL_READ = "read_project_file"
const val TOOL_WRITE = "write_project_file"
const val TOOL_SEARCH = "search_history"

val SETTING_FILES = listOf("核心卡.md", "人设.md", "世界观.md", "剧情线.md", "脑洞池.md")
const val PROJECT_META = "project.json"

/** 工具上下文 */
class ToolCtx(
    val projectDir: File,
    val author: String,
    val sessionsRoot: File,
)

/** 向模型声明的工具 schema(OpenAI function calling 格式) */
fun toolSpecs(): List<JsonObject> = listOf(
    buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", TOOL_READ)
            put("description", "读取设定集文件内容(path 只能是:核心卡.md、人设.md、世界观.md、剧情线.md、脑洞池.md 之一)。需要了解设定先读再写。")
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject { put("type", "string"); put("description", "设定文件名") })
                })
                put("required", buildJsonArray { })
            })
        })
    },
    buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", TOOL_WRITE)
            put("description", "把新设定固化写入设定集文件并自动 git commit(path 只能是五个设定文件之一;content 为文件完整新内容,不是补丁,写入前请先 read 原文件再改动)。commit_msg 用主题化格式,如:feat(人设): 新增角色 X 的怕光设定。")
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject { put("type", "string"); put("description", "设定文件名") })
                    put("content", buildJsonObject { put("type", "string"); put("description", "文件完整新内容(markdown)") })
                    put("commit_msg", buildJsonObject { put("type", "string"); put("description", "本次固化的提交信息") })
                })
                put("required", buildJsonArray { })
            })
        })
    },
    buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", TOOL_SEARCH)
            put("description", "在历史对话中检索之前聊过的内容(用户提到很久之前讨论的细节、当前上下文看不到时用)。关键词尽量用用户的原话,会自动分词匹配。")
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("query", buildJsonObject { put("type", "string"); put("description", "想找的细节描述或关键词") })
                })
                put("required", buildJsonArray { })
            })
        })
    },
)

/** 校验文件名在白名单内(防穿越/防写非设定文件) */
fun checkPath(path: String): String {
    val name = path.trim().trimStart('/')
    if (name.contains('/') || name.contains('\\') || name.contains("..")) throw IllegalArgumentException("非法路径: $path")
    if (name !in SETTING_FILES) throw IllegalArgumentException("只允许访问设定文件:${SETTING_FILES.joinToString("、")},收到: $name")
    return name
}

/** 执行工具调用。返回给模型看的文本结果(作为 tool 消息回传);抛异常时由调用方转错误文本。 */
fun executeTool(ctx: ToolCtx, name: String, args: JsonObject, git: JGitRepo): String = when (name) {
    TOOL_READ -> {
        val fileName = checkPath(args.str("path") ?: throw IllegalArgumentException("缺少 path 参数"))
        val content = File(ctx.projectDir, fileName).readText()
        "【$fileName】\n$content"
    }
    TOOL_WRITE -> {
        val fileName = checkPath(args.str("path") ?: throw IllegalArgumentException("缺少 path 参数"))
        val content = args.str("content") ?: throw IllegalArgumentException("缺少 content 参数")
        val commitMsg = (args.str("commit_msg") ?: "chore: 更新设定").trim()
        File(ctx.projectDir, fileName).writeText(content)
        touchUpdatedAt(ctx.projectDir)
        val hash = git.commitAll(ctx.projectDir, collectGitFiles(ctx.projectDir), commitMsg, ctx.author)
        "已固化到 $fileName 并提交(commit $hash):$commitMsg"
    }
    TOOL_SEARCH -> {
        val query = args.str("query") ?: throw IllegalArgumentException("缺少 query 参数")
        val hits = SessionStore(ctx.sessionsRoot).search(query, 5)
        if (hits.isEmpty()) "历史对话中没有检索到相关内容,可尝试其他关键词,或读取设定集文件确认。"
        else "检索到 ${hits.size} 条相关历史(按相关度排序):\n\n${hits.joinToString("\n\n---\n\n")}"
    }
    else -> throw IllegalArgumentException("未知工具: $name")
}