@echo off
REM MindOC 开发启动脚本:先起 vite 再编译运行(debug exe 依赖 vite)
cd /d D:\ocstudio
echo [MindOC] 正在启动(首次约 1-2 分钟,窗口弹出前请勿关闭本窗口)...
call npm run tauri dev
pause
