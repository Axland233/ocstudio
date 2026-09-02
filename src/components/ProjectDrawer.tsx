import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import type { ProjectInfo } from '../lib/types';

interface Props {
  open: boolean;
  activeName: string | null;
  onClose: () => void;
  onOpen: (name: string) => void;
  onNew: () => void;
  onSettings: () => void;
}

/** 工程列表抽屉(移动端左滑侧栏 / PC 左下角展开) */
export function ProjectDrawer({ open, activeName, onClose, onOpen, onNew, onSettings }: Props) {
  const [projects, setProjects] = useState<ProjectInfo[]>([]);

  useEffect(() => {
    if (open) api.listProjects().then(setProjects).catch(console.error);
  }, [open, activeName]);

  if (!open) return null;

  return (
    <>
      <div className="scrim" onClick={onClose} />
      <div className="drawer">
        <div className="drawer-title">我的工程</div>
        <div className="drawer-list">
          {projects.length === 0 && <div className="rp-empty">还没有工程,点下方新建一个</div>}
          {projects.map((p) => (
            <button
              key={p.name}
              className={p.name === activeName ? 'drawer-item active' : 'drawer-item'}
              onClick={() => {
                onOpen(p.name);
                onClose();
              }}
            >
              <div className="drawer-item-name">{p.name}</div>
              <div className="drawer-item-desc">{p.desc || '无描述'}</div>
            </button>
          ))}
        </div>
        <div className="drawer-footer">
          <button className="btn-tonal" onClick={() => { onNew(); onClose(); }}>＋ 新建工程</button>
          <button className="btn-text" onClick={() => { onSettings(); onClose(); }}>⚙ 设置</button>
        </div>
      </div>
    </>
  );
}
