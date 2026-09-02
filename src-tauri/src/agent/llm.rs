//! LLM 网络层:调用 OpenAI 兼容的 /chat/completions(stream=true),
//! 解析 SSE 流,把增量文本与 tool_calls 片段累积出来。
//!
//! 不依赖官方 SDK——协议就是一次 POST + SSE 行解析,自己写最薄。

use futures_util::StreamExt;
use serde_json::{json, Value};

use crate::agent::AgentEvent;

/// 累积中的一条 tool_call(stream 模式 delta 按 index 分片到达)
#[derive(Debug, Clone, Default)]
pub struct PendingToolCall {
    #[allow(dead_code)] // 仅用于对齐 stream 分片顺序
    pub index: usize,
    pub id: String,
    pub name: String,
    pub arguments: String,
}

/// 一轮请求的结果
#[derive(Debug)]
pub struct TurnResult {
    /// 模型回复的正文(可能为空,当轮是纯工具调用)
    pub content: String,
    /// 模型要调用的工具(空 = 本轮结束)
    pub tool_calls: Vec<PendingToolCall>,
}

/// 修正 base_url:允许用户填带或不带 /v1 的地址
fn chat_url(base: &str) -> String {
    let base = base.trim().trim_end_matches('/');
    if base.ends_with("/chat/completions") {
        base.to_string()
    } else {
        format!("{base}/chat/completions")
    }
}

/// 发起一轮流式请求。
/// messages: OpenAI 格式历史消息(含 tools 与 tool 结果);tools: 工具定义。
/// 每个增量文本通过 `emit` 回调推送(供 Channel 转发);函数返回完整结果。
pub async fn chat_stream(
    client: &reqwest::Client,
    base_url: &str,
    api_key: &str,
    model: &str,
    messages: Vec<Value>,
    tools: Vec<Value>,
    mut emit: impl FnMut(AgentEvent),
) -> Result<TurnResult, String> {
    let mut body = json!({
        "model": model,
        "messages": messages,
        "stream": true,
    });
    if !tools.is_empty() {
        body["tools"] = Value::Array(tools);
    }

    let resp = client
        .post(chat_url(base_url))
        .bearer_auth(api_key)
        .json(&body)
        .send()
        .await
        .map_err(|e| format!("请求失败: {e}"))?;

    if !resp.status().is_success() {
        let status = resp.status();
        let text = resp.text().await.unwrap_or_default();
        return Err(format!("API 错误 {status}: {}", truncate(&text, 300)));
    }

    let mut stream = resp.bytes_stream();
    let mut buffer: Vec<u8> = Vec::new();

    let mut content = String::new();
    let mut tool_calls: Vec<PendingToolCall> = Vec::new();
    let mut finish_reason: Option<String> = None;

    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| format!("读取流失败: {e}"))?;
        buffer.extend_from_slice(&chunk);

        // SSE 按行切分;行可能跨 chunk,只处理完整行,留尾部
        let mut consumed = 0;
        for (i, b) in buffer.iter().enumerate() {
            if *b == b'\n' {
                let line = &buffer[consumed..i];
                consumed = i + 1;
                let line = String::from_utf8_lossy(line).trim().to_string();
                if line.is_empty() {
                    continue;
                }
                if let Some(data) = line.strip_prefix("data:") {
                    let data = data.trim();
                    if data == "[DONE]" {
                        finish_reason = Some("stop".into());
                        break;
                    }
                    if let Ok(v) = serde_json::from_str::<Value>(data) {
                        parse_delta(&v, &mut emit, &mut content, &mut tool_calls, &mut finish_reason);
                    }
                }
            }
        }
        if consumed > 0 {
            buffer.drain(..consumed);
        }
        if finish_reason.is_some() {
            break;
        }
    }

    Ok(TurnResult { content, tool_calls })
}

/// 解析一个 SSE data 的 JSON delta
fn parse_delta(
    v: &Value,
    emit: &mut impl FnMut(AgentEvent),
    content: &mut String,
    tool_calls: &mut Vec<PendingToolCall>,
    finish_reason: &mut Option<String>,
) {
    let Some(choice) = v.get("choices").and_then(|c| c.get(0)) else {
        return;
    };
    if let Some(reason) = choice.get("finish_reason").and_then(|f| f.as_str()) {
        if !reason.is_empty() && reason != "null" {
            *finish_reason = Some(reason.to_string());
        }
    }
    let Some(delta) = choice.get("delta") else { return };

    // 正文增量
    if let Some(text) = delta.get("content").and_then(|c| c.as_str()) {
        if !text.is_empty() {
            content.push_str(text);
            emit(AgentEvent::Token { text: text.to_string() });
        }
    }

    // 工具调用增量(按 index 累积)
    if let Some(calls) = delta.get("tool_calls").and_then(|c| c.as_array()) {
        for call in calls {
            let index = call.get("index").and_then(|i| i.as_u64()).unwrap_or(0) as usize;
            while tool_calls.len() <= index {
                tool_calls.push(PendingToolCall { index: tool_calls.len(), ..Default::default() });
            }
            let slot = &mut tool_calls[index];
            if let Some(id) = call.get("id").and_then(|i| i.as_str()) {
                if slot.id.is_empty() {
                    slot.id = id.to_string();
                }
            }
            if let Some(f) = call.get("function") {
                if let Some(name) = f.get("name").and_then(|n| n.as_str()) {
                    if slot.name.is_empty() {
                        slot.name = name.to_string();
                    }
                }
                if let Some(args) = f.get("arguments").and_then(|a| a.as_str()) {
                    slot.arguments.push_str(args);
                }
            }
        }
    }
}

fn truncate(s: &str, n: usize) -> String {
    if s.chars().count() <= n {
        s.to_string()
    } else {
        s.chars().take(n).collect::<String>() + "…"
    }
}
