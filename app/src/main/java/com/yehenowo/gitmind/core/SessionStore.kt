package com.yehenowo.gitmind.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

// Session 存储与滚动(全部后端完成,UI 不感知 session 边界)。
// 布局与 legacy 完全一致:
//   sessions/<project>/0001.jsonl  每行一条 OpenAI 消息;首行可为 {"type":"meta","summary":...}
//   sessions/<project>/total.json  {"tokens": N} 跨 session 累计消耗

class SessionStore(private val root: File) {

    private fun seqPath(seq: Long) = File(root, "%04d.jsonl".format(seq))
    private fun totalFile() = File(root, "total.json")

    private fun ensureDir() { root.mkdirs() }

    /** 当前最新 session 序号(无则 null) */
    fun latestSeq(): Long? {
        val files = root.listFiles { f -> f.name.endsWith(".jsonl") } ?: return null
        return files.mapNotNull { it.name.removeSuffix(".jsonl").toLongOrNull() }.maxOrNull()
    }

    /** 建首个 session(空文件);已存在则忽略 */
    fun ensureFirst(): Long {
        latestSeq()?.let { return it }
        ensureDir()
        seqPath(1).writeText("")
        return 1
    }

    /** 读指定 session:返回 (summary, messages) */
    fun readSession(seq: Long): Pair<String?, List<JsonObject>> {
        var summary: String? = null
        val messages = mutableListOf<JsonObject>()
        val raw = seqPath(seq).readText()
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            runCatching { APP_JSON.parseToJsonElement(t) as JsonObject }.getOrNull()?.let { v ->
                if (v.str("type") == "meta") {
                    summary = v.str("summary")
                } else {
                    messages.add(v)
                }
            }
        }
        return summary to messages
    }

    /** 加载最新 session(无则 null) */
    fun loadLatest(): Triple<Long, String?, List<JsonObject>>? {
        val seq = latestSeq() ?: return null
        val (summary, messages) = readSession(seq)
        return Triple(seq, summary, messages)
    }

    /** 向指定 session 追加消息 */
    fun appendMessages(seq: Long, messages: List<JsonObject>) {
        if (messages.isEmpty()) return
        ensureDir()
        seqPath(seq).appendText(messages.joinToString("") { APP_JSON.encodeToString(JsonObject.serializer(), it) + "\n" })
    }

    /** 开新 session:seq+1,首行 meta(summary),随后 seed 消息(旧 session 尾部 3 条) */
    fun startNewSession(summary: String, seed: List<JsonObject>): Long {
        val seq = (latestSeq() ?: 0L) + 1
        ensureDir()
        val meta = buildJsonObject {
            put("type", "meta")
            put("summary", summary)
        }
        val sb = StringBuilder(APP_JSON.encodeToString(JsonObject.serializer(), meta) + "\n")
        for (m in seed) sb.append(APP_JSON.encodeToString(JsonObject.serializer(), m) + "\n")
        seqPath(seq).writeText(sb.toString())
        return seq
    }

    // ---------- 累计 token(跨 session/重启) ----------

    fun totalTokens(): Long = runCatching {
        (APP_JSON.parseToJsonElement(totalFile().readText()) as JsonObject).str("tokens")?.toLong()
    }.getOrNull() ?: 0L

    fun addTokens(n: Long) {
        val newTotal = totalTokens() + n
        ensureDir()
        totalFile().writeText("{\"tokens\":$newTotal}\n")
    }

    // ---------- 历史检索(search_history 工具用) ----------

    /** 在历史 session 里关键词检索,返回命中的消息片段文本(带会话语境) */
    fun search(query: String, limit: Int = 5): List<String> {
        val terms = splitTerms(query)
        if (terms.isEmpty()) return emptyList()
        val maxSeq = latestSeq() ?: return emptyList()
        data class Hit(val score: Int, val text: String)
        val hits = mutableListOf<Hit>()
        for (seq in 1..maxSeq) {
            val (_, messages) = runCatching { readSession(seq) }.getOrNull() ?: continue
            // 整段文本便于带上上下文返回
            val texts = messages.mapNotNull { m ->
                val role = m.str("role") ?: "?"
                val content = m.str("content") ?: ""
                if (content.isEmpty()) null else "[$role] $content"
            }
            texts.forEachIndexed { i, text ->
                val score = terms.count { text.contains(it) }
                if (score > 0) {
                    val block = buildList {
                        if (i > 0) add(texts[i - 1])
                        add(text)
                        if (i + 1 < texts.size) add(texts[i + 1])
                    }
                    hits.add(Hit(score, "【历史会话 $seq】\n${block.joinToString("\n")}"))
                }
            }
        }
        return hits.sortedByDescending { it.score }.take(limit).map { it.text }
    }
}

/** 简易分词:英文/数字按 3+ 字符单词;中文按 2-gram(无分词库的务实召回) */
fun splitTerms(query: String): List<String> {
    val terms = mutableListOf<String>()
    val cjk = StringBuilder()
    val asciiWord = StringBuilder()
    fun flushAscii() {
        if (asciiWord.length >= 3) {
            val w = asciiWord.toString().lowercase()
            if (w !in terms) terms.add(w)
        }
        asciiWord.clear()
    }
    for (c in query) {
        if (c.code in 0x4e00..0x9fff) {
            flushAscii(); cjk.append(c)
        } else if (c.isLetterOrDigit() && c.code < 0x80) {
            asciiWord.append(c)
        } else {
            flushAscii()
        }
    }
    flushAscii()
    if (cjk.length >= 2) {
        for (i in 0 until cjk.length - 1) {
            val t = cjk.substring(i, i + 2)
            if (t !in terms) terms.add(t)
        }
    }
    return terms
}