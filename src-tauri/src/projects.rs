//! 工程(设定集)管理:每个工程 = workspace 下一个目录,
//! 内含 5 个设定 md + project.json 元数据,目录本身是 git 仓库。

use std::path::{Path, PathBuf};
use serde::{Deserialize, Serialize};

use crate::{gitmod, settings::{self, AppSettings}};

/// 设定集文件白名单(顺序即展示顺序;AI 固化工具只允许写这些)
pub const SETTING_FILES: [&str; 5] = [
    "核心卡.md",
    "人设.md",
    "世界观.md",
    "剧情线.md",
    "脑洞池.md",
];

/// project.json = 工程"身份证":进 git、随工程一起分享,
/// 其他 MindOC App / 用户凭它识别一个目录是否为工程。
pub const PROJECT_META: &str = "project.json";

/// 进入 git 跟踪的全部文件 = 5 个设定文件 + project.json
pub fn git_files() -> Vec<&'static str> {
    let mut v: Vec<&'static str> = SETTING_FILES.to_vec();
    v.push(PROJECT_META);
    v
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProjectInfo {
    pub name: String,
    pub desc: String,
    pub author: String,
    pub created_at: String,
    pub updated_at: String,
    pub path: String, // 绝对路径
    /// 关联的 GitHub 远程仓库(project.json 中,可随工程分享)
    pub remote_url: String,
}

/// 前端一次性要的"当前工程视图"
#[derive(Debug, Clone, Serialize)]
pub struct ProjectView {
    pub info: ProjectInfo,
    pub files: Vec<ProjectFile>, // 5 个 md 内容
}

#[derive(Debug, Clone, Serialize)]
pub struct ProjectFile {
    pub name: String, // 文件名(白名单内)
    pub content: String,
}

// ---------- 模板 ----------

const CORE_CARD_TPL: &str = r#"# 核心卡

## 一句话世界观
(待定)

## 当前进度
(待定)

## 硬规则区(HARD RULES)
(本工程的非常规设定。AI 必须无条件遵守:禁止合理化、禁止遗忘、禁止软化)

## 最近更新记录
- (日期) 初始化工程
"#;

const CHARACTERS_TPL: &str = "# 人设\n\n(角色详细设定。每个角色一个 `## 角色名` 小节)\n";
const WORLD_TPL: &str = "# 世界观\n\n(世界规则。**HARD RULES** 标注的规则最高优先级)\n";
const STORY_TPL: &str = "# 剧情线\n\n## 主线进度\n\n## 已发生事件\n\n## 碎片剧情片段\n(未定型想法也记这里)\n";
const BRAINSTORM_TPL: &str = "# 脑洞池\n\n(未定型想法缓存区,可\"转正\"合并到其他文件)\n";

fn template_for(name: &str) -> &'static str {
    match name {
        "核心卡.md" => CORE_CARD_TPL,
        "人设.md" => CHARACTERS_TPL,
        "世界观.md" => WORLD_TPL,
        "剧情线.md" => STORY_TPL,
        "脑洞池.md" => BRAINSTORM_TPL,
        _ => "",
    }
}

// ---------- 元数据读写 ----------

fn read_meta(project_dir: &Path) -> Result<ProjectInfo, String> {
    let raw = std::fs::read_to_string(project_dir.join(PROJECT_META))
        .map_err(|e| format!("读取工程元数据失败: {e}"))?;
    let meta: ProjectMeta = serde_json::from_str(&raw)
        .map_err(|e| format!("工程元数据损坏: {e}"))?;
    Ok(ProjectInfo {
        name: meta.name,
        desc: meta.desc,
        author: meta.author,
        created_at: meta.created_at,
        updated_at: meta.updated_at,
        path: project_dir.to_string_lossy().to_string(),
        remote_url: meta.github.remote_url,
    })
}

#[derive(Serialize, Deserialize)]
struct ProjectMeta {
    /// App 标识:mindoc。其他 MindOC App 据此识别本目录是工程
    app: String,
    schema_version: u32,
    name: String,
    desc: String,
    author: String,
    created_at: String,
    updated_at: String,
    github: ProjectGitHub,
}

#[derive(Serialize, Deserialize, Default)]
struct ProjectGitHub {
    remote_url: String, // 公开信息,可随工程分享;token 永不进工程文件
}

impl Default for ProjectMeta {
    fn default() -> Self {
        Self {
            app: "mindoc".into(),
            schema_version: 1,
            name: String::new(),
            desc: String::new(),
            author: String::new(),
            created_at: String::new(),
            updated_at: String::new(),
            github: ProjectGitHub::default(),
        }
    }
}

fn meta_to_json(meta: &ProjectMeta) -> Result<String, String> {
    serde_json::to_string_pretty(meta).map_err(|e| e.to_string())
}

fn now_str() -> String {
    // 简单时间戳(不引 chrono;够用即可)
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs().to_string())
        .unwrap_or_default()
}

/// 工程名合法性:仅中文/字母/数字/下划线/空格/短横线,长度 1..=60,禁止路径分隔符
pub fn validate_name(name: &str) -> Result<(), String> {
    let name = name.trim();
    if name.is_empty() {
        return Err("项目名称不能为空".into());
    }
    if name.chars().count() > 60 {
        return Err("项目名称过长(≤60 字符)".into());
    }
    if name.contains(['/', '\\', ':', '*', '?', '"', '<', '>', '|']) {
        return Err("项目名称包含非法字符".into());
    }
    Ok(())
}

