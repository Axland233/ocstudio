//! OC Studio — Tauri 入口 + IPC commands
//!
//! 前端(WebView)通过 invoke 调用这些 command;流式对话走 Channel 事件。
//! 会话体系(用户"无限脑洞画板"哲学):
//!   - 每个工程一个会话目录(app_data/sessions/<工程>),JSONL 持久化,退出不丢
//!   - 用量以 API usage 为准(本地估算兜底),usage ≥ 70% × 窗口时静默滚动:
//!     后端总结本 session -> 开新 session(摘要+最近3条seed),前端无感

mod agent;
mod gitmod;
mod projects;
mod sessions;
mod settings;
mod tokenizer;

use std::path::PathBuf;
use std::sync::Mutex;
use serde::Serialize;
use serde_json::Value;
use tauri::{ipc::Channel, Manager, State};
use tauri_plugin_dialog::DialogExt;

use agent::AgentEvent;
use sessions::SessionStore;
use settings::AppSettings;

/// 当前打开的工程(含当前 session 的消息;切换工程即切换)
#[derive(Clone)]
struct ActiveProject {
    name: String,
    desc: String,
    author: String,
    dir: std::path::PathBuf,
    /// 当前 session 消息(内存缓存;增量写回 jsonl)
    history: Vec<Value>,
    /// 当前 session 序号
    session_seq: u64,
    /// 当前 session 摘要(滚动产生;注入 system)
    summary: Option<String>,
}

struct AppState {
    active: Mutex<Option<ActiveProject>>,
    http: reqwest::Client,
}

/// 会话根目录:app_data/sessions/<工程名>
fn sessions_root(app: &tauri::AppHandle, project: &str) -> PathBuf {
    app.path()
        .app_data_dir()
        .expect("app data dir")
        .join("sessions")
        .join(project)
}

/// 打开/新建工程后:初始化并加载该工程的最新 session 到 active
fn activate_project(
    state: &State<'_, AppState>,
    app: &tauri::AppHandle,
    info: &projects::ProjectInfo,
) -> Result<(), String> {
    let store = SessionStore::new(sessions_root(app, &info.name));
    store.ensure_first()?;
    let (seq, summary, history) = match store.load_latest()? {
        Some((seq, summary, messages)) => (seq, summary, messages),
        None => (1, None, vec![]),
    };
    let mut guard = state.active.lock().map_err(|e| e.to_string())?;
    *guard = Some(ActiveProject {
        name: info.name.clone(),
        desc: info.desc.clone(),
        author: info.author.clone(),
        dir: PathBuf::from(&info.path),
        history,
        session_seq: seq,
        summary,
    });
    Ok(())
}

// ---------------------------------------------------------------- commands

/// 引导/首启状态:返回完整设置(含是否已完成引导)
#[tauri::command]
fn bootstrap(app: tauri::AppHandle) -> Result<AppSettings, String> {
    Ok(settings::load(&app))
}

/// 保存 LLM 配置(base_url/api_key/model/context_window),同时标记引导完成
#[tauri::command]
fn save_llm(app: tauri::AppHandle, llm: settings::LlmConfig) -> Result<AppSettings, String> {
    let mut s = settings::load(&app);
    s.llm = llm;
    s.onboarded = true;
    settings::save(&app, &s)?;
    Ok(s)
}

/// 保存主题(模式 + 主题色种子)
#[tauri::command]
fn save_theme(app: tauri::AppHandle, theme: settings::ThemeConfig) -> Result<AppSettings, String> {
    let mut s = settings::load(&app);
    s.theme = theme;
    settings::save(&app, &s)?;
    Ok(s)
}

/// 保存 GitHub token(App 全局;remote_url 在工程级 project.json 里)
#[tauri::command]
fn save_github_token(app: tauri::AppHandle, token: String) -> Result<AppSettings, String> {
    let mut s = settings::load(&app);
    s.github.token = token;
    settings::save(&app, &s)?;
    Ok(s)
}

