#!/usr/bin/env python3
"""
Skeleton toolkit for All Things Table Pet.

Two jobs, from one source of truth (the character JSON):

  1. --verify  bakes the rest pose, then proves the two-bone IK actually puts the
               end effector where the finger is, in reach and out of it.
  2. --render  draws the drawing template a user traces over in Huashijie.

The baking and IK maths here are the reference the Kotlin engine mirrors; if the two
ever disagree, this file is the one to trust while debugging.
"""
import argparse, json, math, sys

# ------------------------------- model -------------------------------

class Bone:
    __slots__ = ("name", "parent_name", "head", "tail", "length",
                 "rest_pos", "rest_rot", "world_rest_angle", "min_a", "max_a",
                 "spring", "physics", "rotation",
                 "wpos", "wrot", "parent")

    def __init__(self, spec, parent):
        self.name = spec["name"]
        self.parent_name = spec.get("parent")
        self.parent = parent
        self.head = tuple(map(float, spec["head"]))
        self.tail = tuple(map(float, spec["tail"]))
        lim = spec.get("limits", [-180, 180])
        self.min_a = math.radians(lim[0])
        self.max_a = math.radians(lim[1])
        self.spring = bool(spec.get("spring", False))
        self.physics = spec.get("physics", {})
        self.rotation = 0.0
        dx, dy = self.tail[0] - self.head[0], self.tail[1] - self.head[1]
        self.length = math.hypot(dx, dy)
        self.world_rest_angle = math.atan2(dy, dx)

    def local_rot(self):
        return self.rest_rot + self.rotation


def norm_angle(a):
    while a > math.pi:
        a -= 2 * math.pi
    while a <= -math.pi:
        a += 2 * math.pi
    return a


def bake(bones_spec):
    """Turn authored head/tail pairs into parent-relative rest transforms."""
    by_name, order = {}, []
    for s in bones_spec:
        parent = by_name.get(s.get("parent"))
        if s.get("parent") and parent is None:
            raise SystemExit("bone '%s' names a parent not defined earlier: %s"
                             % (s["name"], s["parent"]))
        b = Bone(s, parent)
        by_name[b.name] = b
        order.append(b)
    for b in order:
        if b.parent is None:
            b.rest_pos = b.head
            b.rest_rot = b.world_rest_angle
        else:
            dx, dy = b.head[0] - b.parent.head[0], b.head[1] - b.parent.head[1]
            a = -b.parent.world_rest_angle
            b.rest_pos = (dx * math.cos(a) - dy * math.sin(a),
                          dx * math.sin(a) + dy * math.cos(a))
            b.rest_rot = norm_angle(b.world_rest_angle - b.parent.world_rest_angle)
    return by_name, order


def update(order, root_offset=(0.0, 0.0)):
    """Forward kinematics: world = world(parent) composed with local(bone).

    root_offset displaces the whole figure. The ragdoll needs it because its root joint
    is a free particle rather than a fixed origin.
    """
    for b in order:
        r = b.local_rot()
        if b.parent is None:
            b.wpos = (b.rest_pos[0] + root_offset[0], b.rest_pos[1] + root_offset[1])
            b.wrot = r
        else:
            px, py = b.parent.wpos
            pr = b.parent.wrot
            c, s = math.cos(pr), math.sin(pr)
            b.wpos = (px + b.rest_pos[0] * c - b.rest_pos[1] * s,
                      py + b.rest_pos[0] * s + b.rest_pos[1] * c)
            b.wrot = pr + r


def tip(b):
    return (b.wpos[0] + b.length * math.cos(b.wrot),
            b.wpos[1] + b.length * math.sin(b.wrot))


def clamp_rot(b, v):
    b.rotation = max(b.min_a, min(b.max_a, v))


def _at_limit(b, eps=1e-4):
    return abs(b.rotation - b.min_a) < eps or abs(b.rotation - b.max_a) < eps


# ------------------------------- two-bone IK -------------------------------

