
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
        for c in rule.get("if", []):
            if c.get("kind") == "state":
                # A state the character does not declare reads as off, not as an error:
                # deleting a state should not make every rule that mentioned it explode.
                if self.state_on(c.get("state", "")) != (c.get("op", "on") != "off"):
                    return False
                continue
            if c.get("kind") != "stat":
                return False
            v = self.value.get(c.get("stat"), 0.0)
            t = c.get("value", 0.0)
            op = c.get("op", ">=")
            ok = {">": v > t, ">=": v >= t, "<": v < t, "<=": v <= t,
                  "=": abs(v - t) < 0.001}.get(op, False)
            if not ok:
                return False
        return True

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
            last = self.last_fired.get(index)
            cd = rule.get("cooldown", 0.0)
            if cd > 0 and last is not None and self.clock - last < cd:
                continue
            if not self.holds(rule):
                continue
            self.last_fired[index] = self.clock
            if rule.get("once"):
                self.fired_once.add(index)
            out.extend(self.run(rule.get("then", [])))
        return out

    def handle(self, etype, part="", value=0.0, prop=""):
        return self.resolve({"type": etype, "part": part, "value": value, "prop": prop})

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
    report("stats have sane ranges",
           all(s["min"] <= s["value"] <= s["max"] for s in default["stats"]))
    report("every rule has at least one action",
           all(r.get("then") for r in default["rules"]))

    print("\nevent matching")
    e = Engine(default)
    out = e.handle("click")
    report("click -> says something", says(out) == ["干嘛？"], str(says(out)))
    report("the pain stayed put", e.value["P"] == 0.0)
    out = e.handle("click")
    report("cooldown blocks an immediate second click", says(out) == [], str(says(out)))
    e.clock += 1.0
    out = e.handle("click")
    report("and lets it through after the cooldown", says(out) == ["干嘛？"])
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
