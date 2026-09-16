# 从 Material Symbols Rounded 可变字体裁出项目用到的图标码点。
# 保留 FILL/GRAD/opsz/wght 四轴可变性(运行时按 filled 参数切 FILL)。
# 图标名不硬编码码点:直接用字体自带 liga 连字表解析出字形,再从 cmap 反查码点,
# 保证与官方 codepoints 一致。新增图标:往 ICONS 里加名字重跑本脚本即可。
# 用法: python3.13 make_icon_subset.py
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "Material_Symbols_Rounded" / "MaterialSymbolsRounded-VariableFont_FILL,GRAD,opsz,wght.ttf"
OUT = ROOT.parent / "app" / "src" / "main" / "res" / "font" / "material_symbols_rounded.ttf"

ICONS = [
    "menu", "settings", "history", "send", "stop", "add",
    "auto_awesome", "file_upload", "file_download",
    "palette", "folder_open", "auto_stories", "commit", "close", "arrow_back",
    "visibility", "visibility_off",
]

font = TTFont(SRC)
cmap = font.getBestCmap()            # 码点 -> 字形名
rev = {g: cp for cp, g in cmap.items()}

# 收集 GSUB 里所有 LigatureSubst(含 Extension 包裹):组件字形序列 -> 连字字形
ligatures = {}
for lookup in font["GSUB"].table.LookupList.Lookup:
    for st in lookup.SubTable:
        if lookup.LookupType == 7:
            st = st.ExtSubTable
        ligs = getattr(st, "ligatures", None)
        if not ligs:
            continue
        for first, lig_list in ligs.items():
            for lig in lig_list:
                comps = tuple([first] + list(lig.Component))
                ligatures.setdefault(comps, lig.LigGlyph)


def resolve(name: str) -> int:
    comps = tuple(cmap[ord(c)] for c in name)
    glyph = ligatures.get(comps)
    if glyph is None or glyph not in rev:
        raise SystemExit(f"图标名解析失败: {name}")
    return rev[glyph]


cps = []
print("图标            码点")
for name in ICONS:
    cp = resolve(name)
    cps.append(cp)
    print(f"{name:<16} U+{cp:04X}")

opts = subset.Options()
opts.layout_features = []            # 运行时用码点直取,连字表不需要
opts.name_IDs = [1, 2, 3, 4, 6]
ss = subset.Subsetter(opts)
ss.populate(unicodes=cps)
ss.subset(font)
OUT.parent.mkdir(parents=True, exist_ok=True)
font.save(OUT)

# 自检:子集后 cmap 仍含全部码点,且四个可变轴都在
chk = TTFont(OUT)
have = set(chk.getBestCmap())
missing = [f"U+{c:04X}" for c in cps if c not in have]
assert not missing, f"丢失码点: {missing}"
axes = {a.axisTag: (a.minValue, a.maxValue) for a in chk["fvar"].axes}
assert {"FILL", "GRAD", "opsz", "wght"} <= set(axes), f"可变轴缺失: {axes}"
print(f"OK -> {OUT} ({OUT.stat().st_size / 1024:.0f} KB) axes={axes}")
