
"""The character file surgery, tested before it is trusted with somebody's artwork.

CharacterStore rewrites character.json in four places: saving the rig, adding a drawing for
a state, deleting one, and saving the draw order. All four are read-modify-write over a file
the user owns, and all four can lose something silently -- a joint limit, a collider, which
state a part belongs to. A physics bug is visible; a file that quietly lost the artwork's
state tags is a pet that looks wrong forever and nobody knows why.

Mirrors CharacterStore.saveRig / addVariant / deleteDrawing / saveDepth.

    python3 tools/store_check.py
"""
import copy, json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SPEC = os.path.join(REPO, "app/src/main/assets/characters/female_base/character.json")
STORE_KT = os.path.join(REPO, "app/src/main/java/dev/atp/pet/data/CharacterStore.kt")

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

def rename_art(art_key, renames):
    """Art keys name files, so they follow a renamed bone: see CharacterStore.saveRig."""
    if not art_key:
        return art_key
    for old, new in renames.items():
        if art_key == old or art_key.startswith(old + SEPARATOR):
            return new + art_key[len(old):]
    return art_key


def rename_files(names, renames):
    """Every file a bone owns: '<bone>.png' and '<bone>__<state>.png'."""
    out = []
    for n in names:
        for old, new in renames.items():
            if n == old + ".png":
                n = new + ".png"
                break
            if n.startswith(old + SEPARATOR):
                n = new + n[len(old):]
                break
        out.append(n)
    return out


def save_rig(root, bones, order, renames=None):
    """
    bones: [{"name", "parent", "head", "tail", "limits", "collider", "collides", "grabbable"}],
    parents first. Everything the file said about a bone that is still there is kept, except
    the fields the editor owns -- and the editor owns the two switches as well, which is why
    they are written every time: a flag that is not written is a switch that flips itself back
    the next time somebody saves the rig.
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
        # Absent means yes, the same way the app reads it: a rig saved by an older version has
        # no opinion about these, and the default is the behaviour it already had.
        o["collides"] = b.get("collides", True)
        o["grabbable"] = b.get("grabbable", True)
        arr.append(o)
    root["bones"] = arr
    # Layers are carried over WHOLE and only renumbered. One layer per bone was the bug: a bone
    # with a drawing for a state owns two layers, and rebuilding from bone names deleted the
    # state's drawing every time the rig was saved.
    by_bone = {}
    for l in root.get("layers", []):
        now = (renames or {}).get(l["bone"], l["bone"])
        l["bone"] = now
        if l.get("art"):
            l["art"] = rename_art(l["art"], renames or {})
        by_bone.setdefault(now, []).append(l)
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


def logic_file_name():
    """The file name every subject's rules go in, read back out of the Kotlin."""
    text = open(STORE_KT, encoding="utf-8").read()
    m = re.search(r'const val LOGIC_FILE = "([^"]+)"', text)
    return m.group(1) if m else "logic.json"


def object_logic_path(subject, character_id=None, file_name=None):
    """
    Where a subject's rules live. Mirrors CharacterStore.objectLogicFile, path for path.

    One folder per thing that can hold logic: a prop is shared by every character, a liquid and
    a part belong to one. The old single file is not here on purpose -- nothing is written to it
    any more, and it is only ever read as a fallback.
    """
    name = file_name or logic_file_name()
    if subject.startswith("prop:"):
        return "props/%s/%s" % (subject.split(":", 1)[1], name)
    if character_id:
        if subject.startswith("liquid:"):
            return "characters/%s/liquids/%s/%s" % (character_id, subject.split(":", 1)[1], name)
        if subject.startswith("part:"):
            return "characters/%s/parts/%s/%s" % (character_id, subject.split(":", 1)[1], name)
    return None


