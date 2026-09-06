// Tauri invoke 封装
import { invoke, Channel } from '@tauri-apps/api/core';
import type {
  AgentEvent,
  AppSettings,
  ChatHistoryMsg,
  CommitInfo,
  FileDiff,
  LlmConfig,
  ProjectInfo,
  ProjectView,
  TestResult,
  ThemeConfig,
} from './types';

export const api = {
  bootstrap: () => invoke<AppSettings>('bootstrap'),
  saveLlm: (llm: LlmConfig) => invoke<AppSettings>('save_llm', { llm }),
  saveTheme: (theme: ThemeConfig) => invoke<AppSettings>('save_theme', { theme }),
  saveGithubToken: (token: string) => invoke<AppSettings>('save_github_token', { token }),
  pickWorkspace: () => invoke<string | null>('pick_workspace'),
  workspacePath: () => invoke<string>('workspace_path'),
  backExit: () => invoke<void>('back_exit'),
  createProject: (name: string, desc: string, author: string) =>
    invoke<ProjectInfo>('create_project', { name, desc, author }),
  listProjects: () => invoke<ProjectInfo[]>('list_projects'),
  openProject: (name: string) => invoke<ProjectView>('open_project', { name }),
  currentProject: () => invoke<ProjectView | null>('current_project'),
  chatHistory: () => invoke<ChatHistoryMsg[]>('chat_history'),
  chatStop: () => invoke<void>('chat_stop'),
  testConnection: (baseUrl?: string, apiKey?: string, model?: string) =>
    invoke<TestResult>('test_connection', { baseUrl, apiKey, model }),
  gitLog: () => invoke<CommitInfo[]>('git_log'),
  gitDiff: (oldId: string | null, newId: string) =>
    invoke<FileDiff[]>('git_diff', { old: oldId, new: newId }),
  setRemote: (remoteUrl: string) => invoke<ProjectInfo>('set_remote', { remoteUrl }),
};

/** 发起一次对话,返回 Channel(已 onmessage 挂上 handler);await 结束 = 后端跑完 */
export async function chatSend(
  text: string,
  onEvent: (ev: AgentEvent) => void,
): Promise<void> {
  const channel = new Channel<AgentEvent>();
  channel.onmessage = onEvent;
  await invoke('chat_send', { channel, text });
}
