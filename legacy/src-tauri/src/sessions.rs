//! Session 存储与滚动(全部后端无感,前端不感知 session 边界)
//!
//! 存储布局(不进 git、不随工程分享,属于用户私有工作数据):
//!   app_data/sessions/<project>/
//!     0001.jsonl     每条消息一行(OpenAI message JSON);首行可为 {"type":"meta",...}
//!     0002.jsonl
//!     total.json      {"tokens": N} 跨 session/重启累计的总消耗 token
//!
//! 滚动:usage 超过 70% × context_window 时,
//!   ① 后端静默调 LLM 总结本 session
//!   ② 建新 session:meta 存摘要 + 注入旧 session 尾部 3 条原文作 seed
//! 摘要作为新 session 的"记忆锚点"注入 system;旧 session 全文保留可检索。

use std::path::{Path, PathBuf};
use serde_json::Value;

/// 每个 session 文件最大序号位数(0001.jsonl -> 4 位,足够)
const SEQ_WIDTH: usize = 4;

pub struct SessionStore {
    root: PathBuf,
}

impl SessionStore {
    /// root = app_data/sessions/<project>
    pub fn new(root: PathBuf) -> Self {
        Self { root }
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    fn ensure_dir(&self) -> Result<(), String> {
        std::fs::create_dir_all(&self.root).map_err(|e| e.to_string())
    }

    fn seq_path(&self, seq: u64) -> PathBuf {
        self.root.join(format!("{seq:0SEQ_WIDTH$}.jsonl"))
    }

    /// 当前最新 session 序号(无则 None)
    pub fn latest_seq(&self) -> Option<u64> {
        if !self.root.exists() {
            return None;
        }
        let mut max: Option<u64> = None;
        for entry in std::fs::read_dir(&self.root).ok()? {
            let entry = entry.ok()?;
            let name = entry.file_name().to_string_lossy().to_string();
            if let Some(num) = name.strip_suffix(".jsonl") {
                if let Ok(n) = num.parse::<u64>() {
                    max = Some(max.map_or(n, |m| m.max(n)));
                }
            }
        }
        max
    }

    /// 建首个 session(空文件);已存在则忽略
    pub fn ensure_first(&self) -> Result<u64, String> {
        if let Some(seq) = self.latest_seq() {
            return Ok(seq);
        }
        self.ensure_dir()?;
        std::fs::write(self.seq_path(1), "").map_err(|e| e.to_string())?;
        Ok(1)
    }

    /// 读取指定 session:返回 (summary, messages)
    fn read_session(&self, seq: u64) -> Result<(Option<String>, Vec<Value>), String> {
        let path = self.seq_path(seq);
        let raw = std::fs::read_to_string(&path).map_err(|e| format!("读 session 失败: {e}"))?;
        let mut summary = None;
        let mut messages = Vec::new();
        for line in raw.lines() {
            let line = line.trim();
            if line.is_empty() {
                continue;
            }
            if let Ok(v) = serde_json::from_str::<Value>(line) {
                if v.get("type").and_then(|t| t.as_str()) == Some("meta") {
                    summary = v.get("summary").and_then(|s| s.as_str()).map(String::from);
                    continue;
                }
                messages.push(v);
            }
        }
        Ok((summary, messages))
    }

    /// 加载最新 session(无则返回 None)
    pub fn load_latest(&self) -> Result<Option<(u64, Option<String>, Vec<Value>)>, String> {
        match self.latest_seq() {
            Some(seq) => {
                let (summary, messages) = self.read_session(seq)?;
                Ok(Some((seq, summary, messages)))
            }
            None => Ok(None),
        }
    }

    /// 向指定 session 追加消息
    pub fn append_messages(&self, seq: u64, messages: &[Value]) -> Result<(), String> {
        if messages.is_empty() {
            return Ok(());
        }
        self.ensure_dir()?;
        let path = self.seq_path(seq);
        use std::io::Write;
        let mut f = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&path)
            .map_err(|e| e.to_string())?;
        for m in messages {
            let line = serde_json::to_string(m).map_err(|e| e.to_string())?;
            writeln!(f, "{line}").map_err(|e| e.to_string())?;
        }
        Ok(())
    }

    /// 开新 session:seq+1,首行 meta(summary),随后 seed 消息(旧 session 尾部 3 条)
    pub fn start_new_session(
        &self,
        summary: &str,
        seed: &[Value],
    ) -> Result<u64, String> {
        let seq = self.latest_seq().unwrap_or(0) + 1;
        self.ensure_dir()?;
        let path = self.seq_path(seq);
        let mut lines = Vec::new();
        lines.push(format!(
            "{{\"type\":\"meta\",\"summary\":{}}}\n",
            serde_json::to_string(summary).map_err(|e| e.to_string())?
        ));
        for m in seed {
            let line = serde_json::to_string(m).map_err(|e| e.to_string())?;
            lines.push(line + "\n");
        }
        std::fs::write(&path, lines.join("")).map_err(|e| e.to_string())?;
        Ok(seq)
    }

    // ---------- 累计 token(跨 session/重启) ----------

    fn total_path(&self) -> PathBuf {
        self.root.join("total.json")
    }

    pub fn total_tokens(&self) -> u64 {
        std::fs::read_to_string(self.total_path())
            .ok()
            .and_then(|s| serde_json::from_str::<serde_json::Value>(&s).ok())
            .and_then(|v| v.get("tokens").and_then(|t| t.as_u64()))
            .unwrap_or(0)
    }

