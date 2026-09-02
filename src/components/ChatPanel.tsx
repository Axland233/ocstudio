import { useEffect, useRef, useState } from 'react';
import { chatSend } from '../lib/api';
import type { AgentEvent, ChatHistoryMsg } from '../lib/types';
import { MdView } from './MdView';
import { Icon } from './Icon';

export type ChatItem =
  | { kind: 'user'; text: string }
  | { kind: 'assistant'; text: string; closed?: boolean }
  | { kind: 'tool'; name: string; summary: string }
  | { kind: 'error'; text: string };

/** 把后端历史消息转成前端渲染项(tool 消息只留一行摘要) */
export function historyToItems(history: ChatHistoryMsg[]): ChatItem[] {
  return history.map((m) => {
    if (m.role === 'user') return { kind: 'user' as const, text: m.content };
    if (m.role === 'tool') {
      const first = m.content.split('\n')[0].slice(0, 120);
      return { kind: 'tool' as const, name: '历史工具', summary: first };
    }
    return { kind: 'assistant' as const, text: m.content, closed: true };
  });
}

/** 工具名 -> MD3 图标 */
function toolIcon(name: string): string {
  if (name === 'write_project_file') return 'save';
  if (name === 'read_project_file') return 'description';
  if (name === 'search_history') return 'search';
  return 'settings';
}
function toolLabel(name: string): string {
  if (name === 'write_project_file') return '已固化并提交';
  return name;
}

/** 输入框最大高度 = 基础(48px)的 4 倍 ≈ 8 行文本 */
const INPUT_MAX_PX = 192;

function autoGrow(el: HTMLTextAreaElement | null) {
  if (!el) return;
  el.style.height = 'auto';
  const target = Math.min(el.scrollHeight, INPUT_MAX_PX);
  el.style.height = `${Math.max(48, target)}px`;
}

interface Props {
  projectName: string;
  /** 打开工程时注入的历史消息(组件由父级 remount,直接作为初始状态) */
  initialItems?: ChatItem[];
  onSettingChange?: () => void; // 固化发生后通知父级刷新设定集/git
}

export function ChatPanel({ projectName, initialItems, onSettingChange }: Props) {
  const [items, setItems] = useState<ChatItem[]>(initialItems ?? []);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  // 本轮用量(气泡下方灰字;本轮消耗 + 历史累计)
  const [usage, setUsage] = useState<{ turn: number; total: number } | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  // 草稿机制:输入框未发送文本持久化,退出/重启后保留(按工程隔离)
  const draftKey = `mindoc-draft:${projectName}`;
  useEffect(() => {
    const saved = localStorage.getItem(draftKey);
    if (saved) setInput(saved);
    // remount(工程切换)后恢复输入框高度
    requestAnimationFrame(() => autoGrow(inputRef.current));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  useEffect(() => {
    localStorage.setItem(draftKey, input);
  }, [input, draftKey]);

  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [items, busy]);

  function pushItem(item: ChatItem) {
    setItems((prev) => [...prev, item]);
  }
  /** 给"当前 assistant 气泡"追加文本;若最后不是 assistant 则新建 */
  function appendToken(text: string) {
    setItems((prev) => {
      const last = prev[prev.length - 1];
      if (last && last.kind === 'assistant' && !last.closed) {
        const copy = [...prev];
        copy[copy.length - 1] = { ...last, text: last.text + text };
        return copy;
      }
      return [...prev, { kind: 'assistant', text }];
    });
  }
  function closeAssistant(removeIfEmpty: boolean) {
    setItems((prev) => {
      const last = prev[prev.length - 1];
      if (last && last.kind === 'assistant' && !last.closed) {
        if (removeIfEmpty && !last.text.trim()) return prev.slice(0, -1);
        const copy = [...prev];
        copy[copy.length - 1] = { ...last, closed: true };
        return copy;
      }
      return prev;
    });
  }

  async function send() {
    const text = input.trim();
    if (!text || busy) return;
    setInput('');
    setUsage(null); // 新一轮开始,清掉上一轮灰字
    setBusy(true);
    pushItem({ kind: 'user', text });
    try {
      await chatSend(text, (ev: AgentEvent) => {
        switch (ev.type) {
          case 'token':
            appendToken(ev.text);
            break;
          case 'tool_start':
            closeAssistant(true);
            break;
          case 'tool_done':
            pushItem({
              kind: 'tool',
              name: ev.name,
              summary: ev.summary.split('\n')[0].slice(0, 120),
            });
            onSettingChange?.(); // 有固化动作,刷新设定集与 git 历史
            break;
          case 'usage':
            setUsage({ turn: ev.turn_tokens, total: ev.total_tokens });
            break;
          case 'done':
            closeAssistant(true);
            break;
          case 'error':
            closeAssistant(true);
            pushItem({ kind: 'error', text: ev.message });
            break;
        }
      });
    } catch (e) {
      pushItem({ kind: 'error', text: String(e) });
    } finally {
      setBusy(false);
      requestAnimationFrame(() => autoGrow(inputRef.current));
    }
  }

  const canSend = !busy && input.trim().length > 0;

  return (
    <div className="chat-wrap">
      <div className="chat-scroll" ref={scrollRef}>
        {items.length === 0 && (
          <div className="chat-empty">
            <div className="chat-empty-title">{projectName}</div>
            <div>和 AI 一起打磨你的设定集吧 —— 聊人设、世界观、剧情脑洞,</div>
            <div>AI 会把值得固化的内容写进设定文件并自动 git 提交。</div>
          </div>
        )}
        {items.map((it, i) => {
          switch (it.kind) {
            case 'user':
              return (
                <div key={i} className="msg msg-user">
                  <div className="bubble bubble-user">{it.text}</div>
                </div>
              );
            case 'assistant':
              return it.text.trim() ? (
                <div key={i} className="msg msg-assistant">
                  <div className="bubble bubble-assistant">
                    <MdView text={it.text} />
                  </div>
                </div>
              ) : null;
            case 'tool':
              return (
                <div key={i} className="msg msg-tool">
                  <span className="tool-chip">
                    <Icon name={toolIcon(it.name)} size="tiny" />
                    <span>{toolLabel(it.name)}</span>
                  </span>
                  <span className="tool-summary">{it.summary}</span>
                </div>
              );
            case 'error':
              return (
                <div key={i} className="msg msg-error">
                  <Icon name="error" size="small" />
                  <span>{it.text}</span>
                </div>
              );
          }
        })}
        {busy && items[items.length - 1]?.kind !== 'assistant' && (
          <div className="msg msg-assistant">
            <div className="bubble bubble-assistant thinking">思考中…</div>
          </div>
        )}
        {/* 本轮用量灰字(仅显示在消息气泡下方,不打断对话) */}
        {usage && !busy && (
          <div className="usage-hint">本轮 {usage.turn} tokens · 累计 {usage.total}</div>
        )}
      </div>

      <div className="chat-input-row">
        <textarea
          ref={inputRef}
          className="chat-input"
          placeholder="聊聊你的脑洞…(Enter 发送,Shift+Enter 换行)"
          value={input}
          disabled={busy}
          onChange={(e) => {
            setInput(e.target.value);
            autoGrow(e.target);
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              void send();
            }
          }}
        />
        <button className="send-btn" disabled={!canSend} onClick={() => void send()} aria-label="发送">
          <Icon name="send" className="icon-send" />
        </button>
      </div>
    </div>
  );
}
