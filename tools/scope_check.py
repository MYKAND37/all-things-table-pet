#!/usr/bin/env python3
"""狙击镜的命中判定：一枪打出去，打到的是哪一根骨头。

    python3 tools/scope_check.py

用户要的是「第一人称视角的射击道具：点击道具就像打开一个狙击镜」，于是这一枪是**立刻**结算的
（hitscan）：没有飞出去的东西，所以"打中了"这件事只发生在 `Skeleton.rayHit` 那一段纯几何里 ——
从枪口沿瞄准方向发一条射线，命中**最近的**一根挡在路上的骨头（骨头是一段线段），返回骨头名和
命中点，没打中返回 null。

值得有这一份，是因为它同时钉着三件会各自漂、而且漂了在屏幕上都看不出来的事：

  1. **几何**：最近的一根、背后的不中、reach 之外不中、宽容度是**按骨头问**的。这些在界面上
     只差一条事件 —— 打中了没有，眼睛看不出来；
  2. **镜像**：tools/ 里这份 Python 和 Skeleton.kt 是两份手写的同一件事，漂了之后 Python 这边
     照样全绿，只有手机上那只宠物打不中。所以下面第 0 节把判据原文和两个 epsilon 从源码里
     **读出来**逐条比；
  3. **接线**：判据再对，接错线也白搭 —— 开火必须走 `sk.rayHit` 并把求解器的碰撞半径当宽容度
     递进去（"打得中"和"撞得上"因此是同一个宽度），而且这一版**没有飞行中的子弹**。

判据自己是"笨办法"验的（第 3 节）：沿射线密集采样、逐点量到每根线段的距离，和镜像里那套
"两条直线求交"是两套完全不同的算法 —— 两边算出同一个答案才算数。

## 如果这一份是红的：只可能是 s 的分母（1.34.0 写完那天发现的）

`Skeleton.kt` 里 s 的分母写成了 `-denom`：

    s = (px * uy - py * ux) / -denom        // 现在是这个
    s = (px * uy - py * ux) / denom         // 几何上该是这个

解一遍就知道：射线 `O + t*u` 与骨头 `A + s*e` 相交，`t` 和 `s` 是

    t = (p × e) / (u × e)        s = (p × u) / (u × e)        p = A - O

而 `denom` 就是 `u × e`、`p × u` 就是 `px*uy - py*ux` —— 分母没有负号。符号一反，算出来的 s 是
**真值取负**：一根横在正前方的骨头（真值 +0.5）算出 -0.5，被 `s < 0f` 那一关丢掉，**打不中**。
反过来，交点落在骨头头端往后那一小段里的，倒会被当成命中（那是这个符号的**镜像**，不是"射线到
线段的距离"）。所以宽容度 `slackOf` 现在只在平行那一支里真的有用。

一个字符的事：`/-denom` → `/denom`。改完这一份**一个字都不用动**就会全绿 —— 第 1 节和第 3 节
红的那几条钉的就是它，红在哪一条，就是哪一面没对上。
"""
import json, math, os, random, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
JAVA_DIR = os.path.join(REPO, "app/src/main/java")
SKELETON_KT = os.path.join(JAVA_DIR, "dev/atp/pet/engine/skeleton/Skeleton.kt")
VIEW_KT = os.path.join(JAVA_DIR, "dev/atp/pet/ui/PhysicsSandboxView.kt")
PROP_DIR = os.path.join(JAVA_DIR, "dev/atp/pet/engine/prop")
SPEC = os.path.join(REPO, "app/src/main/assets/characters/female_base/character.json")

FAILURES = []
ASSERTIONS = 0

#: 两个 epsilon 是 **Kotlin 的**：第 0 节把它们从 Skeleton.kt 里读出来对一遍。漂了的镜像比
#: 没有镜像更糟 —— 它会一直说自己是对的。
EPS_DIR = 1e-4
EPS_PARALLEL = 1e-6