def solve_two_bone(by_name, upper_name, lower_name, target, bend, order):
    """
    Pose a two-segment chain so its tip lands on the target point.

    Closed form, no iteration: the law of cosines gives the elbow angle, then the
    forearm is simply aimed from the ACTUAL elbow at the target. That second step is
    what makes joint limits behave -- when the shoulder clamps and the chain can no
    longer reach, the forearm still points the right way instead of snapping.
    """
    upper, lower = by_name[upper_name], by_name[lower_name]
    lu, ll = upper.length, lower.length

    tx, ty = target[0] - upper.wpos[0], target[1] - upper.wpos[1]
    d = math.hypot(tx, ty)
    if d < 1e-6:
        d = 1e-6
    d_c = max(abs(lu - ll) * 1.0001, min((lu + ll) * 0.99995, d))

    base = math.atan2(ty, tx)
    cos_a = (lu * lu + d_c * d_c - ll * ll) / (2 * lu * d_c)
    cos_a = max(-1.0, min(1.0, cos_a))
    upper_world = base + bend * math.acos(cos_a)

    # world = parent + rest + rotation, so solve for rotation and normalise the WHOLE
    # sum. Normalising (world - parent) and then subtracting rest only stays in range
    # when rest is small; a bone authored pointing backwards (rest = 180 deg, e.g. the
    # thighs) lands outside and gets wrongly clamped.
    parent_rot = upper.parent.wrot if upper.parent else 0.0
    clamp_rot(upper, norm_angle(upper_world - parent_rot - upper.rest_rot))
    update(order)

    ex, ey = tip(upper)
    lower_world = math.atan2(target[1] - ey, target[0] - ex)
    clamp_rot(lower, norm_angle(lower_world - upper.wrot - lower.rest_rot))
    update(order)


def _cross(ax, ay, bx, by):
    return ax * by - ay * bx


def elbow_side(upper, target):
    """Which side of the root-to-target line the elbow currently sits on: +1, -1 or 0."""
    root = upper.wpos
    elbow = tip(upper)
    c = _cross(elbow[0] - root[0], elbow[1] - root[1],
               target[0] - root[0], target[1] - root[1])
    if abs(c) < 1e-6:
        return 0
    return 1 if c > 0 else -1


def solve_drag(by_name, up, lo, target, default_bend, order):
    """
    What a drag actually does.

    Two-bone IK has TWO solutions that put the tip on the same point -- elbow one way
    or the other. Picking by a fixed sign is what makes a dragged arm suddenly flip
    its elbow through the body. The elbow's side is fully determined by the bend
    sign, so read the side the elbow is on right now and keep it. The authored bend
    is only the tie-break for the degenerate case where the arm is dead straight.

    The sign is inverted on purpose. Solving with bend = +1 places the elbow at
    base + alpha, and the cross product of (elbow - root) against (target - root) is
    then NEGATIVE -- so preserving a side of s means solving with -s. Getting this
    backwards still lands the tip on the target, because both mirrored solutions do;
    it just flips the elbow through the body, and the shoulder must then swing past
    its limit to get there. Only a drag test starting from a live pose catches it.
    """
    upper = by_name[up]
    side = elbow_side(upper, target)
    bend = default_bend if side == 0 else -side
    solve_two_bone(by_name, up, lo, target, bend, order)
    return bend


# ------------------------------- verification -------------------------------

