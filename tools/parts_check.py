
"""Which layers draw, and in what order.

Mirrors LayerSpec.visible and the bit of PartRenderer that decides. It is three lines of
logic and it is the three lines that decide whether a mechanical arm replaces an arm or is
drawn on top of it, so it is worth a test rather than a look.

    python3 tools/parts_check.py
"""
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SPEC = os.path.join(REPO, "app/src/main/assets/characters/female_base/character.json")
FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)


def visible(state, states):
    """LayerSpec.visible: empty always, "x" needs x on, "!x" needs x off."""
    if not state:
        return True
    on = states.get(state.lstrip("!"), False)
    return (not on) if state.startswith("!") else on


def art_key(layer):
    return layer.get("art") or layer["bone"]


def drawn(layers, states, library):
    """Back to front, the layers that actually draw. Library: which files exist."""
    out = []
    for layer in sorted(layers, key=lambda l: l["z"]):
        if art_key(layer) not in library:
            continue
        if not visible(layer.get("state", ""), states):
            continue
        out.append(art_key(layer))
    return out


def main():
    print("no state, or a state that is on or off")
    report("no tag always draws", visible("", {}) and visible("", {"x": False}))
    report("a state needs it on", visible("x", {"x": True}) and not visible("x", {"x": False}))
    report("a bang needs it off", visible("!x", {"x": False}) and not visible("!x", {"x": True}))
    report("an undeclared state reads as off: the plain one draws",
           visible("!gone", {}) and not visible("gone", {}))

    print("\na variant replaces instead of stacking")
    layers = [
        {"bone": "upperarm_L", "z": 10, "state": "!mech"},
        {"bone": "upperarm_L", "z": 20, "state": "mech", "art": "upperarm_L__mech"},
    ]
    library = {"upperarm_L", "upperarm_L__mech"}
    off = drawn(layers, {"mech": False}, library)
    on = drawn(layers, {"mech": True}, library)
    report("state off: the plain arm, once", off == ["upperarm_L"], str(off))
    report("state on: the mechanical arm, once", on == ["upperarm_L__mech"], str(on))
    report("never both at once", len(off) == 1 and len(on) == 1)
    report("deleting the state falls back to the plain arm",
           drawn(layers, {}, library) == ["upperarm_L"])

    print("\norder and missing files")
    report("z decides the order",
           drawn([
               {"bone": "a", "z": 30}, {"bone": "b", "z": 10}, {"bone": "c", "z": 20},
           ], {}, {"a", "b", "c"}) == ["b", "c", "a"])
    report("a variant with no file draws nothing rather than the plain one",
           drawn(layers, {"mech": True}, {"upperarm_L"}) == [])

    print("\nthe shipped character file still parses")
    spec = json.load(open(SPEC))
    layers = spec.get("layers", [])
    report("every layer names a bone that exists",
           all(l["bone"] in {b["name"] for b in spec["bones"]} for l in layers))
    report("and every layer has a z",
           all(isinstance(l.get("z"), int) for l in layers), str(len(layers)) + " layers")

    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        for f in FAILURES:
            print("  " + f)
        return 1
    print("all parts tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
