#!/usr/bin/env python3
"""绳子和钉子，在它们被画出来之前先算一遍。

锚点、钉子、连绳最后都落成同一个东西：一根绳子拉直了就是一串 pin，一颗钉子
就是一个目标永不移动的 pin。所以这里测的不是三种道具，是那一条换算 —— 以及
它周围那几条只有手指才会碰到的规矩：钉的是被点中的那个点而不是关节、桩不拉
身体而是挡身体、一个点被工具吃掉之后就不再是双击的一半。

    python3 tools/tether_check.py
"""
import math, sys

FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)


# ------------------------- mirror of PhysicsSandboxView.kt -------------------------

PIECE_PUSH = 10.0        # 每帧把骨头推出去的比例，和碎块共用
PIECE_PUSH_MAX = 90.0    # 一次推多少封顶
CONTACT_PERIOD = 0.25    # 同一个桩对同一根骨头的接触事件间隔
ROPE_GRAB = 30.0         # 点绳子取下时的容差，世界像素
PIN_HEAD = 1.2           # 点钉子拔出来时，判定半径 = 道具半径 * 这个 + 24
SEGMENT_MIN = 26.0       # 小于这个的框是手指抖了一下，不是绳子
SEGMENT_MIN_R = 5.0      # 绳子的粗细上下限
SEGMENT_MAX_R = 48.0
SEGMENT_END_REACH = 26.0  # 点端头算「再接一截」的容差


class End:
    """绳子的一端：骨头上的某个点、一个道具、或者世界上的一个点。"""

    def __init__(self, bone=None, offset=0.0, prop=None, at=(0.0, 0.0)):
        self.bone, self.offset, self.prop, self.at = bone, offset, prop, at

    def point(self, bones):
        if self.prop is not None:
            return self.prop
        if self.bone is not None:
            b = bones[self.bone]
            # grip_point：0 是关节，非 0 是沿骨头方向的有符号距离。
            a = b["angle"]
            return (b["head"][0] + math.cos(a) * self.offset,
                    b["head"][1] + math.sin(a) * self.offset)
        return self.at


class Rope:
    def __init__(self, owner, a, b, length):
        self.owner, self.a, self.b, self.length = owner, a, b, length


def rope_pins(ropes, bones):
    """拉直才成钉：松弛的绳子一根 pin 都不该产生。"""
    out = []
    for rope in ropes:
        ax, ay = rope.a.point(bones)
        bx, by = rope.b.point(bones)
        dx, dy = bx - ax, by - ay
        d = math.hypot(dx, dy)
        if d <= rope.length or d < 1e-4:
            continue
        t = rope.length / d
        if rope.b.bone is not None:
            out.append((rope.b.bone, (ax + dx * t, ay + dy * t), rope.b.offset))
        if rope.a.bone is not None:
            out.append((rope.a.bone, (bx - dx * t, by - dy * t), rope.a.offset))
    return out


def nail_pins(nails):
    return [(n["bone"], n["at"], n["offset"]) for n in nails if n["bone"] is not None]


def peg_push(peg, bone, radius_of):
    """桩把骨头推出去。返回 (方向, 力度)，不该推的时候返回 None。

    方向必须背离桩 —— 反过来就是把骨头吸进桩里，而症状看起来像「角色贴上去就
    不动了」，不像符号错。
    """
    a, t = bone["head"], bone["tip"]
    abx, aby = t[0] - a[0], t[1] - a[1]
    len_sq = abx * abx + aby * aby
    k = 0.0 if len_sq < 1e-6 else max(0.0, min(1.0, ((peg[0] - a[0]) * abx + (peg[1] - a[1]) * aby) / len_sq))
    dx, dy = peg[0] - (a[0] + abx * k), peg[1] - (a[1] + aby * k)
    dist = math.hypot(dx, dy)
    gap = radius_of + bone["r"]
    if dist >= gap or dist < 1e-4:
        return None
    slide = gap - dist
    return ((-dx / dist, -dy / dist), min(slide * PIECE_PUSH, PIECE_PUSH_MAX))


def grip_at(point, bones, head_height):
    """Ragdoll.grabAt：给得比骨头宽得多，因为漏掉一次抓取是最糟的结果。"""
    best, best_d, best_off = None, None, 0.0
    for name, b in bones.items():
        a, t = b["head"], b["tip"]
        abx, aby = t[0] - a[0], t[1] - a[1]
        len_sq = abx * abx + aby * aby
        k = 0.0 if len_sq < 1e-6 else max(0.0, min(1.0, ((point[0] - a[0]) * abx + (point[1] - a[1]) * aby) / len_sq))
        near = (a[0] + abx * k, a[1] + aby * k)
        d = math.hypot(point[0] - near[0], point[1] - near[1]) - b["r"]
        if best_d is None or d < best_d:
            best, best_d, best_off = name, d, k * b["length"]
    if best is None or best_d >= head_height * 0.9:
        return None
    return best, best_off