#: rayHit 的签名（空白拍平之后逐字比）。那个默认值 `{ 0f }` 是这一版加参数时的重点：老调用点
#: 一个都不用改，而新调用点（狙击镜）要从求解器那儿把宽度问过来。
SIGNATURE = ("fun rayHit( from: Vec2, dir: Vec2, reach: Float,"
             " slackOf: (Bone) -> Float = { 0f }, ): Pair<String, Vec2>?")

#: 笨办法沿射线采多少个点。
SAMPLES = 2001

#: 随机对照时递给两边的那点宽容度（不是 0，见第 3 节：0 会让"命中"取决于浮点恰好相等）。
FUZZ_SLACK = 1e-3


def report(label, ok, detail=""):
    global ASSERTIONS
    ASSERTIONS += 1
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)


# ── 读 Kotlin：只读，不改 ────────────────────────────────────────────────────

def read(path):
    return open(path, encoding="utf-8").read()


def flat(text):
    """换行和缩进无关的比对：判据散在几行里，读的时候不该被排版骗了。"""
    return " ".join(text.split())


def code_only(text):
    """把注释和字符串抠掉（换成长度相同的空格），只看真代码。

    括号配对找函数体时，注释里的一个 `}` 会把函数截断；断言也不该误命中注释里的一句话 ——
    这一版恰好把"没有飞出去的子弹"写在了注释里。
    """
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                out[i] = " "
                i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                if text[i] != "\n":
                    out[i] = " "
                i += 1
            for k in (i, i + 1):
                if k < n:
                    out[k] = " "
            i += 2
        elif c == '"':
            out[i] = " "
            i += 1
            while i < n and text[i] != '"':
                if text[i] != "\n":
                    out[i] = " "
                i += 2 if text[i] == "\\" else 1
            if i < n:
                out[i] = " "
                i += 1
        else:
            i += 1
    return "".join(out)


def kotlin_function(text, name):
    """源码里 `fun name(...) { ... }` 那一段：从 `fun` 到配对上的那个 `}`。

    参数表里有 lambda（`slackOf: (Bone) -> Float = { 0f }`），所以先按圆括号找到参数表的结尾，
    再从那里往后找函数体的第一个 `{` —— 直接找第一个 `{` 会落进那个默认值里。
    """
    masked = code_only(text)
    m = re.search(r"(?:internal |private |public )?fun %s\(" % re.escape(name), masked)
    if m is None:
        return ""
    start = m.start()
    i = masked.index("(", m.start())
    depth = 0
    while i < len(masked):
        if masked[i] == "(":
            depth += 1
        elif masked[i] == ")":
            depth -= 1
            if depth == 0:
                break
        i += 1
    j = masked.index("{", i)
    depth = 0
    while j < len(masked):
        if masked[j] == "{":
            depth += 1
        elif masked[j] == "}":
            depth -= 1
            if depth == 0:
                return text[start:j + 1]
        j += 1
    return ""


def kotlin_constants(text):
    """`private const val X = 1.5f`，和 camera_check.py 里那把一样的读法。"""
    return dict((m.group(1), float(m.group(2)))
                for m in re.finditer(r"private const val (\w+) = ([0-9.]+)f", text))


# ── 镜像：Skeleton.rayHit 那一段几何，一行一行抄过来 ────────────────────────

class Bone:
    """Bone 里判定用得着的那四样：名字、世界位置、世界旋转、长度。

    世界旋转就是骨头自己 x 轴指的方向（弧度）；一根骨头因此是一段线段 ——
    从 head 沿 (cos, sin) 走 length。
    """
    __slots__ = ("name", "x", "y", "rotation", "length")

    def __init__(self, name, head, rotation, length):
        self.name = name
        self.x, self.y = float(head[0]), float(head[1])
        self.rotation = float(rotation)
        self.length = float(length)

    def head(self):
        return (self.x, self.y)

    def tail(self):
        return (self.x + math.cos(self.rotation) * self.length,
                self.y + math.sin(self.rotation) * self.length)

    def __repr__(self):
        return "Bone(%s)" % self.name


def bone(name, head, deg, length):
    """写用例用的一根骨头：角度用**度**（摆在纸上直观），里面一律弧度。"""
    return Bone(name, head, math.radians(deg), length)


