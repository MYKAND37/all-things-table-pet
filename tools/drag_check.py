
"""What a drag actually feels like, tested rather than eyeballed.

The complaint this file exists for: grab a limp figure by the ankle and lift, and it has
to HANG -- the whole body swings until it dangles below the hand. The old solver rotated
whatever joint reached the target, and the hip is the joint that reaches everything, so it
spun the entire figure instead of letting it hang, and then fought gravity for it.

The second complaint, and the reason for the vibration section at the bottom: a pet lying
on the bench, dragged along it by the hip, shook. Not wobbled -- shook, with the root
alternating a hundred pixels every single frame.

    python3 tools/drag_check.py
"""
import json, math, os, random, sys
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
from skeleton_tool import bake, room
import ragdoll
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


def tremor(series):
    """
    How much of a motion is vibration, and how big it is.

    A smooth swing has a small second difference; a vibration reverses every frame, so its
    second difference is as big as the step itself. ratio is therefore ~0.3 for a swing and
    ~2.0 for a limit cycle, and amp is the size of the alternating part in the signal's own
    units. Both are needed: a fast smooth drag has a large step, and only the ratio says
    whether it is smooth.
    """
    if len(series) < 8:
        return 0.0, 0.0
    d1 = [series[i] - series[i - 1] for i in range(1, len(series))]
    d2 = [d1[i] - d1[i - 1] for i in range(1, len(d1))]
    s1 = math.sqrt(sum(v * v for v in d1) / len(d1))
    s2 = math.sqrt(sum(v * v for v in d2) / len(d2))
    return (s2 / s1 if s1 > 1e-6 else 0.0), s2 / 2.0


