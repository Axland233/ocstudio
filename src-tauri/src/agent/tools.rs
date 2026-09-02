//! Agent 工具:模型通过 function calling 调用这些工具完成"固化"。
//!
//! 安全边界:文件路径是强白名单(SETTING_FILES),模型无法读写工程目录之外
//! 或非设定文件 —— 这是把 oc-worldbuilder 的固化协议变成可执行代码的地方。

use std::path::PathBuf;
use serde_json::{json, Value};

use crate::{gitmod, projects};

pub const TOOL_READ: &str = "read_project_file";
pub const TOOL_WRITE: &str = "write_project_file";

/// 向模型声明的工具 schema(OpenAI function calling 格式)
pub fn tool_specs() -> Vec<Value> {
    vec![
        json!({
            "type": "function",
            "function": {
                "name": TOOL_READ,
                "description": "读取设定集文件内容(path 只能是:核心卡.md、人设.md、世界观.md、剧情线.md、脑洞池.md 之一)。需要了解设定先读再写。",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "path": { "type": "string", "description": "设定文件名" }
                    },
                    "required": ["path"]
                }
            }
        }),
        json!({
            "type": "function",
            "function": {
                "name": TOOL_WRITE,
                "description": "把新设定固化写入设定集文件并自动 git commit(path 只能是五个设定文件之一;content 为文件完整新内容,不是补丁,写入前请先 read 原文件再改动)。commit_msg 用主题化格式,如:feat(人设): 新增角色 X 的怕光设定。",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "path": { "type": "string", "description": "设定文件名" },
                        "content": { "type": "string", "description": "文件完整新内容(markdown)" },
                        "commit_msg": { "type": "string", "description": "本次固化的提交信息" }
                    },
                    "required": ["path", "content", "commit_msg"]
                }
            }
        }),
    ]
}

/// Agent 上下文:当前工程信息
pub struct ToolCtx {
    pub project_dir: PathBuf,
    pub author: String,
}

/// 校验文件名在白名单内(防穿越/防写非设定文件)
fn check_path(path: &str) -> Result<String, String> {
    let name = path.trim().trim_start_matches('/');
    if name.contains('/') || name.contains('\\') || name.contains("..") {
        return Err(format!("非法路径: {path}"));
    }
    if !projects::SETTING_FILES.contains(&name) {
        return Err(format!(
            "只能写入设定文件({}),不允许: {name}",
            projects::SETTING_FILES.join("、")
        ));
    }
    Ok(name.to_string())
}

/// 执行工具调用。返回给模型看的文本结果(会作为 tool 消息回传)。
pub fn execute(ctx: &ToolCtx, name: &str, args: &Value) -> Result<String, String> {
    match name {
        TOOL_READ => {
            let path = args
                .get("path")
                .and_then(|p| p.as_str())
                .ok_or("缺少 path 参数")?;
            let name = check_path(path)?;
            let content =
                std::fs::read_to_string(ctx.project_dir.join(&name)).map_err(|e| e.to_string())?;
            Ok(format!("【{name}】\n{content}"))
        }
        TOOL_WRITE => {
            let path = args
                .get("path")
                .and_then(|p| p.as_str())
                .ok_or("缺少 path 参数")?;
            let content = args
                .get("content")
                .and_then(|c| c.as_str())
                .ok_or("缺少 content 参数")?;
            let commit_msg = args
                .get("commit_msg")
                .and_then(|c| c.as_str())
                .unwrap_or("chore: 更新设定")
                .trim();
            let name = check_path(path)?;

            // 1) 写入文件
            std::fs::write(ctx.project_dir.join(&name), content).map_err(|e| e.to_string())?;

            // 2) 刷新 project.json 的 updated_at
            projects::touch_updated_at(&ctx.project_dir)?;

            // 3) 全量快照提交(设定文件 + project.json)
            let files = projects::collect_git_files(&ctx.project_dir)?;
            let hash = gitmod::commit_all(&ctx.project_dir, &files, commit_msg, &ctx.author)?;

            Ok(format!(
                "已固化到 {name} 并提交(commit {hash}):{commit_msg}"
            ))
        }
        _ => Err(format!("未知工具: {name}")),
    }
}
