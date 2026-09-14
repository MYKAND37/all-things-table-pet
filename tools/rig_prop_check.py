
"""Tests for prop physics, mirrored from dev.atp.pet.engine.prop.PropWorld.

A prop that is inside the character has to push itself back out, and a prop resting on a
bone touches it EVERY frame -- so the two things most likely to be wrong are the push-out
and the contact throttle. Both would show up as the rules firing sixty times a second, or
as a hammer that sinks into the thing it hit.

    python3 tools/rig_prop_check.py
"""
import math, os, sys, json
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)


MAX_PROPS = 40
RESTITUTION = 0.28
GRAB_SLACK = 40.0
IMPULSE_FLOOR = 200.0
IMPULSE_GAIN = 0.22
MIN_HIT = 220.0
CONTACT_PERIOD = 0.25
TRANSIENT_LIFE = 8.0


class Spec:
    def __init__(self, id, kind="throw", radius=60.0, force=1.0, gravity=1.0, transient=False):
        self.id, self.kind, self.radius = id, kind, radius
        self.force, self.gravity, self.transient = force, gravity, transient

    def bullet(self):
        return Spec(self.id, "throw", max(self.radius * 0.4, 6.0), self.force * 1.8, 0.35, True)


class Prop:
    def __init__(self, serial, spec, x, y, vx=0.0, vy=0.0):
        self.serial, self.spec = serial, spec
        self.x, self.y, self.vx, self.vy = x, y, vx, vy
        self.held = False
        self.age = 0.0
        self.still = 0.0
        self.on_floor = False
        self.last_drag = None

    def begin_drag(self):
        self.last_drag = (self.x, self.y)
        self.held = True

    def drag_to(self, p, dt):
        if self.last_drag is not None and dt > 1e-4:
            vx = (p[0] - self.last_drag[0]) / dt
            vy = (p[1] - self.last_drag[1]) / dt
            n = math.hypot(vx, vy)
            if n > 4000:
                vx, vy = vx / n * 4000, vy / n * 4000
            self.vx, self.vy = vx, vy
        self.x, self.y = p
        self.last_drag = p

    def end_drag(self):
        self.last_drag = None
        self.held = False