def drive_pin(point, bones, head_height):
    """返回 (bone, offset, at)：钉在部位上时，钉的是中线上的那个点。"""
    grip = grip_at(point, bones, head_height)
    if grip is None:
        return None, 0.0, point
    name, offset = grip
    b = bones[name]
    a = b["angle"]
    at = (b["head"][0] + math.cos(a) * offset, b["head"][1] + math.sin(a) * offset)
    return name, offset, at


def segment_distance(p, a, b):
    abx, aby = b[0] - a[0], b[1] - a[1]
    len_sq = abx * abx + aby * aby
    if len_sq < 1e-6:
        return math.hypot(p[0] - a[0], p[1] - a[1])
    t = max(0.0, min(1.0, ((p[0] - a[0]) * abx + (p[1] - a[1]) * aby) / len_sq))
    return math.hypot(p[0] - (a[0] + abx * t), p[1] - (a[1] + aby * t))


def tool_tap(point, state, bones, head_height):
    """一次点击先给工具。返回做了什么，或者 None（那才是「被点一下」）。"""
    for nail in list(state["nails"]):
        if math.hypot(point[0] - nail["at"][0], point[1] - nail["at"][1]) <= nail["r"] * PIN_HEAD + 24:
            state["nails"].remove(nail)
            state["waiting_pins"].append(nail)
            return "pull"
    if state["waiting_pins"]:
        pin = state["waiting_pins"].pop(0)
        bone, offset, at = drive_pin(point, bones, head_height)
        state["nails"].append({"bone": bone, "offset": offset, "at": at, "r": pin["r"]})
        return "drive"
    if state["draft"] is not None:
        draft = state["draft"]
        ax, ay = draft["first"].point(bones)
        grip = grip_at(point, bones, head_height)
        second = End(bone=grip[0], offset=grip[1]) if grip else End(at=point)
        bx, by = second.point(bones)
        state["ropes"].append(Rope(draft["owner"], draft["first"], second, math.hypot(bx - ax, by - ay)))
        state["draft"] = None
        return "tie"
    if state["waiting_ropes"]:
        rope = state["waiting_ropes"].pop(0)
        grip = grip_at(point, bones, head_height)
        first = End(bone=grip[0], offset=grip[1]) if grip else End(at=point)
        state["draft"] = {"owner": rope, "first": first}
        return "start"
    for rope in list(state["ropes"]):
        # 锚点的绳子不这么取下来：那是把桩拿起来的事。
        if rope.owner.get("kind") != "rope":
            continue
        a, b = rope.a.point(bones), rope.b.point(bones)
        if segment_distance(point, a, b) < ROPE_GRAB:
            state["ropes"].remove(rope)
            return "untie"
    return None


def rope_from(frm, to):
    """一个拖出来的长方形变成什么：长边是长度，短边是粗细。

    两端在短边的**中点**，不是框的角 —— 角上的绳子比画出来的框长一截，而且贴着
    边走，画一个胖框就会得到一根斜的绳子。
    """
    w, h = abs(to[0] - frm[0]), abs(to[1] - frm[1])
    # 只有**长边**要够大。横着拖的手指不会顺便画出一个高度，它画的是一条几像素
    # 厚的线；把这种框扔掉的规矩会把所有人画的绳子都扔掉。
    if max(w, h) < SEGMENT_MIN:
        return None
    r = max(SEGMENT_MIN_R, min(SEGMENT_MAX_R, min(w, h) / 2.0))
    mid_x, mid_y = (frm[0] + to[0]) / 2.0, (frm[1] + to[1]) / 2.0
    if w >= h:
        return ((min(frm[0], to[0]), mid_y), (max(frm[0], to[0]), mid_y), r)
    return ((mid_x, min(frm[1], to[1])), (mid_x, max(frm[1], to[1])), r)


def extend(seg, from_b):
    """复制：一模一样的一截，接在被点中的那头。"""
    a, b, r = seg
    dx, dy = b[0] - a[0], b[1] - a[1]
    if math.hypot(dx, dy) < 1e-3:
        return None
    if from_b:
        return (b, (b[0] + dx, b[1] + dy), r)
    return ((a[0] - dx, a[1] - dy), a, r)


