#!/usr/bin/env python3
"""气泡多大：一句话折几行、一张图缩到多宽 —— 以及"框跟着内容走"这件事的判据。

    python3 tools/bubble_check.py

用户报的是「说话的内容框能自动适配大小」。在这之前气泡是**写死的**：`x ± 200`、`y - 82 .. y - 4`，
一句话一行不折 —— 长句子从框里溢出来，短句子留一大块空白。现在框的大小是**算出来的**
（`render/Bubble.kt`），而这一份就是那道算术的镜像。

为什么值得有镜像：折行和封顶错了，看上去只是"这个气泡有点丑"或"字挤出去了" —— 而它是
**每一句话**都会经过的一条路（规则里十句话有八句是"说一句话"）。能在本地逐条钉住的东西，
不该靠手机上一句句看。

镜像和 Kotlin 的常数**从源码里读**（`PAD_X` / `MAX_TEXT_W` / `LINE_H` / `MAX_LINES` /
`MAX_IMAGE` / `GAP`）：漂了的镜像比没有镜像更糟，它会一直说自己是对的。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
BUBBLE_KT = os.path.join(REPO, "app/src/main/java/dev/atp/pet/render/Bubble.kt")
VIEW_KT = os.path.join(REPO, "app/src/main/java/dev/atp/pet/ui/PhysicsSandboxView.kt")

FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)
    return ok


def constants():
    """从 Bubble.kt 里把常数读出来 —— 镜像的职责是"和它一样"，不是"我记得是 520"。"""
    src = open(BUBBLE_KT, encoding="utf-8").read()
    out = {}
    for name in ("PAD_X", "PAD_Y", "MAX_TEXT_W", "LINE_H", "MAX_LINES", "MAX_IMAGE", "GAP"):
        m = re.search(r"const val %s = ([0-9.]+)f?" % name, src)
        out[name] = float(m.group(1)) if m else None
    return out


C = constants()
PAD_X, PAD_Y = C["PAD_X"], C["PAD_Y"]
MAX_TEXT_W, LINE_H = C["MAX_TEXT_W"], C["LINE_H"]
MAX_LINES, MAX_IMAGE, GAP = int(C["MAX_LINES"] or 0), C["MAX_IMAGE"], C["GAP"]

#: 一把假尺子：一个字宽 44（和气泡字号同量级），ASCII 半个宽。用假尺子是因为这里要证的
#: 是**折行和封顶的规矩**，不是某台设备上 Paint 量出来多少像素。
def measure(text):
    return sum(22.0 if ord(ch) < 128 else 44.0 for ch in text)


def wrap(text, max_width=MAX_TEXT_W):
    """镜像 Bubble.wrap：按宽度贪心折，只在字与字之间断。"""
    out = []
    for paragraph in text.split("\n"):
        if paragraph == "":
            out.append("")
            continue
        line = ""
        for ch in paragraph:
            if line and measure(line + ch) > max_width:
                out.append(line)
                line = ""
            line += ch
        if line:
            out.append(line)
    return out


def fit(text):
    """镜像 Bubble.fit：折完封顶，超出的用省略号收尾。"""
    lines = wrap(text)
    if len(lines) <= MAX_LINES:
        return lines
    kept = lines[:MAX_LINES]
    kept[MAX_LINES - 1] = kept[MAX_LINES - 1] + "…"
    return kept


def text_size(lines):
    """镜像 Bubble.textSize：[宽, 高]。宽取最长那一行，高取行数。"""
    widest = max([measure(line) for line in lines] or [0.0])
    rows = max(len(lines), 1)
    return (widest + PAD_X * 2, rows * LINE_H + PAD_Y * 2)


def image_size(w, h):
    """镜像 Bubble.imageSize：按比例缩到最长边不超过 MAX_IMAGE，小图不放大。"""
    if w <= 0 or h <= 0:
        return (MAX_IMAGE / 2, MAX_IMAGE / 2)
    longest = float(max(w, h))
    k = MAX_IMAGE / longest if longest > MAX_IMAGE else 1.0
    return (w * k + PAD_X * 2, h * k + PAD_Y * 2)


def box(x, y, size, view_left, view_right):
    """镜像 Bubble.box：以头顶那一点为底边中点向上长，左右夹在**看得见的那一块**里。"""
    half = size[0] / 2
    left = min(max(x - half, view_left), max(view_right - size[0], view_left))
    bottom = y - GAP
    return (left, bottom - size[1], left + size[0], bottom)


def main():
    view = open(VIEW_KT, encoding="utf-8").read()
    print("== 0. 镜像和 Kotlin 的常数一致（从源码读，不是抄一遍）==")
    for name, value in C.items():
        report("%s 读得到" % name, value is not None, str(value))

    print("\n== 1. 折行：框跟着内容走 ==")
    report("一句话不折（够短就一行）", wrap("你好") == ["你好"])
    long_cn = "主人" * 30
    lines = wrap(long_cn)
    report("长句子折成好几行", len(lines) > 1, "%d 行" % len(lines))
    report("**折完没有一行超宽**（这就是「字挤出框」的那条）",
           all(measure(line) <= MAX_TEXT_W + 44 for line in lines),
           "最宽的一行 %d，上限 %d" % (max(measure(l) for l in lines), MAX_TEXT_W))
    report("一个字都没丢、也没多（折行只是换行，不改内容）",
           "".join(lines) == long_cn)
    report("显式的换行是**用户的意思**（不并成一行）",
           wrap("第一行\n第二行") == ["第一行", "第二行"])
    report("空句子给一行空的（不是一个没有行的框）", wrap("") == [""])

    print("\n== 2. 封顶：再长也不许盖住半只宠物 ==")
    huge = wrap("很长" * 400)
    report("超长的那句先折出很多行", len(huge) > MAX_LINES, "%d 行" % len(huge))
    report("封顶到 %d 行，最后一行带省略号" % MAX_LINES,
           len(fit("很长" * 400)) == MAX_LINES and fit("很长" * 400)[-1].endswith("…"))
    report("没超的时候一个字都不改（不画蛇添足加省略号）",
           fit("你好") == ["你好"])

    print("\n== 3. 框的尺寸：宽取最长那一行，高取行数 ==")
    one = text_size(["你好"])
    two = text_size(["你好", "你好"])
    report("一行的高 = 行高 + 上下留白", one[1] == LINE_H + PAD_Y * 2, str(one[1]))
    report("两行就是两倍行高（多一行多一行的地方）", two[1] == one[1] + LINE_H)
    report("宽取最长那一行（短的居中对齐，不留歪）",
           text_size(["短", "长长长"])[0] == measure("长长长") + PAD_X * 2)

    print("\n== 4. 一张图：按比例，不拉扁、不放大 ==")
    big = image_size(1200, 600)
    report("大图缩到最长边 %d（加上留白）" % MAX_IMAGE,
           abs(big[0] - (MAX_IMAGE + PAD_X * 2)) < 0.01 and abs(big[1] - (MAX_IMAGE / 2 + PAD_Y * 2)) < 0.01,
           "%.0f×%.0f" % big)
    small = image_size(40, 20)
    report("小图**不放大**（一张 40px 的贴纸拉成 420 会糊）",
           abs(small[0] - (40 + PAD_X * 2)) < 0.01 and abs(small[1] - (20 + PAD_Y * 2)) < 0.01,
           "%.0f×%.0f" % small)
    a, b = image_size(1000, 500)
    report("比例留着（宽是高的两倍，框也是）",
           abs((a - PAD_X * 2) / (b - PAD_Y * 2) - 2.0) < 0.01)
    report("尺寸是 0 也不崩（给一个能看的默认）", image_size(0, 0)[0] > 0)

    print("\n== 5. 框挂在宠物头顶，而且夹在**看得见的那一块**里 ==")
    size = text_size(["你好"])
    bx = box(500, 900, size, 0, 1024)
    report("底边在头顶上方 %d（不是压在头上）" % GAP, abs(bx[3] - (900 - GAP)) < 0.01)
    report("左右居中（框的中点就是那一点）", abs((bx[0] + bx[2]) / 2 - 500) < 0.01)
    edge = box(10, 900, size, 0, 1024)
    report("贴着左边缘时不探出去（探出去就是「这句话没说全」）", edge[0] >= 0, "%.0f" % edge[0])
    wide = box(500, 900, (2000.0, 100.0), 0, 1024)
    report("比看得见的那块还宽的框至少从左边缘开始（夹得住，不崩）", wide[0] == 0)
    # 1.39.0：夹的必须是**看得见的那一块**，不是角色那张画的画布 —— 用户报的"悬浮气泡被限定
    # 在了一个区域内，没有跟着角色走"就是这条：宠物走出那 1024 宽的带子，气泡钉在带子边上。
    follow = box(3000, 900, size, 2400, 3400)
    report("宠物走到 x=3000 时气泡跟着走到 3000（不是钉在 1024 的带子边上）",
           abs((follow[0] + follow[2]) / 2 - 3000) < 0.01, "%.0f" % ((follow[0] + follow[2]) / 2))
    far = box(5200, 900, size, 2400, 3400)
    report("走出看得见的那一块时才夹住（夹在右边缘，不是夹在画布边）",
           abs(far[2] - 3400) < 0.01, "右边 %.0f" % far[2])
    pan = box(500, 900, size, 0, 1024)
    report("画布边界（1024）不再参与夹取 —— 同一个 x、窗口不同，结果不同",
           pan[0] != follow[0] and abs(pan[0] - follow[0]) > 100)

    print("\n== 6. 视图那边真的用它（不然这些算术只是好看的函数）==")
    report("画气泡走的是 Bubble 的三个函数",
           "Bubble.fit(" in view and "Bubble.textSize(" in view
           and "Bubble.imageSize(" in view and "Bubble.box(" in view)
    report("写死的那个 400×78 不在了（`x - 200f` 那一行）",
           "x - 200f" not in view and "y - 82f" not in view)
    report("图也走同一个气泡（说话和发图是同一件事的两半）",
           "bubbleArt" in view and "IMAGE_BUBBLE_SECONDS" in view
           and "imageArt[a.text]" in view)
    # 1.39.0：夹的是**看得见的那一块**，不是角色那张画的画布（用户报的"被限定在了一个区域
    # 内、没有跟着角色走"）。
    report("调用点传的是视口（panX / viewScale），不是 s.canvasWidth",
           "Bubble.box(x, y, size, viewLeft, viewRight)" in view
           and "val viewRight = panX + width / viewScale" in view
           and "Bubble.box(x, y, size, s.canvasWidth)" not in view)

    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        for f in FAILURES:
            print("  " + f)
        return 1
    print("all bubble tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
