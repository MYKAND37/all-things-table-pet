#!/usr/bin/env python3
"""动画：两帧之间的插值、速度、循环，以及"图什么时候换"。

「加入动画功能，一个是纯演算动画（摆 A、摆 B，中间自己走），另一个是绘制动画（画不同的帧
然后播放，帧之间还能调骨骼）」。这两件事在这一版里是**同一个模型**：一帧 = 一个可选姿势 +
一个可选开关（开关决定这帧画哪套图）。所以真正需要被测的不是"播放"，而是**采样**：
在 t 时刻该摆什么姿势、显示哪一帧的图 —— 那是纯数学，也是唯一会悄悄错的地方。

    python3 tools/anim_check.py

镜像 Animation.kt 的 Anim.sample / duration / realDuration，常数从 Kotlin 源码里读出来。
"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
KT = os.path.join(REPO, "app/src/main/java/dev/atp/pet/engine/anim/Animation.kt")

FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)


def kotlin_const(name, default):
    """从 Kotlin 源码里读一个 const val —— 夹住的数字漂了两边都看不出来。"""
    text = open(KT, encoding="utf-8").read()
    m = re.search(r"const val %s\s*=\s*([0-9.]+)f" % name, text)
    return float(m.group(1)) if m else default


MIN_FRAME = kotlin_const("MIN_FRAME", 0.05)
MIN_SPEED = kotlin_const("MIN_SPEED", 0.1)
MAX_SPEED = kotlin_const("MAX_SPEED", 4.0)
#: 新抓的一帧默认走多久。界面（抓帧）和引擎（AnimFrame 的默认值）用同一个数。
DEFAULT_FRAME_SECONDS = kotlin_const("DEFAULT_FRAME_SECONDS", 0.4)


# ------------------------------- mirror of Anim -------------------------------


def frame_seconds(f):
    return max(MIN_FRAME, float(f.get("seconds", 0.4)))


def states_of(state):
    """一帧里写着的那几个图的开关。分隔符 `+`。

    `+` 进不了状态 id（`RigEdit.sanitise` 把它换成下划线），所以这里怎么拆都不会有歧义。
    """
    return [p.strip() for p in (state or "").split("+") if p.strip()]


def join_states(states):
    """反过来拼回去：去重、保序（先点的先写）。"""
    out = []
    for s in states:
        s = s.strip()
        if s and s not in out:
            out.append(s)
    return "+".join(out)


def frame_pose(a, index):
    """第 index 帧**开始那一刻**的姿势 —— 编辑器和播放器必须看到同一个东西。"""
    frames = a["frames"]
    if index < 0 or index >= len(frames):
        return {}
    here = frames[index]
    nxt = frames[index + 1] if index + 1 < len(frames) else (
        frames[0] if a.get("loop", True) else None)
    if nxt is None or nxt is here:
        return dict(here.get("angles", {}))
    return blend(here, nxt, 0.0)


def start_seconds(a, index):
    """第 index 帧在动画自己的时间里从第几秒开始。"""
    i = min(max(index, 0), len(a["frames"]))
    return sum(frame_seconds(f) for f in a["frames"][:i])


def duration(a):
    return sum(frame_seconds(f) for f in a["frames"])


def speed_of(a):
    return min(MAX_SPEED, max(MIN_SPEED, float(a.get("speed", 1.0))))


def real_duration(a):
    return duration(a) / speed_of(a)


def blend(x, y, f):
    """两帧之间插值：每根骨头各自算，没写到的那一侧保持另一侧的值。

    这条规矩是刻意的（见 Animation.kt）：只写要动的那几节是写动画最自然的方式，而"帧里没提
    腿"当成 0 度，会让第二帧一到整只宠物的腿弹回站姿。
    """
    if not x.get("angles") and not y.get("angles"):
        return {}
    names = list(x.get("angles", {}).keys()) + [n for n in y.get("angles", {})
                                                if n not in x.get("angles", {})]
    out = {}
    for name in names:
        a = x["angles"].get(name, y["angles"].get(name, 0.0))
        b = y["angles"].get(name, x["angles"].get(name, 0.0))
        out[name] = a + (b - a) * f
    return out


def sample(a, seconds):
    frames = a["frames"]
    if not frames:
        return None
    total = duration(a)
    raw = max(0.0, seconds) * speed_of(a)
    if a.get("loop", True):
        t = raw - total * (raw // total)
    else:
        t = min(raw, total)
    acc = 0.0
    for i, f in enumerate(frames):
        d = frame_seconds(f)
        if t < acc + d or i == len(frames) - 1:
            frac = 0.0 if d <= 0 else min(1.0, max(0.0, (t - acc) / d))
            nxt = frames[i + 1] if i + 1 < len(frames) else (frames[0] if a.get("loop", True) else f)
            return {
                "angles": blend(f, nxt, frac),
                "state": f.get("state", ""),
                "frame": i,
                "frames": len(frames),
            }
        acc += d
    return {"angles": frames[-1].get("angles", {}), "state": frames[-1].get("state", ""),
            "frame": len(frames) - 1, "frames": len(frames)}


def anim(frames, speed=1.0, loop=True):
    return {"id": "a", "name": "a", "speed": speed, "loop": loop, "frames": frames}


def frame(angles=None, state="", seconds=DEFAULT_FRAME_SECONDS):
    return {"angles": angles or {}, "state": state, "seconds": seconds}


def main():
    print("常数从 Kotlin 源码里读")
    report("MIN_FRAME / MIN_SPEED / MAX_SPEED 和 Kotlin 一致",
           MIN_FRAME == 0.05 and MIN_SPEED == 0.1 and MAX_SPEED == 4.0,
           "%.2f / %.2f / %.1f" % (MIN_FRAME, MIN_SPEED, MAX_SPEED))

    print("\n没有帧：什么都不放")
    report("空动画采样是 None", sample(anim([]), 0.0) is None)
    report("时长是 0", duration(anim([])) == 0.0)

    print("\n纯演算：两帧之间自己走")
    # 手从 -30 度走到 60 度，两帧各 1 秒。这是用户说的那个例子："先摆到 A，再摆到 B"。
    a = anim([frame({"hand": -30.0}, seconds=1.0),
              frame({"hand": 60.0}, seconds=1.0)])
    s0 = sample(a, 0.0)
    report("t=0 就是第一帧", s0["frame"] == 0 and abs(s0["angles"]["hand"] + 30) < 1e-4,
           str(s0["angles"]))
    mid = sample(a, 0.5)
    report("中途走到一半（-30 → 15）", abs(mid["angles"]["hand"] - 15.0) < 1e-4,
           "%.2f" % mid["angles"]["hand"])
    report("而且还在第一帧里", mid["frame"] == 0)
    s1 = sample(a, 1.0)
    report("第二帧一开始就是目标角度", s1["frame"] == 1 and abs(s1["angles"]["hand"] - 60) < 1e-4,
           str(s1["angles"]))
    # 两帧、每帧 1 秒、循环：第二帧的那一秒是在往第一帧走 —— 循环是闭合的。
    back = sample(a, 1.5)
    report("循环时最后一帧插值回第一帧（闭合）",
           abs(back["angles"]["hand"] - 15.0) < 1e-4 and back["frame"] == 1,
           "%.2f" % back["angles"]["hand"])
    # 刚好一圈：t=2.0 → 0，正是第一帧的开头。（第一版这里写的是 2.1，期望 -30 度 —— 错了
    # 的是断言不是代码：2.1 已经走进了第一帧 10%，角度是 -21，因为它正朝下一帧走。）
    wrap = sample(a, 2.0)
    report("刚好一圈回到开头", wrap["frame"] == 0 and abs(wrap["angles"]["hand"] + 30) < 1e-4,
           "%.2f" % wrap["angles"]["hand"])
    just = sample(a, 2.1)
    report("绕回来之后继续往下一帧走（不是卡在起点）",
           just["frame"] == 0 and -30.0 < just["angles"]["hand"] < 60.0,
           "%.2f" % just["angles"]["hand"])

    print("\n不循环：停在最后一帧")
    b = anim([frame({"hand": -30.0}, seconds=1.0), frame({"hand": 60.0}, seconds=1.0)], loop=False)
    end = sample(b, 99.0)
    report("时间夹在末尾，姿势是最后一帧的", end["frame"] == 1 and abs(end["angles"]["hand"] - 60) < 1e-4)
    report("不循环时第二帧不再往第一帧走", abs(sample(b, 1.5)["angles"]["hand"] - 60.0) < 1e-4,
           "%.2f" % sample(b, 1.5)["angles"]["hand"])

    print("\n速度是一个倍率")
    fast = anim([frame({"hand": -30.0}, seconds=1.0), frame({"hand": 60.0}, seconds=1.0)], speed=2.0)
    report("2 倍速：一半时间就到中点", abs(sample(fast, 0.25)["angles"]["hand"] - 15.0) < 1e-4,
           "%.2f" % sample(fast, 0.25)["angles"]["hand"])
    report("2 倍速：一半时间就换到第二帧", sample(fast, 0.5)["frame"] == 1)
    slow = anim([frame({"hand": -30.0}, seconds=1.0), frame({"hand": 60.0}, seconds=1.0)], speed=0.5)
    report("0.5 倍速：中点要等到 1.0 秒", abs(sample(slow, 1.0)["angles"]["hand"] - 15.0) < 1e-4)
    report("倍率有上下限（0 会让时间停住）",
           speed_of({"speed": 0.0}) == MIN_SPEED and speed_of({"speed": 99.0}) == MAX_SPEED)
    report("挂钟时长 = 动画时长 / 速度", abs(real_duration(fast) - 1.0) < 1e-4,
           "%.2f（动画 2 秒，2 倍速）" % real_duration(fast))

    print("\n绘制：图在帧边界换，不在中间换")
    c = anim([frame({}, state="帧1", seconds=0.5), frame({}, state="帧2", seconds=0.5)])
    report("第一帧里显示帧1", sample(c, 0.1)["state"] == "帧1" and sample(c, 0.49)["state"] == "帧1")
    report("到了边界就换成帧2", sample(c, 0.5)["state"] == "帧2")
    report("循环回到第一帧时也换回来", sample(c, 1.0)["state"] == "帧1")

    print("\n半演算：图换了，骨骼照样在动")
    d = anim([frame({"arm": 0.0}, state="帧1", seconds=1.0),
              frame({"arm": 90.0}, state="帧2", seconds=1.0)])
    half = sample(d, 0.5)
    report("同一时刻既有插值角度、也有这一帧的图",
           abs(half["angles"]["arm"] - 45.0) < 1e-4 and half["state"] == "帧1",
           "%.1f 度 · %s" % (half["angles"]["arm"], half["state"]))

    print("\n一帧只写要动的那几节")
    e = anim([frame({"arm": 10.0}, seconds=1.0), frame({"arm": 90.0, "leg": 20.0}, seconds=1.0)])
    mid2 = sample(e, 0.5)
    report("没写到的那一侧保持另一侧的值（腿不会弹回 0 度）",
           abs(mid2["angles"]["leg"] - 20.0) < 1e-4, "%.1f" % mid2["angles"]["leg"])
    f2 = anim([frame({"arm": 10.0, "leg": 40.0}, seconds=1.0), frame({"arm": 90.0}, seconds=1.0)])
    report("反过来也一样（第一帧有、第二帧没写 → 保持）",
           abs(sample(f2, 0.5)["angles"]["leg"] - 40.0) < 1e-4)
    report("两帧都没有角度时不做无谓的插值", sample(anim([frame({}, seconds=1.0),
                                                   frame({}, seconds=1.0)]), 0.5)["angles"] == {})

    print("\n一帧的动画")
    one = anim([frame({"arm": 33.0}, state="帧1", seconds=0.4)])
    report("一直保持那一帧", sample(one, 0.0)["frame"] == 0 and sample(one, 9.0)["frame"] == 0
           and abs(sample(one, 9.0)["angles"]["arm"] - 33.0) < 1e-4)

    print("\n0 秒的帧不会把时间轴弄坏")
    z = anim([frame({"arm": 0.0}, seconds=0.0), frame({"arm": 100.0}, seconds=1.0)])
    report("0 秒的帧按最短时长算（不是除以零）",
           abs(frame_seconds({"seconds": 0.0}) - MIN_FRAME) < 1e-9
           and sample(z, 0.5)["angles"]["arm"] is not None)
    report("负的秒数也一样", frame_seconds({"seconds": -3.0}) == MIN_FRAME)

    print("\n一帧可以同时开好几个图的开关（1.22.0）")
    kt_anim = open(KT, encoding="utf-8").read()
    report("默认帧时长只有一个来源（Kotlin 的常数 = 这份镜像）",
           abs(DEFAULT_FRAME_SECONDS - 0.4) < 1e-9
           and "val seconds: Float = Anim.DEFAULT_FRAME_SECONDS" in kt_anim,
           "%.2f" % DEFAULT_FRAME_SECONDS)
    report("拆 / 拼的规矩在 Kotlin 那边也是加号，而且去了空",
           "state.split('+')" in kt_anim and 'joinToString("+")' in kt_anim
           and ".filter { it.isNotEmpty() }" in kt_anim)
    report("空 = 什么都不开", states_of("") == [] and states_of("   ") == [])
    report("加号拆开", states_of("帧2+出汗") == ["帧2", "出汗"])
    report("多余的加号 / 空格不会拆出空名字", states_of(" a + b + ") == ["a", "b"])
    report("拼回去：去重、保序（先点的先写）", join_states(["b", "a", "b"]) == "b+a")
    report("拆了再拼是原样", join_states(states_of("帧2+出汗")) == "帧2+出汗")
    report("什么都不开拼回空串 —— 和没写过是同一个值", join_states([]) == "")
    # 分隔符不能出现在 id 里，否则 "a+b" 是"两个开关"还是"一个名字"就分不出来。
    # 这条读的是 Kotlin 源码里那份 sanitise 的字符表，不靠记性。
    kt_rig = open(os.path.join(REPO, "app/src/main/java/dev/atp/pet/engine/skeleton/RigEdit.kt"),
                  encoding="utf-8").read()
    report("状态 id 里进不了加号（sanitise 会把它换成下划线）",
           '"/\\\\:*?\\"<>|+"' in kt_rig)

    print("\n编辑器看到的那一帧，就是播放器演到那一刻的样子")
    # 帧起始的姿势必须**等于** sample 在那一刻的结果，不能是"另算一套"：编辑器摆好、
    # 存下去、一播却不一样，是最难查的那种错。时间用二进制精确的值（0.5 秒、整倍数速度），
    # 所以边界上的相等是精确的，不需要容差兜着。
    cases = [
        ("两帧循环", anim([frame({"arm": -30.0}, seconds=0.5),
                       frame({"arm": 60.0}, seconds=0.5)]), 1.0),
        ("三帧不循环", anim([frame({"arm": -30.0}, seconds=0.5),
                         frame({"arm": 0.0, "leg": 10.0}, seconds=0.5),
                         frame({"arm": 60.0}, seconds=0.5)], loop=False), 1.0),
        ("2 倍速", anim([frame({"arm": -30.0}, seconds=0.5),
                       frame({"arm": 60.0}, seconds=0.5)], speed=2.0), 2.0),
        ("一帧", anim([frame({"arm": 33.0}, state="帧1", seconds=0.5)]), 1.0),
        ("0 秒的帧也在里面", anim([frame({"arm": 1.0}, seconds=0.0),
                             frame({"arm": 50.0}, seconds=0.5)]), 1.0),
    ]
    for label, a, speed in cases:
        ok = True
        detail = ""
        for i in range(len(a["frames"])):
            pose = frame_pose(a, i)
            s = sample(a, start_seconds(a, i) / speed_of(a))
            if s is None or s["frame"] != i:
                ok = False
                detail = "第 %d 帧落到了第 %s 帧" % (i + 1, s["frame"] + 1 if s else "?")
                break
            if set(pose.keys()) != set(s["angles"].keys()) or any(
                    abs(pose[k] - s["angles"][k]) > 1e-6 for k in pose):
                ok = False
                detail = "第 %d 帧 %s ≠ %s" % (i + 1, pose, s["angles"])
                break
        report("%s：每一帧的起始姿势都和播放器一致" % label, ok, detail)

    # 这条是上一条最容易出错的地方，单独说一遍：下一帧新引入的骨头，在这一帧里**已经是**
    # 下一帧的值（blend 的"没写到的一侧保持另一侧"）。编辑器照这个显示，才不会摆出一个
    # 引擎演不出来的姿势。
    inherit = anim([frame({"arm": 10.0}, seconds=0.5),
                    frame({"arm": 90.0, "leg": 20.0}, seconds=0.5)])
    report("下一帧才写的骨头，在这一帧里就已经是那个值（不是 0）",
           abs(frame_pose(inherit, 0)["leg"] - 20.0) < 1e-6, str(frame_pose(inherit, 0)))
    report("没有这一帧时给空表（不是崩）",
           frame_pose(inherit, 9) == {} and frame_pose(inherit, -1) == {})
    report("帧起点的时间：前面几帧加起来", abs(start_seconds(inherit, 1) - 0.5) < 1e-9
           and start_seconds(inherit, 0) == 0.0)
    report("帧起点的时间会夹住（越界不返回负数）",
           start_seconds(inherit, -5) == 0.0 and abs(start_seconds(inherit, 99) - 1.0) < 1e-9)

    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        for f in FAILURES:
            print("  " + f)
        return 1
    print("all anim tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
