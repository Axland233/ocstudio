import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from './lib/api';
import type { AppSettings, ProjectView } from './lib/types';
import { initTheme } from './lib/themeApply';
import { listen } from '@tauri-apps/api/event';
import { Bootstrap } from './components/Bootstrap';
import { ChatPanel, historyToItems, type ChatItem } from './components/ChatPanel';
import { Icon } from './components/Icon';
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
  const [chatItems, setChatItems] = useState<ChatItem[]>([]);
  const [chatEpoch, setChatEpoch] = useState(0);
  const stopWatchRef = useRef<(() => void) | null>(null);
  const chatColRef = useRef<HTMLDivElement>(null);

  // 手机左右滑手势:右滑打开左侧边栏,左滑打开右侧面板(Git 历史/设定集)
  useEffect(() => {
    if (!isMobile) return;
    const el = chatColRef.current;
    if (!el) return;
    let sx = 0;
    let sy = 0;
    let fired = false;
    const onStart = (e: TouchEvent) => {
      sx = e.touches[0].clientX;
      sy = e.touches[0].clientY;
      fired = false;
    };
    const onMove = (e: TouchEvent) => {
      if (fired) return;
      const dx = e.touches[0].clientX - sx;
      const dy = e.touches[0].clientY - sy;
      // 横向位移足够且明显大于纵向(避免与上下滚动冲突)
      if (Math.abs(dx) < 56 || Math.abs(dx) < Math.abs(dy) * 1.4) return;
      fired = true;
      if (dx > 0) setDrawerOpen(true);
      else setRightOpen(true);
    };
    el.addEventListener('touchstart', onStart, { passive: true });
    el.addEventListener('touchmove', onMove, { passive: true });
    return () => {
      el.removeEventListener('touchstart', onStart);
      el.removeEventListener('touchmove', onMove);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isMobile, project?.info.name]);

  useEffect(() => {
    const onResize = () => setIsMobile(window.innerWidth < 900);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  // Android 返回键:Rust emit "back-press"。
  // 优先级:关闭浮层(返回上一级)diff弹窗>右面板>抽屉>新建/设置弹窗;全在主页 -> 真退出。
  useEffect(() => {
    const un = listen('back-press', () => {
      if (settingsOpen) setSettingsOpen(false);
      else if (newOpen) setNewOpen(false);
      else if (rightOpen) setRightOpen(false);
      else if (drawerOpen) setDrawerOpen(false);
      else void api.backExit();
    });
    return () => {
      void un.then((f) => f());
    };
  }, [settingsOpen, newOpen, rightOpen, drawerOpen]);

  // 初始加载
  useEffect(() => {
    (async () => {
      const s = await api.bootstrap();
      setSettings(s);
      if (s.onboarded) {
        try {
          const p = await api.currentProject();
          if (p) {
            setProject(p);
            const hist = await api.chatHistory();
            setChatItems(historyToItems(hist));
          }
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

  // 工程目录变更后:刷新设置;当前工程若不在新目录则回到空态
  async function handleWorkspaceChanged() {
    const s = await api.bootstrap().catch(() => null);
    if (s) setSettings(s);
    try {
      const p = await api.currentProject();
      if (p) {
        setProject(p);
        const hist = await api.chatHistory();
        setChatItems(historyToItems(hist));
        setChatEpoch((e) => e + 1);
      } else {
        setProject(null);
        setChatItems([]);
      }
    } catch {
      // 当前工程在新目录不存在 -> 空态,从列表重新打开
      setProject(null);
      setChatItems([]);
    }
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
    // 恢复该工程最新 session 的消息到聊天区(退出重开不丢)
    try {
      const hist = await api.chatHistory();
      setChatItems(historyToItems(hist));
    } catch {
      setChatItems([]);
    }
    setChatEpoch((e) => e + 1);
  }

  if (!settings) return <div className="boot-loading">MindOC 加载中…</div>;
  if (!settings.onboarded) return <Bootstrap settings={settings} onDone={setSettings} />;

  const headerRight = (
    <>
      <button className="icon-btn" title="新建工程" onClick={() => setNewOpen(true)}>
        <Icon name="add" />
      </button>
      {isMobile ? (
        // 手机端:设置入口在侧边栏,右上角换成"设定集/Git 记录"面板入口
        <button className="icon-btn" title="设定集 / Git 记录" onClick={() => setRightOpen(true)}>
          <Icon name="book" />
        </button>
      ) : (
        <button className="icon-btn" title="设置" onClick={() => setSettingsOpen(true)}>
          <Icon name="settings" />
        </button>
      )}
    </>
  );

  return (
    <div className="app">
      {/* 顶栏 */}
      <header className="topbar">
        <button className="icon-btn" title="工程列表" onClick={() => setDrawerOpen(true)}>
          <Icon name="menu" />
        </button>
        <div className="topbar-project" onClick={() => setDrawerOpen(true)}>
          {project?.info.name ?? '未选择工程'}
          <span className="topbar-desc">{project?.info.desc}</span>
        </div>
        <div className="topbar-right">{headerRight}</div>
      </header>

      {/* 主体 */}
      {project ? (
        <main className="main">
          <div className="chat-col" ref={chatColRef}>
            <ChatPanel
              key={`${project.info.name}-${chatEpoch}`}
              projectName={project.info.name}
              initialItems={chatItems}
              onSettingChange={() => void refreshProject()}
            />
          </div>
          {!isMobile && (
            <div className="side-col">
              <RightPanel project={project} />
            </div>
          )}
        </main>
      ) : (
        <div className="no-project">
          <div className="no-project-card">
            <div className="bootstrap-logo">MindOC</div>
            <div className="bootstrap-h">选择一个工程开始创作</div>
            <button className="btn-filled" onClick={() => setDrawerOpen(true)}>打开工程列表</button>
            <button className="btn-tonal" onClick={() => setNewOpen(true)}>
              <Icon name="add" size="small" /> 新建工程
            </button>
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
        onWorkspaceChanged={() => void handleWorkspaceChanged()}
      />
    </div>
  );
}
