//! Agent 循环:用户消息 -> 流式调 LLM -> 若模型要工具则执行 -> 循环,
//! 直到模型不再要工具。全程通过 AgentEvent 流式推给前端。

pub mod llm;
pub mod prompts;
pub mod tools;

use serde::Serialize;
use serde_json::{json, Value};
use std::path::PathBuf;

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
) -> Result<(), String> {
    let system = json!({
        "role": "system",
        "content": prompts::build(&ctx.project_name, &ctx.project_desc, &ctx.author),
    });

    history.push(json!({
        "role": "user",
        "content": user_text,
    }));

    let tool_ctx = tools::ToolCtx {
        project_dir: ctx.project_dir.clone(),
        author: ctx.author.clone(),
    };

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

        // 本轮没有工具调用 -> 对话结束
        if result.tool_calls.is_empty() {
            history.push(json!({
                "role": "assistant",
                "content": result.content,
            }));
            emit(AgentEvent::Done { content: result.content });
            return Ok(());
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

    Err("工具调用轮数超过上限,已终止".into())
}
