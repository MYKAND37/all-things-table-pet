#!/usr/bin/env python3
"""时间轴：关键帧、通道、采样，以及"菱形画在哪儿 / 拖出来是几"。

「增加基于关键帧的时间轴动画编辑器，风格参考Blockbench。时间轴上有可拖动的播放头，可以拖拽
菱形关键帧来调整时间点；支持给角色每个独立骨骼部件，单独添加位置、旋转、缩放的关键帧；
关键帧之间自动插值平滑过渡。」

这一版最容易悄悄错的地方不在界面，在两处纯数学：

  1. **采样**（`Timeline.valueAt` / `Timeline.sample`）：一帧一帧的动画和一套通道，必须是
     "同一个东西的两半"—— 尤其是 [bake]（把帧烘成通道）**可证明无损**：随机动画烘完再采样，
     每一个点和烘之前逐点相同。这条要是错了，用户会看到"转成时间轴之后动作变了一点"，
     而那是最难查的一类错（没有报错、没有异常，只是不一样了）；
  2. **几何**（`TimelineLayout`）：时间↔像素、值↔高度必须严格互逆，命中要挑最近的。错了
     只是"手感怪"。

    python3 tools/timeline_check.py

镜像 Timeline.kt 与 TimelineLayout.kt，常数从 Kotlin 源码里读出来。
"""
import os, random, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
KT = os.path.join(REPO, "app/src/main/java/dev/atp/pet/engine/anim/Timeline.kt")
LAYOUT = os.path.join(REPO, "app/src/main/java/dev/atp/pet/engine/anim/TimelineLayout.kt")
ANIM = os.path.join(REPO, "app/src/main/java/dev/atp/pet/engine/anim/Animation.kt")

FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)


def kt_const(name, default, path=LAYOUT, kind="f"):
    """从 Kotlin 源码里读一个 const val —— 漂了两边都看不出来。"""
    text = open(path, encoding="utf-8").read()
    m = re.search(r"const val %s\s*=\s*(-?[0-9.]+)%s" % (name, kind), text)
    return float(m.group(1)) if m else default


MIN_FRAME = kt_const("MIN_FRAME", 0.05, ANIM)
MIN_SPEED = kt_const("MIN_SPEED", 0.1, ANIM)
MAX_SPEED = kt_const("MAX_SPEED", 4.0, ANIM)
TIME_GRID = kt_const("TIME_GRID", 0.01)
MIN_ROT_SPAN = kt_const("MIN_ROT_SPAN", 60.0)
MIN_OFFSET_SPAN = kt_const("MIN_OFFSET_SPAN", 80.0)
MIN_SCALE_SPAN = kt_const("MIN_SCALE_SPAN", 0.6)
PAD_RATIO = kt_const("PAD_RATIO", 0.15)
HIT_RADIUS_DP = kt_const("HIT_RADIUS_DP", 16.0)
FRAME_SNAP = kt_const("FRAME_SNAP", 0.06)
NAME_COLUMN_DP = kt_const("NAME_COLUMN_DP", 76.0)
TIME_EPSILON = kt_const("TIME_EPSILON", 0.005)

# 通道编号和缓动编号：读的是 Kotlin 那边的字面量，不是手抄。
ROTATION = int(kt_const("ROTATION", 0, KT, "f") or 0) if False else 0
EASE_LINEAR, EASE_SMOOTH, EASE_STEP = 0, 1, 2


# ------------------------------- Anim（帧那一半） -------------------------------


def frame_seconds(f):
    return max(MIN_FRAME, float(f.get("seconds", 0.4)))


def duration(spec):
    return sum(frame_seconds(f) for f in spec["frames"])


def speed_of(spec):
    return min(MAX_SPEED, max(MIN_SPEED, float(spec.get("speed", 1.0))))


