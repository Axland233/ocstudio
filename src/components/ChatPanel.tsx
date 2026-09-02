import { useEffect, useRef, useState } from 'react';
import { chatSend } from '../lib/api';
import type { AgentEvent } from '../lib/types';
import { MdView } from './MdView';

export type ChatItem =
  | { kind: 'user'; text: string }
  | { kind: 'assistant'; text: string; closed?: boolean }
  | { kind: 'tool'; name: string; summary: string }
  | { kind: 'error'; text: string };

interface Props {
  projectName: string;
  onBusyChange?: (busy: boolean) => void;
  onSettingChange?: () => void; // 固化发生后通知父级刷新设定集/git
}

export function ChatPanel({ projectName, onBusyChange, onSettingChange }: Props) {
  const [items, setItems] = useState<ChatItem[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const itemsRef = useRef(items);
  itemsRef.current = items;

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
    setBusy(true);
    onBusyChange?.(true);
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
      onBusyChange?.(false);
    }
  }

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
                  <span className="tool-chip">🔧 {it.name === 'write_project_file' ? '已固化并提交' : it.name}</span>
                  <span className="tool-summary">{it.summary}</span>
                </div>
              );
            case 'error':
              return (
                <div key={i} className="msg msg-error">
                  ⚠️ {it.text}
                </div>
              );
          }
        })}
        {busy && items[items.length - 1]?.kind !== 'assistant' && (
          <div className="msg msg-assistant">
            <div className="bubble bubble-assistant thinking">思考中…</div>
          </div>
        )}
      </div>
      <div className="chat-input-row">
        <textarea
          className="chat-input"
          placeholder="聊聊你的脑洞…(Enter 发送,Shift+Enter 换行)"
          value={input}
          disabled={busy}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              void send();
            }
          }}
        />
        <button className="send-btn" disabled={busy || !input.trim()} onClick={() => void send()}>
          发送
        </button>
      </div>
    </div>
  );
}