class World:
    def __init__(self, floor_y, world_width):
        self.floor_y, self.world_width = floor_y, world_width
        self.live = []
        self.next_serial = 1
        self.last_hit = {}
        self.clock = 0.0
        self.impulses = []

    def spawn(self, spec, at, velocity=(0.0, 0.0)):
        while len(self.live) >= MAX_PROPS:
            i = next((k for k, p in enumerate(self.live) if p.spec.transient), None)
            if i is None:
                break
            self.live.pop(i)
        prop = Prop(self.next_serial, spec, at[0], at[1], velocity[0], velocity[1])
        self.next_serial += 1
        self.live.append(prop)
        return prop

    def grab_at(self, point):
        best, best_d = None, GRAB_SLACK
        for p in self.live:
            d = math.dist((p.x, p.y), point) - p.spec.radius
            if d < best_d:
                best_d, best = d, p
        return best

    def step(self, dt, gravity, bones, radius_of, on_impulse):
        self.clock += dt
        hits, gone = [], []
        for p in self.live:
            p.age += dt
            if not p.held:
                if not p.on_floor:
                    p.vy += gravity * p.spec.gravity * dt
                p.x += p.vx * dt
                p.y += p.vy * dt
            self._collide(p, bones, radius_of, hits, on_impulse)
            self._borders(p)
            if math.hypot(p.vx, p.vy) < 40:
                p.still += dt
            else:
                p.still = 0.0
            if p.spec.transient and (p.age > TRANSIENT_LIFE or p.still > 1.5):
                gone.append(p)
                self.last_hit = {k: v for k, v in self.last_hit.items()
                                 if not k.startswith(str(p.serial) + "|")}
        for p in gone:
            self.live.remove(p)
        return hits

    def _borders(self, p):
        r = p.spec.radius
        if p.y + r >= self.floor_y:
            p.y = self.floor_y - r
            if p.vy > 0:
                p.vy = -p.vy * RESTITUTION
            if abs(p.vy) < 30:
                p.vy = 0.0
            p.on_floor = True
        else:
            p.on_floor = False
        if p.x - r < 0:
            p.x = r
            p.vx = abs(p.vx) * RESTITUTION
        elif p.x + r > self.world_width:
            p.x = self.world_width - r
            p.vx = -abs(p.vx) * RESTITUTION

    def _collide(self, p, bones, radius_of, hits, on_impulse):
        speed = math.hypot(p.vx, p.vy)
        for name, a, b in bones:
            c = closest_on_segment((p.x, p.y), a, b)
            gap = p.spec.radius + radius_of(name)
            ox, oy = p.x - c[0], p.y - c[1]
            d = math.hypot(ox, oy)
            if d >= gap:
                continue
            if d < 1e-3:
                nx, ny = 0.0, -1.0
            else:
                nx, ny = ox / d, oy / d
            if not p.held:
                p.x, p.y = c[0] + nx * gap, c[1] + ny * gap
                into = p.vx * nx + p.vy * ny
                if into < 0:
                    p.vx -= nx * into * (1 + RESTITUTION)
                    p.vy -= ny * into * (1 + RESTITUTION)
                if speed > IMPULSE_FLOOR:
                    self.impulses.append((name, (-nx, -ny), speed * p.spec.force * IMPULSE_GAIN))
            key = str(p.serial) + "|" + name
            last = self.last_hit.get(key)
            if last is None or self.clock - last >= CONTACT_PERIOD:
                self.last_hit[key] = self.clock
                hits.append((p, name, max(speed, MIN_HIT) * p.spec.force))
            speed = 0.0


def closest_on_segment(p, a, b):
    abx, aby = b[0] - a[0], b[1] - a[1]
    l2 = abx * abx + aby * aby
    if l2 < 1e-6:
        return a
    t = max(0.0, min(1.0, ((p[0] - a[0]) * abx + (p[1] - a[1]) * aby) / l2))
    return (a[0] + abx * t, a[1] + aby * t)


BONES = [("torso", (500.0, 500.0), (500.0, 900.0))]


def radius_of(name):
    return 40.0


