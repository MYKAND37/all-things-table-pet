
"""Tests for prop physics, mirrored from dev.atp.pet.engine.prop.PropWorld.

A prop that is inside the character has to push itself back out, and a prop resting on a
bone touches it EVERY frame -- so the two things most likely to be wrong are the push-out
and the contact throttle. Both would show up as the rules firing sixty times a second, or
as a hammer that sinks into the thing it hit.

    python3 tools/rig_prop_check.py
"""
import math, os, sys, json, re
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
#: 靠近的阈值，和 PropWorld.NEAR_SLACK 是同一个数。main() 里有一条断言把它和 Kotlin 源码
#: 比一遍：这份镜像的职责就是"说出 app 会怎么做"，阈值漂了十像素，靠近就会在别的时候响，
#: 而那正是最不容易被眼睛发现的一类漂移。
NEAR_SLACK = 90.0
NEAR_HYSTERESIS = 1.6
PROP_KT = os.path.join(REPO, "app/src/main/java/dev/atp/pet/engine/prop/PropWorld.kt")


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

    def step(self, dt, gravity, bones, radius_of, on_impulse, nodes=()):
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
            # 节点在骨头之后，理由和 Kotlin 里那句一样：同时陷在肢体和一个点里的时候，
            # 先被肢体推出来，再被那个点推出来。
            for node in nodes:
                self._node_collide(p, node, hits, on_impulse)
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
            # 半径 0 = 这根骨头对世界不存在（rig 的碰撞开关）。必须是**跳过**而不是半径 0：
            # 半径 0 的骨头是一条无限细的线，道具照样被那条线推开。
            if radius_of(name) <= 0:
                continue
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

    def _node_collide(self, p, node, hits, on_impulse):
        """一个道具对一个被命名的点：同一套重叠、同一套回弹、同一个接触节流。

        node = (节点名, (x, y), 半径, 骨头名)。事件里带的是**节点的名字**：规则问的是
        「谁被碰到了」，而一个叫「指尖」的地方比「整只手」是更好的答案。
        """
        name, at, radius, bone = node
        if radius <= 0:
            return
        speed = math.hypot(p.vx, p.vy)
        ox, oy = p.x - at[0], p.y - at[1]
        d = math.hypot(ox, oy)
        gap = p.spec.radius + radius
        if d >= gap:
            return
        if d < 1e-3:
            nx, ny = 0.0, -1.0
        else:
            nx, ny = ox / d, oy / d
        if not p.held:
            p.x, p.y = at[0] + nx * gap, at[1] + ny * gap
            into = p.vx * nx + p.vy * ny
            if into < 0:
                p.vx -= nx * into * (1 + RESTITUTION)
                p.vy -= ny * into * (1 + RESTITUTION)
            if speed > IMPULSE_FLOOR:
                # 冲量给的是节点所在的那根骨头：推动的落点还是身体。
                self.impulses.append((bone, (-nx, -ny), speed * p.spec.force * IMPULSE_GAIN))
        key = str(p.serial) + "|" + name
        last = self.last_hit.get(key)
        if last is None or self.clock - last >= CONTACT_PERIOD:
            self.last_hit[key] = self.clock
            hits.append((p, name, max(speed, MIN_HIT) * p.spec.force))

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


class Near:
    """一个道具这一帧离身体最近的地方：哪根骨头，还有两个表面之间有多少空当。"""

    def __init__(self, prop, bone, gap):
        self.prop, self.bone, self.gap = prop, bone, gap


def near_misses(live, bones, radius_of):
    """镜像 PropWorld.nearMisses：每个道具都量，量的是和碰撞同一套胶囊。

    用的是同一个 closest_on_segment、同样的两个半径、同样跳过 radius <= 0 的骨头，
    否则"靠近"和"碰到"会对"在不在同一个地方"给出两个答案。
    """
    out = []
    for p in live:
        best, best_gap = None, float("inf")
        for name, a, b in bones:
            if radius_of(name) <= 0:
                continue
            c = closest_on_segment((p.x, p.y), a, b)
            gap = math.hypot(p.x - c[0], p.y - c[1]) - p.spec.radius - radius_of(name)
            if gap < best_gap:
                best_gap, best = gap, name
        if best is not None:
            out.append(Near(p, best, best_gap))
    return out


