# -*- coding: utf-8 -*-
"""按码点子集化 assets/fonts/sf_pro.ttf，保留全部可变轴。

背景：上游 43034b8 内置的 Apple SF Pro 可变字体（wght/opsz/wdth 三轴）约 24MB，
但模块实际绘制的只有键盘按键文字（ASCII/常用标点，缺失字形按设计回退宿主
WE-Regular）与 SfSymbolCatalog 里的 SF Symbols 图标（Unicode 平面 10 私用区）。
按码点裁掉用不到的字形后体积可降到 MB 级，同时保留可变轴、布局特性与
无压缩存储（Android mmap 零拷贝渲染不受影响）。

用法：
    py tools/subset_sf_pro.py <完整字体> <输出字体>

完整字体可从上游 tag（upstream/NEORUAA）的 git 历史取回：
    git show 43034b8:app/src/main/assets/fonts/sf_pro.ttf > /tmp/sf_pro_full.ttf
"""
import re
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont

REPO = Path(__file__).resolve().parent.parent
CATALOG = REPO / "app/src/main/java/com/xposed/wetypehook/wetype/graphics/SfSymbolCatalog.kt"

# 键盘按键与符号页可能绘制的文字范围；其余文字（如中文按键文案）按上游设计
# 经 Typeface.CustomFallbackBuilder 回退宿主 WE-Regular，无需内置。
UNICODE_RANGES = [
    (0x0020, 0x007E),  # ASCII 可见字符与空格
    (0x00A0, 0x00FF),  # Latin-1（¥ ° é ü 等）
    (0x2000, 0x206F),  # 常用标点（— – … “ ” 等）
    (0x20A0, 0x20CF),  # 货币符号（€ 等）
    (0x2190, 0x21FF),  # 箭头块（← ↑ → ↓ ⏎ 兜底）
    (0x2300, 0x23FF),  # 杂项技术（⌫ ⏎ 等）
    (0x3000, 0x303F),  # 中文标点（，。？！等）
    (0xFF00, 0xFFEF),  # 全角/半角形式（符号键盘全角键值）
]


def catalog_code_points() -> list[int]:
    text = CATALOG.read_text(encoding="utf-8")
    return [int(m, 16) for m in re.findall(r"0x([0-9A-Fa-f]{5,6})", text)]


def unicodes() -> list[int]:
    points = set()
    for start, end in UNICODE_RANGES:
        points.update(range(start, end + 1))
    points.update(catalog_code_points())
    return sorted(points)


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    src, dst = Path(sys.argv[1]), Path(sys.argv[2])

    keep = unicodes()
    print(f"保留码点 {len(keep)} 个（含目录图标 {len(catalog_code_points())} 个）")

    options = subset.Options()
    options.layout_features = ["*"]  # 保留全部布局特性（kern/liga 等）
    options.notdef_outline = True
    options.glyph_names = False
    options.name_IDs = [0, 1, 2, 3, 4, 6]

    font = TTFont(src)
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=keep)
    subsetter.subset(font)
    font.save(dst)

    saved = TTFont(dst, lazy=True)
    axes = [(a.axisTag, a.minValue, a.maxValue) for a in saved["fvar"].axes]
    # 只关心原字体 cmap 里存在、且在保留集内的码点是否存活；
    # 保留集中原字体本来就没有映射的码点（如 CJK 标点）由回退字体负责，不算缺失。
    cmap = saved.getBestCmap()
    original_cmap = TTFont(src, lazy=True).getBestCmap()
    relevant = [cp for cp in keep if cp in original_cmap]
    missing = [hex(cp) for cp in relevant if cp not in cmap]
    print(f"输出 {dst.stat().st_size / 1048576:.2f} MB（原 {src.stat().st_size / 1048576:.2f} MB）")
    print(f"可变轴 {axes}")
    print(f"字形 {len(saved.getGlyphOrder())} 个（原 {len(TTFont(src, lazy=True).getGlyphOrder())}）")
    print(f"原字体映射且保留的码点 {len(relevant)} 个，缺失 {len(missing)} 个:", missing or "无")
    if missing:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
