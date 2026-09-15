
"""What carrying a figure by one limb has to look like.

The complaint this file exists for: grab the pet by the ankle and lift, and it has to
TURN OVER and hang -- whole body, not just the head. Everything here is a GRADUAL drag,
because a finger moves at a speed a hand can move at, and teleporting the target is a
different (and much more violent) experiment.

    python3 tools/carry_check.py
"""
import json, math, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import ragdoll as R
from skeleton_tool import bake, room
from ragdoll import Ragdoll

SPEC = os.path.join(REPO, "app/src/main/assets/characters/female_base/character.json")
FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)
    return ok


def fresh(stiffness=0.0, settle=3.0, fps=60):
    spec = room(json.load(open(SPEC)))
    by_name, order = bake(spec["bones"])
    pet = Ragdoll(spec, by_name, order, stiffness=stiffness)
    for _ in range(int(settle * fps)):
        pet.step(1.0 / fps)
    return pet, by_name


def grab(bone, fraction):
    off = bone.length * fraction
    a = bone.wrot
    return (bone.wpos[0] + math.cos(a) * off, bone.wpos[1] + math.sin(a) * off), off


def drag(pet, bn, name, offset, start, end, seconds=2.0, hold=5.0, fps=60):
    """Move a finger from start to end over that many seconds, then hold it there."""
    steps = max(1, int(seconds * fps))
    for i in range(steps):
        t = (i + 1) / float(steps)
        p = (start[0] + (end[0] - start[0]) * t, start[1] + (end[1] - start[1]) * t)
        pet.step(1.0 / fps, [(name, p, offset)])
    for _ in range(int(hold * fps)):
        pet.step(1.0 / fps, [(name, end, offset)])


def lift(name, fraction, rise, fps=60, seconds=1.0, hold=5.0):
    pet, bn = fresh(fps=fps)
    bone = bn[name]
    start, off = grab(bone, fraction)
    end = (start[0], start[1] - rise)
    drag(pet, bn, name, off, start, end, seconds=seconds, hold=hold, fps=fps)
    return pet, bn, end


def scenario_ankle(part, fps=60, seconds=1.0):
    """
    Drag one ankle up by part of the ROOM, and report which way up the figure ended.

    A fraction of the room, not a pixel count, because that is what the finger is really
    spending: the view fits the room to the screen, so a finger has about one room of travel
    in it and no more. "Lift the ankle a room's worth" is not something a hand can do.
    """
    pet, bn = fresh(fps=fps)
    rise = part * pet.floor
    return lift("foot_L", 0.5, rise, fps=fps, seconds=seconds)[:2] + (rise,)


def scenario_hand(fps=60, speed=900.0):
    pet, bn = fresh(fps=fps)
    hand, hip = bn["hand_L"], bn["hip"]
    start, off = grab(hand, 0.5)
    end = (start[0] + 420.0, start[1] - 420.0)
    drag(pet, bn, "hand_L", off, start, end, seconds=1.2, hold=5.0, fps=fps)
    return dict(err=math.hypot(hand.wpos[0] - end[0], hand.wpos[1] - end[1]),
                hipx=abs(hip.wpos[0] - end[0]), hand=hand.wpos[1], hip=hip.wpos[1])


def scenario_splay(fps=60, speed=900.0):
    pet, bn = fresh(fps=fps)
    left, right = bn["foot_L"], bn["foot_R"]
    ls, lo = grab(left, 0.5)
    rs, ro = grab(right, 0.5)
    le = (ls[0] - 380.0, ls[1] - 140.0)
    re = (rs[0] + 380.0, rs[1] - 140.0)
    steps = 60
    for i in range(steps):
        t = (i + 1) / float(steps)
        lp = (ls[0] + (le[0] - ls[0]) * t, ls[1] + (le[1] - ls[1]) * t)
        rp = (rs[0] + (re[0] - rs[0]) * t, rs[1] + (re[1] - rs[1]) * t)
        pet.step(1.0 / fps, [("foot_L", lp, lo), ("foot_R", rp, ro)])
    for _ in range(int(5.0 * fps)):
        pet.step(1.0 / fps, [("foot_L", le, lo), ("foot_R", re, ro)])
    return dict(spread=abs(left.wpos[0] - right.wpos[0]),
                dl=math.hypot(left.wpos[0] - le[0], left.wpos[1] - le[1]),
                dr=math.hypot(right.wpos[0] - re[0], right.wpos[1] - re[1]))


def scenario_sideways(fps=60):
    """Dragging the pet across the floor by the hand should not plough it into the ground."""
    pet, bn = fresh(fps=fps)
    hand, foot = bn["hand_L"], bn["foot_L"]
    start, off = grab(hand, 0.5)
    end = (start[0] + 500.0, start[1])
    drag(pet, bn, "hand_L", off, start, end, seconds=1.2, hold=3.0, fps=fps)
    # The deepest point of the whole figure, not of one foot: a limp pet dragged across the
    # floor by the hand flops over, and "the foot is in the air" is then the wrong question.
    return dict(sink=max(pet.collider_low(b) for b in pet.order) - pet.floor,
                hip=bn["hip"].wpos[1])