def verify(spec, by_name, order):
    update(order)
    print("=== rest pose ===")
    for b in order:
        if b.parent is None:
            assert abs(b.wpos[0] - b.head[0]) < 1e-3, b.name
            assert abs(b.wpos[1] - b.head[1]) < 1e-3, b.name
        t = tip(b)
        err = math.hypot(t[0] - b.tail[0], t[1] - b.tail[1])
        if err > 1e-3:
            print("  FAIL %s: tail baked to %s, authored %s" % (b.name, t, b.tail))
            return False
    print("  all %d bones bake back to their authored head/tail" % len(order))

    # Reachable targets have to be sampled from poses the joint limits actually allow.
    # Aiming at points on a circle around the joint mostly asks a leg to point upward,
    # which the limits forbid -- and a clamped chain failing to reach is correct
    # behaviour, not a solver bug.
    import random
    rng = random.Random(20260913)

    print("")
    print("=== two-bone IK round trip ===")
    ok = True
    for chain in spec["ikChains"]:
        up, lo, bend = chain["upper"], chain["lower"], chain.get("bend", 1)
        upper, lower = by_name[up], by_name[lo]
        reach = upper.length + lower.length
        worst, worst_at, tested = 0.0, None, 0

        skipped = 0
        for _ in range(500):
            # A target worth testing has to come from a pose the limits permit.
            update(order)
            upper.rotation = rng.uniform(upper.min_a, upper.max_a)
            lower.rotation = rng.uniform(lower.min_a, lower.max_a)
            update(order)
            target = tip(lower)

            upper.rotation = 0.0
            lower.rotation = 0.0
            update(order)
            solve_drag(by_name, up, lo, target, bend, order)

            # If a joint ran into its limit the chain legitimately cannot reach;
            # distance is then bounded by geometry, not by solver accuracy.
            if _at_limit(upper) or _at_limit(lower):
                skipped += 1
                continue

            got = tip(lower)
            err = math.hypot(got[0] - target[0], got[1] - target[1])
            tested += 1
            if err > worst:
                worst, worst_at = err, (upper.rotation, lower.rotation)

        tol = max(0.5, reach * 0.002)
        if worst > tol:
            ok = False
            print("  FAIL %s -> %s  worst=%.3fpx (tol %.3f) at %s"
                  % (up, lo, worst, tol, worst_at))
        else:
            print("  %-28s %d solved (%d limit-bound)  reach=%6.1fpx  worst err=%.4fpx"
                  % (up + " -> " + lo, tested, skipped, reach, worst))

        # The real drag: the figure is already in some pose and the finger moves the
        # end effector from where it is. This must be exact every time.
        live_worst = 0.0
        for _ in range(300):
            update(order)
            upper.rotation = rng.uniform(upper.min_a, upper.max_a)
            lower.rotation = rng.uniform(lower.min_a, lower.max_a)
            update(order)
            target = tip(lower)
            solve_drag(by_name, up, lo, target, bend, order)
            got = tip(lower)
            live_worst = max(live_worst, math.hypot(got[0] - target[0], got[1] - target[1]))
        if live_worst > 0.1:
            ok = False
            print("  FAIL %s -> %s live-pose drag: worst=%.4fpx" % (up, lo, live_worst))
        else:
            print("  %-28s live-pose drag  worst err=%.5fpx" % (up + " -> " + lo, live_worst))

        # Knees must mirror. Dragged straight down from rest, the left knee has to
        # travel left and the right knee right. This is what breaks when the two
        # shins carry IDENTICAL limits instead of mirrored ones: the maths wants a
        # positive rotation on one side, the limit clamps it to zero, and the knee
        # can only ever bend one way. Cheap to write, and it is a real bug that
        # survived every other test here.
        update(order)
        upper.rotation = 0.0
        lower.rotation = 0.0
        update(order)
        root = upper.wpos
        target = (root[0], root[1] + reach * 0.7)
        solve_drag(by_name, up, lo, target, bend, order)
        knee = tip(upper)
        dx = knee[0] - root[0]
        outward = -1.0 if up.endswith("_L") else 1.0
        if dx * outward <= 1.0:
            ok = False
            print("  FAIL %s: knee bends the wrong way from rest (dx=%.1f)" % (up, dx))
        else:
            print("  %-28s knee bends outward from rest (dx=%+.1f)"
                  % (up + " -> " + lo, dx))

        # Out of reach: the chain must stay extended and point at the target rather
        # than snapping to a pose that reaches nothing.
        update(order)
        far = 1.8 * reach
        ang = upper.wrot                 # straight along the chain's own rest direction
        target = (upper.wpos[0] + far * math.cos(ang),
                  upper.wpos[1] + far * math.sin(ang))
        solve_drag(by_name, up, lo, target, bend, order)
        got = tip(lower)
        ext = math.hypot(got[0] - upper.wpos[0], got[1] - upper.wpos[1])
        gap = math.hypot(got[0] - target[0], got[1] - target[1])
        slack = gap - (far - ext)
        if ext < reach * 0.97 or slack > 3.0:
            ok = False
            print("  FAIL %s -> %s over-extension: ext=%.1f reach=%.1f slack=%.1f"
                  % (up, lo, ext, reach, slack))
    return ok


# ------------------------------- placeholder parts -------------------------------

def _lerp_profile(points, y):
    """Piecewise-linear silhouette half-width, used to give the stand-in body shape."""
    if y <= points[0][0]:
        return points[0][1]
    for i in range(len(points) - 1):
        y0, w0 = points[i]
        y1, w1 = points[i + 1]
        if y0 <= y <= y1:
            t = (y - y0) / float(y1 - y0 or 1)
            return w0 + (w1 - w0) * t
    return points[-1][1]


