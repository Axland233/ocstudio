//! git 引擎封装(gix / gitoxide 0.87)
//!
//! 本工程仓库结构是扁平的:仓库根目录直接放几个设定 md + project.json,
//! 没有子目录 —— 因此 commit 不需要维护 index,直接:
//!   写 blob -> 组装 tree -> 写 tree -> 写 commit -> 更新 HEAD 引用
//! (这正是 gitoxide 官方 example 的做法,绕开了 index 的低层 API)
//!
//! push/fetch:gitoxide 0.87 尚未提供客户端 push 高层 API,原型阶段暂不实现,
//! 后续方案见 README(git2 或 GitHub contents API)。

use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};
use serde::Serialize;
use gix::{objs::tree, objs::Tree, ObjectId};

/// 一条提交记录(前端 git 历史列表展示用)
#[derive(Debug, Clone, Serialize)]
pub struct CommitInfo {
    pub id: String,       // 短 hash(前 8 位)
    pub message: String,  // 首行
    pub author: String,
    pub time_secs: i64,   // unix 秒,前端格式化
}

/// 单行变更(逐行 diff)
#[derive(Debug, Clone, Serialize)]
pub struct DiffLine {
    pub kind: String, // "add" | "del" | "ctx"
    pub old_no: Option<usize>,
    pub new_no: Option<usize>,
    pub text: String,
}

/// 单文件 diff:变更类型 + 逐行
#[derive(Debug, Clone, Serialize)]
pub struct FileDiff {
    pub path: String,
    pub kind: String, // A=新增 M=修改 D=删除
    pub lines: Vec<DiffLine>,
}

/// 两个提交之间的完整 diff(文件级 + 逐行)。
/// old: 旧提交短 hash;None = 空树(查看首个提交的全部内容)
/// new: 新提交短 hash
pub fn diff(
    repo_path: &Path,
    old: Option<&str>,
    new: &str,
) -> Result<Vec<FileDiff>, String> {
    let repo = gix::open(repo_path).map_err(|e| format!("打开仓库失败: {e}"))?;

    // 解析 new
    let new_id = resolve_id(&repo, new)?;
    let new_tree_oid = repo
        .find_commit(new_id)
        .map_err(|e| format!("找不到提交 {new}: {e}"))?
        .tree_id()
        .map_err(|e| format!("读取 tree 失败: {e}"))?
        .detach();
    let new_tree = repo
        .find_tree(new_tree_oid)
        .map_err(|e| format!("加载 tree 失败: {e}"))?;

    // 解析 old(空则用 empty tree)
    let old_tree: Option<gix::Tree<'_>> = match old {
        Some(old_id_str) => {
            let old_id = resolve_id(&repo, old_id_str)?;
            let tid = repo
                .find_commit(old_id)
                .map_err(|e| format!("找不到提交 {old_id_str}: {e}"))?
                .tree_id()
                .map_err(|e| format!("读取 old tree 失败: {e}"))?
                .detach();
            Some(
                repo.find_tree(tid)
                    .map_err(|e| format!("加载 old tree 失败: {e}"))?,
            )
        }
        None => None,
    };

    let changes = repo
        .diff_tree_to_tree(old_tree.as_ref(), Some(&new_tree), None)
        .map_err(|e| format!("diff 失败: {e}"))?;

    use gix::object::tree::diff::ChangeDetached;

    let mut out = Vec::new();
    for ch in changes {
        // 变更类型必须 match 变体本身:
        // relation() 对 Modification 恒返回 None,不能用作类型判断(曾导致显示 "?")
        let (kind, location, old_oid, new_oid): (&str, gix::bstr::BString, Option<gix::ObjectId>, Option<gix::ObjectId>) =
            match ch {
                ChangeDetached::Addition { location, id, .. } => ("A", location, None, Some(id)),
                ChangeDetached::Deletion { location, id, .. } => ("D", location, Some(id), None),
                ChangeDetached::Modification {
                    location,
                    previous_id,
                    id,
                    ..
                } => ("M", location, Some(previous_id), Some(id)),
                ChangeDetached::Rewrite {
                    location,
                    source_id,
                    id,
                    ..
                } => ("R", location, Some(source_id), Some(id)),
            };
        let path = String::from_utf8_lossy(&location).into_owned();

        let old_text = blob_text(&repo, old_oid)?;
        let new_text = blob_text(&repo, new_oid)?;

        let mut lines = Vec::new();
        let td = similar::TextDiff::from_lines(&old_text, &new_text);
        for chg in td.iter_all_changes() {
            let (lkind, old_no, new_no) = match chg.tag() {
                similar::ChangeTag::Delete => ("del", chg.old_index(), None),
                similar::ChangeTag::Insert => ("add", None, chg.new_index()),
                similar::ChangeTag::Equal => ("ctx", chg.old_index(), chg.new_index()),
            };
            lines.push(DiffLine {
                kind: lkind.to_string(),
                old_no: old_no.map(|i| i + 1),
                new_no: new_no.map(|i| i + 1),
                text: chg.value().trim_end_matches(['\n', '\r']).to_string(),
            });
        }

        out.push(FileDiff {
            path,
            kind: kind.to_string(),
            lines,
        });
    }
    Ok(out)
}

