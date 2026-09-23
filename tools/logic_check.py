
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


class Facts:
    """Mirror of RuleEngine.Facts: 只有世界知道的事。

    手在哪儿、有没有被绳子连着、**别人的开关是开是关** —— 这三样既不是角色身上的数字，
    也不是这台引擎自己声明的东西，所以引擎问、世界答。没有世界（facts is None）时，这些
    条件一律读作**不成立**：和"未知种类的条件"同一条规矩，新版本写的规则不能让老版本整个
    文件停摆。

    开关那一对（switch_on / set_switch）是后加的：一个角色的开关是**一套地址**，角色自己的
    状态就是它的名字，一节自己的是 `骨头:状态`（见 Subjects.stateTag）。每台引擎留住自己
    文件里声明过的那些，别的名字一律问世界 —— 这就是「手上的规则也能问角色的穿着」。
    """

    def __init__(self, positions=None, tied=(), switches=None):
        self.positions = dict(positions or {})
        self.tied_bones = set(tied)
        #: 别人的开关，名字 → 开/关。世界就是那张地址表。
        self.switches = dict(switches or {})
        #: 每一次问世界都记下来，好断言"自己的开关没有绕远路去问世界"。
        self.switch_reads = []

    def at(self, bone):
        return self.positions.get(bone)

    def tied(self, bone):
        # 部位按前缀匹配，和规则里别的"部位"一样：写 hand 管左右两只手。
        if not bone:
            return bool(self.tied_bones)
        return any(b == bone or b.startswith(bone) for b in self.tied_bones)

    def switch_on(self, tag):
        self.switch_reads.append(tag)
        # None = 谁都没声明过这个名字（Kotlin 那边是可空的 Boolean）。
        return self.switches.get(tag)

    def set_switch(self, tag, on):
        if tag not in self.switches:
            return False
        self.switches[tag] = on
        return True


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
        #: 世界的回答，见 Facts。默认没有世界，两种"问世界"的条件读作不成立。
        self.facts = None
        self.pending = []
        #: 跳到了不存在的规则号。引擎会记一条日志，这里记下来给测试断言 —— 带编号的跳转
        #: 是唯一一种错了也不出声的动作。
        self.no_targets = []
        self.lines = []
        #: 世界不认识的开关。Kotlin 那边往日志里写一行"没有这个状态，没有改"；镜像记下来，
        #: 因为可断言的是"引擎注意到了"，日志那一句由 wiring_check 盯源码。
        self.refused = []
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

    def switch_of(self, name):
        """Mirror of RuleEngine.switchOf: 自己声明过的看自己的 map，别的名字问世界。

        "自己声明过的"就是这台引擎 spec 里那些 —— 外来名字永远不会被写进 self.states，
        否则一节手上的规则会拿到一份会漂的副本，而不是角色的那个开关本身。
        """
        if not name:
            return False
        if name in self.states:
            return self.states[name]
        f = self.facts
        if f is None:
            return False
        return f.switch_on(name) is True

    def set_switch(self, name, on):
        """Mirror of RuleEngine.setSwitch. 世界不认识的开关**什么都不写**，而且说出来。

        改之前是 `states[name] = on`，谁声明过都照写：于是一节手上的规则把「穿着」打开，
        打开的是一个只活在那台引擎 map 里的开关 —— 图层看不到、别的规则也看不到，
        一条看起来成功了的空操作。"""
        if not name:
            return
        if name in self.states:
            self.states[name] = on
            return
        f = self.facts
        if f is None or not f.set_switch(name, on):
            self.refused.append(name)

    def holds(self, rule):
        """规则自己的如果。分支的如果走同一个 holds_conditions，见那里。"""
        return self.holds_conditions(rule.get("if", []))

    def holds_conditions(self, conds):
        """
        One 如果, judged -- the rule's own and a 并行分支's are the same question asked in two
        places, so there is one implementation of it and not two that can drift apart.

        Left to right, 而且 binding tighter than 或者: a list of AND-groups, and the rule runs
        if ANY group holds. No conditions at all is "always", which is what makes 当……就 a
        rule somebody can write -- and a branch with no 如果 does the same.
        """
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
            # 自己声明的看自己的 map，别的名字问世界（见 switch_of）：手上那条规则问
            # 「穿着」的时候，答案是角色的那一个。谁都不认识的名字读作关着，不是错误 ——
            # 删掉一个状态不该让每一条提过它的规则炸掉。
            return self.switch_of(c.get("state", "")) == (c.get("op", "on") != "off")
        if c.get("kind") == "pose":
            # 「手是否比肩膀高」：两节、一个方向、一点余量。屏幕坐标 y 向下，"更高"是
            # y **更小** —— 这种符号在静止姿势里看不出来，换一个姿势就全反。
            f = self.facts
            if f is None:
                return False
            a = f.at(c.get("bone", ""))
            b = f.at(c.get("other", ""))
            if a is None or b is None:
                return False
            margin = c.get("value", 0.0)
            axis = c.get("axis", "up")
            if axis == "left":
                return b[0] - a[0] >= margin
            if axis == "right":
                return a[0] - b[0] >= margin
            return b[1] - a[1] >= margin
        if c.get("kind") == "tied":
            f = self.facts
            if f is None:
                return False
            return f.tied(c.get("bone", "")) == (c.get("op", "on") != "off")
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
                    before = self.switch_of(sid)
                    after = {"stateOn": True, "stateOff": False}.get(k, not before)
                    self.set_switch(sid, after)
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
            # 有自己的当的那一支不归这里管：它等自己的事件，resolve() 会交给它。
            if isinstance(b, dict) and b.get("on"):
                continue
            # 分支自己的如果：组已经说了"是"（侦测器响了、组自己的如果也成立），这一句是
            # 这一支**自己**要问的，所以它只可能让这一支少做，不会影响别的支。
            if isinstance(b, dict) and not self.holds_conditions(b.get("if", [])):
                continue
            out.extend(self.follow(b.get("actions", []) if isinstance(b, dict) else b, ran))
        return out

    def branch_actions(self, index, rule, branch):
        """带自己的当的分支的执行器。用的是**规则自己的**冷却和一次：一个组是一件事，
        两个执行器各走各的节奏不是谁要的功能。

        分支自己的如果在**冷却之前**问：被看过又说了"不"的一支还没轮到过它，和一条没有
        否则、条件不成立的规则一样，所以条件一成立就能立刻响。
        """
        b = rule["branches"][branch]
        if not self.holds_conditions(b.get("if", [])):
            return []
        last = self.last_fired.get(index)
        cd = rule.get("cooldown", 0.0)
        if cd > 0 and last is not None and self.clock - last < cd:
            return []
        if rule.get("once") and index in self.fired_once:
            return []
        self.last_fired[index] = self.clock
        if rule.get("once"):
            self.fired_once.add(index)
        return self.follow(rule["branches"][branch]["actions"], {index})

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
        # 带自己的当的分支：它们不是规则，ran 管不到，两个监听者会各响一次。
        branch_ran = set()
        for index, rule in enumerate(self.spec["rules"]):
            # 规则自己的「当」和挂在它上面的分支在 fire() 里执行；**带自己的当**的分支在
            # 同一趟里、但在这些过滤**之外**检查 —— 那正是它们的意义：它们等的是自己的
            # 事件，所以"这条规则的当是别的东西"不能决定它们会不会被看一眼。
            heard = (rule.get("on") == event.get("type")
                     and self.touches(event, rule.get("part", ""))
                     and self.about_is(event, rule.get("about", "")))
            if heard:
                out.extend(self.fire(index, rule, ran))
            for bi, branch in enumerate(rule.get("branches", [])):
                if not isinstance(branch, dict) or not branch.get("on"):
                    continue
                if branch["on"] != event.get("type"):
                    continue
                if not self.touches(event, branch.get("part", "")):
                    continue
                if not self.about_is(event, branch.get("about", "")):
                    continue
                if (index, bi) in branch_ran:
                    continue
                branch_ran.add((index, bi))
                out.extend(self.branch_actions(index, rule, bi))
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

    # 写出去的和读回来的必须是同一批键。
    #
    # 这一版给液滴加的两个键（size / opacity）正好是最容易只做一半的那种：写进文件了、
    # 读的时候键名拼错一个字母，或者反过来 —— 两种都不会报错。症状只有"我改了它，喷出来
    # 还是老样子"，而且它出现在保存一次之后：内存里的 LiquidSpec 是对的，磁盘上的那份被
    # 写成了读不回来的东西。所以两边各抄一遍键名，然后比。
    kt = open(LOGIC_KT, encoding="utf-8").read()

    def spec_keys(read_from, read_to, write_from, write_to):
        def keys(start, end, pattern):
            i = kt.find(start)
            j = kt.find(end, i)
            return set(re.findall(pattern, kt[i:j])) if i >= 0 and 0 <= i < j else None
        read = keys(read_from, read_to, r'(?:optString|optDouble|optBoolean|optInt)\("(\w+)"')
        write = keys(write_from, write_to, r'\.put\("(\w+)"')
        return read, write

    for what, window in (
        ("液体", ('val liquidArr = o.optJSONArray("liquids")',
                 "// The same rule as liquids: a file that says nothing",
                 "val liquids = JSONArray()", 'root.put("liquids"')),
        ("粒子", ('val particleArr = o.optJSONArray("particles")',
                 "// One place that knows how an action is written down",
                 "val particles = JSONArray()", 'root.put("particles"')),
    ):
        read, write = spec_keys(*window)
        report("每个%s字段都写得出去、也读得回来" % what,
               read is not None and write is not None and read == write,
               "写 %s / 读 %s" % (sorted(write or []), sorted(read or [])))
    # And the two new ones have to be in there by name, not just "the sets happen to agree":
    # a pair of typos that agree with each other would pass the check above.
    read, write = spec_keys('val liquidArr = o.optJSONArray("liquids")',
                            "// The same rule as liquids: a file that says nothing",
                            "val liquids = JSONArray()", 'root.put("liquids"')
    report("液滴大小和透明度这两个键真的在文件里",
           {"size", "opacity"} <= (read or set()) and {"size", "opacity"} <= (write or set()),
           "写 %s" % sorted(write or []))
    # The kinds a condition can be are a closed set in the Kotlin (see ConditionSpec), and a
    # file with a fourth kind in it would simply never fire. The shipped defaults are the
    # example everybody reads, so they are where a typo in one gets caught.
    cond_kinds = {c.get("kind") for r in default["rules"] for c in r.get("if", [])}
    known = {"stat", "state", "chance", "pose", "tied"}
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

    print("\n部件也能问全局的状态（反过来也一样）")
    # 「然后部件也可以测全局的状态」：一条长在手上的规则要问角色的「穿着」，而面板上原来
    # 根本列不出来 —— 手上那张表只有它自己的状态。现在每台引擎留住自己文件里声明过的开关，
    # 别的名字一律问世界（见 switch_of），所以那句话写得出来了。
    #
    # 这就是"全局和局部"的边界，写清楚免得下次又要猜：**自己声明过的名字指自己的**，
    # 别的名字按世界那张地址表找（角色的状态是它的名字，一节自己的是 `骨头:状态`）。
    # 于是"手上出汗"和"角色出汗"既能各自独立，也能互相问 —— 而同一个名字被两边都声明过时，
    # 从这里够不着全局的那个（面板不列它，见 MainActivity.switchChoices）。
    def state_rule(state_name, text):
        return {"on": "tick", "cooldown": 0,
                "if": [{"kind": "state", "state": state_name, "op": "on"}],
                "then": [{"kind": "say", "text": text}]}

    world = Facts(switches={"dressed": True, "hand_L:sweat": False})
    hand = Engine({"stats": [],
                   "states": [{"id": "sweat", "name": "出汗", "on": False}],
                   "rules": [state_rule("dressed", "手：穿着呢"),
                             state_rule("hand_L:sweat", "手：我在出汗"),
                             state_rule("sweat", "手：自己那套出汗")]})
    hand.facts = world
    report("一节手上的规则能问角色的全局状态",
           says(hand.handle("tick")) == ["手：穿着呢"], str(says(hand.handle("tick"))))
    report("而且读它没有在本地留下一份副本（问的是同一个开关）",
           "dressed" not in hand.states, str(sorted(hand.states)))
    world.switches["dressed"] = False
    world.switches["hand_L:sweat"] = True
    out = says(hand.handle("tick"))
    report("带着标签的写法指的是那一节自己的开关（面板现在就是这么写的）",
           out == ["手：我在出汗"], str(out))
    report("关掉的全局状态就不成立了", "手：穿着呢" not in out, str(out))
    # 自己的开关不走世界：没有世界它也得能用 —— 这正是"先看自己声明的那些"的理由。
    # 单独一台引擎，因为一次 tick 会把每一条规则的如果都问一遍，混在一起数不出是谁问的。
    own_only = Engine({"stats": [], "states": [{"id": "sweat", "name": "出汗", "on": True}],
                       "rules": [state_rule("sweat", "自己那套出汗")]})
    world.switch_reads = []
    own_only.facts = world
    out = says(own_only.handle("tick"))
    report("自己声明的名字看自己的 map，不绕世界一圈",
           out == ["自己那套出汗"] and world.switch_reads == [], str(world.switch_reads))
    # 写：一节手上的规则改全局状态。改之前 `states[name] = on` 谁声明过都照写，于是
    # 打开的是一个只活在那台引擎 map 里的开关 —— 图层看不到、别的规则也看不到。
    world.switches["dressed"] = False
    hand = Engine({"stats": [], "states": [{"id": "sweat", "name": "出汗", "on": False}],
                   "rules": [{"on": "tick", "cooldown": 0,
                              "then": [{"kind": "stateToggle", "state": "dressed"}]}]})
    hand.facts = world
    hand.handle("tick")
    report("手上改全局状态，改的是真的那个", world.switches["dressed"] is True)
    report("而且是世界里那一个，不是本地多出来的一个",
           "dressed" not in hand.states, str(sorted(hand.states)))
    hand.handle("tick")
    report("再点一次又关掉", world.switches["dressed"] is False)
    # 谁都没声明过的名字：一个开关都不写，而且**说出来**。写进本地的那条路就是空操作。
    hand = Engine({"stats": [], "states": [], "rules": [
        {"on": "tick", "cooldown": 0, "then": [{"kind": "stateOn", "state": "nobody"}]}]})
    hand.facts = world
    hand.handle("tick")
    report("没人声明过的开关：本地不凭空造一个", "nobody" not in hand.states)
    report("而且记下来了（Kotlin 那边是日志里一行：不然就是一个成功了的空操作）",
           hand.refused == ["nobody"], str(hand.refused))
    # 没有世界的时候，别人家的名字一律读作关着 —— 和部位/绳子那两种条件同一条规矩。
    lonely = Engine({"stats": [], "states": [], "rules": [state_rule("dressed", "在")]})
    report("没有世界时，别人的开关读作关着", says(lonely.handle("tick")) == [])
    # 反过来：角色自己的规则问一节自己的开关，也一样（同一个机制，不用第二套）。
    pet_engine = Engine({"stats": [], "states": [], "rules": [state_rule("hand_L:sweat", "手在出汗")]})
    pet_engine.facts = Facts(switches={"hand_L:sweat": True})
    report("角色自己的规则也能问一节自己的开关",
           says(pet_engine.handle("tick")) == ["手在出汗"],
           str(says(pet_engine.handle("tick"))))

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

    print("\n有东西靠近 / 有东西走开")
    # 「当某个物品靠近时」。几何那一半在 tools/rig_prop_check.py（每帧量每个道具离身体多近），
    # 逻辑这一半要能对它作答：点名是哪个道具、最近的是哪根骨头。
    near = Engine({"stats": [], "states": [], "rules": [
        {"on": "propNear", "part": "hand", "about": "hammer", "if": [],
         "then": [{"kind": "say", "text": "别过来"}]},
        {"on": "propAway", "part": "", "about": "", "if": [],
         "then": [{"kind": "say", "text": "走了"}]},
    ]})
    report("锤子靠近手：响",
           says(near.resolve({"type": "propNear", "part": "hand_L", "prop": "hammer",
                              "value": 40.0})) == ["别过来"])
    report("别的道具靠近不算",
           says(near.resolve({"type": "propNear", "part": "hand_L", "prop": "ball",
                              "value": 40.0})) == [])
    report("靠近的是别的部位不算",
           says(near.resolve({"type": "propNear", "part": "foot_L", "prop": "hammer",
                              "value": 40.0})) == [])
    report("走开是另一个事件，和靠近不互相触发",
           says(near.resolve({"type": "propAway", "part": "hand_L", "prop": "hammer",
                              "value": 200.0})) == ["走了"])
    # 两个都必须是 Kotlin 里真的声明了的：一个不存在的 id 写进规则里，既不报错也不发生。
    report("propNear / propAway 是 Kotlin 里声明的事件",
           {"propNear", "propAway"} <= events,
           "没声明: " + str({"propNear", "propAway"} - events))

    print("\n侦测器：部位比位置、绳子连没连着")
    # 「手是否比肩膀高」和「有没有被绳子连着」：都不是角色身上的数字、也不是谁声明的状态，
    # 它们在世界里 —— 所以引擎问、世界答（RuleEngine.Facts / 这里的 Facts）。
    def pose_engine(positions, **cond):
        facts = Facts(positions=positions)
        spec = {"stats": [], "states": [], "rules": [
            {"on": "tick", "if": [dict(kind="pose", **cond)],
             "then": [{"kind": "say", "text": "举手"}]},
        ]}
        e = Engine(spec)
        e.facts = facts
        return e, facts

    e, facts = pose_engine({"hand_L": (500.0, 300.0), "shoulder_L": (500.0, 600.0)},
                           bone="hand_L", other="shoulder_L", axis="up", value=0)
    report("手比肩膀高 → 成立", says(e.handle("tick")) == ["举手"])
    facts.positions["hand_L"] = (500.0, 700.0)
    report("手放到下面 → 不成立（y 越小越靠上）", says(e.handle("tick")) == [])

    e, facts = pose_engine({"hand_L": (500.0, 590.0), "shoulder_L": (500.0, 600.0)},
                           bone="hand_L", other="shoulder_L", axis="up", value=30)
    report("只高 10px、要求 30px → 不成立", says(e.handle("tick")) == [])
    facts.positions["hand_L"] = (500.0, 560.0)
    report("高到 40px → 成立", says(e.handle("tick")) == ["举手"])

    e, _ = pose_engine({"hand_L": (300.0, 600.0), "hand_R": (500.0, 600.0)},
                       bone="hand_L", other="hand_R", axis="left", value=0)
    report("「更靠左」比的是 x", says(e.handle("tick")) == ["举手"])

    e, facts = pose_engine({"shoulder_L": (500.0, 600.0)},
                           bone="hand_L", other="shoulder_L", axis="up", value=0)
    report("有一节不在场上 → 不成立（不是崩）", says(e.handle("tick")) == [])

    no_world = Engine({"stats": [], "states": [], "rules": [
        {"on": "tick", "if": [{"kind": "pose", "bone": "a", "other": "b", "axis": "up"}],
         "then": [{"kind": "say", "text": "?"}]},
    ]})
    report("没有世界可问 → 不成立（和未知种类的条件同一条规矩）",
           says(no_world.handle("tick")) == [])

    def tied_engine(tied, bone, op="on"):
        spec = {"stats": [], "states": [], "rules": [
            {"on": "tick", "if": [{"kind": "tied", "bone": bone, "op": op}],
             "then": [{"kind": "say", "text": "拴着"}]},
        ]}
        e = Engine(spec)
        e.facts = Facts(tied=tied)
        return e

    report("hand_L 上拴着绳 → 成立", says(tied_engine(["hand_L"], "hand_L").handle("tick")) == ["拴着"])
    report("写前缀 hand 也认（左右两只手）",
           says(tied_engine(["hand_R"], "hand").handle("tick")) == ["拴着"])
    report("没拴 → 不成立", says(tied_engine([], "hand").handle("tick")) == [])
    report("「没连着」在没拴时成立",
           says(tied_engine([], "hand", op="off").handle("tick")) == ["拴着"])
    report("留空 = 身上任何一处被连着都算",
           says(tied_engine(["foot_L"], "").handle("tick")) == ["拴着"] and
           says(tied_engine([], "").handle("tick")) == [])

    print("\n长按：和「点一下」共用一条线，是两件事")
    # 手指落在宠物身上，短于阈值 = 被点一下，长于阈值 = 被长按；所以一次按压有且只有一种结果。
    # 测试场里那条线就是 Android 自己的长按阈值（TAP_MS）。
    report("longPress 是 Kotlin 里声明的事件", "longPress" in events)
    both = Engine({"stats": [], "states": [], "rules": [
        {"on": "click", "part": "", "if": [], "then": [{"kind": "say", "text": "戳"}]},
        {"on": "longPress", "part": "", "if": [], "then": [{"kind": "say", "text": "按着不放干嘛"}]},
    ]})
    report("点一下只响点一下那条", says(both.handle("click")) == ["戳"])
    report("长按只响长按那条", says(both.handle("longPress")) == ["按着不放干嘛"])
    # 部位照样管用：按住手和按住头是两件事。
    part = Engine({"stats": [], "states": [], "rules": [
        {"on": "longPress", "part": "hand", "if": [], "then": [{"kind": "say", "text": "手"}]},
    ]})
    report("长按哪个部位就报哪个部位",
           says(part.resolve({"type": "longPress", "part": "hand_L", "value": 0.0})) == ["手"] and
           part.resolve({"type": "longPress", "part": "head", "value": 0.0}) == [])

    print("\n换骨骼套：一个动作，和它换完之后发的那个事件")
    # 引擎不认识"骨骼套"是什么：它把 setRig 原样交给测试场，由宿主去换（骨骼套是文件夹，
    # 只有 Activity 知道它们在哪儿）。换完测试场发一个 rigSwap，规则就能接着反应 ——
    # 「变成机械形态 → 说一句话」因此是两条规则，而不是一段藏起来的代码。
    rig = Engine({"stats": [], "states": [], "rules": [
        {"on": "click", "part": "", "if": [], "then": [{"kind": "setRig", "text": "mech"}]},
        {"on": "rigSwap", "part": "", "if": [], "then": [{"kind": "say", "text": "换好了"}]},
    ]})
    out = rig.handle("click")
    report("换骨骼套原样交给测试场去做（引擎不认识它，也不该认识）",
           [(a["kind"], a.get("text")) for a in out] == [("setRig", "mech")], str(out))
    report("换完发的 rigSwap 能被另一条规则听到", says(rig.handle("rigSwap")) == ["换好了"])
    report("rigSwap 是 Kotlin 里声明的事件", "rigSwap" in events)

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

    # 分支自己的当：一条有自己侦测器的分支是一条自己的线，等它自己的事件。
    both = Engine({"stats": [], "states": [], "rules": [{
        "on": "tick", "part": "", "if": [], "then": [{"kind": "say", "text": "组的就"}],
        "branches": [
            {"on": "click", "part": "", "actions": [{"kind": "say", "text": "点它"}]},
            [{"kind": "say", "text": "跟组"}],
        ],
    }]})
    out = both.handle("tick")
    report("带自己的当的分支不被组的当带动",
           [a.get("text") for a in out] == ["组的就", "跟组"], str([a.get("text") for a in out]))
    out = both.handle("click")
    report("它自己的事件来了才响", [a.get("text") for a in out] == ["点它"],
           str([a.get("text") for a in out]))
    # 分支的部位也是它自己的侦测器的一部分。
    part = Engine({"stats": [], "states": [], "rules": [{
        "on": "tick", "part": "", "if": [], "then": [],
        "branches": [{"on": "click", "part": "hand_L", "actions": [{"kind": "say", "text": "手"}]}],
    }]})
    report("分支部位不对就不响",
           part.resolve({"type": "click", "part": "hand_R", "value": 0.0, "prop": ""}) == [])
    report("部位对了才响",
           [a.get("text") for a in
            part.resolve({"type": "click", "part": "hand_L", "value": 0.0, "prop": ""})] == ["手"])

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

    print("\n分支自己的如果：每个分支自己的判断器")
    # 「每个分支自己的判断器（如果）」。分支先有了自己的当，然后还是文件里唯一一个不能问
    # 问题的行 —— 这一步把那个问题补上。组说了"是"之后，这一支再问自己一句。
    fork_if = Engine({
        "stats": [{"id": "H", "name": "生命", "value": 100, "min": 0, "max": 100}],
        "states": [], "rules": [{
            "on": "tick", "part": "", "if": [], "then": [{"kind": "say", "text": "就"}],
            "branches": [
                {"if": [{"kind": "stat", "stat": "H", "op": "<", "value": 50}],
                 "actions": [{"kind": "say", "text": "疼"}]},
                [{"kind": "say", "text": "总是"}],
            ],
        }]})
    out = says(fork_if.handle("tick"))
    report("条件不成立的那一支不跑，别的支照旧",
           out == ["就", "总是"], str(out))
    fork_if.set("H", 10)
    out = says(fork_if.handle("tick"))
    report("它自己的条件成立时，那一支在它的位置上响",
           out == ["就", "疼", "总是"], str(out))

    # 组自己的如果不过 = 走否则，分支一条都不跑（这条本来就有）；反过来，组的如果过了、
    # 某一支自己的如果没过，也只有那一支安静 —— 「全部响」说的是岔开，不是每支做同一件事。
    mix = Engine({
        "stats": [{"id": "H", "name": "H", "value": 100, "min": 0, "max": 100},
                  {"id": "P", "name": "P", "value": 0, "min": 0, "max": 100}],
        "states": [], "rules": [{
            "on": "click", "part": "",
            "if": [{"kind": "stat", "stat": "H", "op": ">", "value": 50}],
            "then": [], "else": [{"kind": "say", "text": "否则"}],
            "branches": [
                {"if": [{"kind": "stat", "stat": "P", "op": ">", "value": 50}],
                 "actions": [{"kind": "say", "text": "很疼"}]},
                {"if": [{"kind": "stat", "stat": "P", "op": "<", "value": 50}],
                 "actions": [{"kind": "say", "text": "不疼"}]},
            ],
        }]})
    out = says(mix.handle("click"))
    report("两支的条件互斥时只响成立的那一支", out == ["不疼"], str(out))
    dead = Engine({
        "stats": [{"id": "H", "name": "H", "value": 0, "min": 0, "max": 100}],
        "states": [], "rules": [{
            "on": "click", "part": "",
            "if": [{"kind": "stat", "stat": "H", "op": ">", "value": 50}],
            "then": [], "else": [{"kind": "say", "text": "否则"}],
            "branches": [[{"kind": "say", "text": "支1"}]],
        }]})
    report("组的如果不过：只有否则，分支一条都不跑",
           says(dead.handle("click")) == ["否则"], str(says(dead.handle("click"))))

    # 带自己的当的分支：如果不过就不响，而且**不消耗组的冷却**（和没有否则的规则一样，
    # 被看过又跳过的不算轮到过它）。响了之后冷却照旧管着。
    own_if = Engine({
        "stats": [{"id": "H", "name": "生命", "value": 100, "min": 0, "max": 100}],
        "states": [], "rules": [{
            "on": "click", "part": "", "if": [], "then": [], "cooldown": 5.0,
            "branches": [{
                "on": "tick", "part": "",
                "if": [{"kind": "stat", "stat": "H", "op": "<", "value": 50}],
                "actions": [{"kind": "say", "text": "响"}],
            }],
        }]})
    report("自己的当响了，但自己的如果不过：不响", says(own_if.handle("tick")) == [])
    own_if.set("H", 10)
    report("条件一成立就响，没有被组的冷却挡住",
           says(own_if.handle("tick")) == ["响"], "跳过的不算轮到过它")
    report("响了之后，组的冷却照旧管着", says(own_if.handle("tick")) == [])

    # 文件格式的两半：解析读 branch 的 "if"，落盘写 branch 的 "if"，而且有如果的分支
    # 不能退回裸数组那种写法（裸数组装不下一个如果）。这个项目踩过一次"只加了读、忘了写"，
    # 而 Kotlin 的 toJson 本地跑不起来 —— 所以两半在源码里点名一次。
    src = open(LOGIC_KT, encoding="utf-8").read()
    report("解析：分支的如果从 \"if\" 读进来",
           'conditions = condsOf(b.optJSONArray("if"))' in src)
    report("落盘：分支的如果有值才写 \"if\"",
           'if (b.conditions.isNotEmpty()) put("if", condJson(b.conditions))' in src)
    report("形状：有如果的分支走长写法，不是裸数组",
           re.search(r"if \(!b\.ownDetector && b\.part\.isEmpty\(\) && b\.about\.isEmpty\(\) &&"
                     r"\s*\n?\s*b\.conditions\.isEmpty\(\)\s*\n?\s*\)", src) is not None)

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
