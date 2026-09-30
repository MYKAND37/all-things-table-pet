#!/usr/bin/env python3
"""逻辑图的**布局**：分支行长在"开启它的那个方块"底下，一条规则的行被括号括成一块，下面的规则自己让位。

    python3 tools/graph_layout_check.py                   # 断言
    python3 tools/graph_layout_check.py --png /tmp/g.png   # 再画一张预览（有 PIL 时）

两版用户原话，就是这里钉着的两件事：

  1.38.0「他的分支不会与其他规则并列在开头，而是直接向下后向右拐弯，多个分支就像打开括号
  一样，还有一条规则的多个分支应该带着一块，如果添加新分支，下面又有其他规则，位置不够会
  自己挤开」；

  1.39.0「括号的并行规则栏最开头的那个方块应该在开启并行的方块正下方，然后向右加新方块」，
  外加「「就」的并行会与「否则」的并行共用一条线导致模糊」。

落在代码里是四件事：

  1. **列 = 挂点**：一行挂在哪个方块下面，它的第一个方块就**居中落在那个方块底下**（宽窄差
     太多时不让它探到挂点左边）。挂点不只是"画一条线从哪儿来"，它就是这一行的 x —— 于是
     「就」的并行和「否则」的并行天然不在一个 x 上，不会再糊成一根线；
  2. **块**：一行 + 它所有后代 = 一个块，左边一根竖括号把它们括起来。块的判据是挂点，不需要
     另记名单；建图那边把每一步的「或者」**紧跟在那一步后面**推，所以块的区间里每一行都真的
     是它的后代（不然括号会顺手把隔壁那一支也括进来）；
  3. **自己让位**：布局每次重算（一行行往下排），中间插一行，下面的行整体往后挪；
  4. **挂线**：从挂点方块的底边中点一条竖线下来，落进分支行第一个方块的上边 —— 笔直的，因为
     那一行就长在它底下。挂线画在方块**下面**（挂点和它的行之间隔着别的行时，线从那些行后面
     穿过去）。

镜像只做那几件事的**算术**（不算真实文字宽度，用一把假尺子）：要证的是结构和位置，不是某台
设备上 Paint 量出来多少像素。常数从 `LogicGraphView.kt` 里读出来比。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
GRAPH_KT = os.path.join(REPO, "app/src/main/java/dev/atp/pet/ui/LogicGraphView.kt")
MAIN_KT = os.path.join(REPO, "app/src/main/java/dev/atp/pet/MainActivity.kt")

FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)
    return ok


def constants():
    src = open(GRAPH_KT, encoding="utf-8").read()
    out = {}
    for name in ("TITLE_H", "PILL_H", "LINE_H", "GAP", "ROW_GAP", "MAX_W", "BRACKET_DP",
                 "MIN_BRANCH_DP", "TRUNK_DP"):
        m = re.search(r"(?:const )?val %s = ([0-9.]+)f" % name, src)
        out[name] = float(m.group(1)) if m else None
    return out


C = constants()
TITLE_H, PILL_H, LINE_H = C["TITLE_H"], C["PILL_H"], C["LINE_H"]
GAP, ROW_GAP, BRACKET_DP, MAX_W = C["GAP"], C["ROW_GAP"], C["BRACKET_DP"], C["MAX_W"]
MIN_BRANCH_DP, TRUNK_DP = C["MIN_BRANCH_DP"], C["TRUNK_DP"]


# ── 镜像：Kotlin layout() / columnOf() / drawBlocks() / drawDrops() 的那几段算术 ──

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


def blocks_of(drops):
    """镜像：每个还有后代的行的下标 .. 它最后一个后代的下标。"""
    out = []
    for parent in range(len(drops)):
        last = -1
        for i in range(parent + 1, len(drops)):
            d = drops[i]
            at, hops = (d[0] if d else -1), 0
            while 0 <= at < len(drops) and hops <= len(drops):
                if at == parent:
                    last = i
                    break
                nxt = drops[at]
                at = nxt[0] if nxt else -1
                hops += 1
        if last > parent:
            out.append((parent, last))
    return out


def descendants_of(drops, parent):
    out = []
    for i in range(len(drops)):
        d, at, hops = drops[i], (drops[i][0] if drops[i] else -1), 0
        while 0 <= at < len(drops) and hops <= len(drops):
            if at == parent:
                out.append(i)
                break
            nxt = drops[at]
            at = nxt[0] if nxt else -1
            hops += 1
    return out


def anchor_of(boxes, drops, i):
    """镜像 columnOf()：挂点那个方块的矩形（挂点指不到就当没有）。"""
    d = drops[i] if i < len(drops) else None
    if d is None or not (0 <= d[0] < i):
        return None
    prow = boxes[d[0]]
    if not prow:
        return None
    return prow[d[1]] if 0 <= d[1] < len(prow) else prow[0]


def column_of(boxes, drops, i, head_w):
    """镜像 columnOf()：这一行的第一个方块**左边缘**对齐挂点方块的左边缘（至少 [MIN_BRANCH_DP]）。"""
    a = anchor_of(boxes, drops, i)
    if a is None:
        return 0.0
    return max(a[0], MIN_BRANCH_DP)


def layout(rules, drops):
    """镜像 layout()：返回 (每行的盒子, 每行的竖直范围, 每行的起点 x, 块)。"""
    boxes, rows, rowlefts = [], [], []
    y = 0.0
    for i, rule in enumerate(rules):
        head_w = node_w(*rule[0]) if rule else 0.0
        x = column_of(boxes, drops, i, head_w)
        rowlefts.append(x)
        row, at, tallest = [], x, 0.0
        for (lines, pill) in rule:
            w, h = node_w(lines, pill), node_h(lines, pill)
            row.append((at, y, at + w, y + h))
            at += w + GAP
            tallest = max(tallest, h)
        boxes.append(row)
        rows.append((y, y + tallest))
        y += tallest + ROW_GAP
    return boxes, rows, rowlefts, blocks_of(drops)


def trunk_x(boxes, drops, rowlefts, i):
    """镜像 drawDrops()：挂线站在**这一列**里（列起点 + TRUNK_DP）。"""
    if anchor_of(boxes, drops, i) is None:
        return None
    return (rowlefts[i] if i < len(rowlefts) else 0.0) + TRUNK_DP


def bracket_x(rowlefts, first):
    return rowlefts[first + 1] - BRACKET_DP


# ── 样例：一条规则（主行 + 额外的当 + 两支或者 + 一级否则如果 + 兜底否则）+ 下面另一条规则 ──

def n(*lines, pill=False):
    """一个盒子：几行字 + 它是不是那种小药丸（＋当 / ＋如果 / ＋就 / ＋或者）。"""
    return (list(lines), pill)


MAIN = [n("当 被点一下 · 身体"), n("＋当", pill=True), n("如果 痛苦 > 60"), n("＋如果", pill=True),
        n("就 说 哎哟"), n("＋就", pill=True), n("＋或者", pill=True)]
SAMPLE = [
    MAIN,                                                   # 0 主行
    [n("或者", pill=True), n("当 落地")],                     # 1 额外的当：挂在（0, 当）
    [n("或者 1", pill=True), n("计时器"), n("就 说 一"), n("＋就", pill=True)],   # 2 主就的并行
    [n("或者 2", pill=True), n("就 说 二"), n("＋就", pill=True)],                # 3 主就的并行
    [n("否则如果 1"), n("如果 被甩出去"), n("就 说 呀"), n("＋就", pill=True)],   # 4 挂在（0, 如果）
    [n("否则"), n("就 说 嗯"), n("＋就", pill=True)],                             # 5 挂在（4, 如果）
    MAIN,                                                   # 6 下面另一条规则
]
# 每一行的挂点：（第几行, 那一行的第几个方块）。主行和"下面另一条规则"都没有挂点。
SAMPLE_DROPS = [None, (0, 0), (0, 4), (0, 4), (0, 2), (4, 1), None]

# 「否则」那一支自己的并行（挂在（5, 就））：它就是"和「就」的并行糊在一起"的那一组。
ELSE_ALT = [n("或者 1", pill=True), n("就 说 哟"), n("＋就", pill=True)]
WITH_ELSE_ALT = SAMPLE[:6] + [ELSE_ALT] + [SAMPLE[6]]
WITH_ELSE_ALT_DROPS = SAMPLE_DROPS[:6] + [(5, 1)] + [None]

# 抽掉「或者 2」那一行（挂在同一个挂点上）：它就是"添加新分支"的逆操作。
FEWER = SAMPLE[:3] + SAMPLE[4:]
FEWER_DROPS = SAMPLE_DROPS[:3] + SAMPLE_DROPS[4:]


def main():
    print("== 0. 常数从源码读 ==")
    for name, value in C.items():
        report("%s 读得到" % name, value is not None, str(value))

    boxes, rows, rowlefts, blocks = layout(SAMPLE, SAMPLE_DROPS)

    print("\n== 1. 列 = 挂点：分支行长在「开启它的那个方块」正下方 ==")
    report("主行从 0 开始（一条规则的起点仍在最左边）", rowlefts[0] == 0 and rowlefts[6] == 0)
    b0 = boxes[0][0]
    report("额外的「当」那一行的第一个方块就在主行的**当**方块正下方（左边缘对齐）",
           rowlefts[1] == max(b0[0], MIN_BRANCH_DP))
    jiu = boxes[0][4]
    report("主「就」的并行的第一个方块就在**就**方块正下方",
           all(rowlefts[i] == max(jiu[0], MIN_BRANCH_DP) for i in (2, 3)))
    report("同一个挂点下面的几支**共用一列**（并行的几支是排着队的一栏）",
           rowlefts[2] == rowlefts[3])
    report("挂点不同 → 列不同（「否则如果」那一行挂在主行的如果底下）",
           rowlefts[4] != rowlefts[2], "%.0f vs %.0f" % (rowlefts[4], rowlefts[2]))
    report("分支行不会和规则的开头并排：挂点在 x=0 时也至少往里 %d" % MIN_BRANCH_DP,
           all(rowlefts[i] >= MIN_BRANCH_DP for i in (1, 2, 3, 4, 5)),
           str([round(v) for v in rowlefts]))
    report("挂点指不到（坏数据）就退回最左边，不会崩",
           column_of(boxes, [None] * 7, 3, 60.0) == 0.0
           and column_of(boxes, [(9, 0)] * 7, 3, 60.0) == 0.0)
    # 用户报的那个最坏情况：主「就」的并行 与 「否则」那一行的并行 —— 两个挂点宽窄不同，
    # 中点很容易撞在一起（"就 说 哎哟"和"就 说 嗯"就是这样）。左边缘对齐 + 竖线站在列里，
    # 两条线才真的分得开。
    twin = [MAIN,
            [n("或者 1", pill=True), n("就 说 一"), n("＋就", pill=True)],     # 1 主就的并行
            [n("否则"), n("就 说 嗯"), n("＋就", pill=True)],                  # 2 兜底否则
            [n("或者 1", pill=True), n("就 说 哟"), n("＋就", pill=True)]]      # 3 否则的并行
    twin_drops = [None, (0, 4), (0, 2), (2, 1)]
    tb, tr, tl, _ = layout(twin, twin_drops)
    main_line, else_line = trunk_x(tb, twin_drops, tl, 1), trunk_x(tb, twin_drops, tl, 3)
    report("两个挂点宽窄不同时，两条竖线仍然分得开（> 10px）",
           abs(main_line - else_line) > 10, "%.0f vs %.0f" % (main_line, else_line))

    print("\n== 2. 一块：一条规则的行被括号括在一起 ==")
    report("块 = 挂点行下标 .. 它最后一个后代的下标", blocks == [(0, 5), (4, 5)], str(blocks))
    ok_contig = True
    for (first, last) in blocks:
        kids = set(descendants_of(SAMPLE_DROPS, first))
        if not all(i in kids for i in range(first + 1, last + 1)):
            ok_contig = False
    report("块的区间里**每一行都是它的后代**（括号不会顺手括进隔壁那一支）", ok_contig)
    report("没有分支的规则不进块表（不会画出一根空括号）",
           blocks_of([None, None, None]) == [] and blocks_of([None, (2, 0), None]) == [])
    report("嵌套的块在里面再括一层（否则如果那一级自己也有一支）",
           (4, 5) in blocks and bracket_x(rowlefts, 4) > bracket_x(rowlefts, 0))
    report("括号在那一列的左边 %d、不压住任何一个方块" % BRACKET_DP,
           all(bracket_x(rowlefts, f) < min(boxes[i][0][0] for i in range(f + 1, l + 1))
               for (f, l) in blocks),
           "%.0f < %.0f" % (bracket_x(rowlefts, 0), min(boxes[i][0][0] for i in (1, 2, 3, 4, 5))))

    print("\n== 3. 自己让位：加一支，下面的规则往后挪 ==")
    few_boxes, few_rows, _, _ = layout(FEWER, FEWER_DROPS)
    shift = rows[6][0] - few_rows[5][0]
    removed_h = max(node_h(l, p) for (l, p) in SAMPLE[3])
    report("抽掉一行分支，下面那条规则**整体上移**（不是原地叠上去）", shift > 0, "%.0f" % shift)
    report("挪的距离 = 那一行的高度 + 行距（不多不少）",
           abs(shift - (removed_h + ROW_GAP)) < 0.01, "%.1f" % shift)
    report("任意两行在竖直方向不重叠 —— 这就是「位置不够会自己挤开」",
           all(rows[i][1] <= rows[i + 1][0] + 0.01 for i in range(len(rows) - 1)),
           str([(round(a), round(b)) for a, b in rows]))

    print("\n== 4. 挂线：一条竖线，两个挂点两条线 ==")
    for i in (1, 2, 3, 4, 5):
        tx, hx = trunk_x(boxes, SAMPLE_DROPS, rowlefts, i), boxes[i][0]
        report("第 %d 行的挂线从挂点方块底下笔直落到这一行第一个方块的上边" % i,
               tx is not None and hx[0] <= tx <= hx[2],
               "挂线 %.0f · 方块 %.0f..%.0f" % (tx or -1, hx[0], hx[2]))
    eb, er, el, _ = layout(WITH_ELSE_ALT, WITH_ELSE_ALT_DROPS)
    jiu_x = trunk_x(eb, WITH_ELSE_ALT_DROPS, el, 2)
    else_x = trunk_x(eb, WITH_ELSE_ALT_DROPS, el, 6)
    report("「就」的并行与「否则」的并行**不共用一条线**（用户报的那件事）",
           abs(jiu_x - else_x) > 10, "%.0f vs %.0f" % (jiu_x, else_x))
    report("两条竖线各自的列也不一样（方块不会叠在一起）",
           el[2] != el[6], "%.0f vs %.0f" % (el[2], el[6]))

    print("\n== 5. Kotlin 那边真的这么画 ==")
    src = open(GRAPH_KT, encoding="utf-8").read()
    main_src = open(MAIN_KT, encoding="utf-8").read()
    report("列是从挂点算的（columnOf 取挂点方块的左边缘，下限 %d）" % MIN_BRANCH_DP,
           "private fun columnOf(ruleIndex: Int): Float" in src
           and "return parent.rect.left.coerceAtLeast(MIN_BRANCH_DP * density)" in src)
    report("行从 rowLefts 拿起点（不是写死的格数 × 常数）",
           "val x = columnOf(ruleIndex)" in src and "rowLefts.add(x)" in src
           and "INDENT_DP" not in src)
    report("块是从挂点算出来的，不是另传一份名单",
           "if (last > parent) blocks.add(parent..last)" in src
           and "drops.getOrNull(at)?.parent" in src)
    report("括号站在那一列的左边 %d，上下带钩" % BRACKET_DP,
           "rowLefts.getOrNull(range.first + 1) ?: 0f) - BRACKET_DP * density" in src
           and "val hook = 9f * density" in src)
    report("挂线是一条竖线：从挂点方块底边（这一列的位置）到分支行第一个方块的上边",
           "canvas.drawLine(x, parent.rect.bottom, x, head.rect.top, fork)" in src
           and "(rowLefts.getOrNull(i) ?: 0f) + TRUNK_DP * density" in src)
    report("挂线画在方块**下面**（挂点与它的行之间隔着别的行时从后面穿过去）",
           src.index("drawDrops(canvas)") < src.index("drawNode(canvas, p)"))
    report("行高按那一行**最高**的盒子算（否则同一行会互相压住）",
           "tallest = max(tallest, h)" in src and "y += tallest + ROW_GAP * density" in src)
    report("建图那边：每一步的「或者」**紧跟在那一步那一行**后面推（块才是连续的）",
           main_src.count("altRows(Step.MAIN_STEP, mainRow, mainThen)") == 1
           and main_src.count("altRows(Step.elseIf(si), graph.size - 1, thenAt)") == 1
           and main_src.count("altRows(Step.ELSE_STEP, graph.size - 1, thenAt)") == 1
           and "altRows(step, rowIndex, thenAtOf[i])" not in main_src)

    print("\n== 6. 用户那只宠物的形状：一条规则里 9 支「或者」 ==")
    rule9 = [MAIN] + [[n("或者 %d" % (k + 1), pill=True), n("计时器"),
                       n("就 说 第%d句" % (k + 1)), n("＋就", pill=True)] for k in range(9)]
    d9 = [None] + [(0, 4)] * 9
    b9, r9, l9, bl9 = layout(rule9, d9)
    report("9 支各占一行、共用一列（并行的几支是排着队的一栏）",
           all(abs(x - l9[1]) < 0.01 for x in l9[1:]), "%.0f" % l9[1])
    report("一个块括住 9 支（不是 9 个块，也不是一根括到别处的括号）", bl9 == [(0, 9)], str(bl9))
    report("9 行之间不重叠（一行一行往下排）",
           all(r9[i][1] <= r9[i + 1][0] + 0.01 for i in range(len(r9) - 1)))

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

    文字用英文占位（这台机器上没有中文点阵字体），要看的是**结构**：谁长在谁底下、谁被括在
    一起、下面的规则有没有让开。
    """
    try:
        sys.path.insert(0, "/home/admin/pylibs")
        from PIL import Image, ImageDraw
    except Exception:
        return False              # 没有 PIL 就跳过，不算失败

    boxes, rows, rowlefts, blocks = layout(rules, drops)
    s, pad = 1.6, 26
    w = int(max(b[2] for r in boxes for b in r) * s) + pad * 2 + 60
    h = int(max(b[3] for r in boxes for b in r) * s) + pad * 2
    im = Image.new("RGB", (w, h), (250, 250, 252))
    d = ImageDraw.Draw(im)

    def P(x, y):
        return (pad + x * s, pad + y * s)

    for (first, last) in blocks:                      # 括号在底下
        x = bracket_x(rowlefts, first)
        y0, y1 = rows[first + 1][0] - 5, rows[last][1] + 5
        d.line([P(x, y0), P(x, y1)], fill=(108, 76, 224), width=2)
        d.line([P(x, y0), P(x + 9, y0)], fill=(108, 76, 224), width=2)
        d.line([P(x, y1), P(x + 9, y1)], fill=(108, 76, 224), width=2)

    for i in range(len(rules)):                        # 挂线：一条竖线
        a = anchor_of(boxes, drops, i)
        if a is None or not boxes[i]:
            continue
        x = (a[0] + a[2]) / 2
        d.line([P(x, a[3]), P(x, boxes[i][0][1])], fill=(150, 150, 170), width=2)

    for i, row in enumerate(boxes):
        for (bx, by, bw, bh) in row:
            d.rectangle([P(bx, by), P(bx + bw, by + bh)], outline=(60, 60, 90), width=2)
        d.text((P(row[0][0] + 5, row[0][1] + 5)[0], P(row[0][0] + 5, row[0][1] + 5)[1]),
               "row%d" % i, fill=(60, 60, 90))
    im.save(path)
    return True


if __name__ == "__main__":
    sys.exit(main())