def segment_end_at(p, segments):
    best, best_d = None, None
    for seg in segments:
        for from_b, end in ((False, seg[0]), (True, seg[1])):
            d = math.hypot(p[0] - end[0], p[1] - end[1])
            if d <= seg[2] + SEGMENT_END_REACH and (best_d is None or d < best_d):
                best, best_d = (seg, from_b), d
    return best


def segment_push(seg, bone, radius_of):
    """骨头**端点**对绳子的重叠，和碎块用的是同一套近似和同一个上限。"""
    a, b, r = seg
    out = []
    for end in (bone["head"], bone["tip"]):
        c = closest_on_segment(end, a, b)
        dx, dy = end[0] - c[0], end[1] - c[1]
        dist = math.hypot(dx, dy)
        gap = r + radius_of + bone["r"]
        if dist >= gap or dist < 1e-4:
            continue
        out.append(((dx / dist, dy / dist), min((gap - dist) * PIECE_PUSH, PIECE_PUSH_MAX)))
    return out


def closest_on_segment(p, a, b):
    abx, aby = b[0] - a[0], b[1] - a[1]
    len_sq = abx * abx + aby * aby
    if len_sq < 1e-6:
        return a
    t = max(0.0, min(1.0, ((p[0] - a[0]) * abx + (p[1] - a[1]) * aby) / len_sq))
    return (a[0] + abx * t, a[1] + aby * t)


def tool_armed(state):
    """有待放的工具时，双击不能重置世界：连绳本来就是「点、再点」。"""
    return bool(state["waiting_pins"]) or bool(state["waiting_ropes"]) or state["draft"] is not None


def bone(angle=0.0, length=200.0, r=15.0):
    head = (1000.0, 1000.0)
    return {
        "head": head, "tip": (head[0] + math.cos(angle) * length, head[1] + math.sin(angle) * length),
        "angle": angle, "length": length, "r": r,
    }


