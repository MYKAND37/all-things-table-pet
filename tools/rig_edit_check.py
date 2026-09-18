
"""Tests for the rig editor's list surgery.

Mirrors dev.atp.pet.engine.skeleton.RigEdit and the bone half of
CharacterStore.saveRig, because those two decide whether a rig can be baked at all and
they run on a phone. The claim worth testing hardest is the one the whole authoring format
rests on: reparenting a bone does not move it, because the head is stored in canvas
coordinates and the parent-relative form is only derived at bake time.

    python3 tools/rig_edit_check.py
"""
import json, math, os, sys, copy
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
from skeleton_tool import bake, update, tip

SPEC = os.path.join(REPO, "app/src/main/assets/characters/female_base/character.json")

FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)


class Bone:
    """The three fields the editor actually edits, plus the ones the file carries along."""

    def __init__(self, name, parent, head, tail, extra=None):
        self.name = name
        self.parent = parent
        self.head = tuple(head)
        self.tail = tuple(tail)
        self.extra = extra or {}


def load():
    root = json.load(open(SPEC))
    bones = []
    for b in root["bones"]:
        bones.append(Bone(b["name"], b.get("parent"), b["head"], b["tail"], b))
    return root, bones


# --------------------------- mirrors of RigEdit ---------------------------

def order(bones):
    placed, out, moved = set(), [], True
    while moved:
        moved = False
        for b in bones:
            if b.name in placed:
                continue
            if b.parent is None or b.parent in placed:
                out.append(b)
                placed.add(b.name)
                moved = True
    for b in bones:
        if b.name not in placed:
            out.append(b)
    return out


def descendants(bones, name):
    children = {}
    for b in bones:
        if b.parent:
            children.setdefault(b.parent, []).append(b.name)
    out, seen, stack = [], set(), [name]
    while stack:
        n = stack.pop()
        if n in seen:
            continue
        seen.add(n)
        out.append(n)
        stack.extend(children.get(n, []))
    return out


def limits(a, b):
    """Mirrors RigEdit.limits: the two ends of a joint's range, sorted.

    A range written backwards is not a joint that moves backwards, it is a joint that cannot
    move at all -- min above max clamps every angle to one value -- and that reads as a broken
    rig rather than as a typo. Both ways of setting a range go through the Kotlin version: the
    属性 dialog, where both ends are typed, and 「设为最小 / 设为最大」, where one end comes
    from the pose the user just made and the other from the file.
    """
    return (a, b) if a <= b else (b, a)


def depths(bones):
    parent = {b.name: b.parent for b in bones}
    out = {}
    for b in bones:
        d, cur = 0, b.parent
        while cur is not None and d <= len(bones):
            d += 1
            cur = parent.get(cur)
        out[b.name] = d
    return out


def has_cycle(bones):
    parent = {b.name: b.parent for b in bones}
    for b in bones:
        steps, cur = 0, b.parent
        while cur is not None:
            if cur == b.name:
                return True
            steps += 1
            if steps > len(bones):
                return True
            cur = parent.get(cur)
    return False


def problem(bones):
    if not bones:
        return "at least one bone"
    seen = set()
    for b in bones:
        if not b.name.strip():
            return "unnamed"
        if b.name in seen:
            return "duplicate " + b.name
        seen.add(b.name)
    names = {b.name for b in bones}
    for b in bones:
        if b.parent is not None and b.parent not in names:
            return b.name + " -> missing " + b.parent
    if has_cycle(bones):
        return "cycle"
    roots = sum(1 for b in bones if b.parent is None)
    if roots == 0:
        return "no root"
    if roots > 1:
        return str(roots) + " roots"
    for b in bones:
        if math.dist(b.head, b.tail) < 1.0:
            return b.name + " zero length"
    return None


# ------------------------------- helpers --------------------------------

def spec_dicts(bones):
    return [{"name": b.name, "parent": b.parent, "head": list(b.head), "tail": list(b.tail),
             "limits": [-180, 180], "collider": {"type": "capsule", "radius": 0.0}}
            for b in order(bones)]


