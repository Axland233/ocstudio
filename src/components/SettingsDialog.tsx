import { useEffect, useState } from 'react';
import { api } from '../lib/api';
import type { AppSettings } from '../lib/types';

interface Props {
  open: boolean;
  settings: AppSettings;
  projectName: string | null;
  onClose: () => void;
  onSaved: (s: AppSettings) => void;
}

const SEED_PRESETS = ['#6750A4', '#006A6A', '#8B5000', '#B3261E', '#006C4C', '#3F51B5', '#7D5260'];

export function SettingsDialog({ open, settings, projectName, onClose, onSaved }: Props) {
  const [mode, setMode] = useState(settings.theme.mode);
  const [seed, setSeed] = useState(settings.theme.seed_color);
  const [baseUrl, setBaseUrl] = useState(settings.llm.base_url);
  const [apiKey, setApiKey] = useState(settings.llm.api_key);
  const [model, setModel] = useState(settings.llm.model);
  const [ctxWindow, setCtxWindow] = useState(settings.llm.context_window ?? 131072);
  const [remoteUrl, setRemoteUrl] = useState('');
  const [token, setToken] = useState(settings.github.token);
  const [err, setErr] = useState('');
  const [savedTip, setSavedTip] = useState('');

  // 打开时同步最新值
  useEffect(() => {
    if (!open) return;
    setMode(settings.theme.mode);
    setSeed(settings.theme.seed_color);
    setBaseUrl(settings.llm.base_url);
    setApiKey(settings.llm.api_key);
    setModel(settings.llm.model);
    setCtxWindow(settings.llm.context_window ?? 131072);
    setToken(settings.github.token);
    setErr('');
    setSavedTip('');
    // 当前工程 remote
    if (projectName) {
      api.currentProject().then((v) => setRemoteUrl(v?.info.remote_url ?? '')).catch(() => setRemoteUrl(''));
    }
  }, [open, settings, projectName]);

  if (!open) return null;

  async function saveTheme() {
    const s = await api.saveTheme({ mode, seed_color: seed });
    onSaved(s);
    setSavedTip('主题已保存');
  }
  async function saveLlm() {
    if (!baseUrl.trim() || !apiKey.trim() || !model.trim()) return setErr('请填写 API 地址、密钥与模型名称');
    const window = Math.max(4096, Math.floor(Number(ctxWindow) || 131072));
    const s = await api.saveLlm({
      base_url: baseUrl.trim(),
      api_key: apiKey.trim(),
      model: model.trim(),
      context_window: window,
    });
    onSaved(s);
    setErr('');
    setSavedTip('模型配置已保存');
  }
  async function saveGit() {
    if (projectName) await api.setRemote(remoteUrl.trim());
    const s = await api.saveGithubToken(token.trim());
    onSaved(s);
    setSavedTip('GitHub 配置已保存');
  }

  return (
    <div className="overlay" onClick={onClose}>
      <div className="dialog dialog-wide" onClick={(e) => e.stopPropagation()}>
        <div className="dialog-title">设置</div>

        <div className="sec-title">外观</div>
        <div className="field-row">
          <span>模式</span>
          <div className="seg">
            {(['system', 'light', 'dark'] as const).map((m) => (
              <button key={m} className={mode === m ? 'seg-btn active' : 'seg-btn'} onClick={() => setMode(m)}>
                {{ system: '跟随系统', light: '浅色', dark: '深色' }[m]}
              </button>
            ))}
          </div>
        </div>
        <div className="field-row">
          <span>主题色</span>
          <div className="seed-row">
            <input type="color" value={seed} onChange={(e) => setSeed(e.target.value)} />
            {SEED_PRESETS.map((c) => (
              <button key={c} className="seed-dot" style={{ background: c }} onClick={() => setSeed(c)} />
            ))}
          </div>
        </div>
        <button className="btn-tonal" onClick={() => void saveTheme()}>保存外观</button>

        <div className="sec-title">模型(OpenAI 兼容接口)</div>
        <div className="hint">以服务商官方文档为准填写接口地址、密钥与模型名称。</div>
        <label className="field-label">API 地址</label>
        <input className="md-input" value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder="https://api.example.com/v1" />
        <label className="field-label">API 密钥</label>
        <input className="md-input" type="password" value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder="sk-..." />
        <label className="field-label">模型名称</label>
        <input className="md-input" value={model} onChange={(e) => setModel(e.target.value)} placeholder="按服务商文档填写" />
        <label className="field-label">上下文窗口大小(token)</label>
        <input
          className="md-input"
          type="number"
          min={4096}
          step={1024}
          value={ctxWindow}
          onChange={(e) => setCtxWindow(Number(e.target.value))}
        />
        <div className="hint">按模型官方文档填写(默认 128K;例如 1M 窗口填 1048576)。仅用于自动延续会话的阈值判断,不影响对话本身。</div>
        <button className="btn-tonal" onClick={() => void saveLlm()}>保存模型配置</button>

        <div className="sec-title">GitHub 备份({projectName ? `工程:${projectName}` : '未打开工程'})</div>
        <label className="field-label">远程仓库地址</label>
        <input className="md-input" value={remoteUrl} onChange={(e) => setRemoteUrl(e.target.value)} placeholder="https://github.com/you/repo.git" disabled={!projectName} />
        <label className="field-label">Token(仅保存在本应用配置中,不会写入工程文件)</label>
        <input className="md-input" type="password" value={token} onChange={(e) => setToken(e.target.value)} placeholder="ghp_..." />
        <button className="btn-tonal" onClick={() => void saveGit()}>保存 GitHub 配置</button>

        {err && <div className="form-error">{err}</div>}
        {savedTip && <div className="form-ok">{savedTip}</div>}

        <div className="sec-title">关于本工具</div>
        <div className="about-box">
          <div className="about-name">MindOC(OC Studio)</div>
          <div className="about-line">版本 v0.1.0(原型)· 设定集共创工作台</div>
          <div className="about-line">
            项目地址:
            <a href="https://github.com/Axland233/ocstudio" target="_blank" rel="noreferrer">
              github.com/Axland233/ocstudio
            </a>
          </div>
          <div className="about-line">
            开源协议:
            <a href="https://www.apache.org/licenses/LICENSE-2.0" target="_blank" rel="noreferrer">
              Apache License 2.0
            </a>
          </div>
          <div className="about-note">
            本工具通过你自行填写的 API 服务商处理对话;设定集与对话记录默认仅保存在本机。
          </div>
        </div>

        <div className="dialog-actions">
          <button className="btn-filled" onClick={onClose}>完成</button>
        </div>
      </div>
    </div>
  );
}
