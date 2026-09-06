package com.yehenowo.gitmind.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// 本地 token 估算(兜底用;主计量以 API 返回的 usage 为准)。
// ponytail: 保守字符估算(中文≈1.2 tok/字、其他≈1 tok/4 字符),结果偏高 -> 滚动更早 -> 安全方向。
// upgrade: 需要精确计数时换 tiktoken o200k 离线词表。

/** 估算一段文本的 token 数(偏保守/偏高) */
fun estimateTokens(text: String): Int {
    var cjk = 0
    var other = 0
    for (c in text) {
        when (c.code) {
            in 0x4e00..0x9fff, in 0x3400..0x4dbf, in 0x3040..0x30ff -> cjk++
            else -> other++
        }
    }
    return (cjk * 1.2 + other / 4.0).toInt() + 4
}

/** 估算一组 OpenAI 格式消息的总 token(system+history+tools 近似) */
fun estimateMessages(messages: List<JsonObject>): Int {
    var total = 0
    for (m in messages) {
        m.str("content")?.let { total += estimateTokens(it) }
        // tool_calls 参数也占 token
        m["tool_calls"]?.jsonArray?.forEach { tc ->
            (tc as? JsonObject)?.get("function")?.let { f ->
                ((f as? JsonObject)?.get("arguments"))?.jsonPrimitive?.contentOrNull?.let {
                    total += estimateTokens(it)
                }
            }
        }
        total += 6 // 每条消息的角色/结构开销
    }
    return total
}