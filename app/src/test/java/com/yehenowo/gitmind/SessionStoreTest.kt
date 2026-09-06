package com.yehenowo.gitmind

import com.yehenowo.gitmind.core.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.File

// 平移自 legacy sessions.rs 的测试:JSONL 往返 + 滚动 + 累计 + 检索(含中文 2-gram 分词)
class SessionStoreTest {

    @Test
    fun session_roundtrip_and_roll() {
        val root = File(System.getProperty("java.io.tmpdir"), "gitmind-test-${System.currentTimeMillis()}")
        val store = SessionStore(root)

        store.ensureFirst()
        assertEquals(1L, store.latestSeq())

        store.appendMessages(1, listOf(
            buildJsonObject { put("role", "user"); put("content", "我想让主角怕光") },
            buildJsonObject { put("role", "assistant"); put("content", "好的,已记下:主角怕光") },
            buildJsonObject { put("role", "user"); put("content", "再给他加个设定:他会做奇怪的梦") },
        ))

        // 滚动:总结 + 尾部 3 条 seed
        val seed = store.readSession(1).second.takeLast(3)
        val newSeq = store.startNewSession("主角怕光;会做奇怪的梦", seed)
        assertEquals(2L, newSeq)

        val (summary, messages) = store.readSession(2)
        assertTrue(summary!!.contains("怕光"))
        assertEquals(3, messages.size)

        // 累计 token
        store.addTokens(123)
        store.addTokens(77)
        assertEquals(200L, store.totalTokens())

        // 检索:跨 session 命中旧细节(中文 2-gram)
        val hits = store.search("主角怕光的设定", 3)
        assertTrue(hits.any { it.contains("怕光") })

        root.deleteRecursively()
    }

    @Test
    fun terms_ok() {
        val t = splitTerms("主角怕光的名字叫alice")
        assertTrue(t.contains("alice"))
        assertTrue(t.contains("怕光"))
    }

    @Test
    fun tokenizer_estimate() {
        // 中文 14 字 * 1.2 = 16.8 -> 16,+4 固定开销 = 20(与 legacy 同口径)
        assertEquals(20, estimateTokens("这是一段中文设定文本来测试的"))
        assertEquals(4, estimateTokens(""))
    }
}