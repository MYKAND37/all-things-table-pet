#!/usr/bin/env python3
"""逻辑图的**布局**：分支往右缩进一格、一条规则的行被括号括成一块、下面的规则自己让位。

    python3 tools/graph_layout_check.py                  # 断言
    python3 tools/graph_layout_check.py --png /tmp/g.png  # 再画一张预览（有 PIL 时）

用户的原话是「一条规则在添加分支时，他的分支不会与其他规则并列在开头，而是直接向下后向右
拐弯，多个分支就像打开括号一样，还有一条规则的多个分支应该带着一块，如果添加新分支，下面
又有其他规则，位置不够会自己挤开」。这几句话落在代码里就是三件事：

  1. **缩进**：吊在别人下面的行从 `深度 × 一格` 开始，不再从 0 开始（"与其他规则并列在
     开头"就是所有行都从 0 开始，于是一条规则的三支和三条规则长得一模一样）；
  2. **括号**：一条规则 = 一个**块**（一行 + 它所有后代），块左边一根竖括号把它们括起来；
     块的范围是从挂点（`Drop`）里算出来的，所以"哪几行是一块的"不需要另外维护一份名单 ——
     每一行都指回"我挂在哪一行上"；
  3. **自己让位**：整个布局是**每次重算**的（一行行往下排），所以中间插一行，它下面的每一
     行都往后挪 —— 这一条用"任意两行不重叠、挪的距离正好是那一行的高度 + 行距"来钉。

镜像只做那几件事的**算术**（不算真实文字宽度，用一把假尺子）：要证的是结构和位置，不是某
台设备上 Paint 量出来多少像素。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
GRAPH_KT = os.path.join(REPO, "app/src/main/java/dev/atp/pet/ui/LogicGraphView.kt")

FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)
    return ok


def constants():
    src = open(GRAPH_KT, encoding="utf-8").read()
    out = {}
    for name in ("TITLE_H", "PILL_H", "LINE_H", "GAP", "ROW_GAP", "MAX_W", "INDENT_DP"):
        m = re.search(r"(?:const )?val %s = ([0-9.]+)f" % name, src)
        out[name] = float(m.group(1)) if m else None
    return out


C = constants()
TITLE_H, PILL_H, LINE_H = C["TITLE_H"], C["PILL_H"], C["LINE_H"]
GAP, ROW_GAP, INDENT_DP, MAX_W = C["GAP"], C["ROW_GAP"], C["INDENT_DP"], C["MAX_W"]


# ── 镜像：Kotlin layout() 的那几段算术 ────────────────────────────────────────────

def text_w(text):
    """假尺子：英文 7px、汉字 13px。"""
    return sum(7.0 if ord(ch) < 128 else 13.0 for ch in text)


def node_w(lines, pill=False):
    if pill:
        return max([text_w(l) for l in lines] or [0.0]) + 26
    widest = text_w("＋") + 24
    for line in lines:
        widest = max(widest, text_w(line) + 28)
    return min(widest, MAX_W)


def node_h(lines, pill=False):
    return PILL_H if pill else TITLE_H + len(lines) * LINE_H


def depths_of(drops):
    """镜像 layout() 里那一段：顺着挂点往上数，数到根（parent < 0）就是第 0 格。"""
    out = []
    for i in range(len(drops)):
        depth, at, hops = 0, drops[i], 0
        while isinstance(at, int) and 0 <= at < i and hops <= len(drops):
            depth += 1
            at = drops[at]
            hops += 1
        out.append(depth)
    return out


def blocks_of(drops):
    """镜像：每个还有后代的行的下标 .. 它最后一个后代的下标。"""
    out = []
    for parent in range(len(drops)):
        last = -1
        for i in range(parent + 1, len(drops)):
            at, hops = drops[i], 0
            while isinstance(at, int) and 0 <= at < len(drops) and hops <= len(drops):
                if at == parent:
                    last = i
                    break
                at = drops[at]
                hops += 1
        if last > parent:
            out.append((parent, last))
    return out


def bracket_x(depths, first, last):
    """括号站的那一列：挂点行的左边缘再往里 6dp。

    不是"离分支半格"：挂线也有一条竖道（[lane_x]），两条竖线挨太近就看成一根了 —— 一个说
    "这是谁的分支"，一个说"从哪儿下来"，得看得出来是两件事。
    """
    return depths[first] * INDENT_DP + 6


def lane_x(depths, first, last, parent_centre):
    """挂线那一列：在每一支左边 10dp（取不到就退回父方块中心）。

    「向下后向右拐弯」：主干先在父方块底下横着挪到这条竖道，顺着它往下，最后一支一支往右
    拐进去 —— 落在父方块正下方会从中间穿过下面那些行。
    """
    return min(parent_centre, min(depths[i] * INDENT_DP for i in range(first + 1, last + 1)) - 10)


def layout(rules, drops):
    """镜像 layout()。

    `rules` 是行，一行是几个盒子；盒子是 `(lines, pill)`。返回：
      boxes —— 所有盒子 (x, y, w, h)，按画的顺序（也就是按行）；
      rows  —— 每行 (y0, y1, x0)，x0 是这一行的起点（缩进后的那个 x）。
    """
    depths = depths_of(drops)
    boxes, rows = [], []
    y = 0.0
    for i, rule in enumerate(rules):
        x = depths[i] * INDENT_DP
        x0, tallest = x, 0.0
        for (lines, pill) in rule:
            w, h = node_w(lines, pill), node_h(lines, pill)
            boxes.append((x, y, w, h))
            x += w + GAP
            tallest = max(tallest, h)
        rows.append((y, y + tallest, x0))
        y += tallest + ROW_GAP
    return boxes, rows, depths, blocks_of(drops)


def ladder_drops(else_rows, start_at_main=True):
    """镜像 MainActivity 里"一条主行 + 一级一级往下的否则如果"那段挂点。

    `else_rows[i]` 是第 i 条规则有几行向下的阶梯。返回每行的挂点行号。
    """
    drops, at = [], 0
    for count in else_rows:
        main_row = at
        drops.append(-1)                       # 主行
        at += 1
        from_row = main_row if start_at_main else 0
        for _ in range(count):
            drops.append(from_row)
            from_row = at                      # 下一级挂在刚刚那一行上
            at += 1
    return drops


# ── 样例：一条带三种分支的规则（分支里还套着一层）+ 下面一条普通规则 ──────────────

def n(*lines, pill=False):
    """一个盒子：几行字 + 它是不是那种小药丸（＋当 / ＋如果 / ＋就 / 或者）。"""
    return (list(lines), pill)


SAMPLE = [
    [n("当 被点一下"), n("＋当", pill=True), n("如果 痛苦 > 60"), n("＋如果", pill=True),
     n("就 说 哎哟"), n("＋就", pill=True), n("＋或者", pill=True)],
    [n("或者", pill=True), n("当 落地")],
    [n("否则如果 1"), n("如果 被甩出去"), n("就 说 呀"), n("＋就", pill=True)],
    [n("或者 1", pill=True), n("就 说 ……"), n("＋就", pill=True)],
    [n("当 每隔一会儿"), n("如果 状态 在忙"), n("就 说 马上"), n("＋就", pill=True)],
]
# 行 0 是主行；1 和 2 挂在它下面；3 挂在 2 下面（所以 2 自己也是个块）；4 是另一条规则。
SAMPLE_DROPS = [-1, 0, 0, 2, -1]
# 把「或者 1」那一支抽掉：它就是"添加新分支"的逆操作，剩下的行应该整体上移一格。
FEWER = [SAMPLE[0], SAMPLE[1], SAMPLE[2], SAMPLE[4]]
FEWER_DROPS = [-1, 0, 0, -1]


def main():
    print("== 0. 常数从源码读 ==")
    for name, value in C.items():
        report("%s 读得到" % name, value is not None, str(value))

    boxes, rows, depths, blocks = layout(SAMPLE, SAMPLE_DROPS)
    row0 = len(SAMPLE[0])

    print("\n== 1. 缩进：分支不再与别的规则并列在开头 ==")
    report("主行还是从 0 开始（一条规则的起点仍在最左边）", rows[0][2] == 0)
    report("每一条分支都比它挂的那一行**深一格**", depths == [0, 1, 1, 2, 0], str(depths))
    report("缩进的像素 = 深度 × 一格",
           rows[1][2] == INDENT_DP and rows[3][2] == 2 * INDENT_DP,
           "%.0f / %.0f" % (rows[1][2], rows[3][2]))
    report("下面那条**别的规则**仍然从 0 开始（缩进只给分支，不给规则）", rows[4][2] == 0)
    report("一条规则里的盒子是横着排的：第一行有 %d 个盒子，起点都一样" % row0,
           len([b for b in boxes if abs(b[1] - rows[0][0]) < 0.01]) == row0
           and all(b[1] == rows[0][0] for b in boxes[:row0]))
    report("正常的链（谁挂谁）深度是对的", depths_of([-1, 0, 1]) == [0, 1, 2])
    report("坏数据不会绕圈绕到死：自我挂 / 互挂都退化成有限深度",
           max(depths_of([0, 0, 1, 2, 1])) <= len(SAMPLE) + 1,
           str(depths_of([0, 0, 1, 2, 1])))

    print("\n== 2. 一块：一条规则的行被括在一起 ==")
    report("块 = 挂点行下标 .. 它最后一个后代的下标（嵌套的行自己也成块）",
           blocks == [(0, 3), (2, 3)], str(blocks))
    hanging = [i for i in range(len(SAMPLE)) if depths[i] > 0]
    report("属于主规则的分支全都落在那一对下标之间（没有漏在括号外）",
           all(blocks[0][0] < i <= blocks[0][1] for i in hanging), str(hanging))
    report("块的起点是挂点那一行，不是主行里最后一个盒子（括号跟着行，不跟着盒子）",
           blocks[0][0] == 0 and rows[0][0] < rows[3][0])
    report("同一层有两条分支时只出一个块（它们共用一对括号）",
           blocks_of([-1, 0, 0]) == [(0, 2)])
    report("没有分支的规则不进块表（不会画出一根空括号）",
           blocks_of([-1, -1, 2]) == [] and blocks_of([-1, -1]) == [])
    report("括号贴着挂点行的左边缘（深度 × 一格 + 6dp）",
           bracket_x(depths, 0, 3) == 6 and bracket_x(depths, 2, 3) == INDENT_DP + 6,
           "%.1f / %.1f" % (bracket_x(depths, 0, 3), bracket_x(depths, 2, 3)))
    report("括号在每一支的左边（没有支漏在括号外）",
           all(bracket_x(depths, 0, 3) < rows[i][2] for i in (1, 2, 3)))
    report("嵌套块的括号在外层括号的右边",
           bracket_x(depths, 2, 3) > bracket_x(depths, 0, 3))
    report("括号和挂线那一列不重合（18dp 起步，看得出是两件事）",
           bracket_x(depths, 0, 3) + 12 <= lane_x(depths, 0, 3, 200),
           "括号 %.0f · 挂线 %.0f" % (bracket_x(depths, 0, 3), lane_x(depths, 0, 3, 200)))
    report("挂线那一列在每一支左边（主干不再从行中间穿过去）",
           lane_x(depths, 0, 3, 200) < min(rows[i][2] for i in (1, 2, 3)),
           "%.0f < %.0f" % (lane_x(depths, 0, 3, 200), min(rows[i][2] for i in (1, 2, 3))))
    report("父方块中心本来就靠左时，挂着的那条竖道不硬往右挪",
           lane_x(depths, 0, 3, 8) == 8)

    print("\n== 3. 自己让位：加一支，下面的规则往后挪 ==")
    few_boxes, few_rows, _, _ = layout(FEWER, FEWER_DROPS)
    shift = rows[4][0] - few_rows[3][0]
    report("抽掉一行分支，下面那条规则**整体上移**（不是原地叠上去）",
           shift > 0, "%.0f → %.0f" % (rows[4][0], few_rows[3][0]))
    removed_h = SAMPLE[3] and max(node_h(l, p) for (l, p) in SAMPLE[3])
    report("挪的距离 = 那一行的高度 + 行距（不多不少）",
           abs(shift - (removed_h + ROW_GAP)) < 0.01, "%.1f" % shift)
    report("任意两行在竖直方向不重叠 —— 这就是「位置不够会自己挤开」",
           all(rows[i][1] <= rows[i + 1][0] + 0.01 for i in range(len(rows) - 1)),
           str([(round(a), round(b)) for a, b, _ in rows]))
    report("方向是往下、往右（分支的 y 更大、x 也更大）",
           rows[1][0] > rows[0][0] and rows[1][2] > rows[0][2])

    print("\n== 4. Kotlin 那边真的这么画 ==")
    src = open(GRAPH_KT, encoding="utf-8").read()
    report("缩进用的是 深度 × 一格 × density",
           "depths.getOrElse(ruleIndex) { 0 } * INDENT_DP * density" in src)
    report("块是从挂点（Drop）算出来的，不是另传一份名单",
           "if (last > parent) blocks.add(parent..last)" in src
           and "drops.getOrNull(at)?.parent" in src)
    report("括号先画（压在方块下面），而且只括「下面那几行」",
           "drawBlocks(canvas)" in src and "range.first + 1" in src
           and src.index("drawBlocks(canvas)") < src.index("drawNode(canvas, p)"))
    report("括号是一根竖线 + 上下两个钩（打开括号的样子）",
           "canvas.drawLine(x, top, x, bottom, bracket)" in src
           and "val hook = 9f * density" in src)
    report("括号那一列贴着挂点行的左边缘（Kotlin 与镜像同一个算法）",
           "val x = depth * INDENT_DP * density + 6f * density" in src)
    report("挂线先横着挪到竖道、再顺着竖道往下、最后一支一支往右拐",
           "val lane = minOf(x, heads.minOf { it.rect.left } - 10f * density)" in src
           and "canvas.drawLine(x, top, lane, top, fork)" in src
           and "canvas.drawLine(lane, top, lane, bottom, fork)" in src
           and "canvas.drawLine(lane, ty, tx, ty, fork)" in src)
    report("行高按那一行**最高**的盒子算（否则同一行会互相压住）",
           "tallest = max(tallest, h)" in src and "y += tallest + ROW_GAP * density" in src)

    print("\n== 5. 挂点本身对不对（否则如果那一串挂在**自己这条规则**底下）==")
    # 1.38.0 才发现的一处：`graph` 是所有规则共用的一份名单，而「否则如果」的起点写成了
    # `0` —— 于是第二条规则往后的那一串会吊到**第一条规则**的当盒子底下。以前它只是画错
    # 一条连接线；现在缩进和括号都从挂点算，错了就会整串缩进错、还被括进别人的块里。
    main_src = open(os.path.join(REPO, "app/src/main/java/dev/atp/pet/MainActivity.kt"),
                    encoding="utf-8").read()
    report("否则如果那一串的起点是这一条规则的主行",
           "var fromRow = mainRow" in main_src and "var fromRow = 0" not in main_src)
    rules = [1, 1]          # 两条规则，各带一级「否则如果 / 否则」
    good = ladder_drops(rules, start_at_main=True)
    bad = ladder_drops(rules, start_at_main=False)
    report("对的挂点：每条规则的阶梯挂在自己的主行上", good == [-1, 0, -1, 2], str(good))
    report("对的挂点：两条规则各成一个块，不互相牵扯", blocks_of(good) == [(0, 1), (2, 3)],
           str(blocks_of(good)))
    report("错的挂点：第二条的「否则」被括进**第一条**规则的块（跨过中间的规则）",
           blocks_of(bad) == [(0, 3)], str(blocks_of(bad)))
    report("错的挂点：括号一直垂到别的规则下面（块的尾巴从第 1 行伸到第 3 行）",
           blocks_of(good)[0][1] == 1 and blocks_of(bad)[0][1] == 3,
           "%d → %d" % (blocks_of(good)[0][1], blocks_of(bad)[0][1]))

    if "--png" in sys.argv:
        out = sys.argv[sys.argv.index("--png") + 1]
        report("预览画出来了", draw_png(SAMPLE, SAMPLE_DROPS, out), out)

    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        for f in FAILURES:
            print("  " + f)
        return 1
    print("all graph layout tests passed")
    return 0


def draw_png(rules, drops, path):
    """把镜像的布局画成一张图（给自己看的）：方块 + 挂线 + 括号。

    文字用英文占位（这台机器上没有中文点阵字体），要看的是**结构**：谁缩进、谁被括在一起、
    下面的规则有没有让开。
    """
    try:
        sys.path.insert(0, "/home/admin/pylibs")
        from PIL import Image, ImageDraw
    except Exception:
        return False              # 没有 PIL 就跳过，不算失败

    boxes, rows, depths, blocks = layout(rules, drops)
    box_row = []
    for (x, y, w, h) in boxes:
        box_row.append(next(i for i, (a, b, c) in enumerate(rows) if abs(a - y) < 0.01))

    s, pad = 1.6, 26
    w = int(max(b[0] + b[2] for b in boxes) * s) + pad * 2 + 90
    h = int(max(b[1] + b[3] for b in boxes) * s) + pad * 2
    im = Image.new("RGB", (w, h), (250, 250, 252))
    d = ImageDraw.Draw(im)

    def P(x, y):
        return (pad + x * s, pad + y * s)

    for (first, last) in blocks:                      # 括号在底下
        x = bracket_x(depths, first, last)
        y0, y1 = rows[first + 1][0] - 5, rows[last][1] + 5
        d.line([P(x, y0), P(x, y1)], fill=(108, 76, 224), width=2)
        d.line([P(x, y0), P(x + 9, y0)], fill=(108, 76, 224), width=2)
        d.line([P(x, y1), P(x + 9, y1)], fill=(108, 76, 224), width=2)

    # 挂线：向下后向右拐（和 drawDrops 同一个走法）。同一批"挂在同一行"的支共用一条竖道。
    groups = {}
    for i, parent in enumerate(drops):
        if isinstance(parent, int) and parent >= 0 and i != parent:
            groups.setdefault(parent, []).append(i)
    for parent, below in groups.items():
        prow = [j for j in range(len(boxes)) if box_row[j] == parent]
        if not prow:
            continue
        px = boxes[prow[0]][0] + boxes[prow[0]][2] / 2
        ptop = rows[parent][1]
        lefts = [boxes[[j for j in range(len(boxes)) if box_row[j] == b][0]][0] for b in below]
        lane = min(px, min(lefts) - 10)
        bottom = max((rows[b][0] + rows[b][1]) / 2 for b in below)
        d.line([P(px, ptop), P(lane, ptop)], fill=(150, 150, 170), width=2)
        d.line([P(lane, ptop), P(lane, bottom)], fill=(150, 150, 170), width=2)
        for b in below:
            cj = [j for j in range(len(boxes)) if box_row[j] == b][0]
            ty = (rows[b][0] + rows[b][1]) / 2
            tx = boxes[cj][0]
            d.line([P(lane, ty), P(tx, ty)], fill=(150, 150, 170), width=2)
            d.line([P(tx, ty), P(tx - 9, ty - 5)], fill=(150, 150, 170), width=2)
            d.line([P(tx, ty), P(tx - 9, ty + 5)], fill=(150, 150, 170), width=2)

    for i, (x, y, bw, bh) in enumerate(boxes):
        d.rectangle([P(x, y), P(x + bw, y + bh)], outline=(60, 60, 90), width=2)
        d.text((P(x, y)[0] + 6, P(x, y)[1] + 6), "r%d" % box_row[i], fill=(60, 60, 90))
    im.save(path)
    return True


if __name__ == "__main__":
    sys.exit(main())