def main():
    print("closest point on a bone")
    report("above the middle", closest_on_segment((560, 700), (500, 500), (500, 900)) == (500.0, 700.0))
    report("past the top clamps to the head",
           closest_on_segment((560, 400), (500, 500), (500, 900)) == (500.0, 500.0))
    report("past the bottom clamps to the tip",
           closest_on_segment((560, 1000), (500, 500), (500, 900)) == (500.0, 900.0))
    report("a zero-length bone is its head",
           closest_on_segment((560, 700), (500, 500), (500, 500)) == (500.0, 500.0))

    print("\npushing out of the character")
    w = World(2000.0, 3000.0)
    s = Spec("hammer", radius=60.0)
    p = w.spawn(s, (560.0, 700.0))          # 60px from the bone centre line
    p.vx = -800.0
    w.step(1 / 60, 2400.0, BONES, radius_of, lambda *a: None)
    gap = math.dist((p.x, p.y), (500.0, p.y))
    report("it ends up exactly one gap away", abs(gap - 100.0) < 0.51, "%.2f px" % gap)
    report("and it is no longer inside", p.x - 500.0 >= 100.0 - 0.51)
    report("it has been pushed back out, not through", p.vx >= -0.001, "vx=%.1f" % p.vx)
    report("a hit was reported", len(w.impulses) == 1)
    # The prop was to the right of the bone and travelling left, so the figure is shoved
    # left -- away from the prop, which is the direction the prop was already going.
    report("with a shove in the direction the prop was travelling",
           w.impulses[0][1][0] < 0 and w.impulses[0][2] > 0, str(w.impulses[0]))

    print("\na held prop is not shoved off")
    w = World(2000.0, 3000.0)
    p = w.spawn(Spec("brush"), (560.0, 700.0))
    p.begin_drag()
    p.vx, p.vy = -900.0, 0.0
    w.step(1 / 60, 2400.0, BONES, radius_of, lambda *a: None)
    report("it stays where the finger put it", abs(p.x - 560.0) < 1e-6)
    report("and nothing shoves the character", w.impulses == [])

    print("\ncontact is throttled, because contact is continuous")
    w = World(2000.0, 3000.0)
    p = w.spawn(Spec("brush"), (560.0, 700.0))
    p.begin_drag()
    total = 0
    for i in range(30):                      # half a second of "resting on the body"
        w.step(1 / 60, 2400.0, BONES, radius_of, lambda *a: None)
        total += 1 if i == 0 else 0
    hits = []
    for i in range(30):
        hits += w.step(1 / 60, 2400.0, BONES, radius_of, lambda *a: None)
    report("half a second of contact is two events, not thirty",
           len(hits) == 2, str(len(hits)))

    print("\nfloor and walls")
    w = World(2000.0, 3000.0)
    p = w.spawn(Spec("ball", radius=60.0), (1500.0, 1000.0), (0.0, 3000.0))
    for _ in range(200):
        w.step(1 / 60, 2400.0, [], radius_of, lambda *a: None)
    report("it comes to rest on the floor, not in it",
           abs(p.y - (2000.0 - 60.0)) < 0.5, "y=%.2f" % p.y)
    report("and it stopped moving", math.hypot(p.vx, p.vy) < 1.0)
    p.x, p.vx = -50.0, -400.0
    w.step(1 / 60, 2400.0, [], radius_of, lambda *a: None)
    report("a prop cannot leave through the left wall", p.x >= 60.0 - 1e-6, "x=%.2f" % p.x)

    print("\ntransient props clean themselves up")
    w = World(2000.0, 3000.0)
    b = w.spawn(Spec("gun").bullet(), (1500.0, 1990.0), (0.0, 0.0))
    for _ in range(200):
        w.step(1 / 60, 2400.0, [], radius_of, lambda *a: None)
    report("a spent bullet is collected", w.live == [])
    w = World(2000.0, 3000.0)
    k = w.spawn(Spec("hammer", transient=False), (1500.0, 1990.0))
    for _ in range(600):
        w.step(1 / 60, 2400.0, [], radius_of, lambda *a: None)
    report("a placed prop is not", len(w.live) == 1)

    print("\ngrabbing")
    w = World(2000.0, 3000.0)
    near = w.spawn(Spec("hammer", radius=60.0), (1000.0, 1000.0))
    report("a finger on it grabs it", w.grab_at((1020.0, 1030.0)) is near)
    report("a finger far away does not", w.grab_at((1400.0, 1000.0)) is None)
    report("a finger just outside the edge still can",
           w.grab_at((1000.0 + 60.0 + 30.0, 1000.0)) is near)

    print("\nthrowing keeps the drag speed")
    p = Prop(1, Spec("hammer"), 100.0, 100.0)
    p.begin_drag()
    for i in range(10):
        p.drag_to((100.0 + i * 12.0, 100.0), 1 / 60)
    report("a flick leaves with the right speed",
           abs(math.hypot(p.vx, p.vy) - 720.0) < 1.0, "%.1f" % math.hypot(p.vx, p.vy))
    q = Prop(2, Spec("hammer"), 100.0, 100.0)
    q.begin_drag()
    q.drag_to((100.5, 100.0), 1 / 60)
    report("a slow placement leaves it still", math.hypot(q.vx, q.vy) < 40.0)

    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        for f in FAILURES:
            print("  " + f)
        return 1
    print("all prop tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
