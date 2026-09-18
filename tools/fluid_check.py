
"""A liquid, tested before it is drawn.

The thing that makes a heap of particles look like a liquid is not the particles, it is
that they push each other apart: gravity pulls them into a pile and the crowding flattens
the pile into a puddle. Get that wrong in either direction and it is obvious -- too weak
and the liquid is a pile of balls, too strong and it detonates.

    python3 tools/fluid_check.py
"""
import math, random, sys

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

# The range at which a drop stops being pushed away and starts being pulled back. Without
# it the drops only ever push, nothing ever pulls, and a puddle spreads until it hits a
# wall: it is not surface tension, but it is the same job, and a liquid without it looks
# like a bag of marbles let go on a table.
PASSES = 2             # relaxation passes per step
MAX_DROPS = 600
FLOOR_FRICTION = 6.0
AIR_DRAG = 0.4


class Drop:
    __slots__ = ("x", "y", "px", "py", "vx", "vy", "colour", "r", "age", "dx", "dy",
                 "liquid", "landed", "collides")

    def __init__(self, x, y, vx, vy, colour, r, liquid="", collides=True):
        self.x, self.y, self.vx, self.vy = x, y, vx, vy
        self.px, self.py = x, y
        self.dx, self.dy = 0.0, 0.0
        self.colour, self.r, self.age = colour, r, 0.0
        # Which liquid this is, by name: the colour is not enough, because two liquids can be
        # the same colour and a liquid with rules of its own has to be findable in a crowd.
        self.liquid = liquid
        # Whether the floor has EVER stopped it, so that 落地 happens once to a drop instead
        # of being a state it is in. See _borders for why the two cleverer tests lost.
        self.landed = False
        # Whether the character's body is solid to this drop. Copied from the kind at spill
        # time; the floor and the walls ignore it. Mirrors Drop.collides in Fluid.kt.
        self.collides = collides


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

    def spill(self, x, y, count, colour, speed=260.0, spread=1.0, liquid="", collides=True):
        # All of it goes in, and the oldest drops are the ones that go: a wound that keeps
        # bleeding has to keep bleeding, and a spill that silently does nothing because the
        # pool is full is worse than one that pushes the old liquid out.
        for _ in range(count):
            a = self.rng.uniform(0, math.tau)
            v = speed * self.rng.uniform(0.2, 1.0) * spread
            self.drops.append(
                Drop(x + self.rng.uniform(-6, 6), y + self.rng.uniform(-6, 6),
                     math.cos(a) * v, math.sin(a) * v - 120.0, colour, RADIUS, liquid,
                     collides)
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
        for _ in range(PASSES):
            self._separate()

        for d in self.drops:
            if self._borders(d) and d.liquid:
                landed.append(d.liquid)
            # The body, unless this liquid was told to ignore it: "does not collide with the
            # character" is a statement about the pet, not about the world.
            if d.collides:
                for (a, b, r) in bones:
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

    def _separate(self):
        """
        One relaxation pass, as a Jacobi step with a speed limit.

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
        cell = SPACING
        for d in self.drops:
            d.dx = 0.0
            d.dy = 0.0
            grid.setdefault((int(d.x // cell), int(d.y // cell)), []).append(d)

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
                            if dist < 1e-5 or dist >= SPACING * COHESION_RANGE:
                                continue
                            ux, uy = dx / dist, dy / dist
                            if dist < SPACING:
                                move = (SPACING - dist) * 0.5 * STIFFNESS
                                d.dx -= ux * move
                                d.dy -= uy * move
                                o.dx += ux * move
                                o.dy += uy * move
                            else:
                                # Apart, so pull them together -- but with a force that goes
                                # to zero exactly at SPACING, so the resting distance is the
                                # spacing and not a point.
                                move = (dist - SPACING) * 0.5 * COHESION
                                d.dx += ux * move
                                d.dy += uy * move
                                o.dx -= ux * move
                                o.dy -= uy * move

        limit = SPACING * MAX_CORRECTION
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