def scenario_head_down(fps=60):
    """Pushing the head to the floor must not bury the figure."""
    pet, bn = fresh(fps=fps)
    head = bn["head"]
    start, off = grab(head, 0.5)
    end = (start[0], pet.floor - 40.0)
    drag(pet, bn, "head", off, start, end, seconds=1.2, hold=3.0, fps=fps)
    low = max(pet.collider_low(b) for b in pet.order)
    return dict(sink=low - pet.floor)


def turned_over(bn):
    """(head below the ankle, hip below the ankle) -- is the figure hanging off the grip?"""
    ankle, hip, head = bn["foot_L"], bn["hip"], bn["head"]
    return (head.wpos[1] - ankle.wpos[1], hip.wpos[1] - ankle.wpos[1])


#: A lift is measured in fractions of the room, because that is the finger's budget: the
#: view fits the room to the screen, so a finger has about one room of travel in it. A
#: quarter of a room is a comfortable drag on a phone, and it has to be enough.
LIFTS = (
    (0.20, 150.0, "turned past horizontal"),
    (0.30, 300.0, "hanging"),
    (0.40, 600.0, "upside down, head well below the feet"),
    (0.60, 600.0, "upside down, head well below the feet"),
)


def main():
    print("lifting an ankle turns the whole figure over")
    for part, want, what in LIFTS:
        pet, bn, rise = scenario_ankle(part)
        below, hipbelow = turned_over(bn)
        ankle, hip, head = bn["foot_L"], bn["hip"], bn["head"]
        report("a %2.0f%% lift (%.0fpx): %s" % (part * 100, rise, what), below > want,
               "head is %.0f below the ankle (wanted %0.f)" % (below, want)
               + "   ankle %.0f hip %.0f head %.0f" % (ankle.wpos[1], hip.wpos[1], head.wpos[1]))
        # Generous: a body flipped only just past horizontal still has its hip up beside the
        # hand, and that is what "the pet turned over" looks like at a small lift.
        report("a %2.0f%% lift: and the hip is not left up at the hand" % (part * 100),
               hipbelow > -160.0, "hip is %.0f from the ankle" % hipbelow)

    print("\nit does not matter WHICH part of the leg the finger took hold of")
    for name, fraction in (("foot_L", 0.0), ("foot_L", 1.0), ("shin_L", 0.5),
                           ("thigh_L", 0.5), ("thigh_L", 1.0)):
        pet, bn = fresh()
        bone = bn[name]
        start, off = grab(bone, fraction)
        rise = 0.35 * pet.floor
        drag(pet, bn, name, off, start, (start[0], start[1] - rise), seconds=1.0, hold=5.0)
        ankle, head = bn["foot_L"], bn["head"]
        below = head.wpos[1] - ankle.wpos[1]
        report("%s at %3.0f%% of the bone turns it over" % (name, fraction * 100), below > 400.0,
               "head is %.0f below the ankle" % below)

    print("\nthe same at a few frame rates, and dragging slowly or fast")
    for fps in (30, 60, 90):
        for seconds in (0.5, 1.0, 2.0):
            pet, bn, rise = scenario_ankle(0.35, fps=fps, seconds=seconds)
            below, hipbelow = turned_over(bn)
            report("fps %2d, %3.1fs drag: hangs" % (fps, seconds), below > 300.0,
                   "head is %.0f below the ankle, hip %.0f" % (below, hipbelow))

    print("\nlifting a hand still brings the body with it")
    h = scenario_hand()
    # Loose on purpose: a body hanging deeper than the floor cushion allows gets lifted a
    # little by the floor, and the hand goes up with it. That is the cushion working.
    report("the hand reaches the finger", h["err"] < 90.0, "%.0f px off" % h["err"])
    report("the body came with it", h["hipx"] < 420.0, "hip is %.0f px from the finger" % h["hipx"])

    print("\ntwo fingers can still pull the legs apart")
    s = scenario_splay()
    report("both feet reached their fingers", s["dl"] < 80.0 and s["dr"] < 80.0,
           "left %.0f right %.0f px off" % (s["dl"], s["dr"]))
    report("the legs are actually apart", s["spread"] > 500.0, "%.0f px" % s["spread"])

    print("\nthe floor is still a floor")
    w = scenario_sideways()
    # Loose on purpose: the floor resolves a LIMP figure by turning the deepest bone out of it
    # rather than by lifting the whole body, so a dragged limb crosses the line a little before
    # it stops. A hand's width of overlap is a body resting on the ground; a body's height is
    # the bug this test exists for.
    report("dragging it across the floor does not bury it", w["sink"] < 120.0,
           "lowest part is %.0f px below the line" % w["sink"])
    d = scenario_head_down()
    report("pushing the head down does not bury the figure",
           d["sink"] < R.CARRY_SINK + 20.0, "%.0f px below the line" % d["sink"])

    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        for f in FAILURES:
            print("  " + f)
        return 1
    print("all carry tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
