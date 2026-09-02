import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from './lib/api';
import type { AppSettings, ProjectView } from './lib/types';
import { initTheme } from './lib/themeApply';
import { Bootstrap } from './components/Bootstrap';
import { ChatPanel } from './components/ChatPanel';
import { RightPanel } from './components/RightPanel';
import { ProjectDrawer } from './components/ProjectDrawer';
import { NewProjectDialog } from './components/NewProjectDialog';
import { SettingsDialog } from './components/SettingsDialog';

export default function App() {
  const [settings, setSettings] = useState<AppSettings | null>(null);
  const [project, setProject] = useState<ProjectView | null>(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [rightOpen, setRightOpen] = useState(false); // 移动端右侧面板
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [newOpen, setNewOpen] = useState(false);
  const [isMobile, setIsMobile] = useState(window.innerWidth < 900);
  const stopWatchRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    const onResize = () => setIsMobile(window.innerWidth < 900);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  // 初始加载
  useEffect(() => {
    (async () => {
      const s = await api.bootstrap();
      setSettings(s);
      if (s.onboarded) {
        try {
          setProject(await api.currentProject());
        } catch {
          setProject(null);
        }
      }
    })();
  }, []);

  // 主题:settings 就绪与每次保存后应用
  useEffect(() => {
    if (!settings) return;
    stopWatchRef.current?.();
    stopWatchRef.current = initTheme(settings.theme);
    return () => stopWatchRef.current?.();
  }, [settings]);

  function onSettingsSaved(s: AppSettings) {
    setSettings(s);
  }

  // 工程内容(固化后/手动)刷新
  const refreshProject = useCallback(async () => {
    try {
      setProject(await api.currentProject());
    } catch {
      /* 忽略 */
    }
  }, []);

  async function openProjectByName(name: string) {
    setProject(await api.openProject(name));
  }

  if (!settings) return <div className="boot-loading">MindOC 加载中…</div>;
  if (!settings.onboarded) return <Bootstrap settings={settings} onDone={setSettings} />;

  const headerRight = (
    <>
      <button className="icon-btn" title="新建工程" onClick={() => setNewOpen(true)}>＋</button>
      {isMobile ? (
        // 手机端:设置入口在侧边栏,右上角换成"设定集/Git 记录"面板入口
        <button className="icon-btn" title="设定集 / Git 记录" onClick={() => setRightOpen(true)}>📖</button>
      ) : (
        <button className="icon-btn" title="设置" onClick={() => setSettingsOpen(true)}>⚙</button>
      )}
    </>
  );

  return (
    <div className="app">
      {/* 顶栏 */}
      <header className="topbar">
        <button className="icon-btn" title="工程列表" onClick={() => setDrawerOpen(true)}>☰</button>
        <div className="topbar-project" onClick={() => setDrawerOpen(true)}>
          {project?.info.name ?? '未选择工程'}
          <span className="topbar-desc">{project?.info.desc}</span>
        </div>
        <div className="topbar-right">{headerRight}</div>
      </header>

      {/* 主体 */}
      {project ? (
        <div className="main">
          <div className="chat-col">
            <ChatPanel
              projectName={project.info.name}
              onSettingChange={() => void refreshProject()}
            />
          </div>
          {!isMobile && (
            <div className="side-col">
              <RightPanel project={project} />
            </div>
          )}
        </div>
      ) : (
        <div className="no-project">
          <div className="no-project-card">
            <div className="bootstrap-logo">MindOC</div>
            <div className="bootstrap-h">选择一个工程开始创作</div>
            <button className="btn-filled" onClick={() => setDrawerOpen(true)}>打开工程列表</button>
            <button className="btn-tonal" onClick={() => setNewOpen(true)}>＋ 新建工程</button>
          </div>
        </div>
      )}

      {/* 移动端:右侧设定集/git 面板(滑入) */}
      {isMobile && project && (
        <>
          {rightOpen && <div className="scrim" onClick={() => setRightOpen(false)} />}
          <div className={`mobile-right ${rightOpen ? 'open' : ''}`}>
            <RightPanel project={project} />
          </div>
        </>
      )}

      {/* 移动端悬浮按钮:右侧面板 */}

      <ProjectDrawer
        open={drawerOpen}
        activeName={project?.info.name ?? null}
        onClose={() => setDrawerOpen(false)}
        onOpen={(name) => void openProjectByName(name)}
        onNew={() => setNewOpen(true)}
        onSettings={() => setSettingsOpen(true)}
      />
      <NewProjectDialog
        open={newOpen}
        onClose={() => setNewOpen(false)}
        onCreated={(name) => void openProjectByName(name)}
      />
      <SettingsDialog
        open={settingsOpen}
        settings={settings}
        projectName={project?.info.name ?? null}
        onClose={() => setSettingsOpen(false)}
        onSaved={onSettingsSaved}
      />
    </div>
  );
}