/// 新建工程:建目录 + 5 个模板 md + project.json + git init + 首 commit
pub fn create_project(
    app: &tauri::AppHandle,
    settings: &AppSettings,
    name: &str,
    desc: &str,
    author: &str,
) -> Result<ProjectInfo, String> {
    let name = name.trim();
    validate_name(name)?;

    let root = settings::workspace_root(app, settings);
    let project_dir = root.join(name);
    if project_dir.exists() {
        return Err(format!("项目「{name}」已存在,请换一个名称或直接打开它"));
    }
    std::fs::create_dir_all(&project_dir).map_err(|e| e.to_string())?;

    // 写 5 个模板 md
    for file in SETTING_FILES {
        std::fs::write(project_dir.join(file), template_for(file)).map_err(|e| e.to_string())?;
    }

    // 写元数据(project.json = 工程身份证,进 git)
    let now = now_str();
    let mut meta = ProjectMeta::default();
    meta.name = name.to_string();
    meta.desc = desc.trim().to_string();
    meta.author = author.trim().to_string();
    meta.created_at = now.clone();
    meta.updated_at = now.clone();
    std::fs::write(
        project_dir.join(PROJECT_META),
        meta_to_json(&meta).map_err(|e| e.to_string())?,
    )
    .map_err(|e| e.to_string())?;

    // git init + 首 commit(5 个模板 md + project.json)
    gitmod::init_repo(&project_dir, author)?;
    let files = collect_git_files(&project_dir)?;
    gitmod::commit_all(
        &project_dir,
        &files,
        &format!("chore: 初始化设定集「{name}」"),
        author,
    )?;

    read_meta(&project_dir)
}

/// 列出 workspace 下所有工程
pub fn list_projects(app: &tauri::AppHandle, settings: &AppSettings) -> Result<Vec<ProjectInfo>, String> {
    let root = settings::workspace_root(app, settings);
    if !root.exists() {
        return Ok(vec![]);
    }
    let mut out = Vec::new();
    for entry in std::fs::read_dir(&root).map_err(|e| e.to_string())? {
        let entry = entry.map_err(|e| e.to_string())?;
        let dir = entry.path();
        if dir.is_dir() && dir.join(PROJECT_META).exists() {
            if let Ok(info) = read_meta(&dir) {
                out.push(info);
            }
        }
    }
    out.sort_by(|a, b| b.updated_at.cmp(&a.updated_at)); // 最近更新在前
    Ok(out)
}

/// 按名称打开工程(返回 ProjectInfo)
pub fn open_project(app: &tauri::AppHandle, settings: &AppSettings, name: &str) -> Result<ProjectInfo, String> {
    let root = settings::workspace_root(app, settings);
    let dir = root.join(name);
    if !dir.join(PROJECT_META).exists() {
        return Err(format!("工程「{name}」不存在"));
    }
    read_meta(&dir)
}

/// 读取工程全部设定文件(给前端右栏/侧栏展示)
pub fn read_files(app: &tauri::AppHandle, settings: &AppSettings, name: &str) -> Result<Vec<ProjectFile>, String> {
    let root = settings::workspace_root(app, settings);
    let dir = root.join(name);
    let mut out = Vec::new();
    for file in SETTING_FILES {
        let content = std::fs::read_to_string(dir.join(file)).unwrap_or_default();
        out.push(ProjectFile { name: file.to_string(), content });
    }
    Ok(out)
}

/// 工程目录绝对路径(供 agent 工具使用;name 已由 open_project 校验过)
pub fn project_dir(app: &tauri::AppHandle, settings: &AppSettings, name: &str) -> PathBuf {
    settings::workspace_root(app, settings).join(name)
}

// ---------- git 协作辅助 ----------

/// 收集进入 git 的全部文件(5 个设定文件 + project.json)当前内容。
/// commit_all 是全量快照语义,必须传完整文件集,否则未传的文件会在提交里消失。
pub fn collect_git_files(dir: &Path) -> Result<Vec<(String, String)>, String> {
    let mut out = Vec::new();
    for file in git_files() {
        let content = std::fs::read_to_string(dir.join(file)).map_err(|e| e.to_string())?;
        out.push((file.to_string(), content));
    }
    Ok(out)
}

/// 刷新 project.json 的 updated_at(不改其他字段)。不 commit,由调用方随提交落盘。
pub fn touch_updated_at(dir: &Path) -> Result<(), String> {
    let path = dir.join(PROJECT_META);
    let raw = std::fs::read_to_string(&path).map_err(|e| e.to_string())?;
    let mut meta: ProjectMeta =
        serde_json::from_str(&raw).map_err(|e| format!("工程元数据损坏: {e}"))?;
    meta.updated_at = now_str();
    std::fs::write(&path, meta_to_json(&meta)?).map_err(|e| e.to_string())?;
    Ok(())
}

/// 设置工程的 GitHub 远程仓库地址(写入 project.json 并 commit)。
/// token 不进工程文件,由 App 全局配置单独保存。
pub fn set_remote(
    app: &tauri::AppHandle,
    settings: &AppSettings,
    name: &str,
    remote_url: &str,
    author: &str,
) -> Result<ProjectInfo, String> {
    let dir = project_dir(app, settings, name);
    let path = dir.join(PROJECT_META);
    let raw = std::fs::read_to_string(&path).map_err(|e| e.to_string())?;
    let mut meta: ProjectMeta =
        serde_json::from_str(&raw).map_err(|e| format!("工程元数据损坏: {e}"))?;
    meta.github.remote_url = remote_url.trim().to_string();
    meta.updated_at = now_str();
    std::fs::write(&path, meta_to_json(&meta)?).map_err(|e| e.to_string())?;

    let files = collect_git_files(&dir)?;
    let msg = if remote_url.trim().is_empty() {
        "chore: 移除远程仓库配置"
    } else {
        "chore: 配置远程仓库"
    };
    gitmod::commit_all(&dir, &files, msg, author)?;
    read_meta(&dir)
}