def ray_hit(bones, origin, direction, reach, slack_of=None):
    """Skeleton.rayHit 的镜像（1.34.0）：最近的**一根**骨头，或 None。

    [slack_of] 是那个按骨头问的 lambda：拿一根骨头，回它"擦过去也算"的宽度。默认 0，
    和 Kotlin 的默认参数一样。

    返回 (骨头名, 命中点)，命中点用元组当 Vec2 —— 它是**射线上**参数 t 处的那个点。
    """
    if slack_of is None:
        def slack_of(_b):
            return 0.0
    ln = math.hypot(direction[0], direction[1])
    if ln < EPS_DIR or reach <= 0.0:
        return None
    ux, uy = direction[0] / ln, direction[1] / ln
    best = None
    best_at = 0.0
    best_dist = float("inf")
    for b in bones:
        ax, ay = b.head()
        ang = b.rotation
        bx = ax + math.cos(ang) * b.length
        by = ay + math.sin(ang) * b.length
        ex, ey = bx - ax, by - ay
        denom = ux * ey - uy * ex
        if abs(denom) < EPS_PARALLEL:
            # 平行：取骨头一端的投影（下面第 2 节量的就是这一支）。
            s = 0.0
            t = (ax - origin[0]) * ux + (ay - origin[1]) * uy
        else:
            px, py = ax - origin[0], ay - origin[1]
            t = (px * ey - py * ex) / denom
            # 几何上这里是 / denom：s = (p × u) / (u × e)。写成 / -denom 会把 s 变成真值取负，
            # 于是"横在路上的骨头"被 s < 0f 丢掉 —— 见文件开头，这条镜像照抄 Kotlin，红的。
            s = (px * uy - py * ux) / -denom
        # 反向验证过（1.34.0）：把这一行的 `t < 0.0` 改成 `t < -reach`（背后的骨头也算），
        # 第 1 节的「骨头在起点背后 → 打不中」和「方向反了」立刻变红；改回来就绿。
        if t < 0.0 or t > reach:
            continue
        if s < 0.0 or s > 1.0:
            continue
        hx, hy = origin[0] + ux * t, origin[1] + uy * t
        cx, cy = ax + ex * s, ay + ey * s
        d = math.hypot(hx - cx, hy - cy)
        if d <= slack_of(b) and t < best_dist:
            best_dist = t
            best = b.name
            best_at = t
    if best is None:
        return None
    return (best, (origin[0] + ux * best_at, origin[1] + uy * best_at))


# ── 另一把尺子：点到线段的距离，笨办法用 ────────────────────────────────────

def dist_point_segment(p, a, b):
    """点到**线段**的距离。和上面那套"两条直线求交"毫无关系。"""
    dx, dy = b[0] - a[0], b[1] - a[1]
    l2 = dx * dx + dy * dy
    if l2 <= 0.0:
        return math.hypot(p[0] - a[0], p[1] - a[1])
    s = ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l2
    s = min(1.0, max(0.0, s))
    return math.hypot(p[0] - (a[0] + dx * s), p[1] - (a[1] + dy * s))


def cross(a, b):
    return a[0] * b[1] - a[1] * b[0]


def ray_t(p, origin, direction):
    """p 在射线上的参数 t（p 本来就该在这条射线上）。"""
    ln = math.hypot(direction[0], direction[1])
    return ((p[0] - origin[0]) * direction[0] + (p[1] - origin[1]) * direction[1]) / ln


def on_ray(p, origin, direction, reach, tol=1e-6):
    """命中点在不在射线上：横向偏离 ≈ 0，而且参数落在 [0, reach] 里。"""
    ln = math.hypot(direction[0], direction[1])
    ux, uy = direction[0] / ln, direction[1] / ln
    t = (p[0] - origin[0]) * ux + (p[1] - origin[1]) * uy
    perp = abs((p[0] - origin[0]) * uy - (p[1] - origin[1]) * ux)
    return -tol <= t <= reach + tol and perp <= tol