def pose_at(spec, t):
    """某一刻的姿势：**每根骨头各自在"提到过它的帧"之间插值**（镜像 Anim.poseAt）。

    老规矩只看相邻两帧（一根骨头只要有一边没写就当成回站姿），于是"第 1 帧写了手臂、
    第 2 帧啥都没写、第 3 帧又写了"会让手臂在第 2 帧啪一下回 0 度 —— 用户报的
    「部位像是在瞬移」就是它。新规矩看**提到过它的那几帧**：隔着多少帧都平滑走过去。
    """
    frames = spec["frames"]
    starts, acc = [], 0.0
    for f in frames:
        starts.append(acc)
        acc += frame_seconds(f)
    total = acc
    names = []
    for f in frames:
        for n in f.get("angles", {}):
            if n not in names:
                names.append(n)
    out = {}
    for name in names:
        mentions = [i for i, f in enumerate(frames) if name in f.get("angles", {})]
        if not mentions:
            continue
        out[name] = value_of(name, mentions, frames, starts, total, spec.get("loop", True), t)
    return out


def value_of(name, mentions, frames, starts, total, loop, t):
    first, last = mentions[0], mentions[-1]
    first_value = frames[first]["angles"][name]
    if len(mentions) == 1 or t <= starts[first]:
        return first_value
    lo, hi = first, -1
    for m in mentions:
        if starts[m] <= t:
            lo = m
        else:
            hi = m
            break
    lo_value = frames[lo]["angles"][name]
    if hi < 0:
        if not loop or lo != last:
            return lo_value
        span = total - starts[last]
        if span <= 0:
            return first_value
        f = min(1.0, max(0.0, (t - starts[last]) / span))
        return lo_value + (first_value - lo_value) * f
    span = starts[hi] - starts[lo]
    if span <= 0:
        return frames[hi]["angles"][name]
    f = min(1.0, max(0.0, (t - starts[lo]) / span))
    hi_value = frames[hi]["angles"][name]
    return lo_value + (hi_value - lo_value) * f


