# GitMind

用 git 管理你的想法脑洞,AI 驱动。OC 设定集共创工具的 Android 原生版。

## 技术栈
- Kotlin + Jetpack Compose (Material You / M3,动态取色)
- JGit(git 数据层:每个工程 = 一个 git 仓库,全量快照提交)
- OkHttp(OpenAI 兼容 SSE 流式)
- minSdk 26 / targetSdk 36 / 包名 com.yehenowo.gitmind

## 架构
```
app/src/main/java/com/yehenowo/gitmind/
├─ core/     Agent 引擎(对话循环/工具/SSE/会话滚动/本地token估算)
├─ data/     JGit 引擎 / 工程管理 / 设置 / SAF 导入导出
├─ ui/       Compose 界面(聊天打字机+飞入动画/引导/设置/Git diff)
└─ AppViewModel.kt   全局状态编排
```

## 构建
```
.\gradlew.bat assembleDebug
```
产物:app/build/outputs/apk/debug/app-debug.apk

## 数据格式(与旧版互通)
- 工程 = workspace 下目录:5 个设定 md + project.json,目录即 git 仓库
- 会话 = app 私有目录 sessions/<工程>/NNNN.jsonl(usage≥70%×窗口自动总结滚动)
- 设置 = filesDir/settings.json

`legacy/` 是已废弃的 Tauri 桌面版(Rust + React),保留作规格参考。