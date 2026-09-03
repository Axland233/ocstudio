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
/// stop: 置为 true 时尽快中断(停止按钮),返回已收到的部分内容。
pub async fn chat_stream(
    client: &reqwest::Client,
    base_url: &str,
    api_key: &str,
    model: &str,
    messages: Vec<Value>,
    tools: Vec<Value>,
    stop: &std::sync::atomic::AtomicBool,
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
        // 停止信号:尽快退出流读取,保留已收到的内容
        if stop.load(std::sync::atomic::Ordering::Relaxed) {
            break;
        }
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

/// 连接测试结果(前端据此展示诊断)
#[derive(Debug, Serialize)]
pub struct TestResult {
    pub ok: bool,
    /// 分类:ok | auth(密钥无效) | not_found(地址/模型名不对) | timeout | network(无法连接) | server
    pub kind: String,
    pub message: String,
    /// 模型实际回复(验证 key/模型/推理全链路,ok 时有值)
    pub reply: Option<String>,
    pub latency_ms: u64,
}

/// 测试用户填的 LLM 配置:发一条极小的非流式请求,验证 地址/密钥/模型 全链路。
/// 用 max_tokens=16 控制成本,错误按类别归因,给出可操作的提示。
pub async fn test_connection(
    client: &reqwest::Client,
    base_url: &str,
    api_key: &str,
    model: &str,
) -> TestResult {
    let start = std::time::Instant::now();
    let body = json!({
        "model": model,
        "messages": [{"role": "user", "content": "回复\"连接成功\"四个字"}],
        "max_tokens": 16,
        "stream": false,
    });

    let resp = match client
        .post(chat_url(base_url))
        .bearer_auth(api_key)
        .timeout(std::time::Duration::from_secs(30))
        .json(&body)
        .send()
        .await
    {
        Ok(r) => r,
        Err(e) => {
            let msg = e.to_string();
            let kind = if msg.contains("timed out") || msg.contains("timeout") {
                "timeout"
            } else if msg.contains("dns") || msg.contains("resolve") || msg.contains("Name or service") {
                "dns"
            } else {
                "network"
            };
            let hint = match kind {
                "timeout" => "请求超时:服务商无响应或网络不通/需要代理",
                "dns" => "域名无法解析:检查 API 地址是否拼写正确",
                _ => "无法建立连接:检查网络、API 地址,以及系统是否放行该应用联网",
            };
            return TestResult { ok: false, kind: kind.into(), message: format!("{msg} —— {hint}"), reply: None, latency_ms: start.elapsed().as_millis() as u64 };
        }
    };

    let status = resp.status();
    let latency = start.elapsed().as_millis() as u64;
    if !status.is_success() {
        let text = resp.text().await.unwrap_or_default();
        let kind = match status.as_u16() {
            401 | 403 => "auth",
            404 => "not_found",
            400 => "bad_request",
            429 => "rate_limit",
            _ => if status.is_server_error() { "server" } else { "http" },
        };
        let hint = match kind {
            "auth" => "密钥无效或没有权限:检查 API Key 是否正确、账户是否有该模型的访问权限",
            "not_found" => "接口或模型不存在:检查 API 地址(通常以 /v1 结尾)与模型名称拼写",
            "bad_request" => "请求被拒绝:通常是模型名称不正确,请按服务商文档核对",
            "rate_limit" => "触发限流:请求过于频繁或余额不足,稍后再试",
            "server" => "服务商服务器错误:稍后再试",
            _ => "请求失败",
        };
        return TestResult { ok: false, kind: kind.into(), message: format!("HTTP {status}: {} —— {hint}", truncate(&text, 200)), reply: None, latency_ms: latency };
    }

    // 成功:提取模型回复
    match resp.json::<Value>().await {
        Ok(v) => {
            let reply = v
                .pointer("/choices/0/message/content")
                .and_then(|c| c.as_str())
                .unwrap_or_default()
                .to_string();
            let ok = !reply.is_empty();
            TestResult {
                ok,
                kind: if ok { "ok".into() } else { "empty".into() },
                message: if ok { "连接成功".into() } else { "连接成功但模型回复为空,请核对模型名称".into() },
                reply: if ok { Some(reply) } else { None },
                latency_ms: latency,
            }
        }
        Err(e) => TestResult {
            ok: false,
            kind: "parse".into(),
            message: format!("响应不是有效的 OpenAI 兼容格式: {e} —— 确认 API 地址是否为 /v1/chat/completions 兼容接口"),
            reply: None,
            latency_ms: latency,
        },
    }
}

fn truncate(s: &str, n: usize) -> String {
    if s.chars().count() <= n {
        s.to_string()
    } else {
        s.chars().take(n).collect::<String>() + "…"
    }
}
