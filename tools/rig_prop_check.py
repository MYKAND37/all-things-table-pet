
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
        # 钉住的道具：世界一个像素都不动它，连重力也不算。见 PropKind.isPointed。
        self.planted = False
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
            # 钉子是用点的拔出来的，不是拖出来的：拖动一个钉住的道具只能意味着
            # 「把被它钉住的东西一起拖走」，而对一根绳子来说那东西根本不存在。
            if p.planted:
                continue
            d = math.dist((p.x, p.y), point) - p.spec.radius
            if d < best_d:
                best_d, best = d, p
        return best

    def step(self, dt, gravity, bones, radius_of, on_impulse):
        self.clock += dt
        hits, gone = [], []
        for p in self.live:
            p.age += dt
            # 钉住的道具整个跳过：没有重力、没有接触、没有边界。年龄照样走，
            # 因为 TRANSIENT_LIFE 是按年龄算的。
            if p.planted:
                continue
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
        # 道具对道具放在最后：邻居把它推进去的东西，不该由骨头和地面来收尾。
        self._separate()
        return hits

    def _borders(self, p):
        r = p.spec.radius
        if p.y + r >= self.floor_y:
            p.y = self.floor_y - r
            if p.vy > 0:
                p.vy = -p.vy * RESTITUTION
            # 和 Kotlin 的 borders 逐字对应：趴在地上时每帧再吃掉一点横向速度。
            # 少了这一行，一个在地上滑的道具在镜像里永远停不下来，而在 app 里会停 ——
            # 这种漂移比没有镜像更糟，因为它还会让 PropWorld.separate 里那次「只为真正
            # 动过的道具再跑一次边界」的防护看起来是好的：镜像根本收不到那笔摩擦，
            # 重复收两次也测不出来。
            p.vx *= (1.0 - 4.0 * 0.016)
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
        # 还有一处**故意不镜像**的：Kotlin 落地时会 `p.spin *= 0.7f`。这里的 Prop 根本没有
        # spin 这个字段（道具的旋转在这个镜像里从来没被建模过），所以这不是漂了，是没建模。
        # 写在这里，是因为一句不说地少一个行为，和漂移看起来一模一样。

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

    def _separate(self):
        """道具对道具：圆对圆。重叠对半分，除非其中一个被手指按着。

        对半分是个决定，不是默认值。道具的「重量」按设计不在 PropSpec 里
        （"how heavy it is ... is the rule set's business"），所以规格能支撑的分法
        只有对半。从 force 里读一个质量出来，等于凭空发明一个编辑器从没写过、
        规则也够不着的数；锤子和垫子必须是同一个道具配不同规则，这里也得成立。
        """
        # 用 set 不用 list：一个道具同一帧可能卷进三场碰撞，边界仍然只能为它跑一次。
        # 跑两次就是收两次静置摩擦。
        moved = set()
        for i in range(len(self.live)):
            a = self.live[i]
            for j in range(i + 1, len(self.live)):
                b = self.live[j]
                gap = a.spec.radius + b.spec.radius
                ox, oy = b.x - a.x, b.y - a.y
                d = math.hypot(ox, oy)
                if d >= gap:
                    continue
                if d < 1e-3:
                    nx, ny = 0.0, -1.0
                else:
                    nx, ny = ox / d, oy / d
                overlap = gap - d

                # 两根手指把两个道具按在一起：谁也不让。
                if a.held and b.held:
                    continue
                # 钉住的道具同样不让：有别的东西把它按住了，和手指按住是一个道理。
                if a.planted and b.planted:
                    continue
                share_a = 0.0 if a.held or a.planted else (1.0 if b.held or b.planted else 0.5)
                if share_a > 0:
                    a.x -= nx * overlap * share_a
                    a.y -= ny * overlap * share_a
                    moved.add(a)
                if share_a < 1:
                    b.x += nx * overlap * (1 - share_a)
                    b.y += ny * overlap * (1 - share_a)
                    moved.add(b)

                # 只有「正在靠近」的一对才反弹。两个靠在一起的道具并没有在靠近，
                # 每帧都把它们弹开就会抖 —— 和地面那一遍把静止的骨头转两次是同一个错。
                closing = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny
                if closing >= 0:
                    continue
                kick = (1 + RESTITUTION) * closing
                if share_a > 0:
                    a.vx += nx * kick * share_a
                    a.vy += ny * kick * share_a
                if share_a < 1:
                    b.vx -= nx * kick * (1 - share_a)
                    b.vy -= ny * kick * (1 - share_a)

        # 被推开的道具可能被推进地面或墙里，所以边界要最后说话 —— 但只对真的动过的那些。
        # _borders 顺带施加静置摩擦，对没动过的道具再跑一遍就是在一帧里收两次摩擦。
        for p in moved:
            self._borders(p)


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

    print("\na prop sliding along the floor slows down")
    # 镜像漏掉这条摩擦的时候，一个在地上的道具会永远滑下去，而 app 里早就停了。
    # 没有摩擦的话 vx 会一动不动地停在 600 —— 这条测试就是拿来盯住那一行的。
    w = World(2000.0, 3000.0)
    slid = w.spawn(Spec("a", radius=60.0), (300.0, 1940.0), (600.0, 0.0))
    for _ in range(60):
        w.step(1 / 60, 0.0, [], radius_of, lambda *a: None)
    report("a second on the floor takes most of the sideways speed away",
           slid.vx < 600.0 * 0.2, "vx=%.1f after a second, from 600" % slid.vx)

    print("\nprops against each other")
    noop = lambda *a: None

    w = World(2000.0, 3000.0)
    a = w.spawn(Spec("a", radius=60.0), (1000.0, 1000.0))
    b = w.spawn(Spec("b", radius=60.0), (1080.0, 1000.0))
    w.step(1 / 60, 0.0, [], radius_of, noop)
    gap = math.hypot(b.x - a.x, b.y - a.y)
    report("two props stop overlapping", abs(gap - 120.0) < 1e-6, "gap=%.4f" % gap)
    report("and they share the separation evenly",
           abs((a.x - 1000.0) - (1080.0 - b.x)) < 1e-6,
           "a moved %.4f, b moved %+.4f" % (a.x - 1000.0, b.x - 1080.0))

    w = World(2000.0, 3000.0)
    a = w.spawn(Spec("a", radius=60.0), (1000.0, 1000.0))
    b = w.spawn(Spec("b", radius=60.0), (1080.0, 1000.0))
    a.begin_drag()
    w.step(1 / 60, 0.0, [], radius_of, noop)
    report("a held prop does not give", abs(a.x - 1000.0) < 1e-6, "x=%.4f" % a.x)
    report("the other one takes the whole separation", abs(b.x - 1120.0) < 1e-6, "x=%.4f" % b.x)

    w = World(2000.0, 3000.0)
    a = w.spawn(Spec("a", radius=60.0), (1000.0, 1000.0))
    b = w.spawn(Spec("b", radius=60.0), (1080.0, 1000.0))
    a.begin_drag()
    b.begin_drag()
    w.step(1 / 60, 0.0, [], radius_of, noop)
    report("two fingers pressing two props together move neither",
           a.x == 1000.0 and b.x == 1080.0, "a=%.1f b=%.1f" % (a.x, b.x))

    # 钉住的道具：世界不动它，手指也抓不动它 —— 钉子是用点拔出来的。
    print("\na planted prop is not the world's business")
    w = World(2000.0, 3000.0)
    nailed = w.spawn(Spec("nail", kind="pin", radius=40.0), (1000.0, 500.0))
    nailed.planted = True
    loose = w.spawn(Spec("hammer", radius=60.0), (1000.0, 860.0))
    for _ in range(120):
        w.step(1 / 60, 1200.0, [], radius_of, noop)
    report("a planted prop does not fall", nailed.y == 500.0, "y=%.4f" % nailed.y)
    report("and one that is not planted does", loose.y > 860.0, "y=%.1f" % loose.y)
    report("a planted prop cannot be grabbed", w.grab_at((1000.0, 500.0)) is not loose)

    # 钉住的钉子挡在锤子前面：锤子整份让开，钉子一个像素都不动。
    w = World(2000.0, 3000.0)
    peg = w.spawn(Spec("nail", kind="pin", radius=40.0), (1000.0, 1000.0))
    peg.planted = True
    hit = w.spawn(Spec("hammer", radius=60.0), (1080.0, 1000.0))
    w.step(1 / 60, 0.0, [], radius_of, noop)
    report("a planted prop does not give way", peg.x == 1000.0, "x=%.4f" % peg.x)
    report("so the other one takes the whole separation",
           abs(hit.x - 1100.0) < 1e-6, "x=%.4f" % hit.x)

    # 两根都钉住：谁也不让，重叠就留在那里 —— 两个桩之间不该互相推。
    w = World(2000.0, 3000.0)
    p1 = w.spawn(Spec("a", kind="pin", radius=40.0), (1000.0, 1000.0))
    p2 = w.spawn(Spec("b", kind="pin", radius=40.0), (1050.0, 1000.0))
    p1.planted = True
    p2.planted = True
    w.step(1 / 60, 0.0, [], radius_of, noop)
    report("two planted props move neither", p1.x == 1000.0 and p2.x == 1050.0,
           "a=%.1f b=%.1f" % (p1.x, p2.x))

    # 一个飞过来的撞上一个静止的：相对法向速度按 RESTITUTION 反向。
    w = World(2000.0, 3000.0)
    a = w.spawn(Spec("a", radius=60.0), (1000.0, 1000.0))
    b = w.spawn(Spec("b", radius=60.0), (1100.0, 1000.0), (-600.0, 0.0))
    w.step(1 / 60, 0.0, [], radius_of, noop)
    report("a closing pair bounces",
           abs((b.vx - a.vx) - RESTITUTION * 600.0) < 1e-6, "%.2f px/s apart" % (b.vx - a.vx))
    report("and the one at rest is the one that gets shoved", a.vx < -1.0, "a.vx=%.2f" % a.vx)

    # 静止的一对：分开一次就该停住，不能每帧互相弹。
    w = World(2000.0, 3000.0)
    a = w.spawn(Spec("a", radius=60.0), (1000.0, 1000.0))
    b = w.spawn(Spec("b", radius=60.0), (1100.0, 1000.0))
    for _ in range(120):
        w.step(1 / 60, 0.0, [], radius_of, noop)
    report("a resting pair separates once and then stays put",
           abs((b.x - a.x) - 120.0) < 1e-6 and a.vx == 0.0 and b.vx == 0.0,
           "gap=%.4f a.vx=%.4f b.vx=%.4f" % (b.x - a.x, a.vx, b.vx))

    # 被邻居推进墙里：边界要最后说话。
    w = World(2000.0, 3000.0)
    a = w.spawn(Spec("a", radius=60.0), (60.0, 1000.0))
    b = w.spawn(Spec("b", radius=60.0), (140.0, 1000.0))
    w.step(1 / 60, 0.0, [], radius_of, noop)
    report("a prop shoved at a wall is put back inside it",
           a.x >= 60.0 - 1e-6, "x=%.4f" % a.x)

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