def scan_hit(bones, origin, direction, reach, slack=0.0,
             samples=SAMPLES, subsamples=2001):
    """笨办法：从起点往外一点一点走，每一点量它到每根骨头的距离。

    走到"贴上来了"（距离 ≤ 半步长）就把这一小段再**细走一遍**：

      * 细走之后距离塌到 ≈ 0 → 这一根真的被穿过了，那就是它（从起点往外走，所以是最近的）；
      * 细走之后距离还是明显大于 slack → 只是**擦过去**（骨头的一头离射线很近，但身子没横在
        上面），继续往外走；
      * 卡在中间说不清的返回 "?"，调用方跳过这一个用例 —— 采样法本来就有这么一条缝，宁可少
        判一例，也不拿噪声去冤枉镜像。

    返回 (骨头名, 命中点, t) 或 None 或 "?"。
    """
    ln = math.hypot(direction[0], direction[1])
    if ln < EPS_DIR or reach <= 0.0 or not bones:
        return None
    ux, uy = direction[0] / ln, direction[1] / ln
    step = reach / (samples - 1)
    tol = step * 0.5 + 1e-9
    i = 0
    while i < samples:
        t = i * step
        p = (origin[0] + ux * t, origin[1] + uy * t)
        if min([dist_point_segment(p, b.head(), b.tail()) for b in bones]) <= tol:
            lo, hi = max(0.0, t - step), min(reach, t + step)
            best = None
            for k in range(subsamples + 1):
                ts = lo + (hi - lo) * k / subsamples
                ps = (origin[0] + ux * ts, origin[1] + uy * ts)
                for b in bones:
                    d = dist_point_segment(ps, b.head(), b.tail())
                    if best is None or d < best[0]:
                        best = (d, b.name, ts)
            if best[0] <= max(slack, 0.0) + 1e-3:
                ts = best[2]
                return (best[1], (origin[0] + ux * ts, origin[1] + uy * ts), ts)
            if best[0] < 1e-2:
                return "?"
            i += 3                      # 只是擦过去：这一小段已经细看过了，往外走
        else:
            i += 1
    return None


def random_case(rng):
    """随机骨头 + 随机射线：骨头撒在一个 900x900 的方格里，射线从左边瞄进去。"""
    bones = []
    for k in range(rng.randint(4, 6)):
        bones.append(bone("b%d" % k,
                          (rng.uniform(0.0, 900.0), rng.uniform(0.0, 900.0)),
                          rng.uniform(0.0, 360.0),
                          rng.uniform(40.0, 220.0)))
    origin = (rng.uniform(-150.0, 150.0), rng.uniform(100.0, 800.0))
    aim = (rng.uniform(0.0, 900.0), rng.uniform(0.0, 900.0))
    dx, dy = aim[0] - origin[0], aim[1] - origin[1]
    ln = math.hypot(dx, dy)
    return bones, origin, (dx / ln, dy / ln), rng.uniform(500.0, 1500.0)


def kotlin_sources(root):
    """app 里所有 Kotlin 源码的文本（引擎和界面都在里面），顺序固定，好复跑。"""
    out = []
    for here, dirs, files in os.walk(root):
        dirs.sort()
        for name in sorted(files):
            if name.endswith(".kt"):
                out.append(read(os.path.join(here, name)))
    return out


# ── 断言 ────────────────────────────────────────────────────────────────────

