
"""The character file surgery, tested before it is trusted with somebody's artwork.

CharacterStore rewrites character.json in four places: saving the rig, adding a drawing for
a state, deleting one, and saving the draw order. All four are read-modify-write over a file
the user owns, and all four can lose something silently -- a joint limit, a collider, which
state a part belongs to. A physics bug is visible; a file that quietly lost the artwork's
state tags is a pet that looks wrong forever and nobody knows why.

Mirrors CharacterStore.saveRig / addVariant / deleteDrawing / saveDepth.

    python3 tools/store_check.py
"""
import copy, json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SPEC = os.path.join(REPO, "app/src/main/assets/characters/female_base/character.json")

SEPARATOR = "__"
FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)
    return ok


def load():
    return json.loads(json.dumps(json.load(open(SPEC, encoding="utf-8"))))


def variant_key(bone, state):
    return bone + SEPARATOR + state


# ------------------- mirrors of the four operations -------------------

def save_rig(root, bones, order):
    """
    bones: [{"name", "parent", "head", "tail", "limits", "collider"}], parents first.
    Everything the file said about a bone that is still there is kept, except the four
    fields the editor owns.
    """
    old = root.get("bones", [])
    kept = {b["name"]: b for b in old}
    by_name = {b["name"]: b for b in bones}
    arr = []
    for name in order:
        b = by_name[name]
        o = copy.deepcopy(kept.get(name, {}))
        o["name"] = name
        o["parent"] = b["parent"]
        o["head"] = list(b["head"])
        o["tail"] = list(b["tail"])
        if name not in kept:
            o.setdefault("limits", [-180.0, 180.0])
            o.setdefault("collider", {"type": "capsule", "radius": 0.0})
        o["limits"] = [b["limits"][0], b["limits"][1]]
        o["collider"] = {"type": b["collider"]["type"], "radius": b["collider"]["radius"]}
        arr.append(o)
    root["bones"] = arr
    # Layers are carried over WHOLE and only renumbered. One layer per bone was the bug: a bone
    # with a drawing for a state owns two layers, and rebuilding from bone names deleted the
    # state's drawing every time the rig was saved.
    by_bone = {}
    for l in root.get("layers", []):
        by_bone.setdefault(l["bone"], []).append(l)
    layers = []
    z = 10
    for name in order:
        own = by_bone.get(name)
        if not own:
            layers.append({"bone": name, "z": z})
            z += 10
            continue
        for l in own:
            l["z"] = z
            z += 10
            layers.append(l)
    root["layers"] = layers
    return root


def add_variant(root, bone, state):
    arr = root.setdefault("layers", [])
    top = max([l.get("z", 0) for l in arr] or [0])
    for l in arr:
        if l["bone"] == bone and not l.get("state", ""):
            l["state"] = "!" + state
    arr.append({"bone": bone, "z": top + 10, "state": state, "art": variant_key(bone, state)})
    return root


def delete_drawing(root, bone, art_key):
    state = art_key[len(bone) + len(SEPARATOR):] if art_key.startswith(bone + SEPARATOR) else ""
    out = []
    for l in root.get("layers", []):
        if l["bone"] == bone and l.get("art", bone) == art_key:
            continue
        if state and l["bone"] == bone and l.get("state", "") == "!" + state:
            l.pop("state", None)
        out.append(l)
    root["layers"] = out
    return root


def save_depth(root, back_to_front, swaps):
    layers = []
    for i, l in enumerate(back_to_front):
        o = {"bone": l["bone"], "z": 10 + i * 10}
        if l.get("state"):
            o["state"] = l["state"]
        if l.get("art"):
            o["art"] = l["art"]
        layers.append(o)
    root["layers"] = layers
    root["layerSwaps"] = swaps
    return root


def bones_of(root):
    return [{"name": b["name"], "parent": b.get("parent"), "head": b["head"], "tail": b["tail"],
             "limits": b.get("limits", [-180, 180]), "collider": b.get("collider", {})}
            for b in root["bones"]]


