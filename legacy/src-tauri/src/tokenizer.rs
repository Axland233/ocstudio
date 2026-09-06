//! 本地 token 估算(兜底用;主计量以 API 返回的 usage 为准)
//!
//! 目的:当 API 未返回 usage(流式未开 include_usage / 服务商不支持)时,
//! 用本地估算判断窗口占用,防止上下文溢出。
//!
//! ponytail: 当前为保守字符估算(中文≈1.2 tok/字、其他≈1 tok/4 字符),
//! 结果偏向高估 -> 滚动更早 -> 安全方向。
//! upgrade: 需要精确计数时换 tiktoken-rs + o200k_base 离线词表(约 1.6MB 资源)。

/// 估算一段文本的 token 数(偏保守/偏高)
pub fn estimate_tokens(text: &str) -> usize {
    let mut cjk = 0usize;
    let mut other = 0usize;
    for c in text.chars() {
        if ('\u{4e00}'..='\u{9fff}').contains(&c)
            || ('\u{3400}'..='\u{4dbf}').contains(&c) // CJK 扩展
            || ('\u{3040}'..='\u{30ff}').contains(&c) // 日文假名
        {
            cjk += 1;
        } else {
            other += 1;
        }
    }
    // 中文约 1.0-1.5 tok/字,取 1.2;其余按 4 字符/tok
    let est = (cjk as f64 * 1.2 + other as f64 / 4.0) as usize;
    est + 4 // 少量固定开销(角色/格式标记)
}

/// 估算一组 OpenAI 格式消息的总 token(system + history + tools 近似)
pub fn estimate_messages(messages: &[serde_json::Value]) -> usize {
    let mut total = 0usize;
    for msg in messages {
        if let Some(content) = msg.get("content").and_then(|c| c.as_str()) {
            total += estimate_tokens(content);
        }
        // tool_calls 参数也占 token
        if let Some(tcs) = msg.get("tool_calls").and_then(|t| t.as_array()) {
            for tc in tcs {
                if let Some(args) = tc.pointer("/function/arguments").and_then(|a| a.as_str()) {
                    total += estimate_tokens(args);
                }
            }
        }
        total += 6; // 每条消息的角色/结构开销
    }
    total
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn estimate_basic() {
        // 纯英文
        let en = estimate_tokens("hello world this is a test of token counting");
        assert!(en >= 8 && en <= 16, "英文估算异常: {en}");
        // 中文按字高估:14 字 × 1.2 = 16.8 -> 16,+4 固定开销 = 20
        let zh = estimate_tokens("这是一段中文设定文本用来测试");
        assert_eq!(zh, 20, "中文估算异常: {zh}");
        // 空
        assert_eq!(estimate_tokens(""), 4);
        // 消息序列单调
        let msgs = serde_json::from_str::<Vec<serde_json::Value>>(
            r#"[{"role":"system","content":"你是助手"},{"role":"user","content":"你好"}]"#,
        )
        .unwrap();
        let m = estimate_messages(&msgs);
        assert!(m > estimate_tokens("你是助手你好"), "消息开销应累加");
    }
}