/// 按 oid 读取 blob 内容(作为 UTF-8 文本;None 视为空)
fn blob_text(repo: &gix::Repository, oid: Option<gix::ObjectId>) -> Result<String, String> {
    match oid {
        None => Ok(String::new()),
        Some(id) => {
            let obj = repo
                .find_object(id)
                .map_err(|e| format!("读取对象失败: {e}"))?;
            let blob = obj.into_blob();
            Ok(String::from_utf8_lossy(&blob.data).into_owned())
        }
    }
}

fn short(id: &ObjectId) -> String {
    id.to_hex().to_string().chars().take(8).collect()
}

/// 在 path 初始化一个非 bare 仓库,并写入作者身份(commit 必需)
pub fn init_repo(path: &Path, author_name: &str) -> Result<(), String> {
    let _ = author_name; // 身份在每次 commit_as 时显式传入,init 无需配置
    gix::init(path).map_err(|e| format!("git init 失败: {e}"))?;
    Ok(())
}

/// 把一批文件(文件名 + 内容)整体提交为一个 commit。
/// files 必须覆盖仓库内"应被跟踪"的全部文件(扁平、无目录)。
/// 返回新 commit 的短 hash。
pub fn commit_all(
    repo_path: &Path,
    files: &[(String, String)],
    message: &str,
    author_name: &str,
) -> Result<String, String> {
    let repo = gix::open(repo_path).map_err(|e| format!("打开仓库失败: {e}"))?;

    // 1) 每个文件写为 blob
    let mut entries: Vec<tree::Entry> = Vec::with_capacity(files.len());
    for (name, content) in files {
        let blob_id = repo
            .write_blob(content.as_bytes())
            .map_err(|e| format!("写 blob {name} 失败: {e}"))?
            .detach();
        entries.push(tree::Entry {
            mode: tree::EntryKind::Blob.into(),
            oid: blob_id,
            filename: name.clone().into(),
        });
    }
    // git 要求 tree 的 entry 按文件名排序(否则其他实现读不了)
    entries.sort_by(|a, b| a.filename.cmp(&b.filename));

    // 2) 写 tree
    let tree_id = repo
        .write_object(&Tree { entries })
        .map_err(|e| format!("写 tree 失败: {e}"))?
        .detach();

    // 3) 父提交 = 当前 HEAD(首 commit 无父)
    let parents: Vec<ObjectId> = match repo.head_id() {
        Ok(head) => vec![head.detach()],
        Err(_) => vec![],
    };

    // 4) 写 commit 并更新 HEAD(身份显式传入,不依赖 config/环境)
    let now_secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let time_raw = format!("{now_secs} +0000"); // gix 的 SignatureRef.time 是原始字符串
    let email = format!("{}@mindoc.local", author_name.trim());
    let name_ref: &gix::bstr::BStr = author_name.trim().into();
    let email_ref: &gix::bstr::BStr = email.as_str().into();
    let committer = gix::actor::SignatureRef {
        name: name_ref,
        email: email_ref,
        time: &time_raw,
    };
    let author = gix::actor::SignatureRef {
        name: name_ref,
        email: email_ref,
        time: &time_raw,
    };
    let commit_id = repo
        .commit_as(committer, author, "HEAD", message, tree_id, parents)
        .map_err(|e| format!("写 commit 失败: {e}"))?
        .detach();

    Ok(short(&commit_id))
}