def main():
    skeleton = read(SKELETON_KT)
    view = read(VIEW_KT)
    body = kotlin_function(skeleton, "rayHit")
    kbody = flat(body)

    print("== 0. 镜像和 Kotlin 是同一份判据（读源码，逐条比）==")
    report("Skeleton.kt 里找得到 rayHit 这一个函数", bool(body))
    report("签名是 rayHit(from, dir, reach, slackOf)，而且最后一个有默认值 { 0f }",
           kbody.startswith(flat(SIGNATURE)), flat(SIGNATURE))
    report("宽容度是**按骨头**问的：slackOf 收的是一根骨头（slackOf(b)）",
           "slackOf(b)" in kbody and kbody.count("slackOf(b)") == 1)
    report("这一段里没有 Android（纯几何，所以能整段搬到这儿逐点跑）",
           "android" not in body.lower() and "context" not in body.lower())
    report("骨头就是一段线段：头 = worldPosition，尾 = 头 + 世界旋转 * length",
           "val bx = ax + cos(ang) * b.length" in kbody
           and "val by = ay + sin(ang) * b.length" in kbody)
    m_dir = re.search(r"len < ([0-9eE.+-]+)f", kbody)
    m_par = re.search(r"abs\(denom\) < ([0-9eE.+-]+)f", kbody)
    report("方向太短算零的那个 epsilon：两边是同一个数（%.0e）" % EPS_DIR,
           m_dir is not None and float(m_dir.group(1)) == EPS_DIR,
           "Kotlin 说 %s" % (m_dir.group(1) if m_dir else "没找到"))
    report("平行那一支的 epsilon：两边是同一个数（%.0e）" % EPS_PARALLEL,
           m_par is not None and float(m_par.group(1)) == EPS_PARALLEL,
           "Kotlin 说 %s" % (m_par.group(1) if m_par else "没找到"))
    for want in ("if (len < 1e-4f || reach <= 0f) return null",
                 "if (abs(denom) < 1e-6f) {",
                 "s = 0f",
                 "t = (ax - from.x) * ux + (ay - from.y) * uy",
                 "if (t < 0f || t > reach) continue",
                 "if (s < 0f || s > 1f) continue",
                 "if (d <= slackOf(b) && t < bestDist) {",
                 "bestDist = t",
                 "return name to Vec2(from.x + ux * bestAt, from.y + uy * bestAt)"):
        report("判据原文还在：%s" % want, want in kbody)
    # s 的分母：几何上该是 +denom（见文件开头）。这一条现在红，改一个字符就绿。
    report("s 的分母是 denom（写成 -denom 会把 s 变成真值取负 → 横在路上的骨头打不中）",
           "s = (px * uy - py * ux) / denom" in kbody,
           "Kotlin 里是 `s = (px * uy - py * ux) / -denom`")

    print("== 1. 一枪打到的是最近的哪一根 ==")
    origin, east = (0.0, 0.0), (1.0, 0.0)
    # 横在正前方的一根骨头：交点在它自己身上（真值 s = +0.5，t = 50）。
    torso = [bone("torso", (50.0, -50.0), 90.0, 100.0)]
    hit = ray_hit(torso, origin, east, 1000.0)
    report("正前方横在路上的骨头 → 命中它（交点在骨头中点）",
           hit is not None and hit[0] == "torso", "射线与它交于 (50, 0)")
    report("命中点就是射线与骨头的交点 (50, 0)",
           hit is not None and abs(hit[1][0] - 50.0) < 1e-3 and abs(hit[1][1]) < 1e-3,
           str(hit))
    report("命中点在射线上、而且贴在骨头上（两把尺子各量一次）",
           hit is not None and on_ray(hit[1], origin, east, 1000.0)
           and dist_point_segment(hit[1], torso[0].head(), torso[0].tail()) < 1e-6)
    # 一前一后两根（都横在路上）：挡在前面的先挨打。
    near = bone("hand_L", (50.0, -50.0), 90.0, 100.0)
    far = bone("torso", (200.0, -50.0), 90.0, 100.0)
    front = ray_hit([far, near], origin, east, 1000.0)
    back = ray_hit([near, far], origin, east, 1000.0)
    report("两根一前一后 → 命中**近的**那一根", front is not None and front[0] == "hand_L",
           str(front))
    report("命中点落在近的那一根上（x = 50，不是 200）",
           front is not None and abs(front[1][0] - 50.0) < 1e-3, str(front))
    report("名单顺序倒过来 → 还是近的那一根（比的是距离，不是谁写在前面）",
           back is not None and back[0] == "hand_L", str(back))
    # 骨头在起点背后：t < 0 直接丢。
    behind = bone("hand_L", (100.0, 0.0), 90.0, 100.0)
    report("骨头在起点背后（t = -100 < 0）→ 打不中",
           ray_hit([behind], (200.0, 0.0), east, 1000.0) is None)
    report("方向反过来（骨头还在正前方，射线朝后）→ 一样打不中",
           ray_hit(torso, origin, (-1.0, 0.0), 1000.0) is None)
    # reach：这里量的是 t 那一关，所以骨头头端就摆在射线上，免得被 s 那一关搅进来。
    report("reach 之内 → 命中", ray_hit([behind], origin, east, 500.0) is not None)
    report("距离正好等于 reach（t = 100）→ **算**命中（判据是 t > reach 才丢，不是 >=）",
           ray_hit([behind], origin, east, 100.0) is not None)
    report("差一点点（t = 100 > 99.9）→ 打不中",
           ray_hit([behind], origin, east, 99.9) is None)
    report("起点正好在骨头的头端（t = 0）→ 算命中（判据是 t < 0 才丢）",
           ray_hit([behind], origin, east, 1000.0) is not None)
    report("reach <= 0 → null（一枪打不出零距离，也不倒着打）",
           ray_hit([behind], origin, east, 0.0) is None
           and ray_hit([behind], origin, east, -5.0) is None)
    report("dir 是零向量 → null（不除零、不崩）",
           ray_hit([behind], origin, (0.0, 0.0), 1000.0) is None)
    report("dir 短得算零（1e-5 < 1e-4）→ null",
           ray_hit([behind], origin, (1e-5, 0.0), 1000.0) is None)
    report("dir 刚够长（2e-4）→ 照常命中（那个 epsilon 是太短才算零，不是必须归一）",
           ray_hit([behind], origin, (2e-4, 0.0), 1000.0) is not None)
    report("骨头列表是空的 → null（没有骨头可打）",
           ray_hit([], origin, east, 1000.0) is None)
    tie = ray_hit([bone("first", (100.0, 0.0), 90.0, 100.0),
                   bone("second", (100.0, 0.0), -90.0, 100.0)], origin, east, 1000.0)
    report("两根完全重合在同一个 t → 名单里靠前的那一根（t < bestDist 是严格的，先到先得）",
           tie is not None and tie[0] == "first", str(tie))

    print("== 2. 宽容度：擦过去也算，而且是按骨头问的 ==")
    # 平行那一支（|u × e| < 1e-6）才是宽容度真正在管的事：两条直线没有交点，量的是骨头到射线的
    # **垂直距离**。交叉那一支的交点本来就落在骨头上（d ≈ 0），宽容度给多少都一样。
    along = bone("along", (100.0, 30.0), 0.0, 100.0)      # 与射线平行，横向让开 30
    e_along = (along.tail()[0] - along.head()[0], along.tail()[1] - along.head()[1])
    report("这条用例真的走平行那一支（|u × e| < 1e-6）",
           abs(cross(east, e_along)) < EPS_PARALLEL, "|u × e| = %.1e" % abs(cross(east, e_along)))
    report("平行擦过、让开 30 > 宽容度 10 → 打不中",
           ray_hit([along], origin, east, 1000.0, lambda b: 10.0) is None)
    graze = ray_hit([along], origin, east, 1000.0, lambda b: 40.0)
    report("宽容度调到 40 → 命中（擦过去也算）", graze is not None and graze[0] == "along",
           str(graze))
    report("平行那一支的命中点 = 骨头**头端**在射线上的垂足 (100, 0)",
           graze is not None and abs(graze[1][0] - 100.0) < 1e-4
           and abs(graze[1][1]) < 1e-4, str(graze))
    report("宽容度正好等于距离（30）→ 算命中（判据是 d <= slackOf，不是 <）",
           ray_hit([along], origin, east, 1000.0, lambda b: 30.0) is not None)
    crossing = [bone("torso", (150.0, -50.0), 90.0, 100.0)]
    zero = ray_hit(crossing, origin, east, 1000.0, lambda b: 0.0)
    wide = ray_hit(crossing, origin, east, 1000.0, lambda b: 400.0)
    report("横在路上的骨头：宽容度 0 和 400 命中的是同一根、同一点（交叉那一支用不上它）",
           zero is not None and wide is not None and zero[0] == wide[0]
           and abs(zero[1][0] - wide[1][0]) < 1e-9 and abs(zero[1][1] - wide[1][1]) < 1e-9,
           "0 → %s；400 → %s" % (zero, wide))
    # 同一发子弹，两根骨头各问各的：一个是躯干、一个是手指。
    fat = bone("torso", (300.0, 30.0), 0.0, 100.0)
    thin = bone("hand_L", (300.0, 30.0), 0.0, 100.0)
    asked = []

    def slack_of(b):
        asked.append(b.name)
        return 40.0 if b.name == "torso" else 10.0

    per = ray_hit([thin, fat], origin, east, 1000.0, slack_of)
    report("同一发子弹：粗骨头（40）命中、细骨头（10）不中 —— 签名里那个 lambda 的意义",
           per is not None and per[0] == "torso", str(per))
    report("宽容度是按骨头问的：两根都被问过，各问各的（不是一发子弹一个数）",
           asked == ["hand_L", "torso"], str(asked))
    # 头端离射线很近、但身子没横在射线上：判据是"两条直线交在骨头上"，s 越界就丢 —— 所以
    # "射线到线段的距离"这句 shorthand 只对穿过射线的骨头成立。这一条钉住它（宽容度再大也一样）。
    stub = bone("stub", (400.0, 5.0), 90.0, 100.0)
    report("骨头的一头离射线只有 5（在宽容度 20 之内）、但没穿过它 → 不算命中",
           ray_hit([stub], origin, east, 1000.0, lambda b: 20.0) is None,
           "s 真值是 -0.05，越界")

    print("== 3. 独立参照：沿射线密集采样（笨办法），随机 40 例 ==")
    rng = random.Random(1340)
    compared = skipped = ref_hits = ref_misses = 0
    bad = []
    points = []
    for _ in range(40):
        bones, o, d, reach = random_case(rng)
        ref = scan_hit(bones, o, d, reach)
        if ref == "?":
            skipped += 1
            continue
        mine = ray_hit(bones, o, d, reach)
        compared += 1
        if ref is None:
            ref_misses += 1
            if mine is not None:
                bad.append("参照说打空，镜像说 %s@(%.0f, %.0f)"
                           % (mine[0], mine[1][0], mine[1][1]))
            continue
        ref_hits += 1
        if mine is None:
            bad.append("参照说打中 %s（t=%.0f），镜像说打空" % (ref[0], ref[2]))
            continue
        points.append((mine[1], o, d, reach))
        step = reach / (SAMPLES - 1)
        if mine[0] != ref[0] or abs(ray_t(mine[1], o, d) - ref[2]) > 3 * step:
            bad.append("参照 %s@t=%.0f，镜像 %s@t=%.0f"
                       % (ref[0], ref[2], mine[0], ray_t(mine[1], o, d)))
    report("随机 %d 例：独立参照和镜像逐例一致（名字和命中点都比）—— 覆盖「最近的一根」"
           % compared, not bad, "; ".join(bad[:3]))
    report("这批随机用例有打中的也有打空的（不是空跑）",
           ref_hits >= 5 and ref_misses >= 5 and compared >= 30,
           "%d 例可判定：参照 %d 中 / %d 空，%d 例擦边说不清被跳过"
           % (compared, ref_hits, ref_misses, skipped))
    report("每一个随机命中点都落在那条射线上",
           bool(points) and all(on_ray(p, o, d, r) for p, o, d, r in points),
           "%d 个命中点" % len(points))

    print("== 4. 接线：开火那条路 ==")
    fire = kotlin_function(view, "fireScope")
    fcode = flat(fire)
    report("PhysicsSandboxView 里找得到 fireScope", bool(fire))
    report("开火走的是 sk.rayHit(...)，而且 reach 用的是 SCOPE_REACH",
           "sk.rayHit(muzzle, aim - muzzle, SCOPE_REACH)" in fcode)
    report("宽容度是从求解器要的：{ b -> rag.colliderRadius(b) }"
           "（打得中和撞得上是同一个宽度）",
           "{ b -> rag.colliderRadius(b) }" in fcode and fcode.count("colliderRadius") == 1)
    report("准星 = 屏幕正中，用现成的 toWorld 换算（没有第二套投影）",
           "val aim = toWorld(width / 2f, height / 2f)" in fcode)
    report("打中会发一条「被打到」，带着是哪根骨头、哪个道具打的",
           "fire(GameEvent(EventType.IMPACT, part = bone, prop = prop.spec.id, value = prop.spec.force))"
           in fcode)
    miss_branch = fcode.split("if (hit == null) {", 1)[1].split("val (bone, at) = hit", 1)[0] \
        if "if (hit == null) {" in fcode and "val (bone, at) = hit" in fcode else ""
    report("打空那一支只说一声、什么都不发（不留事件、不留印子）",
           bool(miss_branch) and "fire(" not in miss_branch, flat(miss_branch)[:60])
    report("命中点用上了，而且存进那根骨头自己的坐标系（宠物动，印子跟着动）",
           "val (bone, at) = hit" in fcode
           and "worldTransform?.inverse()?.apply(at)" in fcode)
    all_kt = kotlin_sources(JAVA_DIR)
    report("整个 app 里 rayHit 只有一处定义、一处调用（判据只有一份，接线只有一条）",
           sum(t.count("rayHit(") for t in all_kt) == 2,
           "%d 处" % sum(t.count("rayHit(") for t in all_kt))

    print("== 5. 镜头：借的是现成的 viewScale / pan ==")
    consts = kotlin_constants(view)
    scope_open = kotlin_function(view, "openScope")
    scope_close = kotlin_function(view, "closeScope")
    report("SCOPE_ZOOM 读得到，而且真的是放大（> 1）",
           consts.get("SCOPE_ZOOM", 0.0) > 1.0, "SCOPE_ZOOM = %s" % consts.get("SCOPE_ZOOM"))
    report("放大走的是现成的 viewScale（defaultScale * SCOPE_ZOOM），不是另做一套投影",
           "viewScale = (defaultScale * SCOPE_ZOOM).coerceIn(defaultScale * 0.25f, defaultScale * 6f)"
           in flat(scope_open))
    report("退出时把 scale / panX / panY 三个数原样还回去（开镜是借镜头，不是换镜头）",
           "scopeReturn = Viewport(viewScale, panX, panY)" in flat(scope_open)
           and "viewScale = back.scale" in flat(scope_close)
           and "panX = back.panX" in flat(scope_close)
           and "panY = back.panY" in flat(scope_close))
    world_width = json.load(open(SPEC, encoding="utf-8"))["physics"]["worldWidth"]
    report("一枪打得到世界的另一头（SCOPE_REACH ≥ 世界宽 %.0f）：短了会变成看得见打不着"
           % world_width, consts.get("SCOPE_REACH", 0.0) >= world_width,
           "SCOPE_REACH = %s" % consts.get("SCOPE_REACH"))

    print("== 6. 没有飞行中的子弹（hitscan 之后就没有了）==")
    prop = "\n".join(read(os.path.join(PROP_DIR, n))
                     for n in sorted(os.listdir(PROP_DIR)) if n.endswith(".kt"))
    report("引擎里没有 bullet()（PropSpec / PropWorld 都算上）", "bullet(" not in prop.lower())
    report("界面里也没有（这一枪是「点一下」，不是「拖出去打一发」）",
           "bullet(" not in view.lower())
    report("整个 app 里都没有了，连给它加速度的那个常数（SHOT_SPEED）也删了",
           "bullet(" not in "\n".join(all_kt).lower()
           and "SHOT_SPEED" not in "\n".join(all_kt))

    print("")
    if FAILURES:
        print("%d FAILED（共 %d 条断言）" % (len(FAILURES), ASSERTIONS))
        for f in FAILURES:
            print("  " + f)
        return 1
    print("all scope tests passed（%d 条断言）" % ASSERTIONS)
    return 0


if __name__ == "__main__":
    sys.exit(main())