def emit_parts(spec, by_name, order, outdir):
    """
    Draw a stand-in body, one full-canvas PNG per bone, exactly the way an artist is
    asked to export theirs.

    This exists to prove the assembly path end to end: if these land on the right bones
    and move correctly, then real artwork dropped into the same folder will too. Delete
    the folder and the character goes back to a bare skeleton.
    """
    import os
    from PIL import Image, ImageDraw

    W, H = spec["canvas"]["width"], spec["canvas"]["height"]
    HH = float(spec["headHeight"])
    cx = spec["centreX"]
    wd = spec["proportions"]["widths"]
    top = spec["headTopY"]
    S = 2                                     # supersample, then downscale

    os.makedirs(outdir, exist_ok=True)

    # Place every bone in its rest pose before measuring anything off it.
    for b in order:
        b.rotation = 0.0
    update(order)

    # Widths at the head and the tail of each limb, in head-height units. Limbs taper:
    # a constant-width tube butted against a wide pelvis is what makes a stand-in body
    # read as a skirt rather than as hips.
    limb_w = {"shoulder": (0.130, 0.130), "upperarm": (0.245, 0.190),
              "forearm": (0.190, 0.155), "hand": (0.175, 0.150),
              "thigh": (0.620, 0.360), "shin": (0.330, 0.200),
              "foot": (0.230, 0.205)}

    # Widest at the hip joint row, then tapering in toward the crotch: a band that keeps
    # its full width to the bottom edge reads as a skirt, which is not what a pelvis is.
    torso_profile = [(455, 52), (560, wd["shoulder"] / 2.0), (920, wd["waist"] / 2.0),
                     (1000, wd["hip"] / 2.0 - 14), (1055, wd["hip"] / 2.0 - 8),
                     (1105, wd["hip"] / 2.0 - 46)]

    # Torso bands, each owned by one bone. The artist does the same thing by hand when
    # they split the body into pelvis / belly / ribcage layers.
    bands = {
        "hip": (920, 1110),
        "spine": (686, 922),
        "chest": (556, 688),
    }

    skin = {
        "hip": (232, 205, 186), "spine": (238, 212, 192), "chest": (242, 217, 197),
        "neck": (240, 214, 194), "head": (245, 220, 200),
    }

    def fill_for(name):
        if name in skin:
            return skin[name]
        if name.endswith("_L"):
            return (236, 210, 190)
        return (228, 202, 182)

    written = []
    for b in order:
        img = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        col = fill_for(b.name) + (255,)
        edge = (150, 118, 100, 190)

        if b.name in bands:
            y0, y1 = bands[b.name]
            n = 26
            pts = []
            for i in range(n + 1):
                yy = y0 + (y1 - y0) * i / float(n)
                hw = _lerp_profile(torso_profile, yy)
                pts.append(((cx - hw) * S, yy * S))
            for i in range(n, -1, -1):
                yy = y0 + (y1 - y0) * i / float(n)
                hw = _lerp_profile(torso_profile, yy)
                pts.append(((cx + hw) * S, yy * S))
            d.polygon(pts, fill=col, outline=edge)

        elif b.name == "head":
            rx = wd["head"] / 2.0
            ry = HH / 2.0
            cyy = top + ry
            d.ellipse([(cx - rx) * S, (cyy - ry) * S, (cx + rx) * S, (cyy + ry) * S],
                      fill=col, outline=edge)

        elif b.name == "neck":
            y0, y1 = 424, 560
            for i in range(2):
                pass
            pts = [((cx - 46) * S, y0 * S), ((cx + 46) * S, y0 * S),
                   ((cx + 58) * S, y1 * S), ((cx - 58) * S, y1 * S)]
            d.polygon(pts, fill=col, outline=edge)

        else:
            pair = None
            for key, fracs in limb_w.items():
                if b.name.startswith(key):
                    pair = fracs
                    break
            if pair is None:
                pair = (0.25, 0.25)
            w0, w1 = pair[0] * HH * S, pair[1] * HH * S
            hx, hy = b.wpos[0] * S, b.wpos[1] * S
            tx, ty = tip(b)
            tx, ty = tx * S, ty * S

            dx, dy = tx - hx, ty - hy
            blen = math.hypot(dx, dy) or 1.0
            nx, ny = -dy / blen, dx / blen

            poly = [(hx + nx * w0 / 2, hy + ny * w0 / 2),
                    (tx + nx * w1 / 2, ty + ny * w1 / 2),
                    (tx - nx * w1 / 2, ty - ny * w1 / 2),
                    (hx - nx * w0 / 2, hy - ny * w0 / 2)]
            d.polygon(poly, fill=col)
            for (px, py, ww) in ((hx, hy, w0), (tx, ty, w1)):
                r = ww / 2.0
                d.ellipse([px - r, py - r, px + r, py + r], fill=col, outline=edge)

        out = img.resize((W, H), Image.LANCZOS)
        path = os.path.join(outdir, b.name + ".png")
        out.save(path)
        written.append(b.name)

    print("parts -> %s  (%d files)" % (outdir, len(written)))
    return written