/// 提交历史(新 -> 旧),limit 条
pub fn log(repo_path: &Path, limit: usize) -> Result<Vec<CommitInfo>, String> {
    let repo = gix::open(repo_path).map_err(|e| format!("打开仓库失败: {e}"))?;
    let head = match repo.head_id() {
        Ok(h) => h,
        Err(_) => return Ok(vec![]), // 尚无提交
    };
    let walk = repo
        .rev_walk([head])
        .all()
        .map_err(|e| format!("遍历历史失败: {e}"))?;

    let mut out = Vec::new();
    for item in walk.take(limit) {
        let info = item.map_err(|e| format!("读取提交失败: {e}"))?;
        let commit = info.object().map_err(|e| format!("读取提交对象失败: {e}"))?;
        // message() 返回 MessageRef,title 即首行(BStr)
        let first_line = commit
            .message()
            .map(|m| String::from_utf8_lossy(m.title.as_ref()).into_owned())
            .unwrap_or_default();
        let author = commit
            .author()
            .map(|a| String::from_utf8_lossy(a.name.as_ref()).into_owned())
            .unwrap_or_default();
        // 提交时间优先取 committer 的
        let time_secs = commit
            .committer()
            .ok()
            .and_then(|s| s.time().ok())
            .map(|t| t.seconds)
            .unwrap_or(0);
        out.push(CommitInfo {
            id: short(&info.id),
            message: first_line,
            author,
            time_secs,
        });
    }
    Ok(out)
}

/// 解析短 hash 或完整 hash 为 ObjectId(在仓库内查找)
fn resolve_id(repo: &gix::Repository, id_str: &str) -> Result<ObjectId, String> {
    // 短 hash:gix 用 rev_parse 系列;这里简单实现:按前缀在引用可达对象里找。
    // 原型简化:先试完整解析;短 hash 通过遍历 HEAD 历史匹配前缀。
    if let Ok(oid) = ObjectId::from_hex(id_str.as_bytes()) {
        return Ok(oid);
    }
    // 前缀匹配:遍历 HEAD 历史
    if let Ok(head) = repo.head_id() {
        let walk = repo.rev_walk([head]).all().map_err(|e| e.to_string())?;
        for item in walk {
            let info = item.map_err(|e| e.to_string())?;
            let hex = info.id.to_hex().to_string();
            if hex.starts_with(id_str) {
                return Ok(info.id);
            }
        }
    }
    Err(format!("找不到提交: {id_str}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 端到端闭环:init -> commit -> log -> diff(不依赖外部 git)
    #[test]
    fn commit_log_diff_roundtrip() {
        let dir = std::env::temp_dir().join("mindoc-git-test");
        let _ = std::fs::remove_dir_all(&dir);

        init_repo(&dir, "测试者").unwrap();
        let files = vec![
            ("核心卡.md".to_string(), "# 一句话世界观\n\n(待定)".to_string()),
            ("project.json".to_string(), "{}".to_string()),
        ];
        let c1 = commit_all(&dir, &files, "chore: 初始化设定集", "测试者").unwrap();
        assert_eq!(c1.len(), 8);

        let log1 = log(&dir, 10).unwrap();
        assert_eq!(log1.len(), 1);
        assert_eq!(log1[0].id, c1);
        assert!(log1[0].message.contains("初始化"));
        assert_eq!(log1[0].author, "测试者");

        // 修改一个文件再提交(全量快照:两个文件都要在)
        let files2 = vec![
            ("核心卡.md".to_string(), "# 一句话世界观\n\n雾海之城".to_string()),
            ("project.json".to_string(), "{}".to_string()),
        ];
        let c2 = commit_all(&dir, &files2, "feat(核心卡): 定下世界观", "测试者").unwrap();
        let log2 = log(&dir, 10).unwrap();
        assert_eq!(log2.len(), 2);
        assert_eq!(log2[0].id, c2);
        assert_eq!(log2[1].id, c1);

        // c1 -> c2 应包含 核心卡.md 的修改,且类型为 M,有逐行内容
        let d = diff(&dir, Some(&c1), &c2).unwrap();
        let target = d.iter().find(|f| f.path == "核心卡.md").expect("diff 应包含 核心卡.md");
        assert_eq!(target.kind, "M");
        assert!(
            target.lines.iter().any(|l| l.kind == "add" && l.text.contains("雾海之城")),
            "逐行 diff 应包含新增行,实际: {:?}",
            target.lines.iter().filter(|l| l.kind != "ctx").collect::<Vec<_>>()
        );

        // 首个提交 diff(空树 -> c1)应包含全部文件,kind = A
        let d0 = diff(&dir, None, &c1).unwrap();
        assert_eq!(d0.len(), 2);
        assert!(d0.iter().all(|f| f.kind == "A"));

        let _ = std::fs::remove_dir_all(&dir);
    }
}
