# MindOC / OC Studio — 设定集共创 App(原型)

跨平台(Windows / macOS / Android;iOS 后置)的 OC 创作应用:
朋友各自设备上本地运行,自带 OpenAI 兼容 API Key,设定集 = git 仓库。

## 技术栈

- **壳 / 跨端**:Tauri 2(系统 WebView;PC = WebView2/WKWebView,Android = WebView)
- **后端**:Rust 单体(agent 循环 + 文件 + git)
- **git 引擎**:gix(gitoxide 0.87,纯 Rust)——commit/log/diff 本地操作
- **前端**:React 19 + Vite + TS + @material/web(Material 3 动态主题)
- **LLM**:OpenAI 兼容 `/chat/completions`(SSE 流式 + function calling),用户自填 base_url/key/model

## 工程结构

```
ocstudio/
├─ src/                      # Web UI(自适应 PC/手机)
│  ├─ components/            # 引导/聊天/右侧栏/抽屉/对话框
│  └─ lib/                   # api.ts(invoke 封装)/ theme.ts(M3 动态主题)
└─ src-tauri/
   └─ src/
      ├─ lib.rs              # Tauri commands + 状态(当前工程/对话历史)
      ├─ settings.rs         # App 配置(JSON):llm/theme/github/workspace
      ├─ projects.rs         # 工程管理:5 设定 md + project.json + git init
      ├─ gitmod.rs           # gix 封装:init/commit_all/log/diff
      └─ agent/              # LLM 层 + 工具(固化)+ 对话循环
```

## 数据模型

- **工程目录**(= git 仓库,扁平):
  `核心卡.md / 人设.md / 世界观.md / 剧情线.md / 脑洞池.md / project.json`
- **project.json**(进 git,可分享的"工程身份证"):
  `{ app: "mindoc", schema_version, name, desc, author, created_at, updated_at, github: { remote_url } }`
  token 永不进工程文件,存 App 全局配置。
- App 配置:平台 app_data_dir/settings.json

## 工作流

1. 首次引导:选工程目录(PC)→ 建工程 → 填 API
2. 聊天 → agent 循环调 LLM → 模型调工具 `write_project_file`(白名单 5 md)
   → 写入 + 全量快照 commit(gix)→ 流式回 UI → 右侧栏刷新设定集/git 历史
3. 历史页:git log + 任意两 commit 的变更文件列表

## 已知边界(原型)

- **push/fetch 未实现**:gitoxide 0.87 尚无客户端 push 高层 API。候选:
  a) 等 gix push 落地;b) git2(libgit2,需 NDK C 编译);c) GitHub Contents API 逐文件提交
- **Android 目录授权(SAF)未实现**:当前工程存 App 私有目录;
  后续按需求:默认 Documents/MindOC,引导用户 SAF 授权自定义目录(需 Kotlin 插件桥)
- git diff 目前为"变更文件列表",无逐行 diff
- 对话历史存内存,切工程即清;未落盘
- 供应商预设仅前端;后端 settings 存原始 base_url/key/model

## 开发命令

```bash
npm run tauri dev          # 桌面开发(自动起 WebView)
npm run tauri build        # 桌面打包
npm run tauri android init # 初始化 Android 工程(需 cargo-ndk + NDK)
npm run tauri android dev  # Android 真机/模拟器
```

## 工程名历史

项目代码名 ocstudio;产品名 MindOC。目录 D:\ocstudio。