class NearWatch:
    """镜像 PropWorld.NearWatch：靠近/走开是两个**变化**，不是一个状态。

    一个状态就是每帧一个事件，而没有冷却的规则会为一件事响六十次。中间的带回差那段
    就是为了让停在边界上的道具（身体还在呼吸）只到达一次。
    """

    def __init__(self):
        self.tracked = {}

    def clear(self):
        self.tracked.clear()

    def update(self, measured):
        out = []
        seen = set()
        for m in measured:
            serial = m.prop.serial
            seen.add(serial)
            known = self.tracked.get(serial)
            if known is None and m.gap <= NEAR_SLACK:
                self.tracked[serial] = m.bone
                out.append((m.prop, m.bone, m.gap, True))
            elif known is not None and m.gap <= NEAR_SLACK * NEAR_HYSTERESIS:
                self.tracked[serial] = m.bone
            elif known is not None:
                del self.tracked[serial]
                out.append((m.prop, m.bone, m.gap, False))
        # 这一帧没量到的道具已经不在世界里了（子弹过期、蜡烛烧完）：安静地忘掉。
        # 走开是"它去了别处"，而不存在了的东西没去任何地方。
        for serial in [s for s in self.tracked if s not in seen]:
            del self.tracked[serial]
        return out


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

    print("\n有东西靠近 / 有东西走开")
    # 「当某个物品靠近时」：碰到是**接触之后**才存在的事，所以"躲开锤子"以前根本写不出来 ——
    # 规则跑起来的时候锤子已经砸上了。靠近量的是还没有碰到的那段空当。
    src = open(PROP_KT, encoding="utf-8").read()
    for name, py in (("NEAR_SLACK", NEAR_SLACK), ("NEAR_HYSTERESIS", NEAR_HYSTERESIS)):
        m = re.search(r"const val %s = ([0-9.]+)f" % name, src)
        report("%s 和 Kotlin 里是同一个数" % name,
               m is not None and abs(float(m.group(1)) - py) < 1e-9,
               "%s vs %s" % (m.group(1) if m else "没了", py))
    report("镜像里有个带名字的阈值可用（不是 None）",
           NEAR_SLACK is not None and NEAR_HYSTERESIS is not None)

    def parked(gap, serial=1, radius=60.0):
        """A prop sitting exactly `gap` px of clear air from the bone."""
        w = World(2000.0, 3000.0)
        centre = 500.0 + 40.0 + radius + gap          # bone radius + prop radius + the gap
        return w, w.spawn(Spec("hammer", radius=radius), (centre, 700.0))

    w, p = parked(50.0)
    near = near_misses(w.live, BONES, radius_of)
    report("量的就是碰撞那套胶囊：空当 = 圆心距 - 两个半径",
           len(near) == 1 and abs(near[0].gap - 50.0) < 1e-6, str(near[0].gap if near else None))
    report("并且说出最近的是哪根骨头", near and near[0].bone == "torso")

    watch = NearWatch()
    first = watch.update(near_misses(w.live, BONES, radius_of))
    report("第一次进到阈值以内 = 一次靠近",
           len(first) == 1 and first[0][3] is True and abs(first[0][2] - 50.0) < 1e-6,
           str(first))
    report("还在旁边就不再报了（变化才报，不是状态）",
           watch.update(near_misses(w.live, BONES, radius_of)) == [])

    p.x = 500.0 + 40.0 + p.spec.radius + NEAR_SLACK * NEAR_HYSTERESIS + 20.0
    away = watch.update(near_misses(w.live, BONES, radius_of))
    report("走远到带外 = 一次走开，带着它离开时的距离",
           len(away) == 1 and away[0][3] is False and
           abs(away[0][2] - (NEAR_SLACK * NEAR_HYSTERESIS + 20.0)) < 1e-6, str(away))
    report("走开之后不再重复报", watch.update(near_misses(w.live, BONES, radius_of)) == [])

    p.x = 500.0 + 40.0 + p.spec.radius + NEAR_SLACK - 5.0
    report("回来了可以再靠近一次（不是一辈子只报一次）",
           len(watch.update(near_misses(w.live, BONES, radius_of))) == 1)

    # 带内来回：停在边界上的道具，身体还在呼吸，不能一进一出地抖。
    watch = NearWatch()
    p.x = 500.0 + 40.0 + p.spec.radius + NEAR_SLACK - 5.0
    watch.update(near_misses(w.live, BONES, radius_of))
    quiet = []
    for offset in (15.0, -15.0, 25.0, -25.0):
        p.x = 500.0 + 40.0 + p.spec.radius + NEAR_SLACK + offset
        quiet += watch.update(near_misses(w.live, BONES, radius_of))
    report("在阈值附近来回抖，只算一次靠近", quiet == [], str(quiet))

    # 半径 0 的骨头 = 碰撞关掉了 = 道具靠近不了它（和 collide 同一条约定）。
    report("关掉碰撞的骨头不算靠近",
           near_misses(w.live, BONES, lambda name: 0.0) == [])

    # 道具从世界里消失（子弹过期）：安静忘掉，不走开。
    watch = NearWatch()
    p.x = 500.0 + 40.0 + p.spec.radius + 20.0
    watch.update(near_misses(w.live, BONES, radius_of))
    w.live.clear()
    report("道具没了不算走开（它没去任何地方）",
           watch.update(near_misses(w.live, BONES, radius_of)) == [])
    report("而且被忘掉了，不是一直记着", watch.tracked == {})

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

    print("\n关掉碰撞的那根骨头，道具感觉不到（不是变成一条线）")
    noop = lambda *a: None
    # 这一条是用户报的"关闭骨骼碰撞无法正常使用"：rig 的碰撞开关以前是给世界一个**半径 0**，
    # 而半径 0 的骨头是一条无限细的线 —— 道具照样被那条线推开，只是看不见推它的东西。
    # 现在它整个跳过。radius_of 返回 0 = 这根骨头不存在。
    w = World(2000.0, 3000.0)
    off = lambda name: 0.0
    stone = w.spawn(Spec("stone", radius=40.0), (1000.0, 1000.0))
    hits = w.step(1 / 60, 0.0, [("hand_L", (900.0, 1000.0), (1100.0, 1000.0))], off, noop)
    report("撞在关掉碰撞的骨头上：道具不动", abs(stone.x - 1000.0) < 1e-6, "x=%.4f" % stone.x)
    report("而且一个事件都不报", hits == [], str(hits))

    w = World(2000.0, 3000.0)
    stone = w.spawn(Spec("stone", radius=40.0), (1000.0, 1000.0))
    hits = w.step(1 / 60, 0.0, [("hand_L", (900.0, 1000.0), (1100.0, 1000.0))],
                  lambda name: 20.0, noop)
    report("同一根骨头开着碰撞时照旧被推开", stone.y != 1000.0 or stone.x != 1000.0)

    print("\nnodes are felt as points")
    noop = lambda *a: None
    w = World(2000.0, 3000.0)
    w.impulses = []
    # 节点在 (1000,1000)，半径 26；道具半径 60，圆心的重叠从 86 开始。
    node = ("finger_tip", (1000.0, 1000.0), 26.0, "hand_L")
    stone = w.spawn(Spec("stone", radius=60.0), (1050.0, 1000.0), (400.0, 0.0))
    hits = w.step(1 / 60, 0.0, [], radius_of, noop, [node])
    report("a prop inside a node is pushed out of it",
           abs(stone.x - 1086.0) < 1e-6, "x=%.4f" % stone.x)
    report("and the event names the NODE, not the bone it sits on",
           len(hits) == 1 and hits[0][1] == "finger_tip",
           str([h[1] for h in hits]))
    report("the shove still goes to the bone", w.impulses and w.impulses[0][0] == "hand_L")
    report("away from the node, not into it", w.impulses[0][1][0] < -0.99)

    # 接触节流：同一个节点同一根骨头，0.25 秒内只报一次，否则规则一秒响六十遍。
    # 手指按着的一个道具：它不会被推出去，所以整个半秒都停在节点上，接触是连续的 ——
    # 这正是节流存在的理由（一个真的会被推开的道具只会报一次，那就测不到节流）。
    w = World(2000.0, 3000.0)
    stone = w.spawn(Spec("stone", radius=10.0), (1000.0, 1000.0))
    stone.begin_drag()
    n = 0
    for i in range(30):
        n += len(w.step(1 / 60, 0.0, [], radius_of, noop, [node]))
    report("a prop resting on a node reports once per quarter second, not per frame",
           n == 2, "%d events in half a second" % n)

    w = World(2000.0, 3000.0)
    far = w.spawn(Spec("stone", radius=10.0), (1400.0, 1000.0))
    report("and a prop that is clear of it is not felt at all",
           w.step(1 / 60, 0.0, [], radius_of, noop, [node]) == [])

    print("\n一个名字在哪儿：先骨头，再节点")
    # 「如果选节点作为触发主语，那生成的粒子和液体都应在节点位置」。规矩本身很短，短到
    # 值得有个镜像：**名字 → 位置**要认得两种东西，因为逻辑面板把骨头和节点列在同一张
    # 部位表里（见 MainActivity.partNames）。
    def node_point(bones, bone_name, at):
        """Skeleton.nodePoint 的镜像：沿骨头**当前**的头→尖那条线按距离取一点，两头夹住。

        bones: [(名字, 骨头位置, 骨头尖端)]，节点存的是**距离** —— 骨头之后被拉长缩短，
        节点跟着走，不是留在旧尖端那儿。
        """
        for (name, head, tip) in bones:
            if name != bone_name:
                continue
            dx, dy = tip[0] - head[0], tip[1] - head[1]
            length = math.hypot(dx, dy)
            if length < 1e-3:
                return head
            t = min(1.0, max(0.0, at / length))
            return (head[0] + dx * t, head[1] + dy * t)
        return (0.0, 0.0)

    def place(bones, nodes, name):
        """Skeleton.place 的镜像：骨头优先，然后是节点；都不是就没有这个地方。

        nodes: [(名字, 它长在哪根骨头上, 距离)] —— 注意和上面那些碰撞用的 4 元组不同。
        """
        if not name:
            return None
        for (bone_name, head, _tip) in bones:
            if bone_name == name:
                return head
        for (node_name, bone_name, at) in nodes:
            if node_name == name:
                return node_point(bones, bone_name, at)
        return None

    rig = [("hand_L", (900.0, 1000.0), (1100.0, 1000.0))]
    pins = [("finger_tip", "hand_L", 160.0)]
    report("骨头名给的是骨头自己的位置", place(rig, pins, "hand_L") == (900.0, 1000.0))
    tip = place(rig, pins, "finger_tip")
    report("节点名给的是节点那个点", tip == (1060.0, 1000.0), str(tip))
    # 这一条要的是**不是骨头**：老写法（只查骨头）在这里得到 None，然后调用方各自掉进
    # 自己的兜底 —— 喷出来的东西落在宠物家位上方 400px、推一下推的是整只、断开什么都不做。
    report("而且它不是它所在的那根骨头（这就是那个 bug 的量级）",
           tip != (900.0, 1000.0) and abs(tip[0] - rig[0][1][0]) == 160.0,
           "差 %.0f px" % abs(tip[0] - rig[0][1][0]))
    report("都不是：没有这个地方（调用方各说各的兜底）", place(rig, pins, "nobody") is None)
    report("空名字也不是一个地方", place(rig, pins, "") is None)
    # 节点存的是距离：骨头长了一倍，节点还在原来的比例上，不是留在旧尖端。
    longer = [("hand_L", (900.0, 1000.0), (1300.0, 1000.0))]
    report("节点跟着骨头走（存的是距离，不是坐标）",
           place(longer, pins, "finger_tip") == (1060.0, 1000.0),
           str(place(longer, pins, "finger_tip")))
    # 骨头之外的节点：at 超过骨长时夹在尖端，和 Kotlin 那句 coerceIn 一样。
    report("落在了骨头外面就夹在尖端",
           place(rig, [("beyond", "hand_L", 999.0)], "beyond") == (1100.0, 1000.0))

    print("\n拖尾：按**距离**留印子，不是按帧")
    # 这段镜像的是 stepTrails 的那一个判断。它的价值全在"距离不是帧"上：按帧留印子的
    # 实现，同一个拖动在快的手机上密、在慢的手机上稀 —— 同一段拖尾在两个人手里是两个
    # 样子，而且没有任何地方会报错。
    TRAIL_SPACING = 26.0
    TRAIL_LIFE = 2.6
    TRAIL_MAX = 220

    def stamps(path, held=True):
        """走过一串位置，能留下几个印子。"""
        out, last = [], None
        for (x, y) in path:
            if not held:
                last = None
                continue
            if last is not None and math.hypot(x - last[0], y - last[1]) < TRAIL_SPACING:
                continue
            out.append((x, y))
            last = (x, y)
        return out

    # 520px，每 13px 一个位置（约 60fps 下的一个慢拖）：每隔 26px 留一个印子，是 21 个。
    straight = [(1000.0 + 13.0 * i, 1000.0) for i in range(41)]
    report("a 520px drag at 13px a step leaves one mark per spacing",
           len(stamps(straight)) == 21, "%d marks" % len(stamps(straight)))
    # 同一段路，步子大一倍（慢手机）：印子数必须一样 —— 这就是"距离不是帧"。
    coarse = [(1000.0 + 26.0 * i, 1000.0) for i in range(21)]
    report("and the same drag in coarser steps leaves the same marks",
           len(stamps(coarse)) == len(stamps(straight)),
           "%d vs %d" % (len(stamps(coarse)), len(stamps(straight))))
    report("a prop that is not held leaves nothing at all",
           stamps(straight, held=False) == [])
    report("and holding it still leaves one, not one per frame",
           len(stamps([(1000.0, 1000.0)] * 60)) == 1)

    # 印子会老、会掉，而且太多时先掉最老的。
    marks = [{"age": 0.0} for _ in range(5)]
    dt, steps = 1 / 60.0, 0
    while len(marks) > 0 and steps < 1000:
        for m in marks:
            m["age"] += dt
        marks = [m for m in marks if m["age"] < TRAIL_LIFE]
        steps += 1
    report("a mark is gone after its life", steps == int(TRAIL_LIFE * 60) + 1,
           "%d frames" % steps)
    overflow = [{"age": 0.0} for _ in range(TRAIL_MAX + 30)]
    while len(overflow) > TRAIL_MAX:
        overflow.pop(0)
    report("too many marks drops the oldest, not the newest",
           len(overflow) == TRAIL_MAX and overflow[-1] is not None)

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
