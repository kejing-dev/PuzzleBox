"""Contrast table (D13, ui-audit): every text colour x its background (WCAG ratio) and the 3D colour pairs (CIE L*)."""
def lin(c):
    c = c / 255
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
def hexrgb(h): h = h.lstrip('#'); return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))
def Y(h): r, g, b = (lin(v) for v in hexrgb(h)); return 0.2126 * r + 0.7152 * g + 0.0722 * b
def ratio(a, b): ya, yb = sorted((Y(a), Y(b)), reverse=True); return (ya + 0.05) / (yb + 0.05)
def L(h): y = Y(h); return 116 * (y ** (1 / 3) if y > 0.008856 else 7.787 * y + 16 / 116) - 16
GLASS = "#B4B3B1"  # darkest measured window glass (royal-ur / factory measure)
pairs = [
    ("正文 Ink / 窗口玻璃", "#1B1F23", GLASS, 4.5), ("次要字 InkSecondary / 玻璃", "#3A3F44", GLASS, 4.5),
    ("状态卡 Ink / TagFace", "#1B1F23", "#FBF6EC", 4.5), ("卡内次要字 / TagFace", "#3A3F44", "#FBF6EC", 4.5),
    ("盒子卡 Ink / CardFace", "#1B1F23", "#F3EADB", 4.5), ("盒子卡次要字 / CardFace", "#3A3F44", "#F3EADB", 4.5),
    ("主按钮字 白 / Primary", "#FFFFFF", "#9E3A28", 4.5), ("悔一步高亮 白 / Primary", "#FFFFFF", "#9E3A28", 4.5),
    ("退出按钮字 白 / Error", "#FFFFFF", "#B3261E", 4.5), ("新纪录 白 / Primary", "#FFFFFF", "#9E3A28", 4.5),
    ("玩法序号 白 / Primary", "#FFFFFF", "#9E3A28", 4.5), ("装过勾 Done / CardFace（图标）", "#2E7D4F", "#F3EADB", 3.0),
    ("铭牌 墨字 / 黄铜", "#2A1C0C", "#C9A24E", 4.5), ("木签 墨字 / 浅枫木", "#2E221B", "#EED9B4", 4.5),
    ("盒口刻度 白 / 胡桃盒底（透过亚克力）", "#FFFFFF", "#5A3A26", 3.0),
]
print("| 文字 | 前景 | 底色 | 对比度 | 门槛 | 结果 |\n|---|---|---|---|---|---|")
for n, f, b, t in pairs:
    r = ratio(f, b); print(f"| {n} | {f} | {b} | {r:.2f}:1 | {t} | {'过' if r >= t else '不过'} |")
print()
lp = [("枫木托盘 vs 最暗块 紫 #7B5EA7", "#E3C698", "#7B5EA7", 25), ("枫木托盘 vs 黄 #E9C46A（同色系）", "#E3C698", "#E9C46A", 0),
      ("胡桃盒底 vs 黄 #F7B32B", "#5A3A26", "#F7B32B", 25), ("胡桃盒底 vs 绿松 #2A9D8F", "#5A3A26", "#2A9D8F", 15),
      ("空洞暗红 vs 胡桃盒底", "#9B1C1C", "#5A3A26", 0), ("落点标记 #E9E4D8 vs 亚克力盒口", "#E9E4D8", "#5A3A26", 25),
      ("左螺旋 #3FA7D6 vs 右螺旋 #F7B32B", "#3FA7D6", "#F7B32B", 0), ("转向台面 vs 块（最暗紫）", "#EED9B4", "#7B5EA7", 25)]
print("| 3D 对比 | A | B | ΔL* | 门槛 |\n|---|---|---|---|---|")
for n, a, b, t in lp:
    print(f"| {n} | {a} | {b} | {abs(L(a) - L(b)):.1f} | {'≥ %d' % t if t else '—'} |")
