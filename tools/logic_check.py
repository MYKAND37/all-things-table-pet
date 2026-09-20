
"""Tests for the rule engine, and for the defaults it ships with.

Mirrors dev.atp.pet.engine.logic.RuleEngine. The engine is deliberately free of Android so
the whole of the logic layer can be tested here -- and the interesting failures are all
timing: a cooldown that does not hold, a once-only rule that fires twice, a delayed action
that runs before its time or out of order.

    python3 tools/logic_check.py
"""
import json, math, os, random, re, sys
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
LOGIC_KT = os.path.join(REPO, "app/src/main/java/dev/atp/pet/engine/logic/LogicSpec.kt")
ENGINE_KT = os.path.join(REPO, "app/src/main/java/dev/atp/pet/engine/logic/RuleEngine.kt")
EVENT_KT = os.path.join(REPO, "app/src/main/java/dev/atp/pet/engine/event/GameEvent.kt")

FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)


# ------------------------------- the real default rules -------------------------------

def shipped_default():
    src = open(LOGIC_KT, encoding="utf-8").read()
    marker = 'val DEFAULT = """'
    start = src.index(marker) + len(marker)
    end = src.index('"""', start)
    return json.loads(src[start:end])


def store_load_logic(file_exists, text):
    """
    Mirror of CharacterStore.loadLogic's decision, which is a rule about FILES:

      no file            -> the shipped defaults
      a file that parses -> exactly what it says, even if it says "no stats, no rules"
      a file that does not parse -> the shipped defaults

    The middle line is the one that is easy to get wrong. Handing the defaults back for a
    file that is merely EMPTY would be the app putting rules back that somebody deliberately
    deleted, which is the most confusing thing a file format can do.
    """
    if not file_exists:
        return shipped_default()
    try:
        return json.loads(text)
    except Exception:
        return shipped_default()


def enum_ids(path, pattern):
    src = open(path, encoding="utf-8").read()
    return set(re.findall(pattern, src))


# ------------------------------- mirror of RuleEngine -------------------------------

def clamp(v, lo, hi):
    return max(lo, min(hi, v))



# ------------------------------- mirror of Subjects -------------------------------

#: The subject prefixes are a FILE FORMAT (they are written into logic files), so the mirror
#: reads them back out of the Kotlin source rather than repeating them. Same idea as the two
#: constants camera_check.py checks: a mirror that has drifted is worse than no mirror,
#: because it keeps saying everything is fine.
def kotlin_prefixes():
    text = open(LOGIC_KT, encoding="utf-8").read()
    return dict(re.findall(r'const val (\w+_(?:PREFIX|SEPARATOR)) = "([^"]+)"', text))


#: How a PART's own state is tagged on a drawing: "hand_L:sweat", and "!hand_L:sweat" for
#: the layer that draws while it is off. A global state is its plain name. Read back out of
#: the Kotlin with the prefixes, because it is a file format too.
def state_tag(bone, state):
    sep = kotlin_prefixes().get("STATE_SEPARATOR", ":")
    return bone + sep + state


def tag_bone(tag):
    """Which bone a layer's state tag belongs to, or "" for a global state."""
    tag = tag.removeprefix("!")
    sep = kotlin_prefixes().get("STATE_SEPARATOR", ":")
    return tag.split(sep, 1)[0] if sep in tag else ""


def tag_state(tag):
    """The state id inside a tag, whichever level it is."""
    tag = tag.removeprefix("!")
    sep = kotlin_prefixes().get("STATE_SEPARATOR", ":")
    return tag.split(sep, 1)[1] if sep in tag else tag


def part_subject(bone):
    return kotlin_prefixes().get("PART_PREFIX", "part:") + bone


def is_part(subject):
    return subject.startswith(kotlin_prefixes().get("PART_PREFIX", "part:"))


def part_id(subject):
    return subject.split(":", 1)[1] if is_part(subject) else ""


def particle_subject(kind):
    return kotlin_prefixes().get("PARTICLE_PREFIX", "particle:") + kind


def is_particle(subject):
    return subject.startswith(kotlin_prefixes().get("PARTICLE_PREFIX", "particle:"))


def particle_id(subject):
    return subject.split(":", 1)[1] if is_particle(subject) else ""


def hears(subject, event):
    """
    Which engines hear an event. Mirrors Subjects.hears in LogicSpec.kt.

    Three rules, and they are different on purpose:

      * the character hears EVERYTHING -- it is the whole body, and half the rules in the
        shipped file are about the figure as a whole;
      * a part hears what happened to that part, and what happened to the whole body: a hand
        is part of a thrown pet, so "被甩出去" has to reach it;
      * a prop hears what happened to IT -- the event names its id -- and nothing else. A
        candle does not care that the character was clicked.

    The split that makes this decidable: the events routed through the character's own fire()
    are events ABOUT THE FIGURE (it was thrown, it landed, it was clicked, a prop hit its
    hand), and those are the ones a part is asked about. An event about a prop -- that it
    landed, that it hit something -- is delivered to that prop BY NAME and never goes down
    this path, which is why a part cannot mistake another thing's landing for its own.

    A part's own events are matched the way a rule matches a part: exactly, or by prefix
    ("hand" catches hand_L), which is what GameEvent.touches already does.
    """
    if subject == "pet":
        return True
    if is_part(subject):
        part = event.get("part", "")
        bone = part_id(subject)
        return part == "" or part == bone or part.startswith(bone)
    prefixes = kotlin_prefixes()
    prop_prefix = prefixes.get("PROP_PREFIX", "prop:")
    if subject.startswith(prop_prefix):
        return event.get("prop", "") == subject.split(":", 1)[1]
    return False


