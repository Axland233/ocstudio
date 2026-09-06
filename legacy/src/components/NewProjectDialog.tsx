import { useState } from 'react';
import { api } from '../lib/api';

interface Props {
  open: boolean;
  defaultDesc?: string;
  defaultAuthor?: string;
  onClose: () => void;
  onCreated: (name: string) => void;
}

/** 新建工程(右上角 +),模态盖在主界面上 */
export function NewProjectDialog({ open, defaultDesc, defaultAuthor, onClose, onCreated }: Props) {
  const [name, setName] = useState('');
  const [desc, setDesc] = useState(defaultDesc ?? '');
  const [author, setAuthor] = useState(defaultAuthor ?? '');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  if (!open) return null;

  async function submit() {
    setErr('');
    setBusy(true);
    try {
      await api.createProject(name.trim(), desc.trim(), author.trim());
      onCreated(name.trim());
      onClose();
    } catch (e) {
      setErr(String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="overlay" onClick={onClose}>
      <div className="dialog" onClick={(e) => e.stopPropagation()}>
        <div className="dialog-title">新建工程</div>
        <label className="field-label">工程名称 *</label>
        <input className="md-input" value={name} onChange={(e) => setName(e.target.value)} placeholder="例如:雾海之城" />
        <label className="field-label">一句话描述</label>
        <textarea
          className="md-input"
          rows={2}
          value={desc}
          onChange={(e) => setDesc(e.target.value)}
          placeholder="这个世界讲的是什么?"
        />
        <label className="field-label">作者(会写入 git 提交记录)</label>
        <input className="md-input" value={author} onChange={(e) => setAuthor(e.target.value)} placeholder="你的名字/笔名" />
        {err && <div className="form-error">{err}</div>}
        <div className="dialog-actions">
          <button className="btn-text" onClick={onClose}>取消</button>
          <button className="btn-filled" disabled={busy || !name.trim()} onClick={() => void submit()}>
            {busy ? '创建中…' : '创建'}
          </button>
        </div>
        <div className="hint">提示:手机端可把整个工程目录分享给朋友,对方放入自己的工程目录即可识别。</div>
      </div>
    </div>
  );
}
