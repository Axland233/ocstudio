// Material 3 动态主题:种子色 -> 全量 --md-sys-color-* token
//
// 两个关键点(踩过坑):
// 1. material-color-utilities 的 toJSON() 输出 camelCase 键(onSurface),
//    Material Web/CSS 变量是 kebab-case(--md-sys-color-on-surface),必须转换。
// 2. toJSON() 只含旧版 scheme 字段,【不含 surface-container 系列】;
//    surface-container-low/high 等是 MD3 新 token,需从 palettes.neutral 手动补,
//    否则这些变量永远停在 CSS 兜底值,深浅色切换会一半深一半浅。
import { argbFromHex, themeFromSourceColor } from '@material/material-color-utilities';
import type { ThemeConfig } from './types';

function hexFromArgb(argb: number): string {
  return '#' + (argb & 0xffffff).toString(16).padStart(6, '0');
}

/** camelCase -> kebab-case(onSurface -> on-surface) */
function toKebab(key: string): string {
  return key.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase());
}

/** MD3 surface-container 系列在中性色 palette 上的 tone(light / dark) */
const SURFACE_CONTAINER_TONES: Record<string, [number, number]> = {
  'surface-container-lowest': [100, 4],
  'surface-container-low': [96, 10],
  'surface-container': [94, 12],
  'surface-container-high': [92, 17],
  'surface-container-highest': [90, 22],
  'surface-dim': [87, 6],
  'surface-bright': [98, 24],
};

export function applyTheme(theme: ThemeConfig, systemDark: boolean) {
  const dark = theme.mode === 'dark' || (theme.mode === 'system' && systemDark);
  const source = themeFromSourceColor(argbFromHex(theme.seed_color || '#6750A4'));
  const scheme = dark ? source.schemes.dark : source.schemes.light;
  const root = document.documentElement;

  // 1) toJSON 输出的标准 token
  const tokens = scheme.toJSON() as Record<string, number>;
  for (const [key, value] of Object.entries(tokens)) {
    root.style.setProperty(`--md-sys-color-${toKebab(key)}`, hexFromArgb(value));
  }

  // 2) 手动补 surface-container 系列(toJSON 不含)
  const neutral = source.palettes.neutral;
  for (const [name, [lightTone, darkTone]] of Object.entries(SURFACE_CONTAINER_TONES)) {
    const tone = dark ? darkTone : lightTone;
    root.style.setProperty(`--md-sys-color-${name}`, hexFromArgb(neutral.tone(tone)));
  }

  root.dataset.theme = dark ? 'dark' : 'light';
  // 让原生控件(select 下拉、滚动条、color picker 等)跟随深浅色
  root.style.colorScheme = dark ? 'dark' : 'light';
}

/** 返回当前系统是否深色 */
export function systemPrefersDark(): boolean {
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false;
}

/** 监听系统深浅切换(theme.mode === 'system' 时自动跟随) */
export function watchSystemTheme(cb: (dark: boolean) => void): () => void {
  const mq = window.matchMedia('(prefers-color-scheme: dark)');
  const handler = (e: MediaQueryListEvent) => cb(e.matches);
  mq.addEventListener('change', handler);
  return () => mq.removeEventListener('change', handler);
}
