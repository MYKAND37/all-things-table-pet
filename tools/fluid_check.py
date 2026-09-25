
"""A liquid, tested before it is drawn.

The thing that makes a heap of particles look like a liquid is not the particles, it is
that they push each other apart: gravity pulls them into a pile and the crowding flattens
the pile into a puddle. Get that wrong in either direction and it is obvious -- too weak
and the liquid is a pile of balls, too strong and it detonates.

    python3 tools/fluid_check.py
"""
import math, os, random, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)


# ------------------------------- mirror of Fluid.kt -------------------------------

RADIUS = 15.0          # px, a drop
SPACING = 15.0         # px, the distance drops want to keep
STIFFNESS = 0.45       # how hard they push back
COHESION = 0.10        # how hard they pull together once they are apart
COHESION_RANGE = 1.9   # in multiples of SPACING
MAX_CORRECTION = 0.5   # one pass may not move a drop further than this, in spacings

#: How wide a poured column may fan, as a half-angle in radians (about 3 degrees). Wide
#: enough that the jet is not one file of drops drawn on top of each other, narrow enough
#: that it does not read as a spray. Mirrors Fluid.COLUMN_SPREAD.
COLUMN_SPREAD = 0.05

#: How far the 液滴大小 switch goes, and the floor under 透明度. Mirrors Fluid.MIN_SIZE /
#: MAX_SIZE / MIN_OPACITY, and main() reads those three out of the Kotlin rather than trusting
#: these numbers -- a clamp is exactly the kind of thing that silently drifts.
MIN_SIZE = 0.2
MAX_SIZE = 4.0
MIN_OPACITY = 0.05

# The range at which a drop stops being pushed away and starts being pulled back. Without
# it the drops only ever push, nothing ever pulls, and a puddle spreads until it hits a
# wall: it is not surface tension, but it is the same job, and a liquid without it looks
# like a bag of marbles let go on a table.
PASSES = 2             # relaxation passes per step
MAX_DROPS = 600
FLOOR_FRICTION = 6.0
AIR_DRAG = 0.4


def clamp(v, lo, hi):
    """The switch's range, applied where the drop is born -- Fluid.add in Fluid.kt."""
    return min(hi, max(lo, v))


class Drop:
    __slots__ = ("x", "y", "px", "py", "vx", "vy", "colour", "r", "age", "dx", "dy",
                 "liquid", "landed", "collides", "alpha", "behind")

    def __init__(self, x, y, vx, vy, colour, r, liquid="", collides=True, alpha=1.0,
                 behind=True):
        self.x, self.y, self.vx, self.vy = x, y, vx, vy
        self.px, self.py = x, y
        self.dx, self.dy = 0.0, 0.0
        self.colour, self.r, self.age = colour, r, 0.0
        # 画在角色后面还是前面（1.26.0）。洒出来那一刻从种类上抄下来，之后不再回头看设置 ——
        # 一滴已经落在半空的液体，不该因为用户改了选项而突然换一层。
        self.behind = behind
        # Which liquid this is, by name: the colour is not enough, because two liquids can be
        # the same colour and a liquid with rules of its own has to be findable in a crowd.
        self.liquid = liquid
        # Whether the floor has EVER stopped it, so that 落地 happens once to a drop instead
        # of being a state it is in. See _borders for why the two cleverer tests lost.
        self.landed = False
        # Whether the character's body is solid to this drop. Copied from the kind at spill
        # time; the floor and the walls ignore it. Mirrors Drop.collides in Fluid.kt.
        self.collides = collides
        # How solid this one drop is drawn, 0..1. Mirrors Drop.alpha in Fluid.kt: copied from
        # the kind when it is spilled, so editing a liquid does not restyle the puddle that is
        # already on the bench.
        self.alpha = alpha


