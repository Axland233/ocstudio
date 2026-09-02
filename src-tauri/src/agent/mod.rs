//! Agent 循环:用户消息 -> 流式调 LLM -> 若模型要工具则执行 -> 循环,
//! 直到模型不再要工具。全程通过 AgentEvent 流式推给前端。
//!
//! 用量计量:以 API usage 为准(见 llm.rs);本地估算仅兜底。
//! Session 滚动(总结+开新 session)在 roll_session 中静默完成,前端无感。

pub mod llm;
pub mod prompts;
pub mod tools;

use serde::Serialize;
use serde_json::{json, Value};
use std::path::PathBuf;

use crate::sessions::SessionStore;
use llm::{Usage, chat_simple};

/// 推给前端的事件(tauri Channel 载荷)
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum AgentEvent {
    /// 模型正文增量(前端流式追加)
    Token { text: String },
    /// 开始执行工具
    ToolStart { name: String },
    /// 工具执行完毕(前端此时刷新 git 历史/设定集)
    ToolDone { name: String, summary: String },
    /// 整轮对话结束(可能因多轮工具,无最终正文)
    Done { content: String },
    /// 本轮用量汇报(气泡下方灰字;turn=本轮,total=含历史累计)
    TurnUsage { turn_tokens: u64, total_tokens: u64 },
    /// 出错终止
    #[allow(dead_code)] // 预留:流式中断时推给前端;当前错误走 invoke Err 返回
    Error { message: String },
}

/// 一次对话需要的全部上下文
pub struct AgentCtx {
    pub llm_base_url: String,
    pub llm_api_key: String,
    pub llm_model: String,
    pub project_name: String,
    pub project_desc: String,
    pub author: String,
    pub project_dir: PathBuf,
    /// 当前 session 的摘要(滚动产生;注入 system 作记忆锚点)
    pub summary: Option<String>,
    /// 历史累计消耗 token(跨 session;来自 total.json)
    pub total_so_far: u64,
    /// 历史 session 检索根目录
    pub sessions_root: PathBuf,
}

/// 一轮对话的用量统计(供调用方决定是否滚动)
#[derive(Debug, Default, Clone, Copy)]
pub struct TurnStats {
    /// 本轮所有请求的 total_tokens 之和(计费口径)
    pub turn_total_tokens: u64,
    /// 最后一次请求的输入 token = 当前窗口占用(滚动判断依据)
    pub window_prompt_tokens: u64,
}

/// 单轮对话最多工具调用轮数(防死循环)
const MAX_TURNS: usize = 10;

