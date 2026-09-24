
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


def library_keys(files, bone_names, separator="__"):
    """
    PartLibrary.load: which files become parts, keyed by the name a LAYER would use.

    A bone's own drawing is <bone>.png. A drawing for one of its states is
    <bone>__<state>.png, and the key for that one is the whole stem, because that is what the
    layer's "art" says. Loading only the bone names left every variant out of the library --
    and PartRenderer drops a layer whose art the library does not have, so the file was on
    disk, the parts folder listed it, and the pet never wore it. That is what "上传图片时看不见"
    was: importing a picture for a state and getting nothing.

    This mirrors the CONTRACT rather than the file reading, because the contract is where the
    bug was: which names are in and which are out.
    """
    out = set()
    for name in sorted(files):
        if not name.endswith(".png"):
            continue
        stem = name[:-len(".png")]
        if stem in bone_names or stem.split(separator)[0] in bone_names:
            out.add(stem)
    return out


def drawable(layers, library):
    """PartRenderer.baseOrder: the layers whose artwork is on disk, states ignored."""
    return [art_key(layer) for layer in layers if art_key(layer) in library]


def blank_reason(parts, can_draw, drew):
    """
    What the bench says when it drew nothing, in the same order as the Kotlin.

    Mirrors PhysicsSandboxView.blankReason. The order is the point: no drawings on disk,
    drawings no layer names, and drawings a state is hiding are three different problems
    whose fixes are in three different screens -- and an empty room looks the same for all
    three, which is why this exists at all.
    """
    if parts == 0:
        return "no parts at all"
    if can_draw == 0:
        return "no layer names any of them"
    if drew == 0:
        return "every one of them is hidden by a state"
    return ""


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

    print("\n两层状态：全局的和部件自己的，各画各的")
    # The renderer holds ONE map of switches, so a part's own state goes in under the tag its
    # layers use -- "hand_L:sweat" -- and the character's goes in under its plain name. That is
    # the whole of the two levels as far as drawing is concerned: visible() needs no change,
    # because a tag is a key either way. What it does need is that the two never stand in
    # for each other, which is what this checks.
    two_levels = [
        {"bone": "hand_L", "z": 10, "state": "sweat", "art": "hand_L__sweat"},
        {"bone": "hand_L", "z": 20, "state": "hand_L:sweat", "art": "hand_L__own"},
    ]
    two_library = {"hand_L__sweat", "hand_L__own"}
    report("nothing draws while both are off", drawn(two_levels, {}, two_library) == [])
    report("the character's state draws its own",
           drawn(two_levels, {"sweat": True}, two_library) == ["hand_L__sweat"])
    report("the hand's own state draws its own",
           drawn(two_levels, {"hand_L:sweat": True}, two_library) == ["hand_L__own"])
    report("and neither stands in for the other",
           len(drawn(two_levels, {"sweat": True}, two_library)) == 1 and
           len(drawn(two_levels, {"hand_L:sweat": True}, two_library)) == 1)

    print("\nwhat the loader puts in the library")
    bones = {"upperarm_L"}
    files = ["upperarm_L.png", "upperarm_L__mech.png", "notes.txt"]
    report("a bone's own drawing is loaded", "upperarm_L" in library_keys(files, bones))
    report("and so is a drawing for one of its states",
           "upperarm_L__mech" in library_keys(files, bones),
           "a variant is a LAYER art key, not a bone name")
    report("a file for a bone that is gone is left out",
           library_keys(["thigh_X.png"], bones) == set())
    report("and a file that is not a png at all is left out",
           library_keys(files, bones) == {"upperarm_L", "upperarm_L__mech"})
    # The rule that matters: every art key the layers name has to be loadable, or the drawing
    # exists, the layer names it, and nothing draws it.
    variant_layers = [
        {"bone": "upperarm_L", "z": 10, "state": "!mech"},
        {"bone": "upperarm_L", "z": 20, "state": "mech", "art": "upperarm_L__mech"},
    ]
    lib = library_keys(files, bones)
    missing = [art_key(l) for l in variant_layers if art_key(l) not in lib]
    report("every art key those layers name can be loaded", not missing, str(missing))

    print("\nwhat the bench says when it drew nothing")
    report("an empty parts folder is its own answer", blank_reason(0, 0, 0) == "no parts at all")
    report("drawings that no layer names is a different one",
           blank_reason(19, 0, 0) == "no layer names any of them")
    report("drawings a state hides is a third",
           blank_reason(19, 19, 0) == "every one of them is hidden by a state")
    report("and a frame that drew something says nothing", blank_reason(19, 19, 19) == "")

    print("\nthe shipped character file still parses")
    spec = json.load(open(SPEC))
    layers = spec.get("layers", [])
    report("every layer names a bone that exists",
           all(l["bone"] in {b["name"] for b in spec["bones"]} for l in layers))
    report("and every layer has a z",
           all(isinstance(l.get("z"), int) for l in layers), str(len(layers)) + " layers")

    # The real file, and the same file with its layer list gone: this is the damaged-data
    # case the bench now names instead of showing an empty room.
    library = set(art_key(l) for l in layers)
    report("the shipped file draws every one of its layers",
           blank_reason(len(library), len(drawable(layers, library)),
                        len(drawn(layers, {}, library))) == "",
           "%d layers" % len(layers))
    part_files = os.listdir(os.path.join(REPO, "app/src/main/assets/characters/female_base/parts"))
    shipped_lib = library_keys(part_files, set(b["name"] for b in spec["bones"]))
    unloadable = [art_key(l) for l in layers if art_key(l) not in shipped_lib]
    report("every layer of the shipped character can be loaded", not unloadable, str(unloadable))
    report("a file whose layers were lost says so",
           blank_reason(len(library), len(drawable([], library)), 0)
           == "no layer names any of them")

    print("\n叠加还是替换：同一个状态，两种关系")
    # 1.21.0：「部位状态不一定是替换一张图，而是叠加一张图上去」。两张画的**层**是同一对，
    # 区别只在一处 —— 原来那层挂着 `!状态`（替换：状态开着时它不画）还是空着（叠加：都画）。
    # 所以这条镜像量的是"同一个状态开起来，屏幕上出现几张"。
    replace_pair = [
        {"bone": "arm", "z": 10, "state": "!机械", "art": "arm"},
        {"bone": "arm", "z": 11, "state": "机械", "art": "arm__mech"},
    ]
    overlay_pair = [
        {"bone": "arm", "z": 10, "art": "arm"},
        {"bone": "arm", "z": 11, "state": "绷带", "art": "arm__bandage"},
    ]
    lib = {"arm", "arm__mech", "arm__bandage"}
    report("替换：状态开着时只画新那张（老的让位）",
           drawn(replace_pair, {"机械": True}, lib) == ["arm__mech"],
           str(drawn(replace_pair, {"机械": True}, lib)))
    report("替换：状态关着时只画老那张",
           drawn(replace_pair, {}, lib) == ["arm"], str(drawn(replace_pair, {}, lib)))
    report("叠加：状态开着时**两张都画**，新的在上面",
           drawn(overlay_pair, {"绷带": True}, lib) == ["arm", "arm__bandage"],
           str(drawn(overlay_pair, {"绷带": True}, lib)))
    report("叠加：状态关着时只剩老那张",
           drawn(overlay_pair, {}, lib) == ["arm"], str(drawn(overlay_pair, {}, lib)))
    # z 是"贴着它替换/叠加的那张图"（baseZ + 1），不是压在所有东西上面 —— 一条手臂的变体
    # 不该盖住胸口。这里量的是层序：新层紧跟在它那张图后面。
    report("新层紧贴它那张图，不是压在最上面",
           drawn(overlay_pair + [{"bone": "chest", "z": 99, "art": "chest"}], {"绷带": True},
                 lib | {"chest"}) == ["arm", "arm__bandage", "chest"],
           "手臂的叠加层画在胸口**之前**")

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
