import { useMemo } from 'react';
import { marked } from 'marked';

marked.setOptions({ breaks: true, gfm: true });

export function renderMd(src: string): string {
  return marked.parse(src) as string;
}

/** 只读 markdown 展示块 */
export function MdView({ text, className }: { text: string; className?: string }) {
  const html = useMemo(() => renderMd(text), [text]);
  return <div className={`md-body ${className ?? ''}`} dangerouslySetInnerHTML={{ __html: html }} />;
}