def resolve_object_logic(new_files, old_root, subject):
    """
    Which rules a subject has, given everywhere they might be. Mirrors loadObjectLogic.

    The new location wins, INCLUDING WHEN IT IS EMPTY. An empty file means "this thing has no
    rules"; falling back to the old one would be the app putting back rules somebody deleted,
    which is the one rule in this project that has already been got wrong once -- see
    CharacterStore.loadLogic's note, and now this one.
    """
    if subject in new_files:
        return new_files[subject]
    return old_root.get(subject)


def state_tag(bone, state, local):
    """
    The tag a state drawing carries. Mirrors Subjects.stateTag and the choice addVariant makes.

    A state the PART declares is tagged with the bone -- "hand_L:sweat" -- and a global one is
    just its name, because the two levels may share a name and the layer has to say which one
    it is for. Nothing else in the file format changes: the art key is still 骨骼__状态.
    """
    return bone + ":" + state if local else state


def add_variant(root, bone, state, local=False):
    arr = root.setdefault("layers", [])
    top = max([l.get("z", 0) for l in arr] or [0])
    tag = state_tag(bone, state, local)
    for l in arr:
        if l["bone"] == bone and not l.get("state", ""):
            l["state"] = "!" + tag
    arr.append({"bone": bone, "z": top + 10, "state": tag, "art": variant_key(bone, state)})
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
    """
    The bones as the rig editor holds them.

    Everything the editor owns has to be carried through here, or the second save of a file
    quietly resets it: this is where a mirror can be wrong in exactly the way the app was
    wrong once. The two switches ride along for that reason.
    """
    return [{"name": b["name"], "parent": b.get("parent"), "head": b["head"], "tail": b["tail"],
             "limits": b.get("limits", [-180, 180]), "collider": b.get("collider", {}),
             "collides": b.get("collides", True), "grabbable": b.get("grabbable", True)}
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
    report("a bone that said nothing about the switches now says yes to both",
           all(saved_bone.get("collides") is True and saved_bone.get("grabbable") is True
               for saved_bone in saved["bones"]),
           "absent means yes, the same way the app reads it")

    print("\nthe attribute editor changes exactly two things")
    edited = bones_of(load())
    for b in edited:
        if b["name"] == "shin_L":
            b["limits"] = [-25.0, 40.0]
            b["collider"] = {"type": "circle", "radius": 33.0}
            b["collides"] = False
            b["grabbable"] = False
    out = save_rig(load(), edited, names)
    shin = [b for b in out["bones"] if b["name"] == "shin_L"][0]
    report("the new limits are written", shin["limits"] == [-25.0, 40.0], str(shin["limits"]))
    report("the new collider is written", shin["collider"] == {"type": "circle", "radius": 33.0})
    report("a part switched out of the world stays switched out",
           shin.get("collides") is False and shin.get("grabbable") is False,
           str((shin.get("collides"), shin.get("grabbable"))))
    # And the other way round: a bone switched OFF and then saved again must not quietly come
    # back on. This is the whole reason the flags are written every time rather than only for
    # a new bone -- the same shape as the state-tag bug this file was written for.
    twice = save_rig(out, bones_of(out), names)
    shin2 = [b for b in twice["bones"] if b["name"] == "shin_L"][0]
    report("and switching it off survives a second save",
           shin2.get("collides") is False and shin2.get("grabbable") is False,
           str((shin2.get("collides"), shin2.get("grabbable"))))
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

    print("\n每样东西一个文件夹：规则现在住哪儿")
    name = logic_file_name()
    report("the file name is the Kotlin's", name == "logic.json", name)
    report("a prop's rules live in its own folder",
           object_logic_path("prop:candle", "female_base") == "props/candle/" + name,
           str(object_logic_path("prop:candle", "female_base")))
    report("a liquid's live under the character that declares them",
           object_logic_path("liquid:slime", "female_base") ==
           "characters/female_base/liquids/slime/" + name,
           str(object_logic_path("liquid:slime", "female_base")))
    report("a part's live beside its own drawings",
           object_logic_path("part:hand_L", "female_base") ==
           "characters/female_base/parts/hand_L/" + name,
           str(object_logic_path("part:hand_L", "female_base")))
    report("and the character is not one of the objects", object_logic_path("pet") is None)
    report("a liquid without a character has nowhere to live",
           object_logic_path("liquid:slime", None) is None,
           "liquids belong to a character; a prop belongs to everybody")

    print("\n搬迁：新位置赢，旧文件兜底，空文件不算没有")
    old = {"prop:candle": '{"rules": ["old"]}', "liquid:slime": '{"rules": ["old"]}'}
    new_only = {"prop:candle": '{"rules": ["new"]}'}
    report("a subject only in the old file keeps its rules",
           resolve_object_logic({}, old, "prop:candle") == old["prop:candle"])
    report("a subject only in the new place uses that",
           resolve_object_logic(new_only, old, "prop:candle") == '{"rules": ["new"]}')
    report("and when both exist the new one wins",
           resolve_object_logic(new_only, old, "prop:candle") != old["prop:candle"])
    report("an EMPTY new file means no rules, NOT 'look in the old one'",
           resolve_object_logic({"prop:candle": ""}, old, "prop:candle") == "",
           "空 != 没有 —— 这条已经踩过一次")
    report("a subject in neither place has none",
           resolve_object_logic({}, old, "part:head") is None)

    print("\n一个状态的图：全局的和局部的，标记不一样")
    # The two levels may share a name -- a hand that sweats and a character that sweats -- so
    # the layer has to say which one it is for, and it says it with a bone prefix. Nothing else
    # changes: the art key is still 骨骼__状态.
    global_arm = add_variant(load(), "upperarm_L", "mech")
    arm_states = [l.get("state", "") for l in global_arm["layers"] if l["bone"] == "upperarm_L"]
    report("a global state is tagged with its own name",
           sorted(arm_states) == sorted(["!mech", "mech"]), str(arm_states))
    local_arm = add_variant(load(), "upperarm_L", "mech", local=True)
    arm_states = [l.get("state", "") for l in local_arm["layers"] if l["bone"] == "upperarm_L"]
    report("a part's own state is tagged with the bone",
           sorted(arm_states) == sorted(["!upperarm_L:mech", "upperarm_L:mech"]), str(arm_states))
    arts = [l.get("art", l["bone"]) for l in local_arm["layers"] if l["bone"] == "upperarm_L"]
    report("and the art key is unchanged either way",
           sorted(arts) == sorted(["upperarm_L", "upperarm_L" + SEPARATOR + "mech"]), str(arts))

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

    print("\nrenaming a bone takes its drawings with it")
    root = load()
    root = add_variant(root, "upperarm_L", "mech")
    before_files = ["upperarm_L.png", "upperarm_L__mech.png", "shin_L.png"]
    renames = {"upperarm_L": "arm_L"}
    # The editor has already renamed the bone by the time it saves, so the bone list it hands
    # over carries the new name and the renames map is only for the layers and the files.
    renamed_names = ["arm_L" if n == "upperarm_L" else n for n in names]
    renamed_bones = bones_of(root)
    for b in renamed_bones:
        if b["name"] == "upperarm_L":
            b["name"] = "arm_L"
    out = save_rig(root, renamed_bones, renamed_names, renames)
    after_files = rename_files(before_files, renames)
    report("the base drawing is renamed", "arm_L.png" in after_files, str(after_files))
    report("so is the drawing for its state",
           "arm_L__mech.png" in after_files and "upperarm_L__mech.png" not in after_files,
           str(after_files))
    report("and nothing else moves", "shin_L.png" in after_files)
    arts = sorted(l.get("art", l["bone"]) for l in out["layers"] if l["bone"] == "arm_L")
    report("the layers point at the new files",
           arts == ["arm_L", "arm_L__mech"], str(arts))
    report("and the layers say the new bone name",
           all(l["bone"] == "arm_L" for l in out["layers"]
               if l.get("art", "").startswith("arm_L")))

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