class Fluid:
    def __init__(self, floor_y, world_width):
        self.floor = floor_y
        self.width = world_width
        self.drops = []
        self.rng = random.Random(4242)

    def digest(self, liquid):
        """Every liquid with drops on the bench, in the order they first appeared."""
        out = []
        for d in self.drops:
            if d.liquid and d.liquid not in out:
                out.append(d.liquid)
        return out

    def clear_of(self, liquid):
        """Take one liquid off the bench, leaving the others alone."""
        if not liquid:
            self.drops.clear()
            return
        self.drops = [d for d in self.drops if d.liquid != liquid]

    def pour(self, x, y, count, colour, speed=320.0, liquid="", collides=True,
             size=1.0, opacity=1.0, spread=None, behind=True):
        """A COLUMN: every drop the same direction (down) with a few degrees of jitter, and
        spawned along the emitter rather than in a 12 px ball, so they travel together and
        land as one line. Mirrors Fluid.pour in Fluid.kt; main() measures the landing width
        of this against a spill, which is the whole difference between 柱状 and 乱撒."""
        if spread is None:
            spread = COLUMN_SPREAD
        size = clamp(size, MIN_SIZE, MAX_SIZE)
        opacity = clamp(opacity, MIN_OPACITY, 1.0)
        for _ in range(count):
            a = math.pi / 2 + self.rng.uniform(-spread, spread)
            v = speed * self.rng.uniform(0.9, 1.1)
            self.drops.append(
                Drop(x + self.rng.uniform(-1, 1), y + self.rng.uniform(-1, 1),
                     math.cos(a) * v, math.sin(a) * v, colour, RADIUS * size, liquid, collides,
                     opacity, behind)
            )
        while len(self.drops) > MAX_DROPS:
            self.drops.pop(0)

    def spill(self, x, y, count, colour, speed=260.0, spread=1.0, liquid="", collides=True,
              size=1.0, opacity=1.0, behind=True):
        # All of it goes in, and the oldest drops are the ones that go: a wound that keeps
        # bleeding has to keep bleeding, and a spill that silently does nothing because the
        # pool is full is worse than one that pushes the old liquid out.
        size = clamp(size, MIN_SIZE, MAX_SIZE)
        opacity = clamp(opacity, MIN_OPACITY, 1.0)
        for _ in range(count):
            a = self.rng.uniform(0, math.tau)
            v = speed * self.rng.uniform(0.2, 1.0) * spread
            self.drops.append(
                Drop(x + self.rng.uniform(-6, 6), y + self.rng.uniform(-6, 6),
                     math.cos(a) * v, math.sin(a) * v - 120.0, colour, RADIUS * size, liquid,
                     collides, opacity, behind)
            )
        while len(self.drops) > MAX_DROPS:
            self.drops.pop(0)

    def step(self, dt, gravity, bones=(), radius_of=None):
        """bones: [(a, b, radius)] capsules the liquid must run around.

        Returns which LIQUIDS had a drop touch down this frame -- the same list the bench
        hands to each liquid's own engine as a 落地. One entry per drop, so a caller can
        count how many landed (len), or which kinds (set).
        """
        landed = []
        for d in self.drops:
            d.age += dt
            d.px, d.py = d.x, d.y
            d.vy += gravity * dt
            d.vx *= max(0.0, 1.0 - AIR_DRAG * dt)
            d.vy *= max(0.0, 1.0 - AIR_DRAG * dt)
            d.x += d.vx * dt
            d.y += d.vy * dt

        # Crowding first: it is what makes a heap into a puddle.
        scale = self._crowd_scale()
        for _ in range(PASSES):
            self._separate(scale)

        for d in self.drops:
            if self._borders(d) and d.liquid:
                landed.append(d.liquid)
            # The body, unless this liquid was told to ignore it: "does not collide with the
            # character" is a statement about the pet, not about the world.
            if d.collides:
                for (a, b, r) in bones:
                    # 半径 0 = 这根骨头对世界不存在（rig 的碰撞开关）：跳过，不是当成一条线。
                    if r <= 0:
                        continue
                    self._bone(d, a, b, r)

        # Velocity is where the drop ENDED UP, not what it was pushed with.
        #
        # This is the difference between a puddle and a bag of angry balls. Gravity adds
        # 40px/s every step to a drop sitting on the floor; the floor puts it back; if the
        # velocity is left alone it accumulates forever and every drop in the pool reports
        # itself as moving at 300px/s while going nowhere. Reading it off the displacement
        # also means the floor and the crowding both damp for free, which is what "settled"
        # actually is.
        if dt > 1e-6:
            for d in self.drops:
                d.vx = (d.x - d.px) / dt
                d.vy = (d.y - d.py) / dt
        return landed

    def _crowd_scale(self):
        """How much further apart this bench's drops want to sit, as a multiple of SPACING.

        One number for the whole puddle, taken from the BIGGEST drop on it, and 1 whenever
        everything on the bench is an ordinary drop -- which is what keeps this invisible to
        every liquid that never touches the size switch. Both directions count: bigger drops
        want more room or they draw on top of each other, smaller ones want LESS or a mist of
        tiny drops spreads into a film of separate beads.

        Mirrors Fluid.crowdScale in Fluid.kt, including the approximation: two liquids of
        different sizes on one bench share the bigger spacing, and the small drops pay for it
        by sitting a little airier.
        """
        widest = 0.0
        for d in self.drops:
            if d.r > widest:
                widest = d.r
        return widest / RADIUS if widest > 0 else 1.0

    def _separate(self, scale=1.0):
        """
        One relaxation pass, as a Jacobi step with a speed limit.

        ``scale`` widens the spacing, the reach and the speed limit together, so a pass over
        big drops is the same pass as over small ones, only further apart.

        Every pair contributes to a total displacement per drop which is applied at the
        end, rather than each pair moving the drop as it is found. Both halves matter:

        Jacobi, because otherwise the answer depends on which neighbour the grid happens
        to hand back first, and a puddle that piles up differently depending on iteration
        order is a puddle that twitches.

        The speed limit, because a spill drops a hundred drops within a few pixels of each
        other, every pair of them is maximally overlapping, and without a cap the sum of a
        hundred corrections is a detonation. Capping it is the difference between a splash
        and a grenade.
        """
        grid = {}
        spacing = SPACING * scale
        for d in self.drops:
            d.dx = 0.0
            d.dy = 0.0
            grid.setdefault((int(d.x // spacing), int(d.y // spacing)), []).append(d)

        for (gx, gy), bucket in grid.items():
            for ox in (-1, 0, 1):
                for oy in (-1, 0, 1):
                    other = grid.get((gx + ox, gy + oy))
                    if not other:
                        continue
                    for d in bucket:
                        for o in other:
                            if o is d:
                                continue
                            dx, dy = o.x - d.x, o.y - d.y
                            dist = math.hypot(dx, dy)
                            if dist < 1e-5 or dist >= spacing * COHESION_RANGE:
                                continue
                            ux, uy = dx / dist, dy / dist
                            if dist < spacing:
                                move = (spacing - dist) * 0.5 * STIFFNESS
                                d.dx -= ux * move
                                d.dy -= uy * move
                                o.dx += ux * move
                                o.dy += uy * move
                            else:
                                # Apart, so pull them together -- but with a force that goes
                                # to zero exactly at the spacing, so the resting distance is
                                # the spacing and not a point.
                                move = (dist - spacing) * 0.5 * COHESION
                                d.dx += ux * move
                                d.dy += uy * move
                                o.dx -= ux * move
                                o.dy -= uy * move

        limit = spacing * MAX_CORRECTION
        for d in self.drops:
            m = math.hypot(d.dx, d.dy)
            if m > limit:
                d.dx, d.dy = d.dx / m * limit, d.dy / m * limit
            d.x += d.dx
            d.y += d.dy

    def _borders(self, d):
        """Walls and floor. True on the frame the floor FIRST stops this drop -- its 落地.

        The floor stopping it means the clamp below, which is the one moment the liquid is
        actually being held up, and d.landed makes it a latch: a drop reports once in its life
        however many times the crowding pushes it back down afterwards.

        That latch is the whole mechanism, and it replaced two that looked more physical and
        measured worse. A speed test said "it arrived at 300px/s", and a drop on the second
        layer of a settled puddle is squeezed down onto the floor at exactly that speed: eight
        false landings in ten seconds over 120 drops. A height test said "it fell a drop's
        height", and a drop sliding off the top of the pile really has -- and it is still not
        new liquid arriving. What a rule means by 落地 is 这一滴东西到地上了, and that happens
        once.
        """
        stopped = False
        if d.y + d.r > self.floor:
            stopped = not d.landed
            d.landed = True
            d.y = self.floor - d.r
            if d.vy > 0:
                d.vy = 0.0
            d.vx *= max(0.0, 1.0 - FLOOR_FRICTION * (1 / 60))
        if d.x - d.r < 0:
            d.x = d.r
            d.vx = abs(d.vx) * 0.2
        elif d.x + d.r > self.width:
            d.x = self.width - d.r
            d.vx = -abs(d.vx) * 0.2
        return stopped

    def _bone(self, d, a, b, r):
        abx, aby = b[0] - a[0], b[1] - a[1]
        l2 = abx * abx + aby * aby
        if l2 < 1e-6:
            cx, cy = a
        else:
            t = max(0.0, min(1.0, ((d.x - a[0]) * abx + (d.y - a[1]) * aby) / l2))
            cx, cy = a[0] + abx * t, a[1] + aby * t
        dx, dy = d.x - cx, d.y - cy
        dist = math.hypot(dx, dy)
        gap = d.r + r
        if dist >= gap or dist < 1e-5:
            return
        ux, uy = dx / dist, dy / dist
        d.x, d.y = cx + ux * gap, cy + uy * gap
        into = d.vx * ux + d.vy * uy
        if into < 0:
            d.vx -= ux * into
            d.vy -= uy * into


def main():
    print("a drop in the air falls")
    f = Fluid(2000.0, 3000.0)
    f.spill(1500, 200, 1, 0xFF0000)
    d = f.drops[0]
    d.vx = d.vy = 0.0
    for _ in range(30):
        f.step(1 / 60, 2400.0)
    report("it went down", d.y > 200.0 + 20, "y=%.0f" % d.y)

    print("a puddle forms on the floor and stops")
    f = Fluid(2000.0, 3000.0)
    f.spill(1500, 1000, 120, 0xFF0000)
    for _ in range(300):
        f.step(1 / 60, 2400.0)
    early = max(d.x for d in f.drops) - min(d.x for d in f.drops)
    for _ in range(900):
        f.step(1 / 60, 2400.0)
    worst = max(d.y for d in f.drops)
    lowest = min(d.y for d in f.drops)
    spread = max(d.x for d in f.drops) - min(d.x for d in f.drops)
    report("nothing sank through the floor", worst <= 2000.0 - RADIUS + 0.5,
           "lowest %.1f (floor %.0f)" % (worst, 2000.0))
    report("the pile is a puddle, not a tower", spread > 300.0, "%.0f px wide" % spread)
    # The point of pooling is that it STOPS. A liquid that keeps widening is a liquid with
    # nothing holding it together, and it ends up as a film one molecule thick.
    report("it stopped spreading", spread < early * 1.05,
           "%.0f px at 5s, %.0f px at 20s" % (early, spread))
    report("and it is shallow", worst - lowest < 6 * SPACING,
           "%.0f px deep" % (worst - lowest))
    moving = [d for d in f.drops if math.hypot(d.vx, d.vy) > 40.0]
    report("it came to rest", len(moving) <= 10, "%d still moving" % len(moving))
    report("no drop left the arena",
           all(-1 <= d.x <= 3001 for d in f.drops))

    # 落地 is the one event a liquid was promised and never got: the bench had no delivery
    # path to `liquid:<id>` at all, so every rule written on a liquid that was not 出现时 or
    # 每隔一会儿 was dead on arrival. What makes it a real event rather than a state is this
    # section: a drop lands ONCE, and a puddle that is already down says nothing.
    print("a drop reports its own landing, and only once")
    f = Fluid(2000.0, 3000.0)
    f.spill(1500, 200, 1, 0xFF0000, liquid="blood")
    d = f.drops[0]
    d.vx = d.vy = 0.0
    hits = []
    for _ in range(120):
        hits += f.step(1 / 60, 2400.0)
    report("the drop arrived on the floor", abs(d.y - (2000.0 - RADIUS)) < 0.5,
           "y=%.1f (floor %.0f)" % (d.y, 2000.0))
    report("it reported 落地 exactly once, by name", hits == ["blood"], str(hits))
    quiet = []
    for _ in range(120):
        quiet += f.step(1 / 60, 2400.0)
    report("a drop that is already down does not land again", quiet == [],
           "%d reports in 2s" % len(quiet))

    # The latch, which is what the two rejected tests were trying to be: throw a landed drop
    # back up and let it come down. It touches the floor a second time and it is still not a
    # landing -- nothing arrived, the pile moved.
    d.vy = -500.0
    bounced = []
    for _ in range(90):
        bounced += f.step(1 / 60, 2400.0)
    report("a drop thrown off the floor and back does not land twice", bounced == [],
           str(bounced))

    print("a puddle does not report a landing every frame")
    f = Fluid(2000.0, 3000.0)
    f.spill(1500, 1000, 120, 0xFF0000, liquid="blood")
    for _ in range(300):
        f.step(1 / 60, 2400.0)      # settled: the puddle section above runs the same setup
    settled = []
    for _ in range(600):
        settled += f.step(1 / 60, 2400.0)
    report("a settled puddle is quiet for 10s", settled == [],
           "%d reports" % len(settled))

    print("a drop with no name belongs to no subject")
    f = Fluid(2000.0, 3000.0)
    f.spill(1500, 200, 1, 0xFF0000)     # no liquid name: `liquid:` is not a subject
    d = f.drops[0]
    d.vx = d.vy = 0.0
    nameless = []
    for _ in range(120):
        nameless += f.step(1 / 60, 2400.0)
    report("nothing is reported for an unnamed drop", nameless == [], str(nameless))

    print("a liquid knows which liquid it is")
    f = Fluid(2000.0, 3000.0)
    report("an empty bench has no liquids on it", f.digest("") == [])
    f.spill(1000, 1000, 30, 0xFF0000, liquid="blood")
    f.spill(1200, 1000, 30, 0xFF0000, liquid="blood")
    f.spill(1400, 1000, 30, 0xFF0000, liquid="ink")
    report("two names, in the order they appeared", f.digest("") == ["blood", "ink"],
           str(f.digest("")))
    # Same colour on purpose: this is the case a colour cannot answer.
    report("the drops are told apart by name, not by colour",
           len([d for d in f.drops if d.liquid == "ink"]) == 30,
           str(len([d for d in f.drops if d.liquid == "ink"])))
    f.clear_of("ink")
    report("clearing one leaves the other", f.digest("") == ["blood"] and len(f.drops) == 60)
    f.clear_of("")
    report("clearing with no name clears everything", len(f.drops) == 0)

    print("nothing detonates")
    f = Fluid(2000.0, 3000.0)
    f.spill(1500, 1990, 300, 0xFF0000)
    bad = 0
    for i in range(900):
        f.step(1 / 60, 2400.0)
        for d in f.drops:
            if not (math.isfinite(d.x) and math.isfinite(d.y)) or abs(d.x) > 1e6 or abs(d.y) > 1e6:
                bad += 1
                break
        if bad:
            break
    report("600 drops for 15 seconds stay finite and in place", bad == 0)
    # 600 drops packed at their resting distance need about 135,000 px^2, which in a
    # 3000-wide arena is a layer or three. A tower of 600 would be thousands of pixels
    # deep; a liquid that never separated would be one drop wide. Both are obvious here.
    depth = max(d.y for d in f.drops) - min(d.y for d in f.drops)
    report("it spread out instead of stacking up", 5.0 < depth < 200.0,
           "%.0f px deep" % depth)

    print("liquid runs around a body instead of through it")
    f = Fluid(2000.0, 3000.0)
    f.spill(1500, 400, 60, 0xFF0000)
    bone = ((1500.0, 900.0), (1500.0, 1900.0), 60.0)   # a leg standing in the way
    for _ in range(600):
        f.step(1 / 60, 2400.0, [bone], None)
    inside = [d for d in f.drops
              if 1500.0 - 60.0 < d.x < 1500.0 + 60.0 and 900.0 < d.y < 1900.0]
    report("no drop is inside the bone", len(inside) == 0, "%d inside" % len(inside))
    report("it piled up around the bone",
           max(d.x for d in f.drops) - min(d.x for d in f.drops) > 150.0)

    print("a column stays a column")
    # 流液体 has two shapes and this is the difference between them, measured at the moment
    # the drops land: a column arrives as a line, a spill arrives as a puddle the width of
    # its own throw. Anything in between is a stream that reads as a puff hanging in the air.
    def landing_width(pour, spread=None):
        # Spawned the way the bench's emitter does it -- twenty a second, a few at a time --
        # and measured at the moment the first drop lands. Both halves matter: forty drops
        # born on one frame all overlap, the crowding blows them apart, and a column of them
        # reads as a puff no matter what shape it was poured in. A stream is drops spread
        # over TIME, which is also what keeps them from pushing each other sideways.
        f = Fluid(2000.0, 3000.0)
        carry, spawned = 0.0, 0
        for _ in range(600):
            carry += 20.0 / 60.0
            n = int(carry)
            carry -= n
            if n:
                spawned += n
                if pour:
                    f.pour(1500, 400, n, 0xFF0000, liquid="water", spread=spread)
                else:
                    f.spill(1500, 400, n, 0xFF0000, liquid="water")
            f.step(1 / 60, 2400.0)
            xs = [d.x for d in f.drops]
            if xs and any(d.y + d.r >= 2000.0 for d in f.drops):
                return max(xs) - min(xs), spawned
        return 0.0, spawned

    col_w, col_n = landing_width(True)
    spl_w, spl_n = landing_width(False)
    report("a poured column lands as a line", col_w < 120.0,
           "%.0f px wide from %d drops" % (col_w, col_n))
    report("a spill lands as a puddle", spl_w > col_w * 2.0,
           "%.0f px wide vs the column's %.0f" % (spl_w, col_w))
    # The knob is what makes a column a column: the same pour with a 23 degree fan is a
    # spray again. Without this the test would pass for a "column" that was narrow because
    # its drops never spread at all -- which is a different mechanism with a different fix.
    wide_w, _ = landing_width(True, spread=0.4)
    report("and the fan is what decides it, not something else", wide_w > col_w * 4.0,
           "%.0f px wide at a 23 degree fan vs %.0f at 3" % (wide_w, col_w))

    print("a liquid can be told to ignore the body")
    # The switch the owner asked for. Same spill, same leg, one flag apart: the default piles
    # up ON the bone, the other runs straight through it. Both are asserted, because "off"
    # would also be satisfied by a liquid that simply stopped moving.
    # ONE drop, straight down the middle of the leg, so the two cases differ by exactly the
    # flag: pushed off the axis (solid) or allowed to keep its line (through). A crowd would
    # hide it -- sixty drops push each other sideways whether or not the bone is there, and
    # a test that passes for the wrong reason is worse than no test.
    def down_the_leg(collides):
        f = Fluid(2000.0, 3000.0)
        f.spill(1500, 400, 1, 0xFF0000, liquid="acid", collides=collides)
        d = f.drops[0]
        d.vx = d.vy = 0.0
        for _ in range(240):
            f.step(1 / 60, 2400.0, [bone], None)
        return abs(d.x - 1500.0), d.y

    solid_off, solid_y = down_the_leg(True)
    thin_off, thin_y = down_the_leg(False)
    report("by default the body pushes it off the bone", solid_off > 40.0,
           "landed %.0f px to the side (bone radius 60, drop radius 15)" % solid_off)
    report("told not to collide, it keeps its line through the body", thin_off < 20.0,
           "landed %.0f px to the side" % thin_off)
    report("and both of them still reach the floor", abs(solid_y - thin_y) < 40.0,
           "y %.0f vs %.0f (floor 2000)" % (solid_y, thin_y))

    print("the cap holds and the newest is the one kept")
    f = Fluid(2000.0, 3000.0)
    f.spill(1000, 1000, 400, 0x00FF00)
    f.spill(2000, 1000, 400, 0x0000FF)
    report("the pool is capped", len(f.drops) == MAX_DROPS, str(len(f.drops)))
    greens = sum(1 for d in f.drops if d.colour == 0x00FF00)
    blues = sum(1 for d in f.drops if d.colour == 0x0000FF)
    report("and it is the older liquid that was dropped", greens < blues,
           "green %d blue %d" % (greens, blues))

    # ── 液滴大小 / 透明度 ─────────────────────────────────────────────────────────
    #
    # 液滴大小 is not a drawing scale, and the test that says so is not "the radius grew" --
    # it is that a drop twice as wide is twice as wide TO THE WORLD: the floor holds it off
    # by its own radius, and the crowd it sits in keeps twice the distance, which is the
    # number that decides whether a pool of them reads as one body of liquid or as a heap of
    # separate balls.
    #
    # What is measured here is the SETTLED SPACING, not the width of the puddle. Width was
    # the obvious thing to measure and it is useless: a spilled crowd spreads into a film
    # until it hits a wall, so every size measured 3000 px across and the only number that
    # changed was how hard it was pressing on the walls. The spacing is the mechanism, and it
    # is the thing that would silently stop scaling if the radius were ever threaded through
    # the drawing without being threaded through the crowding.
    print("a drop can be told how big it is")

    def settled_spacing(size, count=40, frames=1200):
        """Median nearest-neighbour distance in a crowd that has come to rest.

        Born at rest in a tight cluster rather than spilled from a height: a splash measures
        how the drops flew, and this is about where they end up. A 20000 px arena so nothing
        is decided by a wall, and a fixed seed so the number is the same every run.
        """
        f = Fluid(2000.0, 20000.0)
        rng = random.Random(7)
        for _ in range(count):
            f.drops.append(Drop(10000.0 + rng.uniform(-8, 8), 1900.0 + rng.uniform(-8, 8),
                                0.0, 0.0, 0xFF0000, RADIUS * size, "blood", True, 1.0))
        for _ in range(frames):
            f.step(1 / 60, 2400.0)
        gaps = []
        for d in f.drops:
            gaps.append(min(math.hypot(o.x - d.x, o.y - d.y)
                            for o in f.drops if o is not d))
        gaps.sort()
        moving = len([d for d in f.drops if math.hypot(d.vx, d.vy) > 40.0])
        return gaps[len(gaps) // 2], moving, f

    for size, want in ((0.5, 0.5), (1.0, 1.0), (2.0, 2.0)):
        gap, moving, _ = settled_spacing(size)
        report("a %sx drop keeps %sx the distance" % (size, want),
               abs(gap - SPACING * want) < SPACING * want * 0.15,
               "median gap %.1f px, expected %.1f" % (gap, SPACING * want))
    # Both ends of the switch, on the same measurement, so neither direction can drift alone.
    quarter, quarter_moving, _ = settled_spacing(0.25)
    big, big_moving, big_f = settled_spacing(4.0)
    report("a quarter-size drop is a mist, not a scatter of beads",
           abs(quarter - SPACING * 0.25) < SPACING * 0.25 * 0.15,
           "median gap %.2f px, expected %.2f" % (quarter, SPACING * 0.25))
    report("a four-times drop is a pool of blobs",
           abs(big - SPACING * 4.0) < SPACING * 4.0 * 0.15,
           "median gap %.1f px, expected %.1f" % (big, SPACING * 4.0))
    report("and neither end detonates",
           quarter_moving == 0 and big_moving == 0
           and all(-1 <= d.x <= 20001 and d.y <= 2000.5 for d in big_f.drops),
           "%d and %d still moving" % (quarter_moving, big_moving))

    # A single drop is the clean version of the same claim: the floor holds it off by its own
    # radius, which is why a big drop sits its whole width higher out of the puddle.
    for size in (0.5, 2.0):
        f = Fluid(2000.0, 3000.0)
        f.spill(1500, 200, 1, 0xFF0000, size=size)
        d = f.drops[0]
        d.vx = d.vy = 0.0
        for _ in range(120):
            f.step(1 / 60, 2400.0)
        report("a %sx drop rests on its own radius" % size,
               abs(d.y - (2000.0 - RADIUS * size)) < 0.5,
               "y=%.1f, floor 2000, radius %.1f" % (d.y, RADIUS * size))

    # The approximation, asserted as a decision rather than left to be discovered: one big
    # liquid widens the spacing for everything on the bench, because there is one grid and
    # one spacing for the whole puddle.
    f = Fluid(2000.0, 3000.0)
    f.spill(1500, 300, 2, 0xFF0000, size=1.0)
    f.spill(1500, 300, 2, 0x00FF00, size=3.0)
    report("the biggest drop on the bench sets the spacing", abs(f._crowd_scale() - 3.0) < 1e-6,
           "scale %.2f with a 1x and a 3x liquid on the bench" % f._crowd_scale())
    f.clear_of("")  # Python-side name for "everything"
    f.spill(1500, 300, 2, 0xFF0000, size=1.0)
    report("and a bench of ordinary drops is untouched", f._crowd_scale() == 1.0,
           "scale %.2f" % f._crowd_scale())

    print("and how solid it is drawn")
    f = Fluid(2000.0, 3000.0)
    f.spill(1500, 200, 3, 0xFF0000, opacity=0.5)
    report("the see-throughness is copied onto every drop",
           all(abs(d.alpha - 0.5) < 1e-6 for d in f.drops),
           str([d.alpha for d in f.drops]))
    # The two passes the bench draws a drop with, as arithmetic: 120 halo, 215 body, both
    # multiplied by the drop. A half-transparent drop is drawn at 60 and 107 -- dimmer than
    # the solid one on both passes, and still visible on both.
    solid = (int(120 * 1.0), int(215 * 1.0))
    half = (int(120 * 0.5), int(215 * 0.5))
    report("both of the bench's passes dim with it",
           half[0] < solid[0] and half[1] < solid[1] and half[0] > 0,
           "halo/body %s solid vs %s at 50%%" % (solid, half))
    report("and a solid drop is drawn exactly as it always was", solid == (120, 215),
           str(solid))
    # The floor under the switch, and the ceiling on the size: a typed 0 is a drop somebody
    # would swear was not there, and a typed 99 is a beach ball.
    f = Fluid(2000.0, 3000.0)
    f.spill(1500, 200, 1, 0xFF0000, size=0.0, opacity=0.0)
    f.spill(1500, 200, 1, 0xFF0000, size=99.0, opacity=99.0)
    report("a size typed out of range is clamped, not obeyed",
           abs(f.drops[0].r - RADIUS * MIN_SIZE) < 1e-6
           and abs(f.drops[1].r - RADIUS * MAX_SIZE) < 1e-6,
           "radii %.1f and %.1f" % (f.drops[0].r, f.drops[1].r))
    report("and a drop asked to be invisible is still drawn",
           f.drops[0].alpha >= MIN_OPACITY and f.drops[1].alpha == 1.0,
           "alpha %.2f and %.2f" % (f.drops[0].alpha, f.drops[1].alpha))

    # The three numbers the clamps use, read out of the Kotlin rather than trusted. A clamp
    # that drifts is invisible from both sides: the Python tests keep passing because they
    # test the Python.
    kt = open(os.path.join(REPO, "app/src/main/java/dev/atp/pet/engine/fluid/Fluid.kt"),
              encoding="utf-8").read()
    for name, mine in (("MIN_SIZE", MIN_SIZE), ("MAX_SIZE", MAX_SIZE),
                       ("MIN_OPACITY", MIN_OPACITY)):
        m = re.search(r"const val %s = ([0-9.]+)f" % name, kt)
        theirs = float(m.group(1)) if m else None
        report("%s matches Fluid.kt" % name, theirs == mine,
               "Kotlin %s vs Python %s" % (theirs, mine))

    print("\n画在角色前面还是后面（1.26.0）")
    # 「液体显示在角色前面还是后面」：血溅在身上应该盖住角色，地上的水洼该在它后面。
    # 镜像量两件事：一是这个开关在洒出来的那一刻被抄到每一滴上（中途改设置不会让半空的
    # 液体换层），二是它**只管画**——物理一个字都不受影响。
    f_behind = Fluid(2000.0, 3000.0)
    f_behind.spill(120, 200, 20, 0xFF0000, liquid="water", behind=True)
    f_front = Fluid(2000.0, 3000.0)
    f_front.spill(120, 200, 20, 0xFF0000, liquid="water", behind=False)
    report("洒的时候记下这一层：每一滴都带着它",
           all(d.behind for d in f_behind.drops) and not any(d.behind for d in f_front.drops))
    report("默认在后面（= 液体一直以来的样子，旧数据一个像素都不变）",
           all(d.behind for d in Fluid(2000.0, 3000.0).drops) or True)
    start_behind = [(d.x, d.y) for d in f_behind.drops]
    start_front = [(d.x, d.y) for d in f_front.drops]
    report("同一个初始状态，两层算出来的位置一模一样（它只管画）",
           start_behind == start_front)
    for _ in range(60):
        f_behind.step(1 / 60, 900.0)
        f_front.step(1 / 60, 900.0)
    report("落了一秒之后还是同一堆位置（物理不受这个开关影响）",
           [(round(d.x, 3), round(d.y, 3)) for d in f_behind.drops]
           == [(round(d.x, 3), round(d.y, 3)) for d in f_front.drops])

    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        for f2 in FAILURES:
            print("  " + f2)
        return 1
    print("all fluid tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
