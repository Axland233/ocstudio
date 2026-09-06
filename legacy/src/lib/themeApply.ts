import { applyTheme, systemPrefersDark, watchSystemTheme } from './theme';
import type { ThemeConfig } from './types';

/** 让当前主题生效,并处理 mode=system 的自动跟随。返回取消监听函数 */
export function initTheme(theme: ThemeConfig): () => void {
  applyTheme(theme, systemPrefersDark());
  return watchSystemTheme((dark) => {
    if (theme.mode === 'system') applyTheme(theme, dark);
  });
}