def main():
    print("绳子和钉子")
    noop = lambda *a: None
    noop()

    # ---- 拉直才成钉 ----
    print("\n松弛的绳子不是钉子")
    bones = {"hand_L": bone()}
    slack = Rope({"kind": "rope"}, End(at=(1000.0, 1000.0)), End(bone="hand_L", offset=0.0), 400.0)
    bones["hand_L"]["head"] = (1400.0, 1000.0)
    bones["hand_L"]["tip"] = (1600.0, 1000.0)
    report("inside the circle there is nothing to feel", rope_pins([slack], bones) == [])

    bones["hand_L"]["head"] = (1600.0, 1000.0)
    bones["hand_L"]["tip"] = (1800.0, 1000.0)
    pins = rope_pins([slack], bones)
    report("once taut, exactly one pin", len(pins) == 1, "%d pin(s)" % len(pins))
    name, target, offset = pins[0]
    d = math.hypot(target[0] - 1000.0, target[1] - 1000.0)
    report("and its target sits ON the circle the rope allows",
           abs(d - 400.0) < 1e-6, "%.4f from the stake" % d)
    report("along the line to the limb, not somewhere else",
           abs(target[1] - 1000.0) < 1e-6, "y=%.4f" % target[1])

    # ---- 两个部位系在一起 ----
    print("\n两个部位系在一起：两端各一个 pin")
    bones = {"hand_L": bone(), "hand_R": bone()}
    bones["hand_L"]["head"] = (1000.0, 1000.0)
    bones["hand_L"]["tip"] = (1200.0, 1000.0)
    bones["hand_R"]["head"] = (1600.0, 1000.0)
    bones["hand_R"]["tip"] = (1800.0, 1000.0)
    tied = Rope({"kind": "rope"}, End(bone="hand_L", offset=0.0), End(bone="hand_R", offset=0.0), 400.0)
    pins = rope_pins([tied], bones)
    report("both ends are held", len(pins) == 2, "%d pin(s)" % len(pins))
    # 每一端的目标都落在「以另一端为心、绳长为半径」的那个圆上：这就是把两个部位
    # 系在一起的全部内容，而它同时要求两边都往中间拉 —— 符号反了就是把它们推开。
    far = {"hand_L": (1600.0, 1000.0), "hand_R": (1000.0, 1000.0)}
    report("each target sits on the circle around the OTHER end",
           all(abs(math.hypot(p[1][0] - far[p[0]][0], p[1][1] - far[p[0]][1]) - 400.0) < 1e-6
               for p in pins))
    report("and both ends are pulled towards each other, not apart",
           min(p[1][0] for p in pins) > 1000.0 and max(p[1][0] for p in pins) < 1600.0,
           "targets at x=%s" % [round(p[1][0], 1) for p in pins])

    # ---- 钉的是被点中的那个点 ----
    print("\n钉在部位上：钉的是被点中的那个点，不是关节")
    bones = {"hand_L": bone()}
    name, offset, at = drive_pin((1100.0, 1010.0), bones, 200.0)
    report("the tap is taken by the bone it landed on", name == "hand_L")
    report("at the offset the finger landed on", abs(offset - 100.0) < 1e-6, "offset=%.4f" % offset)
    report("and the nail holds THAT point", abs(at[0] - 1100.0) < 1e-6 and abs(at[1] - 1000.0) < 1e-6,
           "(%.1f, %.1f)" % at)
    # 记 offset 而不是 0 的理由：钉在指尖上的钉子必须拽住指尖。丢掉 offset 之后
    # 钉子会去拽关节，肢体就会绕着一个根本没人钉的地方摆。
    bones["hand_L"]["head"] = (1200.0, 1000.0)
    bones["hand_L"]["tip"] = (1400.0, 1000.0)
    nail = {"bone": name, "offset": offset, "at": at, "r": 40.0}
    pins = nail_pins([nail])
    report("a nail's target does NOT follow the bone", pins[0][1] == at, str(pins[0][1]))
    report("and the pin still asks for the tapped spot", pins[0][2] == offset)

    # ---- 桌面上的钉子：不拉身体，挡住身体 ----
    print("\n钉在桌上：一个桩，身体撞上去")
    peg = (1000.0, 1000.0)
    bones = {"hand_L": bone()}
    bones["hand_L"]["head"] = (1000.0, 1050.0)
    bones["hand_L"]["tip"] = (1200.0, 1050.0)
    push = peg_push(peg, bones["hand_L"], 40.0)
    report("a bone inside the peg is pushed out", push is not None)
    direction, strength = push
    report("away from the peg, not into it", direction[1] > 0.99, "(%.3f, %.3f)" % direction)
    report("by the overlap, capped", abs(strength - 5.0 * PIECE_PUSH) < 1e-6, "strength=%.1f" % strength)
    bones["hand_L"]["head"] = (1000.0, 1140.0)
    bones["hand_L"]["tip"] = (1200.0, 1140.0)
    report("and a bone that is clear of it is not pushed at all",
           peg_push(peg, bones["hand_L"], 40.0) is None)

    # ---- 被工具吃掉的那一下不是双击 ----
    print("\n有待放的工具时，双击不重置世界")
    state = {"nails": [], "waiting_pins": [{"r": 40.0}], "waiting_ropes": [], "draft": None, "ropes": []}
    report("a waiting nail turns the double tap off", tool_armed(state))
    state["waiting_pins"] = []
    report("nothing waiting, nothing to protect", not tool_armed(state))
    state["draft"] = {"owner": {"kind": "rope"}, "first": End(at=(0.0, 0.0))}
    report("and a half-tied rope counts as armed too", tool_armed(state))

    # ---- 先后 ----
    print("\n一次点击先给工具，而且有先后")
    bones = {"hand_L": bone()}
    state = {
        "nails": [{"bone": "hand_L", "offset": 100.0, "at": (1100.0, 1000.0), "r": 40.0}],
        "waiting_pins": [{"r": 40.0}], "waiting_ropes": [{"r": 40.0}], "draft": None, "ropes": [],
    }
    report("a tap on a driven nail pulls it out first",
           tool_tap((1105.0, 1005.0), state, bones, 200.0) == "pull")
    report("and pulling it out puts it back in the queue",
           len(state["waiting_pins"]) == 2 and state["nails"] == [])

    state = {"nails": [], "waiting_pins": [{"r": 40.0}], "waiting_ropes": [{"r": 40.0}],
             "draft": None, "ropes": []}
    report("a waiting nail beats a waiting rope",
           tool_tap((1500.0, 900.0), state, bones, 200.0) == "drive")
    report("and the nail went into the table, since nothing was under the tap",
           state["nails"][0]["bone"] is None)
    report("the rope is still waiting", len(state["waiting_ropes"]) == 1)

    state = {"nails": [], "waiting_pins": [], "waiting_ropes": [{"r": 40.0}],
             "draft": None, "ropes": []}
    report("an empty tap starts a rope", tool_tap((1500.0, 900.0), state, bones, 200.0) == "start")
    report("the length is not decided yet", state["ropes"] == [] and state["draft"] is not None)
    report("the second tap ties it, and the length is the distance",
           tool_tap((1900.0, 900.0), state, bones, 200.0) == "tie")
    rope = state["ropes"][0]
    report("400px apart", abs(rope.length - 400.0) < 1e-6, "%.4f" % rope.length)

    state = {"nails": [], "waiting_pins": [], "waiting_ropes": [],
             "draft": None,
             "ropes": [Rope({"kind": "rope"}, End(at=(1000.0, 1000.0)), End(at=(1400.0, 1000.0)), 400.0)]}
    report("a 连绳 is taken off by tapping it",
           tool_tap((1200.0, 1010.0), state, bones, 200.0) == "untie")
    state = {"nails": [], "waiting_pins": [], "waiting_ropes": [],
             "draft": None,
             "ropes": [Rope({"kind": "anchor"}, End(at=(1000.0, 1000.0)), End(at=(1400.0, 1000.0)), 400.0)]}
    report("an 锚点 rope is not: the stake is what comes off",
           tool_tap((1200.0, 1000.0), state, bones, 200.0) is None)
    state = {"nails": [], "waiting_pins": [], "waiting_ropes": [], "draft": None, "ropes": []}
    report("and a tap on nothing at all is still a poke",
           tool_tap((5000.0, 5000.0), state, bones, 200.0) is None)

    # ---- 画出来的绳段 ----
    print("\n拖出来的长方形变成一截绳子")
    drawn = rope_from((1000.0, 1000.0), (1400.0, 1080.0))
    report("a wide box makes a horizontal rope", drawn is not None)
    a, b, r = drawn
    report("running the whole length of the long side",
           a[0] == 1000.0 and b[0] == 1400.0, "%.0f..%.0f" % (a[0], b[0]))
    report("at the middle of the short side, not the corner",
           a[1] == 1040.0 and b[1] == 1040.0, "y=%.0f" % a[1])
    report("and the short side is the thickness", abs(r - 40.0) < 1e-6, "r=%.1f" % r)

    drawn = rope_from((1000.0, 1000.0), (1080.0, 1400.0))
    a, b, r = drawn
    report("a tall box makes a vertical one",
           a[0] == 1040.0 and a[1] == 1000.0 and b[1] == 1400.0, "x=%.0f" % a[0])
    report("a twitch is not a rope", rope_from((1000.0, 1000.0), (1010.0, 1005.0)) is None)
    thin = rope_from((1000.0, 1000.0), (1400.0, 1002.0))
    report("a sideways drag with no height is still a rope, at the minimum thickness",
           thin is not None and thin[0] == (1000.0, 1001.0) and thin[2] == SEGMENT_MIN_R,
           "r=%.1f" % (thin[2] if thin else -1))
    report("and a very fat one is capped", rope_from((0.0, 0.0), (400.0, 400.0))[2] == SEGMENT_MAX_R)

    print("\n复制：接在被点中的那一头")
    seg = rope_from((1000.0, 1000.0), (1400.0, 1080.0))
    nxt = extend(seg, True)
    report("the far end grows the rope the way it was going",
           nxt[0] == (1400.0, 1040.0) and nxt[1] == (1800.0, 1040.0), str(nxt[1]))
    report("same length, same angle", abs(math.dist(nxt[0], nxt[1]) - math.dist(seg[0], seg[1])) < 1e-6)
    back = extend(seg, False)
    report("the near end grows it the other way",
           back[0] == (600.0, 1040.0) and back[1] == (1000.0, 1040.0), str(back[0]))
    report("a chain of taps is a longer rope, one segment at a time",
           extend(extend(seg, True), True)[1] == (2200.0, 1040.0))

    print("\n端头才是「再接一截」的靶子")
    segments = [seg]
    report("the end answers", segment_end_at((1405.0, 1045.0), segments) == (seg, True))
    report("the middle does not", segment_end_at((1200.0, 1040.0), segments) is None)
    report("and 26px past the end is already too far", segment_end_at((1470.0, 1040.0), segments) is None)

    print("\n绳段是实体：骨头被推出去，不是拉进去")
    bones = {"foot_L": bone()}
    bones["foot_L"]["head"] = (1200.0, 1000.0)
    bones["foot_L"]["tip"] = (1200.0, 1040.0)
    pushes = segment_push(seg, bones["foot_L"], 50.0)
    report("a bone end inside the rope is pushed", len(pushes) == 1, "%d end(s)" % len(pushes))
    report("away from the rope, not into it", pushes[0][0][1] < -0.99, str(pushes[0][0]))
    bones["foot_L"]["head"] = (1200.0, 1200.0)
    bones["foot_L"]["tip"] = (1200.0, 1240.0)
    report("standing clear of it is not a push", segment_push(seg, bones["foot_L"], 50.0) == [])

    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        for f in FAILURES:
            print("  " + f)
        return 1
    print("all tether tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