/// PC:弹系统目录选择器选 workspace 根目录;移动端由 SAF 授权流程替代
#[tauri::command]
async fn pick_workspace(app: tauri::AppHandle) -> Result<Option<String>, String> {
    #[cfg(not(mobile))]
    {
        let app2 = app.clone();
        let picked = tokio::task::spawn_blocking(move || {
            app2.dialog().file().blocking_pick_folder()
        })
        .await
        .map_err(|e| format!("选择目录失败: {e}"))?;
        if let Some(path) = picked {
            if let Some(pb) = path.as_path() {
                let mut s = settings::load(&app);
                s.workspace_dir = Some(pb.to_string_lossy().to_string());
                settings::save(&app, &s)?;
                return Ok(Some(s.workspace_dir.unwrap()));
            }
        }
        Ok(None)
    }
    #[cfg(mobile)]
    {
        Err("移动端请使用系统目录授权".into())
    }
}

/// 新建工程(自动 git init + 首 commit),创建后自动设为当前工程
#[tauri::command]
fn create_project(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    name: String,
    desc: String,
    author: String,
) -> Result<projects::ProjectInfo, String> {
    let s = settings::load(&app);
    let info = projects::create_project(&app, &s, &name, &desc, &author)?;
    activate_project(&state, &app, &info)?;
    Ok(info)
}

/// 工程列表(按最近更新排序)
#[tauri::command]
fn list_projects(app: tauri::AppHandle) -> Result<Vec<projects::ProjectInfo>, String> {
    let s = settings::load(&app);
    projects::list_projects(&app, &s)
}

/// 打开工程(设为当前,恢复最新 session),返回完整视图
#[tauri::command]
fn open_project(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    name: String,
) -> Result<projects::ProjectView, String> {
    let s = settings::load(&app);
    let info = projects::open_project(&app, &s, &name)?;
    activate_project(&state, &app, &info)?;
    let files = projects::read_files(&app, &s, &name)?;
    Ok(projects::ProjectView { info, files })
}

/// 当前工程完整视图(未打开工程则 Err,前端据此回列表/引导)
#[tauri::command]
fn current_project(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
) -> Result<Option<projects::ProjectView>, String> {
    let Some(active) = state.active.lock().map_err(|e| e.to_string())?.clone() else {
        return Ok(None);
    };
    let s = settings::load(&app);
    let info = projects::open_project(&app, &s, &active.name)?;
    let files = projects::read_files(&app, &s, &active.name)?;
    Ok(Some(projects::ProjectView { info, files }))
}

/// 历史消息(打开工程后前端拉取渲染当前 session)
#[derive(Serialize)]
struct ChatMsgView {
    role: String,
    content: String,
}

#[tauri::command]
fn chat_history(state: State<'_, AppState>) -> Result<Vec<ChatMsgView>, String> {
    let active = require_active(&state)?;
    let mut out = Vec::new();
    for m in &active.history {
        let role = m.get("role").and_then(|r| r.as_str()).unwrap_or("?").to_string();
        let content = m.get("content").and_then(|c| c.as_str()).unwrap_or("");
        if content.trim().is_empty() {
            continue; // 纯工具调用的 assistant 消息不展示
        }
        // tool 消息是给模型看的原始结果(可能很长),只留首行给 UI
        let view_content = if role == "tool" {
            content.lines().next().unwrap_or("").chars().take(120).collect()
        } else {
            content.to_string()
        };
        out.push(ChatMsgView { role, content: view_content });
    }
    Ok(out)
}

/// 当前工程 git 提交历史
#[tauri::command]
fn git_log(state: State<'_, AppState>, limit: Option<usize>) -> Result<Vec<gitmod::CommitInfo>, String> {
    let active = require_active(&state)?;
    gitmod::log(&active.dir, limit.unwrap_or(50))
}

/// 两个提交之间的完整 diff(文件级 + 逐行;old=None 表示空树)
#[tauri::command]
fn git_diff(
    state: State<'_, AppState>,
    old: Option<String>,
    new: String,
) -> Result<Vec<gitmod::FileDiff>, String> {
    let active = require_active(&state)?;
    gitmod::diff(&active.dir, old.as_deref(), &new)
}