/// 运行一轮对话(用户一条消息)。history 为可变历史,运行后已更新。
pub async fn run_conversation(
    http: &reqwest::Client,
    ctx: &AgentCtx,
    history: &mut Vec<Value>,
    user_text: &str,
    emit: &impl Fn(AgentEvent),
) -> Result<TurnStats, String> {
    let system = json!({
        "role": "system",
        "content": prompts::build(&ctx.project_name, &ctx.project_desc, &ctx.author, ctx.summary.as_deref()),
    });

    history.push(json!({
        "role": "user",
        "content": user_text,
    }));

    let tool_ctx = tools::ToolCtx {
        project_dir: ctx.project_dir.clone(),
        author: ctx.author.clone(),
        sessions_root: ctx.sessions_root.clone(),
    };

    let mut stats = TurnStats::default();

    for _turn in 0..MAX_TURNS {
        // 组装 messages:system 在每轮都带上(工程信息可能变化,且实现最简单)
        let mut messages = vec![system.clone()];
        messages.extend(history.iter().cloned());

        let result = llm::chat_stream(
            http,
            &ctx.llm_base_url,
            &ctx.llm_api_key,
            &ctx.llm_model,
            messages,
            tools::tool_specs(),
            |ev| emit(ev),
        )
        .await?;

        // 用量统计(API 没返回 usage 时为 0,由调用方用本地估算兜底)
        if let Some(u) = result.usage {
            stats.turn_total_tokens += u.total_tokens;
            stats.window_prompt_tokens = u.prompt_tokens; // 最后一次请求即当前占用
        }

        // 本轮没有工具调用 -> 对话结束
        if result.tool_calls.is_empty() {
            history.push(json!({
                "role": "assistant",
                "content": result.content,
            }));
            emit(AgentEvent::Done { content: result.content });
            break;
        }

        // 模型要调工具:记录 assistant 消息(tool_calls 原样回传)
        let tool_calls_json: Vec<Value> = result
            .tool_calls
            .iter()
            .map(|tc| {
                json!({
                    "id": tc.id,
                    "type": "function",
                    "function": {
                        "name": tc.name,
                        "arguments": if tc.arguments.is_empty() { "{}" } else { &tc.arguments },
                    }
                })
            })
            .collect();
        history.push(json!({
            "role": "assistant",
            "content": result.content, // 可能为空字符串
            "tool_calls": tool_calls_json,
        }));

        // 逐个执行工具,结果作为 tool 消息回传
        for tc in &result.tool_calls {
            emit(AgentEvent::ToolStart { name: tc.name.clone() });

            // 解析参数 JSON(模型可能给不完整 JSON,容错)
            let args: Value = serde_json::from_str(&tc.arguments).unwrap_or_else(|_| json!({}));
            let output = match tools::execute(&tool_ctx, &tc.name, &args) {
                Ok(text) => text,
                Err(e) => {
                    // 工具失败也回传,让模型看到错误自行处理
                    format!("工具执行失败: {e}")
                }
            };
            emit(AgentEvent::ToolDone {
                name: tc.name.clone(),
                summary: output.clone(),
            });
            history.push(json!({
                "role": "tool",
                "tool_call_id": tc.id,
                "content": output,
            }));
        }
        // 循环下一轮,让模型基于工具结果继续
    }

    // 用量事件(气泡下方灰字用);usage 缺失时用本地估算兜底
    if stats.turn_total_tokens == 0 {
        let est: u64 = crate::tokenizer::estimate_messages(history) as u64;
        stats.turn_total_tokens = est;
        stats.window_prompt_tokens = est;
    }
    emit(AgentEvent::TurnUsage {
        turn_tokens: stats.turn_total_tokens,
        total_tokens: ctx.total_so_far + stats.turn_total_tokens,
    });
    Ok(stats)
}

/// 静默滚动:总结当前 session -> 建新 session(摘要 meta + 尾部 3 条原文 seed)。
/// 全部后端完成,不产生任何前端事件。返回 (新 session 序号, 摘要)。
/// summary_tokens 计入历史累计(total.json)。
pub async fn roll_session(
    http: &reqwest::Client,
    ctx: &AgentCtx,
    store: &SessionStore,
    messages: &[Value],
) -> Result<(u64, String), String> {
    let summary_system = json!({
        "role": "system",
        "content": "你是创作助理。总结下面这段创作对话,用要点列出:1)已确定的设定 2)讨论中未定型的想法 3)用户的偏好与行文风格 4)遗留待办/悬而未决的问题。中文,控制在 400 字内,只输出总结本身,不要客套。",
    });
    let mut msgs = vec![summary_system];
    msgs.extend(messages.iter().cloned());

    let (summary, usage) = chat_simple(
        http,
        &ctx.llm_base_url,
        &ctx.llm_api_key,
        &ctx.llm_model,
        msgs,
        Some(800),
    )
    .await?;
    let summary = summary.trim().to_string();

    // 尾部 3 条原文作 seed(保持语气无缝)
    let seed: Vec<Value> = messages.iter().rev().take(3).rev().cloned().collect();
    let new_seq = store.start_new_session(&summary, &seed)?;

    // 总结请求的消耗计入总账
    let cost = usage
        .map(|u: Usage| u.total_tokens)
        .unwrap_or_else(|| crate::tokenizer::estimate_messages(messages) as u64);
    if cost > 0 {
        let _ = store.add_tokens(cost);
    }

    Ok((new_seq, summary))
}