    pub fn add_tokens(&self, n: u64) -> Result<u64, String> {
        let new_total = self.total_tokens() + n;
        self.ensure_dir()?;
        std::fs::write(
            self.total_path(),
            format!("{{\"tokens\":{new_total}}}\n"),
        )
        .map_err(|e| e.to_string())?;
        Ok(new_total)
    }

    // ---------- 历史检索(search_history 工具用) ----------

    /// 在历史 session 里关键词检索(排除 exclude_seq 指当前 session 所在文件也参与?调用方决定)。
    /// 返回命中的消息片段文本(带会话序号),供模型理解。
    pub fn search(&self, query: &str, limit: usize) -> Vec<String> {
        let mut hits: Vec<(usize, String)> = Vec::new(); // (命中分, 文本)
        let terms = split_terms(query);
        if terms.is_empty() {
            return vec![];
        }
        let Some(max_seq) = self.latest_seq() else {
            return vec![];
        };
        for seq in 1..=max_seq {
            let Ok((_, messages)) = self.read_session(seq) else {
                continue;
            };
            // 整段文本便于带上下文返回
            let texts: Vec<String> = messages
                .iter()
                .filter_map(|m| {
                    let role = m.get("role").and_then(|r| r.as_str()).unwrap_or("?");
                    let content = m.get("content").and_then(|c| c.as_str()).unwrap_or("");
                    if content.is_empty() {
                        None
                    } else {
                        Some(format!("[{role}] {content}"))
                    }
                })
                .collect();
            for (i, text) in texts.iter().enumerate() {
                let score = terms
                    .iter()
                    .filter(|t| text.contains(t.as_str()))
                    .count();
                if score > 0 {
                    // 带前后各 1 条上下文
                    let mut block = Vec::new();
                    if i > 0 {
                        block.push(texts[i - 1].clone());
                    }
                    block.push(text.clone());
                    if i + 1 < texts.len() {
                        block.push(texts[i + 1].clone());
                    }
                    let body = block.join("\n");
                    hits.push((score, format!("【历史会话 {seq}】\n{body}")));
                }
            }
        }
        hits.sort_by(|a, b| b.0.cmp(&a.0));
        hits.into_iter()
            .take(limit)
            .map(|(_, text)| text)
            .collect()
    }
}

/// 简易分词:英文/数字按 3+ 字符单词;中文按 2-gram(无分词库的务实召回方案)
fn split_terms(query: &str) -> Vec<String> {
    let mut terms: Vec<String> = Vec::new();
    let mut cjk: Vec<char> = Vec::new();

    // 先按 ASCII 字母数字切英文词;其余(中文等)收集起来
    let mut ascii_word = String::new();
    let flush_ascii = |word: &mut String, terms: &mut Vec<String>| {
        if word.chars().count() >= 3 {
            let w = word.to_lowercase();
            if !terms.contains(&w) {
                terms.push(w);
            }
        }
        word.clear();
    };
    for c in query.chars() {
        if c.is_ascii_alphanumeric() {
            ascii_word.push(c);
        } else {
            flush_ascii(&mut ascii_word, &mut terms);
            if ('\u{4e00}'..='\u{9fff}').contains(&c) {
                cjk.push(c);
            }
        }
    }
    flush_ascii(&mut ascii_word, &mut terms);

    // 中文 2-gram
    if cjk.len() >= 2 {
        for w in cjk.windows(2) {
            let t: String = w.iter().collect();
            if !terms.contains(&t) {
                terms.push(t);
            }
        }
    }
    terms
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn tmp_root() -> PathBuf {
        let d = std::env::temp_dir().join(format!("mindoc-sessions-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        d
    }

    #[test]
    fn session_roundtrip_and_roll() {
        let root = tmp_root();
        let store = SessionStore::new(root.clone());

        store.ensure_first().unwrap();
        assert_eq!(store.latest_seq(), Some(1));

        // 写几条消息
        store
            .append_messages(
                1,
                &[
                    json!({"role":"user","content":"我想让主角怕光"}),
                    json!({"role":"assistant","content":"好的,已记下:主角怕光"}),
                    json!({"role":"user","content":"再给他加个设定:他会做奇怪的梦"}),
                ],
            )
            .unwrap();

        // 滚动:总结 + seed 尾部 3 条
        let seed: Vec<Value> = store.read_session(1).unwrap().1.into_iter().rev().take(3).rev().collect();
        let new_seq = store.start_new_session("主角怕光;会做奇怪的梦", &seed).unwrap();
        assert_eq!(new_seq, 2);

        let (summary, messages) = store.read_session(2).unwrap();
        assert!(summary.unwrap().contains("怕光"));
        assert_eq!(messages.len(), 3, "seed 3 条");

        // 累计 token
        assert_eq!(store.add_tokens(123).unwrap(), 123);
        assert_eq!(store.add_tokens(77).unwrap(), 200);
        assert_eq!(store.total_tokens(), 200);

        // 检索:跨 session 命中旧细节
        let hits = store.search("主角怕光的设定", 3);
        assert!(
            hits.iter().any(|h| h.contains("怕光")),
            "应命中历史会话内容: {hits:?}"
        );

        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn terms_ok() {
        let t = split_terms("主角怕光的名字叫alice");
        assert!(t.contains(&"alice".to_string()));
        assert!(t.contains(&"怕光".to_string()));
    }
}
