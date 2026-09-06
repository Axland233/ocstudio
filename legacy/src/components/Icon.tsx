// Material Symbols Rounded 图标(ligature 渲染,项目内字体)
export function Icon({
  name,
  size,
  className,
}: {
  name: string;
  size?: 'small' | 'tiny';
  className?: string;
}) {
  const cls = ['md-icon', size ? size : '', className ?? ''].filter(Boolean).join(' ');
  return (
    <span className={cls} aria-hidden="true">
      {name}
    </span>
  );
}
