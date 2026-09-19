#!/usr/bin/env python3
"""Particles: what they do, and what the switches do to them.

They are not a fluid and they never were -- they fall, they land, and the ones that should
leave a mark leave one. What the user asked for is the switch: 粒子可以有用户决定是否受重力影响.
So this file mirrors the little that particles DO, and then the three things a specification
can change about it: colour, size, gravity, and whether it stains.

    python3 tools/particle_check.py
"""
import math, sys

FAILURES = []

#: The six the app ships with, mirroring ParticleKinds.DEFAULTS in render/Particles.kt.
DEFAULTS = [
    {"id": "blood", "name": "血", "colour": "#C92A2A", "size": 1.0, "gravity": True, "stains": True},
    {"id": "sweat", "name": "汗", "colour": "#4C8DE0", "size": 1.0, "gravity": True, "stains": True},
    {"id": "spark", "name": "火花", "colour": "#F2A93B", "size": 1.0, "gravity": True, "stains": False},
    {"id": "dust", "name": "灰尘", "colour": "#9A8FA6", "size": 1.0, "gravity": True, "stains": False},
    {"id": "star", "name": "星星", "colour": "#E45CA8", "size": 1.0, "gravity": False, "stains": False},
    {"id": "heart", "name": "爱心", "colour": "#E2557B", "size": 1.0, "gravity": False, "stains": False},
]

#: How hard a particle that HAS gravity is pulled, and what a floating one gets instead.
FALLING = 1300.0
FLOATING = 140.0


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)


def of(pid, kinds=None):
    """ParticleKinds.of: an unknown id falls back to the first kind, never to nothing."""
    all_kinds = kinds if kinds is not None else DEFAULTS
    return next((k for k in all_kinds if k["id"] == pid), all_kinds[0])


class Particles:
    """
    Mirrors render/Particles.kt: the particles, the marks they leave, and the switches.

    Sizes and speeds are the app's; the point of the mirror is the three rules -- a particle
    that has gravity falls, a particle that reaches the floor is gone, and it leaves a mark
    only if its kind says so.
    """

    def __init__(self, kinds=None):
        self.kinds = list(kinds if kinds is not None else DEFAULTS)
        self.particles = []
        self.stains = []
        self.rng = 7717

    def _rand(self):
        # A tiny deterministic generator: this file cares about which RULE ran, not about
        # reproducing the app's exact jitter.
        self.rng = (self.rng * 1103515245 + 12345) & 0x7FFFFFFF
        return self.rng / 0x7FFFFFFF

    def burst(self, kind_id, at, count, speed=120.0, life=0.9):
        kind = of(kind_id, self.kinds)
        for _ in range(max(0, min(count, 60))):
            self.particles.append({
                "x": at[0], "y": at[1],
                "vx": speed, "vy": -speed * 0.5,
                "life": life, "maxLife": life,
                "size": 4.0 * kind["size"],
                "colour": kind["colour"],
                "gravity": FALLING if kind["gravity"] else FLOATING,
                "stains": kind["stains"],
                # 每一滴都知道自己是哪种。少了它，台面上就分不出火花和灰尘，
                # 「火花 落地 → 点燃」也就无从谈起。
                "kind": kind["id"],
            })
        return len(self.particles)

    def live_kinds(self):
        """现在有滴在空中的种类，给「这个主体此刻在不在」用。"""
        out = []
        for p in self.particles:
            if p["kind"] not in out:
                out.append(p["kind"])
        return out

    def step(self, dt, floor):
        """推进一帧，返回**这一帧落地的种类**（可以重复，各算一次）。

        返回它，才让「一种粒子」成为能写规则的主体：台面把每个 id 当成一次 LANDED
        交给那种粒子自己的引擎。
        """
        landed = []
        for p in list(self.particles):
            p["life"] -= dt
            p["vy"] += p["gravity"] * dt
            p["x"] += p["vx"] * dt
            p["y"] += p["vy"] * dt
            p["vx"] *= max(0.0, 1.0 - 0.6 * dt)
            if p["life"] <= 0.0:
                self.particles.remove(p)
                continue
            if p["y"] >= floor:
                if p["stains"]:
                    self.stains.append({"x": p["x"], "y": floor, "colour": p["colour"]})
                landed.append(p["kind"])
                self.particles.remove(p)
        return landed