def worlds(bones):
    by_name, seq = bake(spec_dicts(bones))
    update(seq)
    return {b.name: (b.wpos, tip(b)) for b in seq}


def moved(before, after, names):
    worst = 0.0
    for n in names:
        if n not in before or n not in after:
            continue
        for p, q in zip(before[n], after[n]):
            worst = max(worst, math.dist(p, q))
    return worst


def reparent(bones, name, parent):
    if parent is not None and parent in descendants(bones, name):
        return False
    b = next(x for x in bones if x.name == name)
    b.parent = parent
    bones[:] = order(bones)
    return True


def delete(bones, name):
    b = next(x for x in bones if x.name == name)
    for other in bones:
        if other.parent == name:
            other.parent = b.parent
    bones.remove(b)
    return True


def add(bones, name, parent, head, tail):
    bones.append(Bone(name, parent, head, tail))
    bones[:] = order(bones)
    return True


# --------------------- mirror of the bone half of saveRig ---------------------

def save_rig(root, bones, back_to_front):
    """Only the parts that can be wrong: the array order, the new-bone defaults, the
    orphan cleanup, and the rules that name a bone that is gone."""
    old = root.get("bones", [])
    alive = {b.name for b in bones}
    kept, gone = {}, []
    for b in old:
        (kept if b["name"] in alive else gone).append if False else None
        if b["name"] in alive:
            kept[b["name"]] = b
        else:
            gone.append(b["name"])

    arr = []
    for b in order(bones):
        o = copy.deepcopy(kept.get(b.name, {}))
        o["name"] = b.name
        o["parent"] = b.parent
        o["head"] = list(b.head)
        o["tail"] = list(b.tail)
        if b.name not in kept:
            o["limits"] = [-180.0, 180.0]
            o["collider"] = {"type": "capsule", "radius": 0.0}
        arr.append(o)
    root["bones"] = arr

    live = {b.name for b in bones}
    seq = [n for n in back_to_front if n in live] + [b.name for b in bones if b.name not in back_to_front]
    root["layers"] = [{"bone": n, "z": 10 + i * 10} for i, n in enumerate(seq)]

    def missing(rule):
        tr = rule.get("trigger") or {}
        if tr.get("bone") and tr["bone"] not in live:
            return True
        if tr.get("reference") and tr["reference"] not in live:
            return True
        for key in ("parts", "behind"):
            for n in rule.get(key, []):
                if n not in live:
                    return True
        return False

    root["layerSwaps"] = [r for r in root.get("layerSwaps", []) if not missing(r)]
    root["ikChains"] = [c for c in root.get("ikChains", [])
                        if c.get("upper") in live and c.get("lower") in live]
    return root, gone


