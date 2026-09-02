import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import type { CommitInfo, FileDiff } from '../lib/types';
import { Icon } from './Icon';

const KIND_LABEL: Record<string, string> = { A: '新增', M: '修改', D: '删除', R: '重命名' };

interface Props {
  commit: CommitInfo;
  olderId: string | null; // 与上一个提交对比;null = 空树(首个提交)
  onClose: () => void;
}

/** Git diff 独立弹窗:commit 完整信息 + 文件列表 + 逐行 diff,大空间阅读 */
export function DiffDialog({ commit, olderId, onClose }: Props) {
  const [diff, setDiff] = useState<FileDiff[] | null>(null);
  const [openFile, setOpenFile] = useState<string | null>(null);
  const [err, setErr] = useState('');

  useEffect(() => {
    let alive = true;
    setDiff(null);
    setOpenFile(null);
    setErr('');
    api.gitDiff(olderId, commit.id)
      .then((d) => {
        if (!alive) return;
        setDiff(d);
        setOpenFile(d[0]?.path ?? null);
      })
      .catch((e) => alive && setErr(String(e)));
    return () => {
      alive = false;
    };
  }, [commit.id, olderId]);

  // Esc 关闭
  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [onClose]);

  const open = diff?.find((d) => d.path === openFile) ?? null;

  return (
    <div className="overlay" onClick={onClose}>
      <div className="diff-window" onClick={(e) => e.stopPropagation()}>
        {/* 头部:hash/作者/时间 + 单行 message(完整版在下方正文区) */}
        <div className="dw-head">
          <div className="dw-head-info">
            <div className="dw-head-meta">
              {commit.id} · {commit.author} · {new Date(commit.time_secs * 1000).toLocaleString()}
            </div>
            <div className="dw-head-msg">{commit.message}</div>
          </div>
          <button className="icon-btn" title="关闭" onClick={onClose}>
            <Icon name="close" />
          </button>
        </div>

        <div className="dw-body">
          {/* 变更文件列表 */}
          <div className="dw-files">
            {err && <div className="form-error">{err}</div>}
            {!err && !diff && <div className="dw-empty">加载中…</div>}
            {diff && diff.length === 0 && <div className="dw-empty">该提交无文件变更</div>}
            {diff?.map((d) => (
              <button
                key={d.path}
                className={openFile === d.path ? 'rp-diff-file active' : 'rp-diff-file'}
                onClick={() => setOpenFile(d.path)}
              >
                <span className={`diff-kind diff-${d.kind.toLowerCase()}`}>{d.kind}</span>
                <span className="rp-diff-file-name">{d.path}</span>
                <span className="rp-diff-file-stats">
                  +{d.lines.filter((l) => l.kind === 'add').length} -{d.lines.filter((l) => l.kind === 'del').length}
                </span>
              </button>
            ))}
          </div>

          {/* 右侧:完整 message + 逐行 diff */}
          <div className="dw-main">
            <div className="dw-message">
              <b>{commit.message}</b>
            </div>
            <div className="dw-lines">
              {!open && <div className="dw-empty">选择一个文件查看逐行变更</div>}
              {open && (
                <>
                  <div className="rp-diff-lines-title">
                    {open.path}({KIND_LABEL[open.kind] ?? open.kind})
                  </div>
                  {open.lines.map((l, i) => (
                    <div key={i} className={`dl ${l.kind}`}>
                      <span className="dl-no">{l.old_no ?? ''}</span>
                      <span className="dl-no">{l.new_no ?? ''}</span>
                      <span className="dl-mark">{l.kind === 'add' ? '+' : l.kind === 'del' ? '-' : ' '}</span>
                      <span className="dl-text">{l.text || ' '}</span>
                    </div>
                  ))}
                </>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