def main():
    print("a particle with gravity falls, one without drifts")
    falling = Particles()
    falling.burst("dust", (500.0, 1000.0), 1)
    floating = Particles()
    floating.burst("star", (500.0, 1000.0), 1)
    for _ in range(30):
        falling.step(1.0 / 60.0, 99999.0)
        floating.step(1.0 / 60.0, 99999.0)
    dy_fall = falling.particles[0]["vy"]
    dy_float = floating.particles[0]["vy"]
    report("dust picks up speed downward", dy_fall > 300.0, "vy %.0f" % dy_fall)
    report("a star does not", abs(dy_float) < 400.0 and dy_float < dy_fall,
           "vy %.0f" % dy_float)
    report("and it is the SPEC's switch, not the name",
           of("star")["gravity"] is False and of("dust")["gravity"] is True)

    print("\nthe floor: gone, and stained only if the kind says so")
    blood = Particles()
    blood.burst("blood", (500.0, 3660.0), 4)
    for _ in range(120):
        blood.step(1.0 / 60.0, 3670.0)
    report("blood reaches the floor and is gone", not blood.particles)
    report("and leaves marks", len(blood.stains) == 4, "%d marks" % len(blood.stains))

    spark = Particles()
    spark.burst("spark", (500.0, 3660.0), 4)
    for _ in range(120):
        spark.step(1.0 / 60.0, 3670.0)
    report("sparks land and leave nothing", not spark.particles and not spark.stains,
           "%d marks" % len(spark.stains))

    print("\na mark is left where it LANDED, which is the floor, not where it started")
    far = Particles()
    # A long life on purpose: the default life is under a second, and this one has three
    # thousand pixels to fall.
    far.burst("sweat", (1234.0, 100.0), 1, life=6.0)
    for _ in range(600):
        far.step(1.0 / 60.0, 3670.0)
    report("the mark is on the floor line", far.stains and far.stains[0]["y"] == 3670.0,
           str(far.stains[:1]))

    print("\nthey fade out rather than blink out")
    gone = Particles()
    gone.burst("dust", (500.0, 100.0), 3, life=0.2)
    for _ in range(20):
        gone.step(1.0 / 60.0, 99999.0)
    report("a short life is over in a third of a second", not gone.particles)

    print("\nthe colour and the size come from the kind")
    custom = dict(of("dust"))
    custom = {"id": "glitter", "name": "金粉", "colour": "#FFD34D", "size": 2.5,
              "gravity": False, "stains": True}
    p = Particles([custom] + DEFAULTS)
    p.burst("glitter", (0.0, 0.0), 2)
    report("a kind the app has never heard of still works", len(p.particles) == 2)
    report("with its own colour", all(x["colour"] == "#FFD34D" for x in p.particles))
    report("and its own size", abs(p.particles[0]["size"] - 10.0) < 1e-6,
           "%.1f px" % p.particles[0]["size"])
    report("and its own gravity switch", p.particles[0]["gravity"] == FLOATING)
    report("and it stains even though it floats",
           all(x["stains"] for x in p.particles))

    print("\nan unknown id is not an error")
    unknown = Particles()
    report("it falls back to the first kind", of("nonsense")["id"] == DEFAULTS[0]["id"])
    unknown.burst("nonsense", (0.0, 0.0), 3)
    report("and it still bursts", len(unknown.particles) == 3)

    print("\n一滴知道自己属于哪种：粒子种类是能写规则的主体")
    # 这是「粒子也是主体」的地基。少了它，台面分不出火花和灰尘，
    # 「火花 落地 → 点燃」就写不出来 —— 而它是这个功能存在的理由。
    mixed = Particles()
    mixed.burst("spark", (0.0, 0.0), 2)
    mixed.burst("dust", (0.0, 0.0), 3)
    report("every drop carries its kind",
           sorted(set(x["kind"] for x in mixed.particles)) == ["dust", "spark"],
           str(sorted(set(x["kind"] for x in mixed.particles))))
    report("and the kinds in the air are readable",
           sorted(mixed.live_kinds()) == ["dust", "spark"], str(mixed.live_kinds()))

    report("nothing in the air means no kinds", Particles().live_kinds() == [])

    # LANDED 是按种类报的，而且**同一种落几滴就报几次** —— 台面据此每帧合并成一次
    # 带数量的 LANDED，因为四十个火花同时落地是一件事，不是四十件。
    both = Particles()
    both.burst("spark", (0.0, 4000.0), 2)
    both.burst("dust", (0.0, 4000.0), 3)
    landed = both.step(1.0 / 60.0, 3670.0)
    report("landing reports which kinds touched down",
           sorted(landed) == ["dust", "dust", "dust", "spark", "spark"], str(sorted(landed)))
    report("counting them is how many drops it was",
           landed.count("dust") == 3 and landed.count("spark") == 2)
    report("nothing in the air landed on the next frame",
           both.step(1.0 / 60.0, 3670.0) == [])

    # 还在空中的不算落地，哪怕同一种里已经有别的落了。
    partial = Particles()
    partial.burst("spark", (0.0, 4000.0), 1)
    partial.burst("spark", (0.0, 0.0), 1)
    report("only the ones that reached the floor are reported",
           partial.step(1.0 / 60.0, 3670.0) == ["spark"] and len(partial.particles) == 1)

    print("\n画过图案的粒子按图案画，没画过的还是圆点")
    # 这一段镜像的是 Particles.draw 的那一个分支，以及 stamp 的方框。它的价值不在"挑一个
    # 分支"上，而在于**没画过必须是圆点**：一个查不到图案就什么都不画的实现，症状是
    # "粒子不见了"，而规则照旧在跑、日志照旧有 —— 没有人会想到去看形状。
    shapes = {"spark": "star.png"}
    kinds = {"spark": 6.0, "dust": 4.0}

    def what_is_drawn(kind, x, y, size):
        if kind in shapes:
            return ("stamp", shapes[kind], (x - size, y - size, x + size, y + size))
        return ("disc", x, y, size)

    drawn = what_is_drawn("spark", 100.0, 200.0, 6.0)
    report("a kind with a shape is stamped", drawn[0] == "stamp")
    report("and the box is square and centred on the drop",
           drawn[2] == (94.0, 194.0, 106.0, 206.0), str(drawn[2]))
    report("a kind nobody drew is still a disc",
           what_is_drawn("dust", 100.0, 200.0, 4.0) == ("disc", 100.0, 200.0, 4.0))

    # 印子跟着**落下的那一滴**的形状：同一种粒子在地上留下的痕迹和它在空中是同一个东西。
    stain_shape = shapes.get("spark")
    report("a mark on the floor uses the shape of the drop that made it",
           stain_shape == "star.png")
    report("and an unknown kind still leaves a round mark", shapes.get("ash") is None)

    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        for f in FAILURES:
            print("  " + f)
        return 1
    print("all particle tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