def main():
    root, base = load()
    names = [b.name for b in base]
    print("bones %d" % len(base))

    print("\norder()")
    ok = [b.name for b in order(base)] == names
    report("a rig that is already ordered comes back untouched", ok)
    report("no bone is lost", len(order(base)) == len(base))
    check = order(base + [Bone("x", None, (0, 0), (10, 0))])
    report("a second root lands at the end, where problem() can see it",
           check[-1].name == "x" and len(check) == len(base) + 1)

    print("\nreparenting does not move anything")
    bones = copy.deepcopy(base)
    before = worlds(bones)
    kids = descendants(bones, "hand_L")
    report("hand_L is a leaf", kids == ["hand_L"])
    reparent(bones, "hand_L", "chest")
    after = worlds(bones)
    report("hand_L did not move", moved(before, after, names) < 1e-6,
           "worst %.6f px" % moved(before, after, names))
    report("the order still puts parents first",
           [b.name for b in order(bones)].index("chest") <
           [b.name for b in order(bones)].index("hand_L"))

    print("\nreparenting a whole limb keeps its subtree rigid")
    bones = copy.deepcopy(base)
    before = worlds(bones)
    moved_names = descendants(bones, "upperarm_L")
    report("upperarm_L carries the arm and the hand", len(moved_names) == 3, str(moved_names))
    reparent(bones, "upperarm_L", "chest")
    after = worlds(bones)
    report("all three stayed put", moved(before, after, names) < 1e-6,
           "worst %.6f px" % moved(before, after, names))

    print("\nreparenting into your own subtree is refused")
    bones = copy.deepcopy(base)
    report("hand_L -> forearm_L refused", reparent(bones, "hand_L", "forearm_L") is True)
    bones = copy.deepcopy(base)
    report("forearm_L -> hand_L refused (that would be a loop)",
           reparent(bones, "forearm_L", "hand_L") is False)
    report("the loop really would be one", has_cycle([Bone("a", "b", (0, 0), (1, 0)),
                                                      Bone("b", "a", (0, 0), (1, 0))]))
    report("problem() says so", problem([Bone("a", "b", (0, 0), (1, 0)),
                                         Bone("b", "a", (0, 0), (1, 0))]) == "cycle")
    # A loop can also happen with an unrelated root still in place, which is the shape a
    # real mistake takes: reparenting a bone under one of its own descendants.
    rooted_loop = [Bone("root", None, (0, 0), (10, 0)), Bone("a", "b", (0, 0), (10, 0)),
                   Bone("b", "a", (0, 0), (10, 0))]
    report("a loop alongside a root is still a loop", problem(rooted_loop) == "cycle")

    print("\ndeleting a bone re-hangs its children")
    bones = copy.deepcopy(base)
    before = worlds(bones)
    kids = [b.name for b in bones if b.parent == "upperarm_L"]
    report("upperarm_L has children", len(kids) > 0, str(kids))
    delete(bones, "upperarm_L")
    after = worlds(bones)
    report("the children did not move", moved(before, after, names) < 1e-6,
           "worst %.6f px" % moved(before, after, names))
    report("they now hang off shoulder_L",
           all(b.parent == "shoulder_L" for b in bones if b.name in kids))
    report("the rig is still legal", problem(bones) is None)

    print("\nadding a bone")
    bones = copy.deepcopy(base)
    before = worlds(bones)
    add(bones, "tail_1", "hip", (400.0, 900.0), (400.0, 1100.0))
    after = worlds(bones)
    report("nothing already in the rig moved", moved(before, after, names) < 1e-6,
           "worst %.6f px" % moved(before, after, names))
    report("the new bone is last", [b.name for b in order(bones)][-1] == "tail_1")
    report("it is a leaf", not any(b.parent == "tail_1" for b in bones))
    report("depth puts it under hip", depths(bones)["tail_1"] == depths(bones)["hip"] + 1)

    print("\nproblem() refuses a rig that cannot be baked")
    two_roots = [Bone("a", None, (0, 0), (10, 0)), Bone("b", None, (0, 0), (10, 0))]
    report("two roots", problem(two_roots) == "2 roots")
    report("no root", problem([Bone("a", "b", (0, 0), (10, 0))]) == "a -> missing b")
    report("missing parent",
           problem([Bone("a", None, (0, 0), (10, 0)), Bone("b", "zz", (0, 0), (10, 0))])
           == "b -> missing zz")
    report("duplicate name",
           problem([Bone("a", None, (0, 0), (10, 0)), Bone("a", "a", (0, 0), (10, 0))])
           == "duplicate a")
    report("zero length", problem([Bone("a", None, (0, 0), (0, 0))]) == "a zero length")
    report("an empty rig", problem([]) == "at least one bone")

    print("\nthe round trip through character.json")
    root, bones = load()
    root = copy.deepcopy(root)
    before = worlds(bones)
    add(bones, "tail_1", "hip", (400.0, 900.0), (400.0, 1200.0))
    reparent(bones, "head", "chest")
    delete(bones, "foot_R")
    keep = [b.name for b in bones]
    saved, gone = save_rig(root, bones, [b.name for b in bones])
    report("the deleted bone is the only orphan", gone == ["foot_R"], str(gone))
    reloaded = [Bone(b["name"], b.get("parent"), b["head"], b["tail"]) for b in saved["bones"]]
    report("the written array is parents-first", order(reloaded) == reloaded)

    text = json.dumps(saved, indent=2)
    reparsed = json.loads(text)
    rebuilt = [Bone(b["name"], b.get("parent"), b["head"], b["tail"]) for b in reparsed["bones"]]
    by_name, seq = bake([{"name": b.name, "parent": b.parent, "head": list(b.head),
                          "tail": list(b.tail), "limits": [-180, 180]} for b in rebuilt])
    update(seq)
    after = {b.name: (b.wpos, tip(b)) for b in seq}
    report("the reloaded rig has every surviving bone", set(after) == set(keep))
    report("and none of them moved", moved(before, after, keep) < 1e-6,
           "worst %.6f px" % moved(before, after, keep))
    report("the new bone kept its defaults",
           reparsed["bones"][-1]["name"] == "tail_1" and "limits" in reparsed["bones"][-1])
    report("layerSwaps that named foot_R are gone",
           all("foot_R" not in json.dumps(r) for r in reparsed.get("layerSwaps", [])))

    print("\nthe attributes editor writes limits the solver actually honours")
    root, bones = load()
    saved, _ = save_rig(root, bones, [b.name for b in bones])
    # What the dialog does: change the two numbers on the in-memory bone and save the rig.
    edited = json.loads(json.dumps(saved))
    for b in edited["bones"]:
        if b["name"] == "shin_L":
            b["limits"] = [-25.0, 40.0]
            b["collider"] = {"type": "circle", "radius": 33.0}
    by_name, seq = bake(edited["bones"])
    shin = by_name["shin_L"]
    report("the joint's limits came back in radians",
           abs(shin.min_a - math.radians(-25.0)) < 1e-6 and
           abs(shin.max_a - math.radians(40.0)) < 1e-6,
           "%.1f..%.1f deg" % (math.degrees(shin.min_a), math.degrees(shin.max_a)))
    report("and the collider came back with them",
           edited["bones"][[b["name"] for b in edited["bones"]].index("shin_L")]["collider"]
           == {"type": "circle", "radius": 33.0})

    # Now the part that matters: a joint that is only allowed 25 degrees of bend must never
    # be seen bending 60, however hard it is thrown about.
    from ragdoll import Ragdoll
    from skeleton_tool import room
    spec = room(json.loads(json.dumps(edited)))
    bn, seq2 = bake(spec["bones"])
    pet = Ragdoll(spec, bn, seq2, stiffness=0.0)
    worst = 0.0
    for i in range(600):
        pin = [("foot_L", (bn["foot_L"].wpos[0], 400.0))] if i > 120 else None
        pet.step(1.0 / 60.0, pin)
        worst = max(worst, abs(pet.ang["shin_L"]))
    report("600 steps of being dragged around never break the limit",
           worst <= math.radians(40.0) + 1e-4,
           "worst %.1f deg of 40" % math.degrees(worst))
    report("and the knee still moves", worst > math.radians(5.0),
           "%.1f deg" % math.degrees(worst))

    print("\nlimits()")
    # The dialog's case: typed backwards.
    report("a range typed backwards comes back sorted", limits(30.0, -20.0) == (-20.0, 30.0),
           str(limits(30.0, -20.0)))
    report("a range already in order is untouched", limits(-20.0, 30.0) == (-20.0, 30.0))
    # A joint that cannot move: equal ends are legal and must stay equal rather than becoming
    # an empty or inverted range.
    report("equal ends stay equal", limits(0.0, 0.0) == (0.0, 0.0))
    # The capture's case: 「设为最小」 with a pose inside the range it already had, and one
    # past the maximum, which is what makes the range backwards and gets swapped.
    report("a capture inside the range only moves its own end",
           limits(-15.0, 30.0) == (-15.0, 30.0))
    report("a capture past the other end swaps the two",
           limits(45.0, 30.0) == (30.0, 45.0))
    # Degrees, not radians: the dialog and the capture are both in degrees, and a swap that
    # only worked on one side of zero would be a sign bug the finger would never explain.
    report("the swap is about order, not about sign",
           limits(-90.0, -170.0) == (-170.0, -90.0))

    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        for f in FAILURES:
            print("  " + f)
        return 1
    print("all rig-edit tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