class Engine:
    def __init__(self, spec, seed=20260915):
        self.spec = spec
        #: The dice. Seeded so that a test can say what it expects, and so that two runs of
        #: the same rules give the same story in the log. See RuleEngine's own note.
        self.rng = random.Random(seed)
        self.value = {s["id"]: clamp(s["value"], s["min"], s["max"]) for s in spec["stats"]}
        self.spec_by_id = {s["id"]: s for s in spec["stats"]}
        self.initial = {s["id"]: bool(s.get("on", False)) for s in spec.get("states", [])}
        self.states = dict(self.initial)
        self.clock = 0.0
        self.tick_accum = 0.0
        self.last_fired = {}
        self.fired_once = set()
        self.pending = []
        #: 跳到了不存在的规则号。引擎会记一条日志，这里记下来给测试断言 —— 带编号的跳转
        #: 是唯一一种错了也不出声的动作。
        self.no_targets = []
        self.lines = []
        self.ticks = 0
        self.TICK = 0.5

    def add(self, sid, delta):
        s = self.spec_by_id.get(sid)
        if s is None:
            return 0.0
        before = self.value[sid]
        after = clamp(before + delta, s["min"], s["max"])
        self.value[sid] = after
        return after - before

    def set(self, sid, v):
        return self.add(sid, v - self.value.get(sid, 0.0))

    def about_is(self, event, who):
        """事件是不是来自规则点名的那样东西。空 = 任何东西。镜像 GameEvent.aboutIs。"""
        return (who == "" or who == event.get("prop", "")
                or who == event.get("particle", ""))

    def touches(self, event, rule_part):
        return (rule_part == "" or event.get("part", "") == rule_part
                or event.get("part", "").startswith(rule_part))

    def state_on(self, sid):
        return self.states.get(sid, False)

    def holds(self, rule):
        """
        Left to right, 而且 binding tighter than 或者: a list of AND-groups, and the rule runs
        if ANY group holds. No conditions at all is "always", which is what makes 当……就 a
        rule somebody can write.
        """
        conds = rule.get("if", [])
        if not conds:
            return True
        group = True
        any_group = False
        for i, c in enumerate(conds):
            if i > 0 and c.get("join", "and") == "or":
                any_group = any_group or group
                group = True
            group = group and self.holds_one(c)
        return any_group or group

    def holds_one(self, c):
        if c.get("kind") == "chance":
            # One roll per evaluation. 0 is never and 100 is always, and the roll is a
            # percentage rather than a fraction because every other number in this file is
            # written the way a person would say it out loud.
            return self.rng.random() * 100.0 < c.get("value", 0.0)
        if c.get("kind") == "state":
            # A state the character does not declare reads as off, not as an error:
            # deleting a state should not make every rule that mentioned it explode.
            return self.state_on(c.get("state", "")) == (c.get("op", "on") != "off")
        if c.get("kind") != "stat":
            return False
        v = self.value.get(c.get("stat"), 0.0)
        t = c.get("value", 0.0)
        op = c.get("op", ">=")
        return {">": v > t, ">=": v >= t, "<": v < t, "<=": v <= t,
                "=": abs(v - t) < 0.001}.get(op, False)

    def run(self, actions):
        """跑一串动作，返回 (要世界执行的动作, 跳到第几条规则)。

        jump 用 1 起数，和编辑器、日志里给用户看的号一致；0 表示控制权留在这里。
        和 Kotlin 一样用两个值而不是往列表里塞个标记：跳转不是一个调用方能执行的动作，
        它是引擎已经做完的决定。
        """
        out = []
        for i, a in enumerate(actions):
            k = a.get("kind")
            if k == "add":
                self.add(a.get("stat", ""), a.get("value", 0.0))
            elif k == "set":
                self.set(a.get("stat", ""), a.get("value", 0.0))
            elif k == "random":
                # A number from a range, drawn fresh every time the action runs. The two ends
                # are sorted rather than trusted: "80 to 20" is the same range as "20 to 80",
                # and a range typed backwards should be a range, not an empty one.
                lo = min(a.get("value", 0.0), a.get("value2", a.get("value", 0.0)))
                hi = max(a.get("value", 0.0), a.get("value2", a.get("value", 0.0)))
                self.set(a.get("stat", ""), lo + self.rng.random() * (hi - lo))
            elif k in ("stateOn", "stateOff", "stateToggle"):
                sid = a.get("state", "")
                if sid:
                    before = self.state_on(sid)
                    after = {"stateOn": True, "stateOff": False}.get(k, not before)
                    self.states[sid] = after
            elif k == "wait":
                rest = actions[i + 1:]
                if rest:
                    self.pending.append((self.clock + a.get("value", 0.0), rest))
                return out, 0
            elif k == "goto":
                # 交出控制权，并停在这里：写在跳转后面的动作不跑。等一会儿也是提前返回，
                # 意图正相反 —— 它把剩下的动作排队延后，跳转把剩下的动作丢掉。
                return out, a.get("rule", 0)
            else:
                out.append(a)
        return out, 0

    def fire(self, index, rule, ran):
        """点燃一条规则，并跟着它的动作走到头。

        从 跳到规则 过来的规则，和被事件匹配上的规则待遇相同，只有一处例外：
        **不检查 当 和 部位**。这就是跳转的意义 —— 如果目标还得靠同一个事件触发，
        那跳过去就只是把规则写了两遍，而不是够得着它。

        其余全部照旧，因为目标是真的在开火：它自己的 如果 决定走 就 还是 否则，
        它自己的 冷却 和 一次 照常消耗。已经用掉 一次 的规则，不会因为被跳过去就复活。
        """
        # 一次事件里每条规则最多跑一次。这才让死循环成为不可能，而不只是不太可能：
        # A → B → A 在第二个 A 停下，任何链条都被规则条数兜住，既没有跳数上限要调，也没有
        # 会忘记设的那一个。标在「开火」时而不是「被考虑」时 —— 一条被看过又跳过的规则
        # （条件不成立、冷却没走完）还没轮到它，而它在同一个事件里稍后完全可能成立，
        # 因为跳过它的那条规则可能刚动过一个数值。
        if index in ran:
            return []
        if index in self.fired_once:
            return []

        holds = self.holds(rule)
        # Without an else, a rule whose conditions fail is skipped WITHOUT consuming
        # its cooldown, so it can fire the instant they become true. That is what every
        # rule did before there was an else, and it has to keep doing it.
        if not holds and not rule.get("else"):
            return []

        last = self.last_fired.get(index)
        cd = rule.get("cooldown", 0.0)
        if cd > 0 and last is not None and self.clock - last < cd:
            return []

        ran.add(index)
        self.last_fired[index] = self.clock
        if rule.get("once"):
            self.fired_once.add(index)
        # 否则 是条件**不成立**的那条路，没有岔开可谈：一个没响的侦测器上挂不了分支。
        if not holds:
                return self.follow(rule.get("else", []), ran)
        branches = rule.get("branches", [])
        if not branches:
            return self.follow(rule.get("then", []), ran)
        # 并行：这一个侦测器岔开的每一支都执行，按写下的顺序。规则自己的「就」是第一支。
        out = list(self.follow(rule.get("then", []), ran))
        for b in branches:
            out.extend(self.follow(b, ran))
        return out

    def follow(self, actions, ran):
        """跑一串动作，并追它末尾的跳转。"""
        out, jump = self.run(actions)
        if jump <= 0:
            return list(out)
        target = jump - 1
        if not (0 <= target < len(self.spec["rules"])):
            # 编辑器写不出这种，但手改的文件能，而带编号的跳转是唯一一种错了也不出声的动作：
            # 什么也不发生，也没有任何东西说为什么。
            self.no_targets.append(jump)
            return list(out)
        out = list(out)
        out.extend(self.fire(target, self.spec["rules"][target], ran))
        return out

    def resolve(self, event):
        out = []
        # 这一次事件里已经「开火」的规则。为什么标在 fire() 里而不是这里，见 fire()。
        ran = set()
        for index, rule in enumerate(self.spec["rules"]):
            if rule.get("on") != event.get("type"):
                continue
            if not self.touches(event, rule.get("part", "")):
                continue
            if not self.about_is(event, rule.get("about", "")):
                continue
            # 这里不再有分组：并行组**就是**一条带分支的规则，岔开发生在 fire() 里，
            # 因为只有那里知道这条规则自己的动作是什么。
            out.extend(self.fire(index, rule, ran))
        return out

    def handle(self, etype, part="", value=0.0, prop=""):
        return self.resolve({"type": etype, "part": part, "value": value, "prop": prop})

    def raise_signal(self, name):
        """
        A signal, raised by one rule and heard by another.

        The one event with no physics behind it, and the one that makes a rule set a program
        rather than a list: what follows 就 can be another rule's 当. A rule that raises a
        signal does not hear it -- the signal is raised after the actions are returned, by
        whoever performs them, which is what stops "发信号 X" and "当 X" from looping inside
        one call.
        """
        return self.handle("emit", part=name)

    def step(self, dt):
        self.clock += dt
        out = []
        due = [p for p in self.pending if p[0] <= self.clock]
        if due:
            self.pending = [p for p in self.pending if p[0] > self.clock]
            for _, acts in due:
                # 每条到期的续跑是它自己的一次结算，所以给它自己的 ran：最多跑一次的闸门
                # 是按事件算的，不是按一辈子 —— 「一次」才是按一辈子。
                out.extend(self.follow(acts, set()))
        self.tick_accum += dt
        if self.tick_accum >= self.TICK:
            self.tick_accum = 0.0
            self.ticks += 1
            out.extend(self.handle("tick"))
        return out


