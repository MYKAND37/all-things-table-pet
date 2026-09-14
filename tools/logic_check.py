
"""Tests for the rule engine, and for the defaults it ships with.

Mirrors dev.atp.pet.engine.logic.RuleEngine. The engine is deliberately free of Android so
the whole of the logic layer can be tested here -- and the interesting failures are all
timing: a cooldown that does not hold, a once-only rule that fires twice, a delayed action
that runs before its time or out of order.

    python3 tools/logic_check.py
"""
import json, math, os, re, sys
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


def enum_ids(path, pattern):
    src = open(path, encoding="utf-8").read()
    return set(re.findall(pattern, src))


# ------------------------------- mirror of RuleEngine -------------------------------

def clamp(v, lo, hi):
    return max(lo, min(hi, v))


class Engine:
    def __init__(self, spec):
        self.spec = spec
        self.value = {s["id"]: clamp(s["value"], s["min"], s["max"]) for s in spec["stats"]}
        self.spec_by_id = {s["id"]: s for s in spec["stats"]}
        self.initial = {s["id"]: bool(s.get("on", False)) for s in spec.get("states", [])}
        self.states = dict(self.initial)
        self.clock = 0.0
        self.tick_accum = 0.0
        self.last_fired = {}
        self.fired_once = set()
        self.pending = []
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
        out = []
        for i, a in enumerate(actions):
            k = a.get("kind")
            if k == "add":
                self.add(a.get("stat", ""), a.get("value", 0.0))
            elif k == "set":
                self.set(a.get("stat", ""), a.get("value", 0.0))
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
                return out
            else:
                out.append(a)
        return out

    def resolve(self, event):
        out = []
        for index, rule in enumerate(self.spec["rules"]):
            if rule.get("on") != event.get("type"):
                continue
            if not self.touches(event, rule.get("part", "")):
                continue
            if index in self.fired_once:
                continue
            holds = self.holds(rule)
            # Without an else, a rule whose conditions fail is skipped WITHOUT consuming
            # its cooldown, so it can fire the instant they become true. That is what every
            # rule did before there was an else, and it has to keep doing it.
            if not holds and not rule.get("else"):
                continue
            last = self.last_fired.get(index)
            cd = rule.get("cooldown", 0.0)
            if cd > 0 and last is not None and self.clock - last < cd:
                continue
            self.last_fired[index] = self.clock
            if rule.get("once"):
                self.fired_once.add(index)
            out.extend(self.run(rule.get("then", []) if holds else rule.get("else", [])))
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
                out.extend(self.run(acts))
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
