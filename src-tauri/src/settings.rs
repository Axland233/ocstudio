//! App 级配置管理(single JSON 文件,std + serde_json,不引配置库)
//!
//! 存于 app_data_dir/settings.json:
//! - onboarded: 是否完成首次引导
//! - llm: OpenAI 兼容接口配置(用户自填)
//! - theme: 主题色种子 + 深浅模式
//! - github: 远程仓库(备份/同步用)
//! - workspace_dir: 工程根目录(PC 引导时用户选择,可选)

use std::path::PathBuf;
use serde::{Deserialize, Serialize};
use tauri::Manager;

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct LlmConfig {
    pub base_url: String, // 例: https://api.deepseek.com/v1 (不含 /chat/completions)
    pub api_key: String,
    pub model: String,    // 例: deepseek-chat
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ThemeConfig {
    pub mode: String,     // "system" | "light" | "dark"
    pub seed_color: String, // Material 3 主题色种子,如 "#6750A4"
}

impl Default for ThemeConfig {
    fn default() -> Self {
        Self { mode: "system".into(), seed_color: "#6750A4".into() }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct GitHubConfig {
    pub remote_url: String, // https://github.com/user/repo.git
    pub token: String,      // PAT(仅本机存储)
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppSettings {
    pub onboarded: bool,
    pub llm: LlmConfig,
    pub theme: ThemeConfig,
    pub github: GitHubConfig,
    pub workspace_dir: Option<String>,
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            onboarded: false,
            llm: LlmConfig::default(),
            theme: ThemeConfig::default(),
            github: GitHubConfig::default(),
            workspace_dir: None,
        }
    }
}

/// 配置文件路径: app_data_dir/settings.json
pub fn settings_path(app: &tauri::AppHandle) -> PathBuf {
    app.path().app_data_dir().expect("app data dir").join("settings.json")
}

/// 读取配置;文件不存在则返回默认(未引导)
pub fn load(app: &tauri::AppHandle) -> AppSettings {
    let path = settings_path(app);
    std::fs::read_to_string(&path)
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default()
}

/// 保存配置(自动建目录)
pub fn save(app: &tauri::AppHandle, settings: &AppSettings) -> Result<(), String> {
    let path = settings_path(app);
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }
    let json = serde_json::to_string_pretty(settings).map_err(|e| e.to_string())?;
    std::fs::write(&path, json).map_err(|e| e.to_string())
}

/// workspace 根目录:用户未选择时默认 app_data_dir/workspace
pub fn workspace_root(app: &tauri::AppHandle, settings: &AppSettings) -> PathBuf {
    match &settings.workspace_dir {
        Some(dir) if !dir.trim().is_empty() => PathBuf::from(dir),
        _ => app
            .path()
            .app_data_dir()
            .expect("app data dir")
            .join("workspace"),
    }
}