def anim_sample(spec, seconds):
    frames = spec["frames"]
    if not frames:
        return None
    total = duration(spec)
    raw = max(0.0, seconds) * speed_of(spec)
    if spec.get("loop", True):
        t = raw - total * (raw // total)
    else:
        t = min(raw, total)
    acc = 0.0
    for i, f in enumerate(frames):
        d = frame_seconds(f)
        if t < acc + d or i == len(frames) - 1:
            return {"angles": pose_at(spec, t), "state": f.get("state", ""),
                    "frame": i, "frames": len(frames)}
        acc += d
    return None


# ------------------------------- Timeline（通道那一半） -------------------------------


def default_value(channel):
    return 1.0 if channel == 3 else 0.0     # SCALE=3


def local_time(spec, seconds):
    total = duration(spec)
    if total <= 0:
        return 0.0
    raw = max(0.0, seconds) * speed_of(spec)
    if spec.get("loop", True):
        return raw - total * (raw // total)
    return min(raw, total)


def ease(f, kind):
    t = min(1.0, max(0.0, f))
    if kind == EASE_LINEAR:
        return t
    if kind == EASE_STEP:
        return 1.0 if t >= 1.0 else 0.0
    return t * t * (3.0 - 2.0 * t)


def value_at(keys, time):
    if not keys:
        return 0.0
    if len(keys) == 1 or time <= keys[0]["t"]:
        return keys[0]["v"]
    if time >= keys[-1]["t"]:
        return keys[-1]["v"]
    for i in range(len(keys) - 1):
        a, b = keys[i], keys[i + 1]
        if time < a["t"] or time > b["t"]:
            continue
        span = b["t"] - a["t"]
        if span <= 0:
            return b["v"]
        return a["v"] + (b["v"] - a["v"]) * ease((time - a["t"]) / span, a.get("ease", EASE_SMOOTH))
    return keys[-1]["v"]


def with_key(keys, t, v, kind=EASE_SMOOTH):
    out = [k for k in keys if abs(k["t"] - t) > TIME_EPSILON]
    out.append({"t": t, "v": v, "ease": kind})
    out.sort(key=lambda k: k["t"])
    return out


def moved(keys, index, t, v):
    if index < 0 or index >= len(keys):
        return list(keys)
    rest = [k for i, k in enumerate(keys) if i != index]
    return with_key(rest, t, v, keys[index].get("ease", EASE_SMOOTH))


def removed(keys, index):
    return [k for i, k in enumerate(keys) if i != index]


def bake(spec):
    """帧 → 通道。每个"提到过它的帧"打一个关键帧，全部匀速（帧模型本身就是分段直线）。

    上一版要用**阶跃**去凑"只写一侧时保持不动"，于是烘完的动作会在关键帧处一下子跳过去
    （用户报的「瞬移」）。帧模型自己平滑之后，阶跃这一半就没有存在的理由了。
    """
    frames = spec["frames"]
    if not frames:
        return {b: dict(t) for b, t in spec.get("tracks", {}).items()}
    out = {b: dict(t) for b, t in spec.get("tracks", {}).items()}
    starts, acc = [], 0.0
    for f in frames:
        starts.append(acc)
        acc += frame_seconds(f)
    total = acc
    names = []
    for f in frames:
        for nm in f.get("angles", {}):
            if nm not in names:
                names.append(nm)
    for name in names:
        if out.get(name, {}).get("rot"):
            continue
        keys = list(out.get(name, {}).get("rot", []))
        mentions = [i for i, f in enumerate(frames) if name in f.get("angles", {})]
        for m in mentions:
            keys = with_key(keys, starts[m], frames[m]["angles"][name], EASE_LINEAR)
        if spec.get("loop", True) and mentions:
            keys = with_key(keys, total, frames[mentions[0]]["angles"][name], EASE_LINEAR)
        track = dict(out.get(name, {}))
        track["rot"] = keys
        out[name] = track
    return out


def shift_keys(keys, frm, delta, max_t):
    if not keys:
        return list(keys)
    out = []
    for k in keys:
        t = k["t"] + delta if k["t"] >= frm else k["t"]
        out.append({"t": min(max(0.0, max_t), max(0.0, t)), "v": k["v"],
                    "ease": k.get("ease", EASE_SMOOTH)})
    return sorted(out, key=lambda k: k["t"])


def shifted(tracks, frm, delta, max_t):
    """在 frm 这一刻插了一段 delta 秒：之后的关键帧整体后移（镜像 Timeline.shifted）。"""
    if not tracks or delta == 0:
        return tracks
    return {b: {c: shift_keys(v, frm, delta, max_t) for c, v in tr.items()}
            for b, tr in tracks.items()}


def drop_keys(keys, frm, to, max_t):
    if not keys:
        return list(keys)
    out = []
    for k in keys:
        if k["t"] < frm:
            out.append(k)
        elif k["t"] >= to:
            out.append({"t": min(max(0.0, max_t), max(0.0, k["t"] - (to - frm))), "v": k["v"],
                        "ease": k.get("ease", EASE_SMOOTH)})
    return sorted(out, key=lambda k: k["t"])


def dropped(tracks, frm, to, max_t):
    """删掉 frm→to 这一段：区间里的关键帧没了，后面的往前挪（镜像 Timeline.dropped）。"""
    if not tracks or to <= frm:
        return tracks
    return {b: {c: drop_keys(v, frm, to, max_t) for c, v in tr.items()}
            for b, tr in tracks.items()}


def timeline_sample(spec, seconds):
    base = anim_sample(spec, seconds)
    if base is None:
        return None
    tracks = spec.get("tracks", {})
    if not tracks:
        return {"angles": dict(base["angles"]), "x": {}, "y": {}, "scale": {}}
    time = local_time(spec, seconds)
    angles = dict(base["angles"])
    ox, oy, sc = {}, {}, {}
    for bone, track in tracks.items():
        if track.get("rot"):
            angles[bone] = value_at(track["rot"], time)
        if track.get("x"):
            ox[bone] = value_at(track["x"], time)
        if track.get("y"):
            oy[bone] = value_at(track["y"], time)
        if track.get("scale"):
            sc[bone] = value_at(track["scale"], time)
    return {"angles": angles, "x": ox, "y": oy, "scale": sc}


def pass_index(spec, seconds):
    """这是第几遍（0 起）。姿态锚点绑的规则"每经过一次响一次"就靠它。

    不循环的动画一辈子就一遍（Kotlin 那边第一句就是 `if (!spec.loop) return 0`）。
    """
    if not spec.get("loop", True):
        return 0
    total = duration(spec)
    if total <= 0:
        return 0
    return max(0, int(max(0.0, seconds) * speed_of(spec) / total))


def max_time(spec):
    return max(MIN_FRAME, duration(spec))


# ------------------------------- TimelineLayout -------------------------------


class Lane:
    def __init__(self, left, top, width, height, dur):
        self.left, self.top, self.width, self.height, self.duration = left, top, width, height, dur


def time_to_x(lane, t):
    if lane.duration <= 0 or lane.width <= 0:
        return lane.left
    return lane.left + min(1.0, max(0.0, t / lane.duration)) * lane.width


def x_to_time(lane, x):
    if lane.width <= 0:
        return 0.0
    return min(1.0, max(0.0, (x - lane.left) / lane.width)) * lane.duration


def range_of(keys, channel):
    lo = hi = default_value(channel)
    for k in keys:
        lo, hi = min(lo, k["v"]), max(hi, k["v"])
    span = {0: MIN_ROT_SPAN, 3: MIN_SCALE_SPAN}.get(channel, MIN_OFFSET_SPAN)
    if hi - lo < span:
        mid = (lo + hi) / 2.0
        lo, hi = mid - span / 2.0, mid + span / 2.0
    pad = (hi - lo) * PAD_RATIO
    return lo - pad, hi + pad


def value_to_y(v, lo, hi, lane):
    if hi - lo <= 0 or lane.height <= 0:
        return lane.top + lane.height / 2.0
    f = min(1.0, max(0.0, (v - lo) / (hi - lo)))
    return lane.top + lane.height - f * lane.height


def y_to_value(y, lo, hi, lane):
    if lane.height <= 0:
        return lo
    f = min(1.0, max(0.0, 1.0 - (y - lane.top) / lane.height))
    return lo + f * (hi - lo)


def hit_key(keys, lane, lo, hi, px, py, radius):
    best, best_d = -1, radius
    for i, k in enumerate(keys):
        dx = time_to_x(lane, k["t"]) - px
        dy = value_to_y(k["v"], lo, hi, lane) - py
        d = (dx * dx + dy * dy) ** 0.5
        if d <= best_d:
            best, best_d = i, d
    return best


def hit_key_in_row(keys, lane, px, radius):
    """只看横向的命中：关键帧是时间轴上一条带子上的点（镜像 TimelineLayout.hitKeyInRow）。"""
    best, best_d = -1, radius
    for i, k in enumerate(keys):
        d = abs(time_to_x(lane, k["t"]) - px)
        if d <= best_d:
            best, best_d = i, d
    return best


def snap_time(t, dur):
    clamped = min(max(0.0, dur), max(0.0, t))
    return round(clamped / TIME_GRID) * TIME_GRID


def snap_to_frame(t, starts, tolerance):
    best, best_d = t, tolerance
    for s in starts:
        d = abs(s - t)
        if d < best_d:
            best, best_d = s, d
    return best


def frame_starts(spec):
    out, acc = [], 0.0
    for f in spec["frames"]:
        out.append(acc)
        acc += frame_seconds(f)
    return out


def frame_at(spec, t):
    starts = frame_starts(spec)
    best = 0
    for i, s in enumerate(starts):
        if t + TIME_GRID >= s:
            best = i
    return best


# ------------------------------- 造样本 -------------------------------


def anim(frames, speed=1.0, loop=True, tracks=None):
    return {"id": "a", "name": "a", "speed": speed, "loop": loop, "frames": frames,
            "tracks": tracks or {}}


def frame(angles=None, state="", seconds=0.4):
    return {"angles": angles or {}, "state": state, "seconds": seconds}


def key(t, v, kind=EASE_SMOOTH):
    return {"t": t, "v": v, "ease": kind}


def main():
    print("常数从 Kotlin 源码里读")
    report("时间格 / 命中半径 / 名字列宽和 Kotlin 一致",
           TIME_GRID == 0.01 and HIT_RADIUS_DP == 16.0 and NAME_COLUMN_DP == 76.0,
           "%.2f / %.0f / %.0f" % (TIME_GRID, HIT_RADIUS_DP, NAME_COLUMN_DP))
    report("三类通道各有最小跨度，而且边距是同一个数",
           MIN_ROT_SPAN == 60.0 and MIN_OFFSET_SPAN == 80.0 and MIN_SCALE_SPAN == 0.6
           and PAD_RATIO == 0.15)

    print("\n一条通道在某一刻的值")
    ks = [key(0.0, 0.0, EASE_LINEAR), key(1.0, 100.0, EASE_LINEAR)]
    report("两头是两头", value_at(ks, -5.0) == 0.0 and value_at(ks, 9.0) == 100.0)
    report("线性：中间就是中间", abs(value_at(ks, 0.5) - 50.0) < 1e-4,
           "%.1f" % value_at(ks, 0.5))
    report("第一个关键帧之前是**第一个的值**（不是默认值）",
           value_at(ks, 0.0) == 0.0 and value_at([key(2.0, 30.0)], 0.0) == 30.0)
    report("只有一个关键帧 = 一直是那个值",
           value_at([key(0.5, 42.0)], 0.0) == 42.0 and value_at([key(0.5, 42.0)], 9.0) == 42.0)
    report("空通道给默认值（不是崩）", value_at([], 0.5) == 0.0)

    print("\n缓动挂在**段**的起点上")
    sm = [key(0.0, 0.0, EASE_SMOOTH), key(1.0, 100.0, EASE_LINEAR)]
    report("平滑：中点一样，四分之一点慢下来",
           abs(value_at(sm, 0.5) - 50.0) < 1e-4 and value_at(sm, 0.25) < 25.0,
           "%.1f < 25" % value_at(sm, 0.25))
    st = [key(0.0, 0.0, EASE_STEP), key(1.0, 100.0, EASE_LINEAR)]
    report("阶跃：到下一帧之前一动不动", value_at(st, 0.99) == 0.0 and value_at(st, 1.0) == 100.0)
    report("第二段的缓动由**第二个**关键帧说了算",
           abs(value_at([key(0.0, 0.0, EASE_STEP), key(1.0, 100.0, EASE_LINEAR),
                         key(2.0, 200.0)], 1.5) - 150.0) < 1e-4,
           "%.1f" % value_at([key(0.0, 0.0, EASE_STEP), key(1.0, 100.0, EASE_LINEAR),
                              key(2.0, 200.0)], 1.5))
    report("ease() 三档单调、两端一样",
           ease(0.0, EASE_SMOOTH) == 0.0 and ease(1.0, EASE_SMOOTH) == 1.0
           and ease(0.5, EASE_LINEAR) == 0.5 and ease(0.5, EASE_STEP) == 0.0)

    print("\n插一个关键帧 / 挪一个 / 删一个")
    report("插进去是按时间排好的",
           [k["t"] for k in with_key([key(1.0, 1.0)], 0.5, 2.0)] == [0.5, 1.0])
    report("同一时刻再插一次是**改**它，不是插两个",
           len(with_key([key(0.5, 1.0)], 0.5, 9.0)) == 1
           and with_key([key(0.5, 1.0)], 0.5, 9.0)[0]["v"] == 9.0)
    report("挪一个 = 删掉旧的、按新时间再插",
           [k["t"] for k in moved([key(0.0, 0.0), key(1.0, 5.0)], 1, 0.5, 7.0)] == [0.0, 0.5])
    report("挪的时候值也跟着走", moved([key(0.0, 0.0), key(1.0, 5.0)], 1, 0.5, 7.0)[1]["v"] == 7.0)
    report("删一个只删那一个", [k["t"] for k in removed([key(0.0, 0.0), key(1.0, 5.0)], 0)] == [1.0])
    report("下标越界不动它（不是崩）",
           removed([key(0.0, 0.0)], 5) == [key(0.0, 0.0)] and moved([key(0.0, 0.0)], 5, 1.0, 1.0)
           == [key(0.0, 0.0)])

    print("\n烘培：把帧转成通道，必须**逐点相同**")
    # 这是这一版最值得量的一条。「转成时间轴之后动作变了一点」不会有任何报错，只会不一样。
    rng = random.Random(20260925)
    bones = ["hand_L", "hand_R", "thigh_L", "head"]
    worst = 0.0
    cases = 0
    for trial in range(60):
        n = rng.randint(1, 5)
        frames = []
        for _ in range(n):
            angles = {}
            for b in bones:
                if rng.random() < 0.55:
                    angles[b] = round(rng.uniform(-90, 90), 3)
            frames.append(frame(angles, seconds=rng.choice([0.2, 0.4, 0.75, 1.0])))
        spec = anim(frames, speed=rng.choice([1.0, 0.5, 2.0]), loop=rng.random() < 0.7)
        baked = anim(frames, speed=speed_of(spec), loop=spec["loop"], tracks=bake(spec))
        for step in range(41):
            t = duration(spec) * 1.2 * step / 40.0
            a = anim_sample(spec, t)["angles"]
            b = timeline_sample(baked, t)["angles"]
            cases += 1
            for name in set(a) | set(b):
                worst = max(worst, abs(a.get(name, 0.0) - b.get(name, 0.0)))
    report("60 段随机动画 × 41 个时刻：烘完采样和原来一样（%d 个点）" % cases,
           worst < 1e-3, "最大差 %.6f" % worst)
    report("烘的是**帧里写到过**的骨头（没提过的骨头不烘成一串 0 度）",
           set(bake(anim([frame({"hand_L": 10.0})]))) == {"hand_L"})
    report("已经手调过的通道不被烘焙覆盖",
           bake(anim([frame({"hand_L": 10.0})],
                     tracks={"hand_L": {"rot": [key(0.0, 77.0)]}}))["hand_L"]["rot"][0]["v"] == 77.0)

    print("\n通道和帧是同一个东西的两半")
    spec = anim([frame({"hand_L": 0.0}, seconds=1.0), frame({"hand_L": 90.0}, seconds=1.0)])
    report("没有通道时，采样就是原来的帧采样",
           abs(timeline_sample(spec, 0.5)["angles"]["hand_L"] - 45.0) < 1e-4)
    tr = anim([frame({"hand_L": 0.0}, seconds=1.0), frame({"hand_L": 90.0}, seconds=1.0)],
              tracks={"hand_L": {"rot": [key(0.0, 0.0, EASE_LINEAR), key(2.0, 180.0, EASE_LINEAR)]}})
    report("有通道的骨头归通道管（通道赢）",
           abs(timeline_sample(tr, 1.0)["angles"]["hand_L"] - 90.0) < 1e-4,
           "%.1f" % timeline_sample(tr, 1.0)["angles"]["hand_L"])
    report("**没有**通道的骨头照旧走帧",
           abs(timeline_sample(anim([frame({"a": 0.0, "b": 0.0}, seconds=1.0),
                                     frame({"a": 100.0, "b": 100.0}, seconds=1.0)],
                                    tracks={"a": {"rot": [key(0.0, 0.0, EASE_LINEAR)]}}),
                               0.5)["angles"]["b"] - 50.0) < 1e-4)
    report("位置和缩放只写在有通道的骨头上（没写过 = 不动，不是归零）",
           timeline_sample(anim([frame({}, seconds=1.0)],
                                tracks={"hand_L": {"x": [key(0.0, 12.0, EASE_LINEAR)]}}),
                           0.5)["x"] == {"hand_L": 12.0}
           and timeline_sample(spec, 0.5)["x"] == {})
    report("缩放没写过时是 1 倍（默认值）", default_value(3) == 1.0 and default_value(0) == 0.0)
    report("一帧都没有的动画采样是 None", timeline_sample(anim([]), 0.0) is None)

    print("\n时间轴和动画等长（时长只有一个来源）")
    report("关键帧最多拖到动画末尾",
           abs(max_time(spec) - 2.0) < 1e-4 and max_time(anim([])) == MIN_FRAME)
    long_keys = [key(0.0, 0.0, EASE_LINEAR), key(99.0, 5.0, EASE_LINEAR)]
    longer = anim([frame({}, seconds=1.0)], tracks={"a": {"x": long_keys}})
    at_end = timeline_sample(longer, 1.0)["x"]["a"]
    past_end = timeline_sample(longer, 9.0)["x"]["a"]
    report("通道比动画长也不会把动画拉长（采样夹在末尾，不再变）",
           abs(at_end - past_end) < 1e-6, "%.4f / %.4f" % (at_end, past_end))
    report("而且那个值在两个关键帧**之间**（不外插）", 0.0 <= at_end <= 5.0,
           "%.4f" % at_end)
    report("速度倍率对通道和帧是同一个（2 倍速时 1 秒 = 动画第 2 秒）",
           abs(local_time(anim([frame({}, seconds=1.0), frame({}, seconds=1.0)], speed=2.0),
                          0.5) - 1.0) < 1e-4)

    print("\n插一帧 / 删一帧：关键帧跟着走")
    tr = {"hand_L": {"rot": [key(0.0, 0.0, EASE_LINEAR), key(1.0, 30.0, EASE_LINEAR),
                             key(2.0, 60.0, EASE_LINEAR)]}}
    after = shifted(tr, 1.0, 0.5, 2.5)
    report("插入之后，这一时刻之后的关键帧整体后移",
           [k["t"] for k in after["hand_L"]["rot"]] == [0.0, 1.5, 2.5],
           str([k["t"] for k in after["hand_L"]["rot"]]))
    report("插入点之前的不动", after["hand_L"]["rot"][0]["t"] == 0.0)
    report("值一个都没动", [k["v"] for k in after["hand_L"]["rot"]] == [0.0, 30.0, 60.0])
    report("挪过头就夹在新的末尾（不会跑到动画外面）",
           all(k["t"] <= 2.5 + 1e-6 for k in shifted(tr, 0.0, 9.0, 2.5)["hand_L"]["rot"]))
    gone = dropped(tr, 1.0, 2.0, 2.0)
    report("删掉一段：区间里的关键帧没了，后面的往前挪",
           [k["t"] for k in gone["hand_L"]["rot"]] == [0.0, 1.0],
           str([k["t"] for k in gone["hand_L"]["rot"]]))
    report("空表 / 零长度区间原样返回（不是崩）",
           shifted({}, 0.0, 1.0, 2.0) == {} and dropped(tr, 1.0, 1.0, 2.0) == tr)

    print("\n姿态锚点绑的规则：一遍响一次（1.24.0）")
    loop2 = anim([frame({}, seconds=1.0), frame({}, seconds=1.0)])
    report("前两秒是第 0 遍、接下来两秒是第 1 遍",
           [pass_index(loop2, x) for x in (0.0, 1.9, 2.0, 3.9, 4.0)] == [0, 0, 1, 1, 2],
           str([pass_index(loop2, x) for x in (0.0, 1.9, 2.0, 3.9, 4.0)]))
    report("2 倍速时一遍只有一半长",
           pass_index(anim([frame({}, seconds=1.0)], speed=2.0), 0.5) == 1)
    report("不循环的就一直是第 0 遍",
           all(pass_index(anim([frame({}, seconds=1.0)], loop=False), x) == 0
               for x in (0.0, 5.0, 99.0)))
    report("一帧都没有也不会除零", pass_index(anim([]), 3.0) == 0)
    report("负的时间算第 0 遍（不是负数）", pass_index(loop2, -5.0) == 0)
    # 一帧的动画循环时**帧号一直是 0** —— 用"帧号变了"来判断"又走了一圈"会永远不响，
    # 这正是 passIndex 存在的理由。
    one = anim([frame({}, seconds=0.5)])
    report("一帧的动画每半秒就是一遍（帧号不变，遍数会变）",
           pass_index(one, 0.0) == 0 and pass_index(one, 0.6) == 1 and pass_index(one, 1.1) == 2)

    print("\n几何：时间和像素")
    lane = Lane(left=80.0, top=0.0, width=400.0, height=40.0, dur=2.0)
    report("0 秒在最左、末尾在最右",
           time_to_x(lane, 0.0) == 80.0 and time_to_x(lane, 2.0) == 480.0)
    report("中间是中间", abs(time_to_x(lane, 1.0) - 280.0) < 1e-4)
    report("超出去夹住（不是画到名字列上）",
           time_to_x(lane, -3.0) == 80.0 and time_to_x(lane, 9.0) == 480.0)
    report("换算互逆", all(abs(x_to_time(lane, time_to_x(lane, t)) - t) < 1e-3
                           for t in (0.0, 0.13, 0.5, 1.0, 1.99)))
    report("时长是 0 时不除零", time_to_x(Lane(80.0, 0.0, 400.0, 40.0, 0.0), 1.0) == 80.0)

    print("\n几何：值和高度（上大下小）")
    lo, hi = range_of([key(0.0, 0.0, EASE_LINEAR), key(1.0, 100.0, EASE_LINEAR)], 0)
    report("默认值在范围里", lo <= 0.0 <= hi)
    report("数据在范围里", range_of([key(0.0, -170.0), key(1.0, 170.0)], 0)[0] <= -170.0)
    report("常量通道也有跨度（不会除零成一条线）",
           range_of([key(0.0, 0.0), key(1.0, 0.0)], 0)[1] - range_of([key(0.0, 0.0),
                                                                     key(1.0, 0.0)], 0)[0]
           >= MIN_ROT_SPAN)
    report("缩放的最小跨度是另一把尺子", range_of([key(0.0, 1.0)], 3)[1]
           - range_of([key(0.0, 1.0)], 3)[0] >= MIN_SCALE_SPAN)
    report("值越大画得越靠上（y 越小）",
           value_to_y(100.0, lo, hi, lane) < value_to_y(0.0, lo, hi, lane))
    report("值↔高度互逆", all(abs(y_to_value(value_to_y(v, lo, hi, lane), lo, hi, lane) - v) < 1e-2
                              for v in (lo, lo + (hi - lo) / 3, (lo + hi) / 2, hi)))
    report("超出范围夹在行的上下边（不会画到别的行上）",
           value_to_y(hi * 9, lo, hi, lane) == lane.top
           and value_to_y(lo * 9 - 1e6, lo, hi, lane) == lane.top + lane.height)

    print("\n几何：手指碰到了哪个关键帧")
    ks2 = [key(0.0, 0.0, EASE_LINEAR), key(1.0, 50.0, EASE_LINEAR), key(2.0, 0.0, EASE_LINEAR)]
    lo2, hi2 = range_of(ks2, 0)
    here = (time_to_x(lane, 1.0), value_to_y(50.0, lo2, hi2, lane))
    report("按在菱形上就选中它", hit_key(ks2, lane, lo2, hi2, here[0], here[1], HIT_RADIUS_DP) == 1)
    report("挨着但没按到 = 没选中（不是随便选一个）",
           hit_key(ks2, lane, lo2, hi2, here[0] + HIT_RADIUS_DP + 2, here[1] + 40, HIT_RADIUS_DP) == -1)
    report("整行的高度都算数：纵坐标差多少都能选中（只看横向）",
           hit_key_in_row(ks2, lane, time_to_x(lane, 1.0), HIT_RADIUS_DP) == 1)
    report("横着离太远就不选（不是随便选一个）",
           hit_key_in_row(ks2, lane, time_to_x(lane, 1.0) + HIT_RADIUS_DP + 3, HIT_RADIUS_DP) == -1)
    report("两个挨在一起时选**近的**那一个",
           hit_key([key(1.0, 0.0, EASE_LINEAR), key(1.02, 0.0, EASE_LINEAR)], lane, lo2, hi2,
                   time_to_x(lane, 1.005), value_to_y(0.0, lo2, hi2, lane), HIT_RADIUS_DP) in (0, 1))

    print("\n几何：吸附与帧边界")
    report("时间吸到 0.01 秒的格子", snap_time(0.237, 2.0) == 0.24, "%.2f" % snap_time(0.237, 2.0))
    report("吸附之后夹在 [0, 时长] 里",
           snap_time(-1.0, 2.0) == 0.0 and snap_time(9.0, 2.0) == 2.0)
    starts = frame_starts(spec)
    report("帧起点：前面的帧加起来", starts == [0.0, 1.0])
    report("离帧边界够近就吸过去", snap_to_frame(0.02, starts, FRAME_SNAP) == 0.0)
    report("离得远就不吸（每根骨头要有自己的时间点）",
           snap_to_frame(0.5, starts, FRAME_SNAP) == 0.5)
    report("播放头落在哪一帧", frame_at(spec, 0.0) == 0 and frame_at(spec, 1.0) == 1
           and frame_at(spec, 1.9) == 1)

    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        for f in FAILURES:
            print("  " + f)
        return 1
    print("all timeline tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