/// 设置当前工程关联的 GitHub 远程仓库(写 project.json 并 commit)
#[tauri::command]
fn set_remote(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    remote_url: String,
) -> Result<projects::ProjectInfo, String> {
    let s = settings::load(&app);
    let active = require_active(&state)?;
    let info = projects::set_remote(&app, &s, &active.name, &remote_url, &active.author)?;
    activate_project(&state, &app, &info)?;
    Ok(info)
}

/// 发送一条用户消息给 agent,事件经 channel 流式回前端。
/// 结束后:消息落盘、token 累计、必要时静默滚动 session(全部后端完成)
#[tauri::command]
async fn chat_send(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    channel: Channel<AgentEvent>,
    text: String,
) -> Result<(), String> {
    let s = settings::load(&app);
    if s.llm.base_url.trim().is_empty() || s.llm.api_key.trim().is_empty() {
        return Err("请先在设置中填写 API 地址与密钥".into());
    }
    let context_window = s.llm.context_window.max(4096); // 防手滑填太小

    // 快照当前工程上下文(锁内取,锁外 await)
    let active = require_active(&state)?;
    let store = SessionStore::new(sessions_root(&app, &active.name));
    let mut history = {
        let mut guard = state.active.lock().map_err(|e| e.to_string())?;
        guard.as_mut().unwrap().take_history()
    };
    let len_before = history.len();
    let session_seq = active.session_seq;
    let summary = active.summary.clone();

    let ctx = agent::AgentCtx {
        llm_base_url: s.llm.base_url,
        llm_api_key: s.llm.api_key,
        llm_model: s.llm.model,
        project_name: active.name.clone(),
        project_desc: active.desc.clone(),
        author: active.author.clone(),
        project_dir: active.dir.clone(),
        summary,
        total_so_far: store.total_tokens(),
        sessions_root: store.root().to_path_buf(),
    };

    let emit = move |ev: AgentEvent| {
        let _ = channel.send(ev);
    };

    let stats = agent::run_conversation(&state.http, &ctx, &mut history, &text, &emit).await?;

    // ---- 落盘 + 累计(后端完成,前端无感) ----
    store.append_messages(session_seq, &history[len_before..])?;
    if stats.turn_total_tokens > 0 {
        let _ = store.add_tokens(stats.turn_total_tokens);
    }

    // ---- 滚动判断:窗口占用 ≥ 70% × context_window ----
    let mut new_seq = session_seq;
    let mut new_summary = ctx.summary.clone();
    let roll_threshold = (context_window * 7) / 10;
    if stats.window_prompt_tokens >= roll_threshold {
        if let Ok((seq, sum)) = agent::roll_session(&state.http, &ctx, &store, &history).await {
            new_seq = seq;
            new_summary = Some(sum);
            // 内存 history 与文件对齐 = 新 session 内容(meta+seed)
            if let Ok(Some((_, _, msgs))) = store.load_latest() {
                history = msgs;
            }
        }
    }

    // 写回 active(锁内短操作)
    if let Ok(mut guard) = state.active.lock() {
        if let Some(a) = guard.as_mut() {
            a.session_seq = new_seq;
            a.summary = new_summary;
            a.restore_history(history);
        }
    }
    let _ = app;
    Ok(())
}

// ---------------------------------------------------------------- helpers

fn require_active(state: &State<'_, AppState>) -> Result<ActiveProject, String> {
    state
        .active
        .lock()
        .map_err(|e| e.to_string())?
        .clone()
        .ok_or_else(|| "没有打开的工程".into())
}

impl ActiveProject {
    fn take_history(&mut self) -> Vec<Value> {
        std::mem::take(&mut self.history)
    }
    fn restore_history(&mut self, h: Vec<Value>) {
        self.history = h;
    }
}

// ---------------------------------------------------------------- entry

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .manage(AppState {
            active: Mutex::new(None),
            http: reqwest::Client::new(),
        })
        .invoke_handler(tauri::generate_handler![
            bootstrap,
            save_llm,
            save_theme,
            save_github_token,
            pick_workspace,
            create_project,
            list_projects,
            open_project,
            current_project,
            chat_history,
            git_log,
            git_diff,
            set_remote,
            chat_send,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
