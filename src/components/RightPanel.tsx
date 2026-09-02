import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import type { CommitInfo, ProjectView } from '../lib/types';
import { MdView } from './MdView';
import { DiffDialog } from './DiffDialog';

/** 右侧栏:上半设定集浏览 / 下半 git 提交历史(点提交开独立 diff 弹窗阅读) */
export function RightPanel({ project }: { project: ProjectView }) {
  const [tab, setTab] = useState<'files' | 'history'>('history');
  const [fileIdx, setFileIdx] = useState(0);
  const [log, setLog] = useState<CommitInfo[]>([]);
  const [activeCommit, setActiveCommit] = useState<string | null>(null);
  // diff 弹窗内容(commit + 与上一提交对比的 older id)
  const [dlg, setDlg] = useState<{ commit: CommitInfo; olderId: string | null } | null>(null);

  // 工程变化后刷新历史
  useEffect(() => {
    setFileIdx(0);
    setActiveCommit(null);
    setDlg(null);
    api.gitLog().then(setLog).catch(console.error);
  }, [project.info.name, project.info.updated_at]);

  const file = project.files[fileIdx];

  return (
    <div className="right-panel">
      <div className="rp-tabs">
        <button className={tab === 'files' ? 'rp-tab active' : 'rp-tab'} onClick={() => setTab('files')}>
          设定集
        </button>
        <button className={tab === 'history' ? 'rp-tab active' : 'rp-tab'} onClick={() => setTab('history')}>
          Git 记录
        </button>
      </div>

      {tab === 'files' && (
        <div className="rp-files">
          <div className="rp-file-list">
            {project.files.map((f, i) => (
              <button
                key={f.name}
                className={i === fileIdx ? 'rp-file-item active' : 'rp-file-item'}
                onClick={() => setFileIdx(i)}
              >
                {f.name.replace('.md', '')}
              </button>
            ))}
          </div>
          <div className="rp-file-content">
            {file && <MdView text={file.content || '（空）'} />}
          </div>
        </div>
      )}

      {tab === 'history' && (
        <div className="rp-history">
          <div className="rp-commit-list">
            {log.length === 0 && <div className="rp-empty">暂无提交</div>}
            {log.map((c, i) => (
              <button
                key={c.id}
                className={activeCommit === c.id ? 'rp-commit active' : 'rp-commit'}
                title={c.message}
                onClick={() => {
                  setActiveCommit(c.id);
                  // 独立弹窗阅读 diff(与上一个提交对比;首个提交对比空树)
                  setDlg({ commit: c, olderId: log[i + 1]?.id ?? null });
                }}
              >
                <span className="commit-hash">{c.id}</span>
                <span className="commit-msg">{c.message}</span>
                <span className="commit-meta">
                  {c.author} · {new Date(c.time_secs * 1000).toLocaleString()}
                </span>
              </button>
            ))}
            {log.length > 0 && <div className="rp-empty hint-click">点击提交在弹窗中查看完整 diff</div>}
          </div>
        </div>
      )}

      {dlg && <DiffDialog commit={dlg.commit} olderId={dlg.olderId} onClose={() => setDlg(null)} />}
    </div>
  );
}