def lying(settle=240):
    """A pet lying on the bench: dragged down to the ground, let go, left to settle."""
    pet, by_name = fresh(settle=120)
    hip = by_name["hip"]
    for _ in range(90):
        pet.step(1.0 / 60.0, [("hip", (hip.wpos[0], pet.floor - 200.0), 0.5)])
    for _ in range(settle):
        pet.step(1.0 / 60.0)
    return pet, by_name


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

    print("\na finger holding a figure against the floor")
    # 拖动时会剧烈抽搐, and this is what it was. The floor treats a carried figure in one of
    # two ways depending on how far over the figure is turned; the two answers differ by
    # HUNDREDS of pixels, and both of them MOVE the figure -- so a figure sitting on that
    # threshold flips between them every single frame, and every flip throws it. Holding the
    # hip just under the floor line made the root alternate 310 px per frame; on the line,
    # 252 px. It was not the solver being stiff: it was a switch with no hysteresis, which is
    # why the cure is two lines in Ragdoll.hanging rather than a gain.
    # The finger target is recomputed from the hip every frame, and that is not an artificial
    # detail -- it is what the APP does. A finger is a screen position, and the world point
    # under it is pan + screen/zoom: with 镜头跟着 on, the camera slides to keep the pet in
    # view, so the world point under a STATIONARY finger slides with the pet. A finger that
    # keeps itself 20 px ahead of what it is holding is a shove that never ends, and the
    # floor answers it every frame. (Holding the finger still instead -- the obvious way to
    # write this test -- makes the vibration disappear, which is exactly how the WRONG cure
    # was found first: a second ground pass after the pins fixed it here and cost the
    # two-finger split test 60 px of reach.)
    for label, target_y, before in (("just under the floor line", 90.0, 311.0),
                                    ("right on the floor line", -5.0, 252.0)):
        pet, bn = fresh(settle=150)
        hip = bn["hip"]
        roots = []
        for _ in range(120):
            pet.step(1.0 / 60.0, [("hip", (hip.wpos[0] + 20.0, pet.floor + target_y), 0.5)])
            roots.append(pet.root_pos[1])
        ratio, amp = tremor(roots)
        report("the root holds still (%s)" % label, amp < 20.0,
               "amp %.2f px, ratio %.2f (it was %.0f px)" % (amp, ratio, before))

    print("\na dragged figure does not buzz")
    # 振幅不大、频率大 -- the report that came with v1.0.0. The pin's IK aimed EXACTLY at the
    # finger every frame, on a body that is also being integrated: measured, the held hand
    # jumped 55 degrees in one frame, then 39 the next, and then rode its joint limits back and
    # forth. A limb pulls toward what it is holding at a finite rate; MAX_IK_RATE is that rate,
    # and it is a RATE so that a 120 Hz phone behaves like a 60 Hz one.
    #
    #   held bone      tremor ratio   amplitude      (unbounded -> 9 rad/s)
    #   hand sideways      1.32          13.2 deg
    #                      0.99           4.6 deg
    #
    # BOTH NUMBERS WERE RE-CALIBRATED, and this is the paragraph that says why it is not a
    # number moved to make a test pass. The old pair was `ratio < 1.1 and amp < 8.0`.
    #
    # ratio is rms(d2)/rms(d1) -- DIMENSIONLESS, so it rises for two different reasons: the
    # alternating part grew, or the SMOOTH part shrank. The reference implementation used to
    # wall the figure at canvas.width = 1024, which the app does not have (the room is
    # physics.worldWidth = 3072; see the note in Ragdoll.__init__). Measured on this exact
    # drag, before and after the wall was moved out of the way:
    #
    #                       held bone             worst bone in the body
    #                ratio   amp    rms d1  rms d2     amp     rms d2
    #   wall at 1024  0.74  1.80°   4.84°   3.60°     0.21 px   0.41 px
    #   room   3072   1.27  1.26°   1.99°   2.53°     0.13 px   0.26 px
    #
    # Every absolute quantity got better -- the alternating part included, by 30% -- because
    # the figure was no longer being shoved into a wall the app does not have, and the shove
    # was most of what the hand's smooth rotation was. rms(d1) fell by 2.4x, rms(d2) by 1.4x,
    # and the quotient of the two rose. So 1.1 was never purely a buzz threshold: on a 1024
    # canvas it was partly a wall measurement, and it cannot be carried over unchanged.
    #
    # What the user can see is the second number, so that one is TIGHTENED (8.0 -> 3.0;
    # 1.26 deg measured), and the ratio bound is re-derived from this scene's own readings on
    # the room the app actually has: 1.6 against 1.27 measured. The d1/d2 pair is printed
    # beside them so the next reader can check the quotient against its own parts instead of
    # having to trust it.
    pet, bn = fresh()
    hand = bn["hand_R"]
    x0, y0 = hand.wpos
    held = []
    for i in range(180):
        t = i / 60.0
        pet.step(1.0 / 60.0, [("hand_R", (x0 + 260.0 * t, y0), 0.5)])
        if i > 30:
            held.append(math.degrees(hand.wrot))
    d1 = [held[i] - held[i - 1] for i in range(1, len(held))]
    d2 = [d1[i] - d1[i - 1] for i in range(1, len(d1))]
    rms1 = math.sqrt(sum(v * v for v in d1) / len(d1))
    rms2 = math.sqrt(sum(v * v for v in d2) / len(d2))
    ratio, amp = tremor(held)
    report("the bone being held does not buzz", ratio < 1.6 and amp < 3.0,
           "ratio %.2f, amp %.2f deg (rms d1 %.2f, d2 %.2f deg/frame; unbounded it was 1.32 and 13.2)"
           % (ratio, amp, rms1, rms2))

    print("\nthe finger's own shake does not become the pet's")

    # A real finger is not a straight line: between two frames a thumb on glass jumps a few
    # px, in any direction, and the physics is asked for a POSITION. The rate cap cannot help
    # -- measured below across 9 to off, this drag reads the same to three decimals -- because
    # the noise is in the INPUT. What can help is a low pass on the target, and the price is
    # that the pet then trails the finger, so both halves are pinned here.
    #
    # The grab is mid-bone (30 px along the hand) on purpose: at a joint (offset 0) the same
    # drag reads 0.14 px and there is nothing to fix, which is why "which part of a bone did
    # you take hold of" has been the question behind this whole file.
    def shaken(alpha, jitter, rate=9.0, speed=260.0, seconds=4.0, seed=7):
        rnd = random.Random(seed)
        ragdoll.MAX_IK_RATE = rate
        pet, bn = fresh()
        bone = bn["hand_R"]
        x0, y0 = bone.wpos
        sx, sy = x0, y0                      # seeded with the raw target: no jump on grab
        loc, lag = [], []
        for i in range(int(seconds * 60)):
            t = i / 60.0
            fx = x0 + speed * t + (rnd.uniform(-jitter, jitter) if jitter else 0.0)
            fy = y0 + (rnd.uniform(-jitter, jitter) if jitter else 0.0)
            sx, sy = ragdoll.smooth_target((sx, sy), (fx, fy), alpha)
            pet.step(1.0 / 60.0, [("hand_R", (sx, sy), 30.0)])
            if i > 40:
                loc.append(math.degrees(bone.rotation))
                got = pet._grip(bone, 30.0)
                lag.append(math.hypot(got[0] - fx, got[1] - fy))
        _, amp = tremor(loc)
        return amp, sum(lag) / len(lag)

    raw_amp, _ = shaken(1.0, 8.0)
    smooth_amp, smooth_lag = shaken(ragdoll.PIN_TARGET_ALPHA, 8.0)
    report("a jittery finger is filtered, not reproduced",
           smooth_amp < raw_amp * 0.4,
           "%.3f -> %.3f deg per frame (%.1fx)" % (raw_amp, smooth_amp, raw_amp / max(smooth_amp, 1e-6)))
    # v * (1 - a) / a / 60 at 260 px/s and a = 0.35 is 8.0 px of steady-state trail; 12 is that
    # with room for the oscillation on top. The number matters because it is the whole price.
    report("and the price is a finger's-width of trail, not a rubber band",
           smooth_lag < 12.0, "%.1f px behind the finger at 260 px/s" % smooth_lag)
    calm_raw, _ = shaken(1.0, 0.0)
    calm_smooth, _ = shaken(ragdoll.PIN_TARGET_ALPHA, 0.0)
    report("a steady finger is not made worse by it",
           calm_smooth < calm_raw * 1.1,
           "%.3f -> %.3f deg per frame" % (calm_raw, calm_smooth))
    # The knob the panel puts in front of the user first. Pinned as a NEGATIVE result: if a
    # future change makes the cap matter here, this line says so instead of leaving it to be
    # discovered by a hand again.
    fast_amp, _ = shaken(ragdoll.PIN_TARGET_ALPHA, 8.0, rate=1.0e6)
    report("the rate cap is not what is shaking it",
           abs(fast_amp - smooth_amp) < 0.01,
           "R=9 %.3f vs off %.3f deg per frame" % (smooth_amp, fast_amp))

    print("\nthe figure does not lurch while a joint is dragged")    # The lever arm. The case above holds the hand 0.5 px along its bone -- a finger that took
    # hold of the joint -- and this one drags by the HIP, which is the same thing one bone up
    # and is the one that moves the whole figure.
    #
    # What used to happen: the pin's aim is an ANGLE, so it asks a joint to turn by |e| / |r|
    # radians, where r runs from the joint to the point the finger is holding. At 0.5 px of
    # lever a 1.4 px position error is 70 degrees, and the rate limit turns the whole 8.59
    # degrees it allows, every frame, in whichever direction that sub-pixel error happens to
    # point this frame. Turning the joint moves the grabbed point by |r| per radian, so all 8
    # of those degrees move it by 0.075 px: the constraint is not being served at all, and what
    # the figure does instead is rotate. Measured on this drag: the root turned +8.13, -8.13,
    # +8.13 degrees per frame -- MAX_IK_RATE * dt is 8.594, so it was spending its whole
    # allowance on a joint that cannot move anything -- and the worst bone in the body
    # alternated by 15.5 px per frame (81.9 px with a jittered step). See Ragdoll.LEVER_MIN.
    #
    # Asserted on the DISPLACEMENT rather than on the held bone's angle, because that is what
    # the user sees, and it is the quantity that separates this from a fast drag: the figure
    # moves 4.3 px per frame while it is being pulled, and none of that is a lurch.
    pet, bn = fresh()
    hip = bn["hip"]
    x0, y0 = hip.wpos
    lurch = []
    before = dict((b.name, b.wpos) for b in pet.order)
    for i in range(180):
        t = i / 60.0
        pet.step(1.0 / 60.0, [("hip", (x0 + 260.0 * t, y0), 0.5)])
        if i > 30:
            lurch.append(max(math.hypot(b.wpos[0] - before[b.name][0],
                                        b.wpos[1] - before[b.name][1]) for b in pet.order))
        before = dict((b.name, b.wpos) for b in pet.order)
    ratio, amp = tremor(lurch)
    report("no bone in the body lurches", amp < 2.0,
           "amp %.2f px, ratio %.2f (it was 15.5 px and 1.55)" % (amp, ratio))

    print("\na pet lying on the bench, dragged along it")
    # The same switch, in the pose it happens most: a pet lying on the bench is turned over
    # just about exactly UPSIDE_DOWN, so dragging it by the hip is dragging it along the
    # threshold. This also catches the WRONG cure: giving the floor the last word of the frame
    # (a second ground pass after the pins) pushed this case from 0.4 px to 154 px.
    pet, bn = lying()
    pet, bn = lying()
    hip = bn["hip"]
    x0, y0 = hip.wpos
    roots, feet = [], []
    n = int(2.0 * 60)
    for i in range(n):
        t = i / (n - 1)
        pet.step(1.0 / 60.0, [("hip", (x0 + 600.0 * t, y0), 0.5)])
        roots.append(pet.root_pos[1])
        feet.append(math.degrees(bn["foot_R"].wrot))
    half = n // 2
    ratio, amp = tremor(roots[half:])
    foot_ratio, foot_amp = tremor(feet[half:])
    report("the root does not vibrate while it is dragged", amp < 5.0,
           "amp %.2f px, ratio %.2f (it was 154 px and 1.97)" % (amp, ratio))
    # Printed and not asserted: the foot still flicks, the cause is known and written down in
    # Ragdoll._solve_pin -- the pin's IK writes an angle WITHOUT absorbing it into ang_prev, so
    # the correction is read back as velocity next frame. _turn_out already does this
    # correctly, and the note there is the same note. Fixing the IK properly means re-tuning
    # the carry gains afterwards, because that injected velocity is currently part of what
    # turns a figure over when it is lifted by one limb; doing it half way broke four carry
    # tests, which is how that was found out.
    print("   ·   foot flicker (known, not asserted): amp %.1f deg, ratio %.2f"
          % (foot_amp, foot_ratio))

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
