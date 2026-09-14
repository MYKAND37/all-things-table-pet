
"""What a drag actually feels like, tested rather than eyeballed.

The complaint this file exists for: grab a limp figure by the ankle and lift, and it has
to HANG -- the whole body swings until it dangles below the hand. The old solver rotated
whatever joint reached the target, and the hip is the joint that reaches everything, so it
spun the entire figure instead of letting it hang, and then fought gravity for it.

    python3 tools/drag_check.py
"""
import json, math, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
from skeleton_tool import bake, room
from ragdoll import Ragdoll

SPEC = os.path.join(REPO, "app/src/main/assets/characters/female_base/character.json")
FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)


def fresh(settle=180):
    spec = room(json.load(open(SPEC)))
    by_name, order = bake(spec["bones"])
    pet = Ragdoll(spec, by_name, order, stiffness=0.0)
    for _ in range(settle):
        pet.step(1.0 / 60.0)
    return pet, by_name


#: Where a hand holds something it is dangling. High enough up the 2048-tall arena that
#: a figure hanging under it has room to actually hang, which is most of the way to the top.
HANG_Y = 380.0


def grip(bone, fraction):
    """The point a finger lands on when it takes hold part way along a bone."""
    off = bone.length * fraction
    a = bone.wrot
    return (bone.wpos[0] + math.cos(a) * off, bone.wpos[1] + math.sin(a) * off), off


def hold(pet, by_name, name, target, seconds=4.0):
    """Hold one point at the target and let the body do whatever physics says."""
    for _ in range(int(seconds * 60)):
        pet.step(1.0 / 60.0, [(name, target)])


def tether(pet, by_name, name, anchor, length, seconds=1.0):
    """
    A rope from a fixed point to one bone: a pin whose target only exists when it is taut.

    This mirrors what the test bench does -- the ragdoll knows nothing about ropes, it
    knows about pins, and a rope is a pin with the target worked out each frame from how
    far away the body has got. That is the whole trick, and it means the rope inherits
    everything the pin already does right: joint limits, the root slide, gravity.
    """
    worst = 0.0
    settle = int(1.5 * 60)
    for i in range(int(seconds * 60)):
        bone = by_name[name]
        dx = bone.wpos[0] - anchor[0]
        dy = bone.wpos[1] - anchor[1]
        d = math.hypot(dx, dy)
        # Measured after the first moment: an anchor driven in too far away starts
        # over-taut by construction, and what matters is that the rope then holds.
        if i >= settle:
            worst = max(worst, d)
        pins = []
        if d > length:
            t = length / d
            pins.append((name, (anchor[0] + dx * t, anchor[1] + dy * t), 0.0))
        pet.step(1.0 / 60.0, pins)
    return worst


