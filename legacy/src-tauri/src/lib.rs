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
use std::sync::{Arc, Mutex};
use serde::Serialize;
use serde_json::Value;
use tauri::{ipc::Channel, Manager, State};
use tauri_plugin_dialog::DialogExt;

use agent::AgentEvent;
use sessions::SessionStore;
use settings::AppSettings;

use agent::llm;

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
    /// 对话停止信号:chat_stop 置位,chat_send 开始时复位(跨进程共享)
    stop: Arc<std::sync::atomic::AtomicBool>,
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

/// 当前 workspace 根目录(用户设置的或默认位置),供设置页展示
#[tauri::command]
fn workspace_path(app: tauri::AppHandle) -> Result<String, String> {
    let s = settings::load(&app);
    Ok(settings::workspace_root(&app, &s).to_string_lossy().to_string())
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
        // Android:默认 workspace 在 app 私有目录(app_data_dir/workspace),
        // 该目录本应用可直接读写,无需任何授权。返回它并保存,即"重置为默认目录"。
        let mut s = settings::load(&app);
        let default = app
            .path()
            .app_data_dir()
            .map_err(|e| e.to_string())?
            .join("workspace");
        s.workspace_dir = Some(default.to_string_lossy().to_string());
        settings::save(&app, &s)?;
        Ok(Some(s.workspace_dir.unwrap()))
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

    let stats =
        agent::run_conversation(&state.http, &ctx, &mut history, &text, state.stop.clone(), &emit).await?;

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

/// 测试大模型连接:用当前(或传入)配置发一条极小请求,验证 地址/密钥/模型 全链路
#[tauri::command]
async fn test_connection(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    base_url: Option<String>,
    api_key: Option<String>,
    model: Option<String>,
) -> Result<llm::TestResult, String> {
    let s = settings::load(&app);
    // 未传则用已保存配置(设置页传当前表单值,便于保存前先测)
    let base = base_url.filter(|b| !b.trim().is_empty()).unwrap_or(s.llm.base_url);
    let key = api_key.filter(|k| !k.trim().is_empty()).unwrap_or(s.llm.api_key);
    let mdl = model.filter(|m| !m.trim().is_empty()).unwrap_or(s.llm.model);
    if base.trim().is_empty() || key.trim().is_empty() || mdl.trim().is_empty() {
        return Ok(llm::TestResult {
            ok: false,
            kind: "config".into(),
            message: "请先完整填写 API 地址、密钥与模型名称".into(),
            reply: None,
            latency_ms: 0,
        });
    }
    Ok(llm::test_connection(&state.http, &base, &key, &mdl).await)
}

/// 停止当前对话:置位停止信号,流式循环在最近安全点中断
#[tauri::command]
fn chat_stop(state: State<'_, AppState>) -> Result<(), String> {
    state
        .stop
        .store(true, std::sync::atomic::Ordering::Relaxed);
    Ok(())
}

// ---------------------------------------------------------------- helpers

/// 构建全局 HTTP 客户端(Android 规避 rustls-platform-verifier 的 JVM 初始化依赖)
fn build_http_client() -> reqwest::Client {
    #[cfg(target_os = "android")]
    {
        // reqwest 默认 TLS 验证走 rustls-platform-verifier,在 Android 上需要向 JVM
        // 注册初始化(Tauri 不做) -> 请求线程 panic,invoke 永不返回,
        // 症状:无任何网络请求、无报错、永远"思考中"(reqwest#2966)。
        // 修复:自建 rustls ClientConfig,根证书用内置 Mozilla CA(webpki-roots,
        // 纯 Rust TrustAnchor,不需要 JVM),再以 use_preconfigured_tls 注入,
        // 完全绕开 platform-verifier 分支。
        use rustls::RootCertStore;

        let mut roots = RootCertStore::empty();
        roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());

        let config = rustls::ClientConfig::builder()
            .with_root_certificates(roots)
            .with_no_client_auth();

        reqwest::Client::builder()
            .use_preconfigured_tls(config)
            .build()
            .expect("failed to build http client")
    }
    #[cfg(not(target_os = "android"))]
    {
        reqwest::Client::new()
    }
}

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

/// 全局 AppHandle(JNI 静态导出里 emit 事件用;Android 专用)
#[cfg(target_os = "android")]
static APP_HANDLE: std::sync::OnceLock<tauri::AppHandle> = std::sync::OnceLock::new();

/// Android 返回键 JNI 导出:MainActivity.onBackPressed -> nativeBackPress()
/// -> emit "back-press" 给前端(前端决定关浮层或调 back_exit 退出)
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "C" fn Java_com_ocstudio_app_MainActivity_nativeBackPress(
    _env: jni::JNIEnv,
    _class: jni::objects::JClass,
) {
    if let Some(handle) = APP_HANDLE.get() {
        use tauri::Emitter;
        let _ = handle.emit("back-press", ());
    }
}

/// 前端确认退出(主页按返回时调用):结束 Activity
#[tauri::command]
fn back_exit(app: tauri::AppHandle) -> Result<(), String> {
    app.exit(0);
    Ok(())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .manage(AppState {
            active: Mutex::new(None),
            // Android 上 reqwest 0.13 默认走 rustls-platform-verifier,
            // 需要向 JVM 注册初始化(Tauri 不做) -> 请求线程 panic 且 invoke 永不返回,
            // 症状:无任何网络请求、无报错、永远"思考中"(reqwest#2966)。
            // 修复:Android 用内置 Mozilla CA(webpki-roots)+ tls_certs_only,不碰 JVM;
            // 桌面端保持默认(平台证书验证)。
            http: build_http_client(),
            stop: Arc::new(std::sync::atomic::AtomicBool::new(false)),
        })
        .invoke_handler(tauri::generate_handler![
            bootstrap,
            save_llm,
            save_theme,
            save_github_token,
            pick_workspace,
            workspace_path,
            create_project,
            list_projects,
            open_project,
            current_project,
            chat_history,
            git_log,
            git_diff,
            set_remote,
            chat_send,
            chat_stop,
            test_connection,
            back_exit,
        ])
        .setup(|app| {
            // Android 返回键:MainActivity.onBackPressed 直接经 JNI 调用
            // ocstudio_lib 的静态导出 nativeBackPress -> emit "back-press" 给前端。
            // 这里只负责把 AppHandle 存入全局(JNI 导出里 emit 用)。
            #[cfg(target_os = "android")]
            {
                let _ = APP_HANDLE.set(app.handle().clone());
            }
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