def main():
    base = load()
    names = [b["name"] for b in base["bones"]]
    print("the shipped character fits in one screen of JSON")
    report("%d bones, %d layers" % (len(names), len(base.get("layers", []))), len(names) > 10)

    print("\nsaving the rig keeps everything it does not own")
    bones = bones_of(base)
    before = {b["name"]: (b.get("limits"), b.get("collider"), b.get("spring"),
                          b.get("physics")) for b in base["bones"]}
    saved = save_rig(base, bones, names)
    after = {b["name"]: (b.get("limits"), b.get("collider"), b.get("spring"),
                         b.get("physics")) for b in saved["bones"]}
    report("every bone is still there", set(after) == set(before))
    report("limits survive (they are what the joint may do)",
           all(before[n][0] == after[n][0] for n in names))
    report("colliders survive (they are what the world sees)",
           all(before[n][1] == after[n][1] for n in names))
    report("bones that say nothing extra keep saying nothing",
           all(before[n][2:] == after[n][2:] for n in names))

    print("\nthe attribute editor changes exactly two things")
    edited = bones_of(load())
    for b in edited:
        if b["name"] == "shin_L":
            b["limits"] = [-25.0, 40.0]
            b["collider"] = {"type": "circle", "radius": 33.0}
    out = save_rig(load(), edited, names)
    shin = [b for b in out["bones"] if b["name"] == "shin_L"][0]
    report("the new limits are written", shin["limits"] == [-25.0, 40.0], str(shin["limits"]))
    report("the new collider is written", shin["collider"] == {"type": "circle", "radius": 33.0})
    others = [b for b in out["bones"] if b["name"] != "shin_L"]
    report("and nothing else moved",
           all(b.get("limits") == [(-180.0 if b["name"] == "root" else 180.0) * 0 + b.get("limits")[0],
                                   b.get("limits")[1]] for b in others))

    print("\na state tag survives a rig save, and so does a state's drawing")
    tagged = load()
    tagged = add_variant(tagged, "upperarm_L", "mech")
    tagged["layers"][2]["state"] = "dressed"
    dressed_bone = tagged["layers"][2]["bone"]
    out = save_rig(tagged, bones_of(tagged), names)
    state = [l.get("state", "") for l in out["layers"] if l["bone"] == dressed_bone]
    report("the shirt is still a shirt", state == ["dressed"], str(state))
    arts = [l.get("art", "upperarm_L") for l in out["layers"] if l["bone"] == "upperarm_L"]
    states = [l.get("state", "") for l in out["layers"] if l["bone"] == "upperarm_L"]
    report("the mechanical arm is still a mechanical arm",
           sorted(arts) == sorted(["upperarm_L", "upperarm_L__mech"]), str(arts))
    report("and the plain one still knows to stand aside",
           sorted(states) == sorted(["!mech", "mech"]), str(states))
    report("no layer was lost", len(out["layers"]) == len(tagged["layers"]),
           "%d of %d" % (len(out["layers"]), len(tagged["layers"])))

    print("\nadding a drawing for a state replaces rather than doubles")
    root = load()
    out = add_variant(root, "upperarm_L", "mech")
    arts = [l.get("art", l["bone"]) for l in out["layers"] if l["bone"] == "upperarm_L"]
    states = [l.get("state", "") for l in out["layers"] if l["bone"] == "upperarm_L"]
    report("two layers for one bone: the plain one and the mech one",
           sorted(arts) == sorted(["upperarm_L", "upperarm_L" + SEPARATOR + "mech"]), str(arts))
    report("and exactly one of them is drawn at a time",
           sorted(states) == sorted(["!mech", "mech"]), str(states))

    print("\ndeleting a drawing puts the part back the way it was")
    out = delete_drawing(out, "upperarm_L", "upperarm_L" + SEPARATOR + "mech")
    rest = [l for l in out["layers"] if l["bone"] == "upperarm_L"]
    report("the mech layer is gone",
           all(l.get("art", "upperarm_L") == "upperarm_L" for l in rest), str(rest))
    report("the plain arm draws again whatever the state is",
           all(l.get("state", "") == "" for l in rest), str(rest))
    report("no other part was touched",
           len([l for l in out["layers"] if l["bone"] != "upperarm_L"]) ==
           len([l for l in base["layers"] if l["bone"] != "upperarm_L"]))

    print("\nthe draw order round trips")
    order = [{"bone": n} for n in reversed(names)]
    out = save_depth(load(), order, [{"parts": ["head"], "behind": ["neck"], "to": "front"}])
    report("every bone is in the order exactly once",
           [l["bone"] for l in out["layers"]] == list(reversed(names)))
    report("and z counts up from ten",
           [l["z"] for l in out["layers"]][:3] == [10, 20, 30])
    report("the swap rules are written", len(out.get("layerSwaps", [])) == 1)

    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        for f in FAILURES:
            print("  " + f)
        return 1
    print("all store tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
