import { useState } from 'react';
import { api } from '../lib/api';
import type { AppSettings } from '../lib/types';

interface Props {
  settings: AppSettings;
  onDone: (s: AppSettings) => void;
}

/**
 * 首次引导向导(三步):
 * 1) 工程目录(PC 可选目录;移动端默认应用目录,未来 SAF 授权)
 * 2) 工程信息:名称 / 描述 / 作者
 * 3) API 配置:供应商 + 地址 + 密钥 + 模型
 */
export function Bootstrap({ settings, onDone }: Props) {
  const [step, setStep] = useState(1);
  const [workspace, setWorkspace] = useState<string | null>(settings.workspace_dir);
  const [name, setName] = useState('');
  const [desc, setDesc] = useState('');
  const [author, setAuthor] = useState('');
  const [baseUrl, setBaseUrl] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [model, setModel] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  const isMobile = window.innerWidth < 900;

  async function pickDir() {
    try {
      const p = await api.pickWorkspace();
      if (p) setWorkspace(p);
    } catch (e) {
      setErr(String(e));
    }
  }

  async function finish() {
    setErr('');
    setBusy(true);
    try {
      // 先存 LLM 配置(标记引导完成;窗口默认 128K,引导页不细设,设置里可改)
      let s = await api.saveLlm({
        base_url: baseUrl.trim(),
        api_key: apiKey.trim(),
        model: model.trim(),
        context_window: 131072,
      });
      // 再建工程
      if (name.trim()) {
        await api.createProject(name.trim(), desc.trim(), author.trim() || '匿名');
      }
      s = await api.bootstrap();
      onDone(s);
    } catch (e) {
      setErr(String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="bootstrap">
      <div className="bootstrap-card">
        <div className="bootstrap-logo">MindOC</div>
        <div className="bootstrap-sub">你的设定集共创工作台 · 首次使用引导</div>

        <div className="steps">
          {[1, 2, 3].map((i) => (
            <div key={i} className={step === i ? 'step active' : 'step'} onClick={() => i < step && setStep(i)}>
              {i === 1 ? '① 目录' : i === 2 ? '② 工程' : '③ API'}
            </div>
          ))}
        </div>

        {step === 1 && (
          <>
            <div className="bootstrap-h">工程文件存放在哪?</div>
            {!isMobile && (
              <>
                <div className="bootstrap-p">选择一个目录用于存放你的所有工程(每个工程是一个 git 仓库文件夹)。</div>
                <button className="btn-tonal" onClick={() => void pickDir()}>
                  {workspace ? `已选择:${workspace}` : '选择工程目录'}
                </button>
              </>
            )}
            {isMobile && (
              <div className="bootstrap-p">
                手机端工程将存放在应用文档目录(Documents/MindOC),后续可通过系统授权选择自定义目录。
              </div>
            )}
            <div className="hint">没有特殊需求可以直接下一步,使用默认位置。</div>
          </>
        )}

        {step === 2 && (
          <>
            <div className="bootstrap-h">创建你的第一个工程</div>
            <label className="field-label">工程名称 *</label>
            <input className="md-input" value={name} onChange={(e) => setName(e.target.value)} placeholder="例如:雾海之城" />
            <label className="field-label">一句话描述</label>
            <textarea className="md-input" rows={2} value={desc} onChange={(e) => setDesc(e.target.value)} placeholder="这个世界讲的是什么?" />
            <label className="field-label">作者(写入 git 提交记录)</label>
            <input className="md-input" value={author} onChange={(e) => setAuthor(e.target.value)} placeholder="你的名字/笔名" />
          </>
        )}

        {step === 3 && (
          <>
            <div className="bootstrap-h">接入大模型(OpenAI 兼容接口)</div>
            <div className="bootstrap-p">
              填写你所用服务商提供的 OpenAI 兼容接口地址、API 密钥与模型名称(以服务商官方文档为准)。
            </div>
            <label className="field-label">API 地址</label>
            <input className="md-input" value={baseUrl} onChange={(e) => setBaseUrl(e.target.value)} placeholder="https://api.example.com/v1" />
            <label className="field-label">API 密钥</label>
            <input className="md-input" type="password" value={apiKey} onChange={(e) => setApiKey(e.target.value)} placeholder="sk-..." />
            <label className="field-label">模型名称</label>
            <input className="md-input" value={model} onChange={(e) => setModel(e.target.value)} placeholder="按服务商文档填写" />
            <div className="hint">对话内容将发送到你填写的服务商处理,请自行确认其隐私政策与数据使用条款。</div>
          </>
        )}

        {err && <div className="form-error">{err}</div>}

        <div className="dialog-actions">
          {step > 1 && <button className="btn-text" onClick={() => setStep(step - 1)}>上一步</button>}
          {step < 3 ? (
            <button className="btn-filled" onClick={() => setStep(step + 1)}>下一步</button>
          ) : (
            <button className="btn-filled" disabled={busy || !baseUrl.trim() || !apiKey.trim() || !model.trim()} onClick={() => void finish()}>
              {busy ? '创建中…' : '开始创作'}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