# ------------------------------- template render -------------------------------

def render(spec, by_name, order, out_path, scale=2):
    from PIL import Image, ImageDraw, ImageFont

    # The verify pass leaves the skeleton in whatever pose it tested last; the
    # template must always be drawn in the rest pose.
    for b in order:
        b.rotation = 0.0
    update(order)

    W, H = spec["canvas"]["width"], spec["canvas"]["height"]
    S = scale
    img = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
    FB = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
    f_small = ImageFont.truetype(FONT, 15 * S)
    f_label = ImageFont.truetype(FB, 19 * S)
    f_axis = ImageFont.truetype(FONT, 17 * S)

    def dashed(p0, p1, fill, width, dash=14, gap=10):
        x0, y0 = p0
        x1, y1 = p1
        total = math.hypot(x1 - x0, y1 - y0)
        if total == 0:
            return
        ux, uy = (x1 - x0) / total, (y1 - y0) / total
        t = 0.0
        while t < total:
            e = min(t + dash, total)
            d.line([(x0 + ux * t, y0 + uy * t), (x0 + ux * e, y0 + uy * e)],
                   fill=fill, width=width * S)
            t = e + gap

    # ---- faint body silhouette -------------------------------------------------
    # Not a drawing to copy: just the approximate bulk of each limb, so nobody has to
    # guess how wide a thigh should be before the skeleton has any art on it.
    HH = spec["headHeight"]
    cx0 = spec["centreX"]
    sil = (150, 162, 205, 42)
    wdict = spec["proportions"]["widths"]

    d.ellipse([(cx0 - wdict["head"] / 2.0) * S, 179 * S,
               (cx0 + wdict["head"] / 2.0) * S, (179 + HH) * S], fill=sil)

    sh, wa, hp = wdict["shoulder"] / 2.0, wdict["waist"] / 2.0, wdict["hip"] / 2.0

    # Neck first, then the torso. The trapezius slope matters: a plain rectangle
    # across the shoulder line reads as a box, not as a person.
    d.polygon([((cx0 - 40) * S, 424 * S), ((cx0 + 40) * S, 424 * S),
               ((cx0 + 54) * S, 566 * S), ((cx0 - 54) * S, 566 * S)], fill=sil)

    d.polygon([((cx0 - 44) * S, 528 * S), ((cx0 + 44) * S, 528 * S),
               ((cx0 + sh) * S, 556 * S), ((cx0 + wa) * S, 920 * S),
               ((cx0 + hp) * S, 1063 * S), ((cx0 - hp) * S, 1063 * S),
               ((cx0 - wa) * S, 920 * S), ((cx0 - sh) * S, 556 * S)], fill=sil)

    # Widths measured off the reference drawing, in head-height units.
    limb_w = {"upperarm": 0.210, "forearm": 0.175, "hand": 0.190,
              "thigh": 0.475, "shin": 0.310, "foot": 0.250}

    def capsule(p0, p1, w, fill):
        d.line([p0, p1], fill=fill, width=max(1, int(round(w))))
        r = w / 2.0
        for (px, py) in (p0, p1):
            d.ellipse([px - r, py - r, px + r, py + r], fill=fill)

    for b in order:
        if b.spring:
            continue
        for key, frac in limb_w.items():
            if b.name.startswith(key):
                capsule((b.wpos[0] * S, b.wpos[1] * S),
                        (tip(b)[0] * S, tip(b)[1] * S), frac * HH * S, sil)
                break

    line_c = (120, 160, 220, 150)
    tick_c = (90, 120, 190, 220)
    for lm in spec["proportions"]["landmarks"]:
        y = lm["y"] * S
        d.line([(0, y), (W * S, y)], fill=line_c, width=1 * S)
        d.line([(0, y), (14 * S, y)], fill=tick_c, width=3 * S)
        d.text((20 * S, y + 3 * S), "%.2fH  %s" % (lm["head"], lm["key"].upper()),
               font=f_small, fill=(70, 100, 160, 235))

    cx = spec["centreX"] * S
    dashed((cx, 0), (cx, H * S), (170, 170, 180, 130), 1)

    w = spec["proportions"]["widths"]
    for key, yy in (("shoulder", 556), ("waist", 920), ("hip", 1063)):
        half = w[key] / 2.0
        y = yy * S
        dashed((cx - half * S, y), (cx + half * S, y), (230, 140, 90, 200), 2, 9, 7)
        d.text((cx + half * S + 6 * S, y - 8 * S), "%s %dpx" % (key, w[key]),
               font=f_small, fill=(200, 110, 60, 230))

    core = (124, 92, 224, 215)
    for b in order:
        if b.spring:
            col = (26, 168, 160, 205)
        elif b.name.startswith(("shoulder", "upperarm", "forearm", "hand")):
            col = (226, 90, 140, 215) if b.name.endswith("_L") else (232, 140, 60, 215)
        elif b.name.startswith(("thigh", "shin", "foot")):
            col = (70, 170, 110, 215) if b.name.endswith("_L") else (60, 150, 180, 215)
        else:
            col = core

        hx, hy = b.wpos[0] * S, b.wpos[1] * S
        tx, ty = tip(b)
        tx, ty = tx * S, ty * S
        if b.spring:
            dashed((hx, hy), (tx, ty), col, 2, 11, 8)
        else:
            d.line([(hx, hy), (tx, ty)], fill=col, width=3 * S)
        r = (4 if b.spring else 5) * S
        d.ellipse([hx - r, hy - r, hx + r, hy + r], fill=(255, 255, 255, 255),
                  outline=col, width=2 * S)

    # Label every bone at its midpoint, pushed out along the bone's normal. The joint
    # ends are where the interesting geometry is, so keeping text off them stops the
    # shoulder cluster from turning into an unreadable pile.
    for b in order:
        if b.spring and b.parent is not None and b.parent.spring:
            continue          # label a spring chain once, at its root segment
        hx, hy = b.wpos[0], b.wpos[1]
        tx, ty = tip(b)
        mx, my = (hx + tx) / 2.0, (hy + ty) / 2.0
        dx, dy = tx - hx, ty - hy
        blen = math.hypot(dx, dy) or 1.0
        nx, ny = -dy / blen, dx / blen
        # Point the offset away from the body's centre line.
        if nx * (mx - cx0) + ny * (my - 0) < 0 and abs(nx * (mx - cx0)) > 1e-6:
            nx, ny = -nx, -ny
        if nx * (mx - cx0) < 0:
            nx, ny = -nx, -ny
        off = 17.0
        lx, ly = (mx + nx * off) * S, (my + ny * off) * S
        if b.spring:
            name = b.name.rstrip("_123456789")
            d.text((lx, ly), name, font=f_small, fill=(24, 158, 150, 240), anchor="mm")
        else:
            d.text((lx, ly), b.name, font=f_label, fill=(58, 58, 78, 240), anchor="mm")

    hdr = [
        "%s   canvas %dx%d   head %dpx   %.2f heads tall"
        % (spec["id"], W, H, spec["headHeight"],
           spec["proportions"].get("headsTall", 6.0)),
        "Draw each part on its own layer, export EVERY layer at full canvas size,",
        "one PNG per bone, named after the bone.  A missing file just draws nothing.",
    ]
    for i, line in enumerate(hdr):
        d.text((20 * S, (16 + i * 24) * S), line, font=f_axis,
               fill=(40, 40, 60, 235) if i == 0 else (110, 110, 130, 220))

    img = img.resize((W, H), Image.LANCZOS)
    img.save(out_path)
    print("template -> %s  (%dx%d)" % (out_path, W, H))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--spec", required=True)
    ap.add_argument("--out")
    ap.add_argument("--parts", help="write a stand-in part PNG per bone into this directory")
    ap.add_argument("--verify", action="store_true")
    args = ap.parse_args()

    spec = json.load(open(args.spec, encoding="utf-8"))
    by_name, order = bake(spec["bones"])

    ids = [b.name for b in order]
    assert len(ids) == len(set(ids)), "duplicate bone names"
    for b in order:
        if b.length <= 0:
            raise SystemExit("bone '%s' has zero length" % b.name)
    print("%s: %d bones, %d springy, %d IK chains" % (
        spec["id"], len(order), sum(1 for b in order if b.spring), len(spec["ikChains"])))
    print("")

    rc = 0
    if args.verify:
        if not verify(spec, by_name, order):
            rc = 1
    if args.out:
        render(spec, by_name, order, args.out)
    if args.parts:
        emit_parts(spec, by_name, order, args.parts)
    return rc


if __name__ == "__main__":
    sys.exit(main())
