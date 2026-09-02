// 与 Rust 侧结构一一对应的类型

export interface LlmConfig {
  base_url: string;
  api_key: string;
  model: string;
}

export interface ThemeConfig {
  mode: 'system' | 'light' | 'dark';
  seed_color: string;
}

export interface GitHubConfig {
  remote_url: string;
  token: string;
}

export interface AppSettings {
  onboarded: boolean;
  llm: LlmConfig;
  theme: ThemeConfig;
  github: GitHubConfig;
  workspace_dir: string | null;
}

export interface ProjectFile {
  name: string;
  content: string;
}

export interface ProjectInfo {
  name: string;
  desc: string;
  author: string;
  created_at: string;
  updated_at: string;
  path: string;
  remote_url: string;
}

export interface ProjectView {
  info: ProjectInfo;
  files: ProjectFile[];
}

export interface CommitInfo {
  id: string;
  message: string;
  author: string;
  time_secs: number;
}

export interface DiffLine {
  kind: 'add' | 'del' | 'ctx';
  old_no: number | null;
  new_no: number | null;
  text: string;
}

export interface FileDiff {
  path: string;
  kind: string; // A=新增 M=修改 D=删除
  lines: DiffLine[];
}

// agent 流式事件(与 Rust AgentEvent 对应,type 为 snake_case)
export type AgentEvent =
  | { type: 'token'; text: string }
  | { type: 'tool_start'; name: string }
  | { type: 'tool_done'; name: string; summary: string }
  | { type: 'done'; content: string }
  | { type: 'error'; message: string };