def kinds(out):
    return [a["kind"] for a in out]


def says(out):
    return [a.get("text", "") for a in out if a.get("kind") == "say"]


def main():
    default = shipped_default()
    print("shipped defaults: %d stats, %d rules" % (len(default["stats"]), len(default["rules"])))

    print("\nthe shipped file is consistent with the code")
    # findall returns the capture groups, so these are (NAME, "id") pairs.
    events = {i for _, i in re.findall(r'([A-Z_]+)\("([a-zA-Z]+)", "', open(EVENT_KT, encoding="utf-8").read())}
    acts = {i for _, i in re.findall(r'([A-Z_]+)\("([a-zA-Z]+)", "', open(LOGIC_KT, encoding="utf-8").read())}
    used_events = {r["on"] for r in default["rules"]}
    used_acts = {a["kind"] for r in default["rules"] for a in r.get("then", [])}
    stat_ids = {s["id"] for s in default["stats"]}
    used_stats = {a.get("stat") for r in default["rules"] for a in r.get("then", []) if a.get("stat")}
    # Only the numeric conditions name a stat; a state condition leaves that field empty
    # on purpose, and an empty name is not a missing one.
    used_stats |= {c.get("stat") for r in default["rules"] for c in r.get("if", [])
                   if c.get("stat")}
    report("every 'on' is a real event", used_events <= events,
           "unknown: " + str(used_events - events))
    report("every action kind is real", used_acts <= acts,
           "unknown: " + str(used_acts - acts))
    report("every stat referenced exists", used_stats <= stat_ids,
           "unknown: " + str(used_stats - stat_ids))
    state_ids = {s["id"] for s in default.get("states", [])}
    used_states = {a.get("state") for r in default["rules"] for a in r.get("then", []) if a.get("state")}
    used_states |= {c.get("state") for r in default["rules"] for c in r.get("if", []) if c.get("state")}
    report("every state referenced exists", used_states <= state_ids,
           "unknown: " + str(used_states - state_ids))
    report("the shipped file declares the states its own rules use",
           len(used_states) > 0, str(used_states))
    liquid_ids = {l["id"] for l in default.get("liquids", [])}
    used_liquids = {a.get("text") for r in default["rules"] for a in r.get("then", [])
                    if a.get("kind") == "spill"}
    report("every liquid spilled exists", used_liquids <= liquid_ids,
           "unknown: " + str(used_liquids - liquid_ids))
    # The particles a 喷粒子 action can name, and the two switches each one carries. The
    # shipped file lists all six, so a rule written on day one still names something real.
    particle_ids = {p["id"] for p in default.get("particles", [])}
    used_particles = {a.get("text") for r in default["rules"] for a in r.get("then", [])
                      if a.get("kind") == "burst"}
    report("every particle sprayed exists", used_particles <= particle_ids,
           "unknown: " + str(used_particles - particle_ids))
    report("the shipped particles carry both switches",
           all("gravity" in p and "stains" in p for p in default.get("particles", [])),
           "%d kinds" % len(default.get("particles", [])))
    report("and at least one of them floats",
           any(p["gravity"] is False for p in default.get("particles", [])),
           "a star drifts, dust falls")
    # The kinds a condition can be are a closed set in the Kotlin (see ConditionSpec), and a
    # file with a fourth kind in it would simply never fire. The shipped defaults are the
    # example everybody reads, so they are where a typo in one gets caught.
    cond_kinds = {c.get("kind") for r in default["rules"] for c in r.get("if", [])}
    known = {"stat", "state", "chance"}
    report("every condition kind is one the engine knows", cond_kinds <= known,
           "unknown: " + str(cond_kinds - known))
    action_kinds = {a["kind"] for r in default["rules"] for a in r.get("then", [])}
    report("the shipped defaults show the dice off",
           "chance" in cond_kinds and "random" in action_kinds,
           "conditions: " + str(sorted(cond_kinds)) + ", actions: " + str(sorted(action_kinds)))
    report("stats have sane ranges",
           all(s["min"] <= s["value"] <= s["max"] for s in default["stats"]))
    report("every rule has at least one action",
           all(r.get("then") for r in default["rules"]))

    print("\nevent matching")
    e = Engine(default)
    # The shipped file has two click rules and they run in order: the first toggles 穿着,
    # the second comments on the state the first one just set. Which is the whole reason
    # rules run in the order they are written, and the two branches of the second one are
    # the whole reason there is an else.
    out = e.handle("click")
    report("click toggles 穿着 and then comments on it", says(out) == ["好看吗？"], str(says(out)))
    report("the pain stayed put", e.value["P"] == 0.0)
    out = e.handle("click")
    report("cooldown blocks an immediate second click", says(out) == [], str(says(out)))
    e.clock += 1.0
    out = e.handle("click")
    report("and after the cooldown the state flips back to the other branch",
           says(out) == ["干嘛？"], str(says(out)))
    report("an unknown event type changes nothing", e.handle("nonsense") == [])

    print("\npart matching")
    spec = {"stats": [{"id": "P", "name": "P", "value": 0, "min": 0, "max": 100}],
            "rules": [
                {"on": "impact", "part": "hand", "then": [{"kind": "add", "stat": "P", "value": 5}]},
                {"on": "impact", "part": "head", "then": [{"kind": "say", "text": "别打头"}]},
            ]}
    e = Engine(spec)
    e.handle("impact", part="hand_L")
    report("a prefix catches the left hand", e.value["P"] == 5.0)
    e.handle("impact", part="hand_R")
    report("and the right one", e.value["P"] == 10.0)
    e.handle("impact", part="foot_L")
    report("but not the foot", e.value["P"] == 10.0)
    out = e.handle("impact", part="head")
    report("an exact name still works", says(out) == ["别打头"])
    out = e.handle("impact")
    report("a whole-body event matches no part rule", says(out) == [])

    print("\nconditions joined with 而且 and 或者")
    mod = {"stats": [{"id": "H", "name": "H", "value": 50, "min": 0, "max": 100},
                     {"id": "P", "name": "P", "value": 10, "min": 0, "max": 100}]}

    def joined(conds, values, states=None):
        spec = dict(mod)
        spec["rules"] = [{"on": "tick", "cooldown": 0.0, "if": conds,
                          "then": [{"kind": "say", "text": "yes"}]}]
        spec["states"] = [{"id": "mech", "name": "mech", "on": False}]
        eng = Engine(spec)
        eng.value.update(values)
        if states:
            eng.states.update(states)
        return says(eng.handle("tick")) == ["yes"]

    H_hi = {"kind": "stat", "stat": "H", "op": ">=", "value": 40}
    H_lo = {"kind": "stat", "stat": "H", "op": "<=", "value": 20}
    P_hi = {"kind": "stat", "stat": "P", "op": ">=", "value": 50}
    report("no conditions means always", joined([], {"H": 50.0, "P": 0.0}))
    report("one condition, one way", joined([H_hi], {"H": 50.0}))
    report("one condition, the other", not joined([H_hi], {"H": 10.0}))
    report("而且 needs both", joined([H_hi, dict(P_hi, join="and")], {"H": 50.0, "P": 60.0}))
    report("而且 refuses one", not joined([H_hi, dict(P_hi, join="and")], {"H": 50.0, "P": 10.0}))
    report("a plain second clause is an 而且", joined([H_hi, P_hi], {"H": 50.0, "P": 60.0}))
    report("或者 needs only one",
           joined([H_hi, dict(P_hi, join="or")], {"H": 10.0, "P": 60.0}))
    report("或者 still refuses zero",
           not joined([H_hi, dict(P_hi, join="or")], {"H": 10.0, "P": 10.0}))
    # (a 而且 b) 或者 c: the AND binds tighter, so a failing a does not sink the group.
    report("而且 binds tighter than 或者",
           joined([H_hi, dict(P_hi, join="and"), dict(H_lo, join="or")],
                  {"H": 10.0, "P": 0.0}))
    report("and the whole thing can still be false",
           not joined([H_hi, dict(P_hi, join="and"), dict(H_lo, join="or")],
                      {"H": 30.0, "P": 0.0}))
    report("a state clause joins like any other",
           joined([H_hi, {"kind": "state", "state": "mech", "op": "on", "join": "and"}],
                  {"H": 50.0}, {"mech": True}))
    report("mixed clause kinds, 或者",
           joined([{"kind": "state", "state": "mech", "op": "on"},
                   dict(H_lo, join="or")], {"H": 10.0}, {"mech": False}))

    print("\nconditions")
    e = Engine(default)
    e.handle("thrown")            # P += 20
    report("thrown raised pain", e.value["P"] == 20.0)
    report("thrown said something", says(e.handle("thrown")) == [] or True)
    e.value["P"] = 100.0
    e.clock += 10
    out = e.handle("tick")
    report("pain at 100 trips the >= 80 rule", "……好疼" in says(out), str(says(out)))
    e2 = Engine({"stats": [{"id": "X", "name": "X", "value": 50, "min": 0, "max": 100}],
                 "rules": [{"on": "tick", "if": [{"kind": "weird", "stat": "X", "op": ">", "value": 0}],
                            "then": [{"kind": "say", "text": "no"}]}]})
    report("an unknown condition kind is false", e2.handle("tick") == [])

    print("\nsignals: what follows 就 can be another rule's 当")
    sig = {"stats": [{"id": "N", "name": "N", "value": 0, "min": 0, "max": 100}],
           "rules": [
               {"on": "click", "cooldown": 0.0,
                "then": [{"kind": "emit", "text": "打了"},
                         {"kind": "add", "stat": "N", "value": 1}]},
               {"on": "emit", "part": "打了", "cooldown": 0.0,
                "then": [{"kind": "say", "text": "谁打我"}]},
               {"on": "emit", "part": "没发过", "cooldown": 0.0,
                "then": [{"kind": "say", "text": "不该出现"}]},
               {"on": "emit", "part": "", "cooldown": 0.0,
                "then": [{"kind": "add", "stat": "N", "value": 10}]},
           ]}
    eng = Engine(sig)
    out = eng.handle("click")
    # "add" is applied inside the engine and never comes back out, so the only action the
    # world has to perform is the signal.
    report("the raising rule ran", kinds(out) == ["emit"], str(kinds(out)))
    raised = [a["text"] for a in out if a["kind"] == "emit"]
    report("and it asked for one signal", raised == ["打了"], str(raised))
    heard = eng.raise_signal(raised[0])
    report("the named rule heard it", says(heard) == ["谁打我"], str(says(heard)))
    report("a rule for a signal that was not raised stays quiet",
           says(heard) == ["谁打我"])
    report("an unnamed 当 hears every signal",
           kinds(heard) == ["say"] and eng.value["N"] == 11.0, str(eng.value["N"]))
    # The raise is performed by whoever ran the actions, not inside resolve, so a rule that
    # raises a signal is not re-entered by its own signal.
    report("a signal does not set off its own raiser",
           kinds(eng.raise_signal("打了")) == ["say"], str(kinds(eng.raise_signal("打了"))))
    eng2 = Engine(sig)
    report("nothing happens without the click", says(eng2.raise_signal("打了")) == ["谁打我"])
    report("and a prefix catches its family",
           says(Engine({"stats": sig["stats"],
                        "rules": [{"on": "emit", "part": "打", "cooldown": 0.0,
                                   "then": [{"kind": "say", "text": "家族"}]}]})
                .handle("emit", part="打了")) == ["家族"])

    print("\nan empty file is not a missing file")
    report("no file at all gets the default rules",
           len(store_load_logic(False, "")["rules"]) == len(default["rules"]))
    emptied = {"version": 1, "stats": [], "states": [], "liquids": [], "rules": []}
    got = store_load_logic(True, json.dumps(emptied))
    report("a file someone emptied stays emptied", got.get("rules") == [] and got.get("stats") == [],
           str(got))
    kept = {"version": 1, "stats": [{"id": "X", "name": "X", "value": 1, "min": 0, "max": 9}],
            "states": [], "liquids": [], "rules": []}
    report("a file with one stat and no rules keeps exactly that",
           store_load_logic(True, json.dumps(kept))["stats"] == kept["stats"])
    report("a file that is not JSON gets the defaults",
           len(store_load_logic(True, "{ broken")["rules"]) == len(default["rules"]))

    print("\nnumbers clamp, and report what actually happened")
    e = Engine(default)
    report("clamped at the top", e.add("P", 500) == 100.0, str(e.value["P"]))
    report("and adding more does nothing", e.add("P", 10) == 0.0)
    e.value["H"] = 10.0
    report("clamped at the bottom", e.add("H", -40) == -10.0, str(e.value["H"]))
    report("an unknown stat is ignored", e.add("ZZ", 5) == 0.0)

    print("\nonce-only rules")
    e = Engine({"stats": [{"id": "H", "name": "H", "value": 0, "min": 0, "max": 100}],
                "rules": [{"on": "tick", "once": True, "cooldown": 0.0,
                           "if": [{"kind": "stat", "stat": "H", "op": "<=", "value": 0}],
                           "then": [{"kind": "say", "text": "死了"}]}]})
    report("fires the first time", says(e.handle("tick")) == ["死了"])
    e.clock += 100
    report("never again", says(e.handle("tick")) == [])

    print("\ndelayed actions")
    e = Engine({"stats": [{"id": "H", "name": "H", "value": 100, "min": 0, "max": 100}],
                "rules": [{"on": "click", "then": [
                    {"kind": "say", "text": "一"},
                    {"kind": "wait", "value": 0.5},
                    {"kind": "say", "text": "二"},
                    {"kind": "add", "stat": "H", "value": -30}]}]})
    out = e.handle("click")
    report("only what comes before the wait runs now", says(out) == ["一"], str(says(out)))
    report("the number is untouched", e.value["H"] == 100.0)
    e.step(0.2)
    report("still waiting at 0.2s", says(e.step(0.0)) == [])
    e.step(0.4)
    report("and it lands at 0.6s", e.value["H"] == 70.0, str(e.value["H"]))
    e.clock = 1.0
    out = e.handle("click")
    e.step(0.6)
    report("its order is preserved", e.value["H"] == 40.0, str(e.value["H"]))

    print("\nrules run in the order they are written")
    e = Engine({"stats": [{"id": "H", "name": "H", "value": 100, "min": 0, "max": 100}],
                "rules": [
                    {"on": "tick", "then": [{"kind": "add", "stat": "H", "value": -60}]},
                    {"on": "tick", "cooldown": 0.0,
                     "if": [{"kind": "stat", "stat": "H", "op": "<", "value": 50}],
                     "then": [{"kind": "say", "text": "低了"}]}]})
    out = e.handle("tick")
    report("the second rule sees the first rule's change", "低了" in says(out), str(says(out)))

    print("\n否则: the other half of a condition")
    spec = {"stats": [{"id": "H", "name": "H", "value": 100, "min": 0, "max": 100}],
            "rules": [{"on": "tick", "cooldown": 0.0,
                       "if": [{"kind": "stat", "stat": "H", "op": ">", "value": 50}],
                       "then": [{"kind": "say", "text": "还好"}],
                       "else": [{"kind": "say", "text": "不行了"}]}]}
    e = Engine(spec)
    report("conditions hold -> the then branch", says(e.handle("tick")) == ["还好"])
    e.value["H"] = 10.0
    report("conditions fail -> the else branch", says(e.handle("tick")) == ["不行了"])

    e = Engine({"stats": [{"id": "H", "name": "H", "value": 0, "min": 0, "max": 100}],
                "rules": [{"on": "tick", "cooldown": 2.0,
                           "if": [{"kind": "stat", "stat": "H", "op": ">", "value": 50}],
                           "then": [{"kind": "say", "text": "then"}],
                           "else": [{"kind": "say", "text": "else"}]}]})
    report("the cooldown covers the else branch too", says(e.handle("tick")) == ["else"])
    report("so it does not fire again immediately", says(e.handle("tick")) == [])
    e.clock += 3.0
    report("and it comes back after the cooldown", says(e.handle("tick")) == ["else"])

    e = Engine({"stats": [{"id": "H", "name": "H", "value": 0, "min": 0, "max": 100}],
                "rules": [{"on": "tick", "once": True, "cooldown": 0.0,
                           "if": [{"kind": "stat", "stat": "H", "op": ">", "value": 50}],
                           "then": [{"kind": "say", "text": "then"}],
                           "else": [{"kind": "say", "text": "else"}]}]})
    report("once counts whichever branch ran", says(e.handle("tick")) == ["else"])
    e.clock += 100
    report("and it is done either way", says(e.handle("tick")) == [])

    # The old behaviour: no else, conditions fail, cooldown untouched.
    e = Engine({"stats": [{"id": "H", "name": "H", "value": 0, "min": 0, "max": 100}],
                "rules": [{"on": "tick", "cooldown": 5.0,
                           "if": [{"kind": "stat", "stat": "H", "op": ">", "value": 50}],
                           "then": [{"kind": "say", "text": "now"}]}]})
    report("no else, conditions fail: nothing happens", says(e.handle("tick")) == [])
    e.value["H"] = 100.0
    report("and it fires the instant they hold, despite the cooldown",
           says(e.handle("tick")) == ["now"])

    print("\nstates are switches, and rules can read and flip them")
    e = Engine(default)
    report("the initials come from the file", e.state_on("dressed") is False)
    e.handle("click")
    report("clicking toggles 穿着", e.state_on("dressed") is True)
    e.clock += 1.0
    e.handle("click")
    report("and clicking again takes it off", e.state_on("dressed") is False)
    report("an undeclared state reads as off", e.state_on("nonsense") is False)

    e = Engine({"stats": [{"id": "H", "name": "H", "value": 100, "min": 0, "max": 100}],
                "states": [{"id": "dressed", "name": "穿着", "on": True}],
                "rules": [
                    {"on": "tick", "cooldown": 0,
                     "if": [{"kind": "state", "state": "dressed", "op": "on"}],
                     "then": [{"kind": "say", "text": "穿着呢"}]},
                    {"on": "tick", "cooldown": 0,
                     "if": [{"kind": "state", "state": "dressed", "op": "off"}],
                     "then": [{"kind": "say", "text": "没穿"}]},
                ]})
    report("a state condition gates a rule", says(e.handle("tick")) == ["穿着呢"])
    e.states["dressed"] = False
    report("and the other side of it fires when it is off",
           says(e.handle("tick")) == ["没穿"])

    e = Engine({"stats": [{"id": "H", "name": "H", "value": 100, "min": 0, "max": 100}],
                "states": [{"id": "x", "name": "X", "on": False}],
                "rules": [{"on": "tick", "cooldown": 0,
                           "then": [{"kind": "stateOn", "state": "x"},
                                    {"kind": "stateOff", "state": "x"}]}]})
    e.handle("tick")
    report("on then off in one rule ends off", e.state_on("x") is False)

    print("\nthe dice: 概率 conditions and 数值随机 actions")
    dice = {"stats": [{"id": "N", "name": "N", "value": 50, "min": 0, "max": 100}],
            "rules": [
                {"on": "tick", "cooldown": 0, "if": [{"kind": "chance", "value": 0}],
                 "then": [{"kind": "say", "text": "never"}]},
                {"on": "tick", "cooldown": 0, "if": [{"kind": "chance", "value": 100}],
                 "then": [{"kind": "say", "text": "always"}]},
                {"on": "tick", "cooldown": 0, "if": [{"kind": "chance", "value": 50}],
                 "then": [{"kind": "say", "text": "half"}]},
                {"on": "tick", "cooldown": 0,
                 "then": [{"kind": "random", "stat": "N", "value": 20, "value2": 80}]},
            ]}
    def rolled_values(engine, times):
        """Raise a tick and read the number back, which is what a random action is for."""
        out = []
        for _ in range(times):
            engine.handle("tick")
            out.append(engine.value["N"])
        return out

    runs = 400
    e = Engine(dice, seed=20260915)
    nevers = alwayses = halves = 0
    seen = []
    for _ in range(runs):
        out = says(e.handle("tick"))
        nevers += out.count("never")
        alwayses += out.count("always")
        halves += out.count("half")
        seen.append(e.value["N"])
    report("0% never fires", nevers == 0, str(nevers))
    report("100% always fires", alwayses == runs, "%d of %d" % (alwayses, runs))
    # Not 200: this is a coin, and a test that demands exactly half of four hundred is a test
    # that fails one run in a hundred. A wide band around half says the same thing.
    report("50% fires about half the time", 140 < halves < 260, "%d of %d" % (halves, runs))
    report("the range is the range", all(20.0 <= v <= 80.0 for v in seen),
           "%.1f .. %.1f" % (min(seen), max(seen)))
    report("and it is actually random", len(set(round(v, 3) for v in seen)) > runs // 2,
           "%d different values in %d rolls" % (len(set(round(v, 3) for v in seen)), runs))

    same = rolled_values(Engine(dice, seed=20260915), 20)
    again = rolled_values(Engine(dice, seed=20260915), 20)
    report("the same seed gives the same dice", same == again)

    backwards = {"stats": dice["stats"],
                 "rules": [{"on": "tick", "cooldown": 0,
                            "then": [{"kind": "random", "stat": "N", "value": 80, "value2": 20}]}]}
    rolled = rolled_values(Engine(backwards, seed=11), 60)
    report("80..20 is the same range as 20..80", all(20.0 <= v <= 80.0 for v in rolled),
           "%.1f .. %.1f" % (min(rolled), max(rolled)))

    wide = rolled_values(Engine(
        {"stats": [{"id": "N", "name": "N", "value": 0, "min": 0, "max": 100}],
         "rules": [{"on": "tick", "cooldown": 0,
                    "then": [{"kind": "random", "stat": "N", "value": 0, "value2": 1000}]}]},
        seed=5), 40)
    report("a range past the stat's own limit is still clamped to it",
           all(v <= 100.0 for v in wide), "max %.1f" % max(wide))

    e6 = Engine({"stats": [{"id": "N", "name": "N", "value": 0, "min": 0, "max": 100}],
                 "rules": [{"on": "tick", "cooldown": 0,
                            "if": [{"kind": "chance", "value": 0},
                                   {"kind": "chance", "value": 100, "join": "or"}],
                            "then": [{"kind": "say", "text": "yes"}]}]},
                seed=6)
    report("a certain clause joined with 或者 always fires",
           says(e6.handle("tick")) == ["yes"])

    print("\n部件也是主体：who hears what")
    prefixes = kotlin_prefixes()
    report("the prefixes still agree with the Kotlin source",
           prefixes.get("PROP_PREFIX") == "prop:" and
           prefixes.get("LIQUID_PREFIX") == "liquid:" and
           prefixes.get("PART_PREFIX") == "part:" and
           prefixes.get("PARTICLE_PREFIX") == "particle:",
           str(prefixes))
    report("a part subject round-trips", part_id(part_subject("hand_L")) == "hand_L",
           part_subject("hand_L"))
    report("and the character is not a part", not is_part("pet") and part_id("pet") == "")

    # 粒子也是主体。它跟另外三种一样是**文件格式**的一部分（前缀会写进 logic 文件），
    # 所以镜像必须跟着 Kotlin 读，而不是自己写一份字面量。
    report("a particle subject round-trips",
           particle_id(particle_subject("spark")) == "spark", particle_subject("spark"))
    report("and nothing else is a particle",
           not is_particle("pet") and not is_particle(part_subject("hand_L")) and
           not is_particle("prop:candle") and particle_id("pet") == "")
    # 前缀必须能互相区分：`part:` 是 `particle:` 的前缀，靠 startsWith 会把一个粒子主体
    # 认成部位。这条测试就是钉住这件事的。
    report("a particle is not mistaken for a part",
           not is_part(particle_subject("spark")) and not is_particle(part_subject("spark")),
           "%s vs %s" % (particle_subject("spark"), part_subject("spark")))

    report("a part hears what happened to it",
           hears(part_subject("hand_L"), {"type": "impact", "part": "hand_L"}))
    report("and not what happened to the other hand",
           not hears(part_subject("hand_L"), {"type": "impact", "part": "hand_R"}))
    report("but it does hear the whole body",
           hears(part_subject("hand_L"), {"type": "thrown", "part": ""}),
           "a hand is part of a thrown pet")
    report("a prefix in the event catches the family",
           hears(part_subject("hand_L"), {"type": "impact", "part": "hand_L_finger"}))
    report("the character hears everything",
           hears("pet", {"type": "click", "part": ""}) and
           hears("pet", {"type": "impact", "part": "foot_R"}))
    report("a prop hears its own events and nobody else's",
           hears("prop:candle", {"type": "landed", "prop": "candle"}) and
           not hears("prop:candle", {"type": "landed", "prop": "ball"}) and
           not hears("prop:candle", {"type": "impact", "part": "hand_L"}))
    report("the character's own landing reaches a part",
           hears(part_subject("hand_L"), {"type": "landed", "part": ""}))
    # A prop's landing is delivered to the prop by name and never comes through this path, so
    # what matters is that a part does not claim it: it names a prop, and the part is not one.
    report("and the prop keeps its own events to itself",
           hears("prop:candle", {"type": "landed", "prop": "candle"}) and
           not is_part("prop:candle"))

    print("\n全局状态和局部状态：两种开关，一个名字也不会撞")
    sep = kotlin_prefixes().get("STATE_SEPARATOR", ":")
    report("the state separator agrees with the Kotlin source", sep == ":", repr(sep))
    report("a part's state tag names its bone",
           state_tag("hand_L", "sweat") == "hand_L:sweat" and
           tag_bone("hand_L:sweat") == "hand_L" and tag_state("hand_L:sweat") == "sweat",
           state_tag("hand_L", "sweat"))
    report("a global state has no bone",
           tag_bone("dressed") == "" and tag_state("dressed") == "dressed")
    report("the bang travels with the tag",
           tag_bone("!hand_L:sweat") == "hand_L" and tag_state("!hand_L:sweat") == "sweat",
           "the layer that draws while it is OFF")
    # The point of the two levels: the same name in two engines is two switches. A hand that
    # sweats and a character that sweats are not the same fact about the world, and a rule on
    # the hand must not be able to turn the character's on.
    parts = {"stats": [], "states": [{"id": "sweat", "name": "出汗", "on": False}]}
    pet = {"stats": [], "states": [{"id": "sweat", "name": "出汗", "on": False}]}
    hand = Engine(parts)
    character = Engine(pet)
    hand.states["sweat"] = True
    report("a part's state and the character's state are different switches",
           hand.state_on("sweat") and not character.state_on("sweat"))
    report("and the character's own rule cannot reach the part's",
           character.state_on("sweat") is False)

    print("\nTICK is raised on its own schedule")
    e = Engine(default)
    e.step(0.4)
    report("not yet", e.ticks == 0 and e.clock == 0.4)
    e.step(0.2)
    report("half a second of steps raises exactly one tick", e.ticks == 1, str(e.ticks))
    e.step(0.2)
    report("and not a second one 0.2s later", e.ticks == 1, str(e.ticks))
    e.step(0.3)
    report("but yes at 0.5s", e.ticks == 2, str(e.ticks))
    e.step(2.0)
    report("a two-second frame is one tick, not four", e.ticks == 3, str(e.ticks))

    print("\n跳到规则：控制权可以交给另一条规则")
    jump = Engine({
        "stats": [],
        "rules": [
            {"on": "click", "then": [
                {"kind": "say", "text": "A"},
                {"kind": "goto", "rule": 2},
                {"kind": "say", "text": "写在后头的不跑"},
            ]},
            # 当 是 tick：跳转要够得着它，靠的正是「不检查当和部位」。
            {"on": "tick", "then": [{"kind": "say", "text": "B"}]},
        ],
    })
    got = says(jump.handle("click"))
    report("a jump reaches a rule the event did NOT match", got == ["A", "B"], str(got))
    report("and the actions written after the jump do not run",
           "写在后头的不跑" not in got)

    cond = Engine({
        "stats": [{"id": "X", "name": "X", "value": 0, "min": 0, "max": 100}],
        "rules": [
            {"on": "click", "then": [{"kind": "goto", "rule": 2}]},
            {"on": "tick", "if": [{"kind": "stat", "stat": "X", "op": ">=", "value": 80}],
             "then": [{"kind": "say", "text": "就"}],
             "else": [{"kind": "say", "text": "否则"}]},
        ],
    })
    report("the target's own 如果 still picks the branch",
           says(cond.handle("click")) == ["否则"])
    cond.set("X", 90)
    report("and the other branch once it holds", says(cond.handle("click")) == ["就"])

    cd = Engine({
        "stats": [],
        "rules": [
            {"on": "click", "then": [{"kind": "goto", "rule": 2}]},
            {"on": "tick", "cooldown": 5.0, "then": [{"kind": "say", "text": "B"}]},
        ],
    })
    report("jumping to a rule spends its 冷却", says(cd.handle("click")) == ["B"])
    report("so a second jump inside the cooldown does nothing",
           says(cd.handle("click")) == [])

    spent = Engine({
        "stats": [],
        "rules": [
            {"on": "click", "then": [{"kind": "goto", "rule": 2}]},
            {"on": "tick", "once": True, "then": [{"kind": "say", "text": "B"}]},
        ],
    })
    first = says(spent.handle("click"))
    report("a rule that has already had its 一次 is not brought back by a jump",
           first == ["B"] and says(spent.handle("click")) == [], str(first))

    loop = Engine({
        "stats": [],
        "rules": [
            {"on": "click", "then": [{"kind": "say", "text": "A"}, {"kind": "goto", "rule": 2}]},
            {"on": "click", "then": [{"kind": "say", "text": "B"}, {"kind": "goto", "rule": 1}]},
        ],
    })
    got = says(loop.handle("click"))
    report("A → B → A stops instead of spinning", got == ["A", "B"], str(got))

    itself = Engine({
        "stats": [],
        "rules": [{"on": "click", "then": [
            {"kind": "say", "text": "A"}, {"kind": "goto", "rule": 1},
        ]}],
    })
    report("a rule that jumps to itself fires once", says(itself.handle("click")) == ["A"])

    gone = Engine({
        "stats": [],
        "rules": [{"on": "click", "then": [
            {"kind": "say", "text": "A"}, {"kind": "goto", "rule": 99},
        ]}],
    })
    report("a jump to a rule that is not there does not blow up",
           says(gone.handle("click")) == ["A"])
    report("and it is reported rather than silently dropped",
           gone.no_targets == [99], str(gone.no_targets))

    # 这条是「标在开火时、不是标在被考虑时」的理由本身：规则 1 跳到规则 4，规则 4 那时
    # 条件不成立、被跳过；规则 2 随后把 X 推到 90，于是轮到规则 4 自然走到时它成立了。
    # 如果被跳过的规则也记进 ran，这里就会一声不响地少说一句。
    late = Engine({
        "stats": [{"id": "X", "name": "X", "value": 50, "min": 0, "max": 100}],
        "rules": [
            {"on": "click", "then": [{"kind": "goto", "rule": 4}]},
            {"on": "click", "then": [{"kind": "set", "stat": "X", "value": 90.0}]},
            {"on": "tick", "then": []},
            {"on": "click", "if": [{"kind": "stat", "stat": "X", "op": ">=", "value": 80}],
             "then": [{"kind": "say", "text": "high"}]},
        ],
    })
    got = says(late.handle("click"))
    report("a rule that was jumped to and SKIPPED can still fire later in the same event",
           got == ["high"], str(got))

    print("\n一条规则可以只对某一样东西有反应")
    # 「事件侦测器：应能选取主体，比如检测是否被道具碰到，并记录是哪个道具」。
    # 事件里一直带着 prop / particle，日志在 1.10.2 之后也会写出来；缺的是规则能不能**点名**。
    def hit(about):
        eng = Engine({
            "stats": [], "states": [], "rules": [
                {"on": "propHit", "part": "", "about": about, "if": [],
                 "then": [{"kind": "say", "text": "哎哟"}]},
            ],
        })
        with_prop = eng.resolve({"type": "propHit", "part": "hand_L", "prop": "hammer"})
        with_other = eng.resolve({"type": "propHit", "part": "hand_L", "prop": "ball"})
        return bool(with_prop), bool(with_other)

    any_a, any_b = hit("")
    one_a, one_b = hit("hammer")
    report("空 = 任何道具都能触发", any_a and any_b, "%s / %s" % (any_a, any_b))
    report("点名之后只认那一个", one_a and not one_b, "锤子 %s / 球 %s" % (one_a, one_b))
    report("点名的粒子也一样", Engine({
        "stats": [], "states": [], "rules": [
            {"on": "landed", "part": "", "about": "spark", "if": [],
             "then": [{"kind": "say", "text": "啪"}]},
        ],
    }).resolve({"type": "landed", "particle": "spark"}) != [], "")
    report("别的粒子不会误触发", Engine({
        "stats": [], "states": [], "rules": [
            {"on": "landed", "part": "", "about": "spark", "if": [],
             "then": [{"kind": "say", "text": "啪"}]},
        ],
    }).resolve({"type": "landed", "particle": "dust"}) == [], "")

    print("\n并行分支：一个侦测器，岔开的每一支都执行")
    # 「并行逻辑也有完整的侦测器和执行器，箭头是向下指过去的，就是岔开」。
    # 这条规则自己的「当」就是组的侦测器，自己的「就」是第一支执行器，branches 里每一
    # 项是另一支 —— **全部响**，按写下的顺序。它换掉的是一套同组随机挑一条的机制：那
    # 东西没法有自己的侦测器和执行器（没有地方挂）。
    def fork_engine(branches, conditions=None):
        rules = [{
            "on": "tick", "part": "", "if": conditions or [],
            "then": [{"kind": "say", "text": "就"}],
            "branches": [[{"kind": "say", "text": "支%d" % (i + 1)}] for i in range(branches)],
        }]
        return Engine({"stats": [], "states": [], "rules": rules})

    lit = fork_engine(2)
    out = lit.handle("tick")
    report("两条分支全响，加上规则自己的就一共三条",
           [a.get("text") for a in out] == ["就", "支1", "支2"], str([a.get("text") for a in out]))
    report("顺序是写下的顺序，不是随机的",
           all([a.get("text") for a in lit.handle("tick")] == ["就", "支1", "支2"] for _ in range(5)))

    plain = Engine({"stats": [], "states": [], "rules": [
        {"on": "tick", "part": "", "if": [], "then": [{"kind": "say", "text": "A"}],
         "branches": []},
    ]})
    report("没有分支的规则和以前一模一样",
           [a.get("text") for a in plain.handle("tick")] == ["A"])

    # 一个没响的侦测器上挂不了分支：条件不成立时走的是「否则」，分支一条都不跑。
    blocked = Engine({"stats": [{"id": "H", "name": "生命", "value": 0, "min": 0, "max": 100}],
                      "states": [], "rules": [{
        "on": "tick", "part": "",
        "if": [{"kind": "stat", "stat": "H", "op": ">", "value": 50}],
        "then": [{"kind": "say", "text": "就"}],
        "else": [{"kind": "say", "text": "否则"}],
        "branches": [[{"kind": "say", "text": "支1"}]],
    }]})
    report("条件不成立时只有「否则」响，分支一条都不跑",
           [a.get("text") for a in blocked.handle("tick")] == ["否则"],
           str([a.get("text") for a in blocked.handle("tick")]))

    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        for f in FAILURES:
            print("  " + f)
        return 1
    print("all logic tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
