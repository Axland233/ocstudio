import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import type { CommitInfo, FileDiff, ProjectView } from '../lib/types';
import { MdView } from './MdView';

const KIND_LABEL: Record<string, string> = { A: '新增', M: '修改', D: '删除', R: '重命名' };

/** 右侧栏:上半设定集浏览 / 下半 git 提交历史 + 逐行 diff */
export function RightPanel({ project }: { project: ProjectView }) {
  const [tab, setTab] = useState<'files' | 'history'>('history');
  const [fileIdx, setFileIdx] = useState(0);
  const [log, setLog] = useState<CommitInfo[]>([]);
  const [diff, setDiff] = useState<FileDiff[] | null>(null);
  const [diffTarget, setDiffTarget] = useState<string | null>(null);
  const [openFile, setOpenFile] = useState<string | null>(null);
  const [loadingDiff, setLoadingDiff] = useState(false);

  // 工程变化后刷新
  useEffect(() => {
    setFileIdx(0);
    setDiff(null);
    setDiffTarget(null);
    setOpenFile(null);
    api.gitLog().then(setLog).catch(console.error);
  }, [project.info.name, project.info.updated_at]);

  async function showDiff(commit: CommitInfo, index: number) {
    const older = log[index + 1]?.id ?? null; // 与上一个提交比较
    setDiffTarget(commit.id);
    setDiff(null);
    setOpenFile(null);
    setLoadingDiff(true);
    try {
      setDiff(await api.gitDiff(older, commit.id));
    } catch (e) {
      console.error(e);
    } finally {
      setLoadingDiff(false);
    }
  }

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
                className={diffTarget === c.id ? 'rp-commit active' : 'rp-commit'}
                onClick={() => void showDiff(c, i)}
                title={c.message}
              >
                <span className="commit-hash">{c.id}</span>
                <span className="commit-msg">{c.message}</span>
                <span className="commit-meta">
                  {c.author} · {new Date(c.time_secs * 1000).toLocaleString()}
                </span>
              </button>
            ))}
          </div>
          <div className="rp-diff">
            {loadingDiff && <div className="rp-empty">加载 diff…</div>}
            {!loadingDiff && diffTarget && !diff && <div className="rp-empty">无变更</div>}
            {diff && diff.length === 0 && <div className="rp-empty">该提交无文件变更</div>}
            {diff && diff.length > 0 && (
              <>
                <div className="rp-diff-file-list">
                  {diff.map((d) => (
                    <button
                      key={d.path}
                      className={openFile === d.path ? 'rp-diff-file active' : 'rp-diff-file'}
                      onClick={() => setOpenFile(openFile === d.path ? null : d.path)}
                    >
                      <span className={`diff-kind diff-${d.kind.toLowerCase()}`}>{d.kind}</span>
                      <span className="rp-diff-file-name">{d.path}</span>
                      <span className="rp-diff-file-stats">
                        +{d.lines.filter((l) => l.kind === 'add').length}
                        {' '}-{d.lines.filter((l) => l.kind === 'del').length}
                      </span>
                    </button>
                  ))}
                </div>
                {openFile && (() => {
                  const fd = diff.find((d) => d.path === openFile);
                  if (!fd) return null;
                  return (
                    <div className="rp-diff-lines">
                      <div className="rp-diff-lines-title">{fd.path}({KIND_LABEL[fd.kind] ?? fd.kind})</div>
                      {fd.lines.map((l, i) => (
                        <div key={i} className={`dl ${l.kind}`}>
                          <span className="dl-no">{l.old_no ?? ''}</span>
                          <span className="dl-no">{l.new_no ?? ''}</span>
                          <span className="dl-mark">{l.kind === 'add' ? '+' : l.kind === 'del' ? '-' : ' '}</span>
                          <span className="dl-text">{l.text || ' '}</span>
                        </div>
                      ))}
                    </div>
                  );
                })()}
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
