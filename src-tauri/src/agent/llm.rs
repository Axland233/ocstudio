//! LLM 网络层:调用 OpenAI 兼容的 /chat/completions。
//! - chat_stream: 流式(SSE),带 usage(stream_options.include_usage)
//! - chat_simple: 非流式,用于"总结本会话"等后台任务
//!
//! usage 是会话滚动与 token 展示的主计量来源。

use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use crate::agent::AgentEvent;

/// 单次请求的 token 用量(OpenAI 兼容 usage 字段)
#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize)]
pub struct Usage {
    pub prompt_tokens: u64,     // 输入(含历史/system/tools)= 窗口占用
    pub completion_tokens: u64, // 输出
    pub total_tokens: u64,      // 计费量 = prompt + completion
}

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
    /// 本次请求的真实用量(服务商返回才 Some)
    pub usage: Option<Usage>,
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
/// messages: OpenAI 格式历史消息;tools: 工具定义。
/// 每个增量文本通过 `emit` 回调推送;函数返回完整结果(含 usage)。
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
        // 流式响应默认不带 usage,必须显式请求(最后多一个 usage chunk)
        "stream_options": { "include_usage": true },
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
    let mut usage: Option<Usage> = None;

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
                        // usage chunk:choices 为空但带 usage
                        if usage.is_none() {
                            usage = v
                                .get("usage")
                                .and_then(|u| serde_json::from_value::<Usage>(u.clone()).ok());
                        }
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

    Ok(TurnResult {
        content,
        tool_calls,
        usage,
    })
}

/// 非流式调用(后台任务:如"总结本会话")。不做工具、不流式,返回文本 + usage。
pub async fn chat_simple(
    client: &reqwest::Client,
    base_url: &str,
    api_key: &str,
    model: &str,
    messages: Vec<Value>,
    max_tokens: Option<u64>,
) -> Result<(String, Option<Usage>), String> {
    let mut body = json!({
        "model": model,
        "messages": messages,
    });
    if let Some(mt) = max_tokens {
        body["max_tokens"] = json!(mt);
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

    let v: Value = resp.json().await.map_err(|e| format!("解析响应失败: {e}"))?;
    let content = v
        .pointer("/choices/0/message/content")
        .and_then(|c| c.as_str())
        .unwrap_or_default()
        .to_string();
    let usage = v
        .get("usage")
        .and_then(|u| serde_json::from_value::<Usage>(u.clone()).ok());
    Ok((content, usage))
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
                tool_calls.push(PendingToolCall {
                    index: tool_calls.len(),
                    ..Default::default()
                });
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