def main():
    print("standing still is still standing")
    pet, bn = fresh()
    hip, head = bn["hip"], bn["head"]
    report("the head starts above the hip", head.wpos[1] < hip.wpos[1],
           "head %.0f hip %.0f" % (head.wpos[1], hip.wpos[1]))

    print("\nlifting a limp figure by the ankle makes it hang")
    pet, bn = fresh()
    hip, head, foot = bn["hip"], bn["head"], bn["foot_L"]
    # Lifted most of the way up the arena, not by a fixed amount. A figure 1349px tall
    # hanging from an ankle needs about that much room BELOW the grip, and the floor is at
    # 2048: grab the ankle 700px up and the body has nowhere to go, so it heaps on the
    # floor and the test measures the floor rather than the hanging.
    target = (foot.wpos[0], HANG_Y)
    hold(pet, bn, "foot_L", target)
    risen = foot.wpos[1] - target[1]
    report("the ankle went where the finger is", abs(risen) < 12.0, "%.1f px off" % risen)
    # Hanging means the body is BELOW the hand. Screen y grows downwards.
    report("the hip ended up below the ankle", hip.wpos[1] > foot.wpos[1] + 20.0,
           "ankle %.0f hip %.0f" % (foot.wpos[1], hip.wpos[1]))
    report("and the head below the hip", head.wpos[1] > hip.wpos[1] + 20.0,
           "hip %.0f head %.0f" % (hip.wpos[1], head.wpos[1]))
    report("the figure is upside down, not folded up",
           abs(head.wpos[1] - foot.wpos[1]) > 500.0,
           "ankle-to-head %.0f px" % abs(head.wpos[1] - foot.wpos[1]))

    print("\nthe other leg hangs too, rather than staying folded")
    other = bn["foot_R"]
    report("the free foot is below the hip as well", other.wpos[1] > hip.wpos[1] - 40.0,
           "hip %.0f other foot %.0f" % (hip.wpos[1], other.wpos[1]))

    print("\na finger takes hold of a POINT, not a joint")
    # The bug this exists for: a pin used to hold the bone's head, and the head of the
    # thigh IS the hip. Grabbing a leg anywhere along it therefore pinned the top of the
    # body, lifted the figure upright by the waist, and no amount of gravity could turn it
    # over -- there was nothing above the grip left to hang.
    for name, fraction in (("thigh_L", 0.0), ("thigh_L", 0.5), ("thigh_L", 1.0),
                           ("shin_L", 0.5), ("foot_L", 0.0)):
        pet, bn = fresh()
        bone = bn[name]
        start, off = grip(bone, fraction)
        target = (start[0], HANG_Y)
        for _ in range(int(8.0 * 60)):
            pet.step(1.0 / 60.0, [(name, target, off)])
        got, _ = grip(bone, fraction)
        err = math.hypot(got[0] - target[0], got[1] - target[1])
        hip, head = bn["hip"], bn["head"]
        hangs = head.wpos[1] > hip.wpos[1] + 100.0
        report("%s at %.0f%% hangs" % (name, fraction * 100), hangs and err < 20.0,
               "weight %.0f vs hand %.0f, reached to %.0f px" % (head.wpos[1], got[1], err))

    print("\na rope holds the body on a leash")
    pet, bn = fresh()
    hip = bn["hip"]
    anchor = (hip.wpos[0], hip.wpos[1] - 900.0)      # a stake driven into the sky
    rope = 420.0
    worst = tether(pet, bn, "hip", anchor, rope, seconds=8.0)
    report("once settled the hip never gets further than the rope",
           worst <= rope + 25.0, "worst %.0f of %.0f px" % (worst, rope))
    report("and it is actually hanging on it", worst > rope - 25.0,
           "worst %.0f of %.0f px" % (worst, rope))
    d = math.hypot(hip.wpos[0] - anchor[0], hip.wpos[1] - anchor[1])
    report("it settles at the end of the rope", abs(d - rope) < 30.0, "%.0f px" % d)
    report("the rest of it hangs below", bn["head"].wpos[1] > hip.wpos[1],
           "hip %.0f head %.0f" % (hip.wpos[1], bn["head"].wpos[1]))

    print("\nslack rope does not move the body")
    pet, bn = fresh()
    before = bn["hip"].wpos
    for _ in range(int(4.0 * 60)):
        bone = bn["hip"]
        dx = bone.wpos[0] - before[0]
        dy = bone.wpos[1] - before[1]
        d = math.hypot(dx, dy)
        pins = []
        if d > 300.0:                      # a long rope, and the body barely moves
            t = 300.0 / d
            pins.append(("hip", (before[0] + dx * t, before[1] + dy * t), 0.0))
        pet.step(1.0 / 60.0, pins)
    moved = math.hypot(bn["hip"].wpos[0] - before[0], bn["hip"].wpos[1] - before[1])
    report("the body is still where it was", moved < 300.0, "%.0f px" % moved)

    print("\nlifting by a hand still works")
    pet, bn = fresh()
    hand, hip, head = bn["hand_L"], bn["hip"], bn["head"]
    target = (hand.wpos[0] + 500.0, hand.wpos[1] - 500.0)
    hold(pet, bn, "hand_L", target, seconds=6.0)
    off = math.hypot(hand.wpos[0] - target[0], hand.wpos[1] - target[1])
    report("the hand reaches the finger", off < 40.0, "%.1f px off" % off)
    # The body should follow the hand, not be left behind: a limp arm cannot hold a body
    # out sideways, so the hip ends up roughly under the hand.
    report("the body came with it", abs(hip.wpos[0] - target[0]) < 420.0,
           "hip x %.0f target x %.0f" % (hip.wpos[0], target[0]))

    print("\ntwo fingers can pull a figure apart")
    pet, bn = fresh()
    left, right = bn["foot_L"], bn["foot_R"]
    x0 = (left.wpos[0] + right.wpos[0]) / 2.0
    y0 = min(left.wpos[1], right.wpos[1])
    tl = (x0 - 420.0, y0 - 120.0)
    tr = (x0 + 420.0, y0 - 120.0)
    for _ in range(int(6.0 * 60)):
        pet.step(1.0 / 60.0, [("foot_L", tl), ("foot_R", tr)])
    dl = math.hypot(left.wpos[0] - tl[0], left.wpos[1] - tl[1])
    dr = math.hypot(right.wpos[0] - tr[0], right.wpos[1] - tr[1])
    report("both feet reached their fingers", dl < 60.0 and dr < 60.0,
           "left %.0f right %.0f px off" % (dl, dr))
    spread = abs(left.wpos[0] - right.wpos[0])
    report("the legs are actually apart", spread > 500.0, "%.0f px" % spread)
    report("the figure did not simply fly sideways",
           abs((left.wpos[0] + right.wpos[0]) / 2.0 - x0) < 500.0)

    print("\none finger pulled one way, another the other way")
    pet, bn = fresh()
    lh, rh = bn["hand_L"], bn["hand_R"]
    cx = (lh.wpos[0] + rh.wpos[0]) / 2.0
    y = min(lh.wpos[1], rh.wpos[1]) - 200.0
    for _ in range(int(4.0 * 60)):
        pet.step(1.0 / 60.0, [("hand_L", (cx - 430.0, y)), ("hand_R", (cx + 430.0, y))])
    report("both hands reached their fingers",
           math.hypot(lh.wpos[0] - (cx - 430.0), lh.wpos[1] - y) < 90.0 and
           math.hypot(rh.wpos[0] - (cx + 430.0), rh.wpos[1] - y) < 90.0,
           "L %.0f R %.0f" % (math.hypot(lh.wpos[0] - (cx - 430.0), lh.wpos[1] - y),
                              math.hypot(rh.wpos[0] - (cx + 430.0), rh.wpos[1] - y)))

    print("\nletting go throws with the finger's speed")
    pet, bn = fresh()
    hand = bn["hand_L"]
    x = hand.wpos[0]
    y = hand.wpos[1]
    for i in range(40):
        pet.step(1.0 / 60.0, [("hand_L", (x + i * 14.0, y - i * 4.0))])
    speed = math.hypot(pet.pin_vel[0], pet.pin_vel[1])
    report("the recorded finger speed is the real one", 700.0 < speed < 1100.0,
           "%.0f px/s" % speed)
    pet.release()
    report("and letting go hands it to the body",
           math.hypot(pet.root_vel[0], pet.root_vel[1]) > 500.0,
           "%.0f px/s" % math.hypot(pet.root_vel[0], pet.root_vel[1]))

    print("\nnothing exploded")
    pet, bn = fresh()
    for i in range(600):
        pet.step(1.0 / 60.0, [("foot_L", (600.0, 400.0)), ("hand_R", (900.0, 500.0))])
        for b in bn.values():
            if not (math.isfinite(b.wpos[0]) and math.isfinite(b.wpos[1])):
                report("positions stay finite", False, "at step %d" % i)
                break
        else:
            continue
        break
    else:
        report("positions stay finite", True)

    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        for f in FAILURES:
            print("  " + f)
        return 1
    print("all drag tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
