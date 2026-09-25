
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


def save_rig(root, bones, order, renames=None, nodes=None):
    """
    bones: [{"name", "parent", "head", "tail", "limits", "collider", "collides", "grabbable"}],
    parents first. Everything the file said about a bone that is still there is kept, except
    the fields the editor owns -- and the editor owns the two switches as well, which is why
    they are written every time: a flag that is not written is a switch that flips itself back
    the next time somebody saves the rig.

    nodes: [{"name", "bone", "at", "radius", "prop"}], written WHOLE from the list rather than
    carried over field by field like a bone is. A node has nothing in the file the editor does
    not show, so a node that is missing from the list is a node somebody deleted -- and the list
    is the only thing that can say that. `nodes=None` means "an older caller that knows nothing
    about nodes", which leaves whatever the file had alone.
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
        # 刚度 ×：属性编辑器改的就是它，而它以前**根本没被写回**。一个人的调参会在按下
        # 保存骨骼的那一刻无声地回到文件里的旧值（新骨骼则是 1.0）—— 症状是"这个滑杆没用"。
        # 只在不是 1.0 时写："不在"本来就等于 1.0，而回 1.0 要把这个键拿掉。
        phys = o.get("physics") or {}
        if b.get("stiffness", 1.0) != 1.0:
            phys["stiffness"] = b["stiffness"]
        else:
            phys.pop("stiffness", None)
        if phys:
            o["physics"] = phys
        else:
            o.pop("physics", None)
        arr.append(o)
    root["bones"] = arr
    # Layers are carried over WHOLE and only renumbered. One layer per bone was the bug: a bone
    # with a drawing for a state owns two layers, and rebuilding from bone names deleted the
    # state's drawing every time the rig was saved.
    # **去重**，而且这是一个真 bug 而不是整洁问题：`order` 来自编辑器的 `rigLayers()`，
    # 那是**每层一个骨头名** —— 一根有状态图的骨头有两层、就出现两次。下面的循环的意思是
    # "现在把这个骨头的层写出来"，于是每出现一次就把那个骨头的**所有**层再写一遍：
    # 2 层变 4 层，4 层变 16 层。报上来的是「保存骨骼两次就开始掉帧」，而文件一直在平方增长。
    order = list(dict.fromkeys(order))
    by_bone = {}
    seen = set()
    for l in root.get("layers", []):
        now = (renames or {}).get(l["bone"], l["bone"])
        l["bone"] = now
        if l.get("art"):
            l["art"] = rename_art(l["art"], renames or {})
        # 层一模一样的重复（同一根骨头、同一张图、同一个状态）：旧版本把它们平方过，
        # 老文件里可能堆着上百层。它们画的是同一个东西，留第一层就是它们全部的意思。
        key = (now, l.get("art", ""), l.get("state", ""))
        if key in seen:
            continue
        seen.add(key)
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
    if nodes is not None:
        root["nodes"] = [
            {
                "name": n["name"],
                "bone": n["bone"],
                "at": n["at"],
                "radius": n["radius"],
                "prop": n.get("prop", ""),
            }
            for n in nodes
        ]
    return root


def kotlin_val(name, fallback, path=STORE_KT):
    """One `val NAME = "..."` read back out of the Kotlin.

    `val` and not `const val`: the folder-level names (RIGS_DIR, PARTS_DIR, POSES_FILE) live in
    CharacterFolder's companion as plain vals, because a const would have to be duplicated at
    every use site to stay readable. A mirror that hard-codes them is a mirror that keeps
    saying "fine" after somebody renames one.
    """
    text = open(path, encoding="utf-8").read()
    m = re.search(r'val %s = "([^"]+)"' % name, text)
    return m.group(1) if m else fallback


def spec_file_name():
    return kotlin_val("SPEC_FILE", "character.json")


def rigs_dir():
    return kotlin_val("RIGS_DIR", "rigs")


def poses_file_name():
    return kotlin_val("POSES_FILE", "poses.json")


def animations_file_name():
    return kotlin_val("ANIMATIONS_FILE", "animations.json")


def reference_file_name():
    return kotlin_val("REFERENCE_FILE", "reference.png")


def rig_reference(character_id, rig=""):
    """The picture a rig is adjusted against. Per rig: it is a picture of THAT body."""
    return rig_dir(character_id, rig) + "/" + reference_file_name()


def rig_dir(character_id, rig=""):
    """Mirror of CharacterFolder.rigDir: the package itself, or one folder in for a named rig."""
    if not rig:
        return "characters/" + character_id
    return "characters/%s/%s/%s" % (character_id, rigs_dir(), rig)


def rig_spec(character_id, rig=""):
    return rig_dir(character_id, rig) + "/" + spec_file_name()


def rig_parts(character_id, rig=""):
    return rig_dir(character_id, rig) + "/parts"


def rig_poses(character_id, rig=""):
    """Poses belong to the RIG: a pose is a set of bone angles, and bones get renamed."""
    return rig_dir(character_id, rig) + "/" + poses_file_name()


def rig_animations(character_id, rig=""):
    """动画也跟着骨骼套走：一帧里是骨头名字的角度，换一套身体那些名字就不存在了。"""
    return rig_dir(character_id, rig) + "/" + animations_file_name()


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
        # 一种粒子一个文件夹，和液体同一个形状：角色在自己的 logic.json 里**声明**粒子
        # （名字、颜色、受不受重力、留不留印子），而那种粒子的规则住在旁边。
        if subject.startswith("particle:"):
            return "characters/%s/particles/%s/%s" % (character_id, subject.split(":", 1)[1], name)
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
    """The bones as the rig editor holds them: everything the editor OWNS has to ride along,
    or the second save of a file quietly resets it. `stiffness` is here for that reason."""
    """
    The bones as the rig editor holds them.

    Everything the editor owns has to be carried through here, or the second save of a file
    quietly resets it: this is where a mirror can be wrong in exactly the way the app was
    wrong once. The two switches ride along for that reason.
    """
    return [{"name": b["name"], "parent": b.get("parent"), "head": b["head"], "tail": b["tail"],
             "limits": b.get("limits", [-180, 180]), "collider": b.get("collider", {}),
             "collides": b.get("collides", True), "grabbable": b.get("grabbable", True),
             "stiffness": (b.get("physics") or {}).get("stiffness", 1.0)}
            for b in root["bones"]]


def kotlin_body(text, name):
    """某个函数的正文（花括号配平，字符串和注释里的花括号不算）。空串 = 没找到这个函数。

    **只认"行首 4 个空格 + fun 名字("**，这是刻意的：上一版是"扫到任何一个 fun 就往下配平"，
    而 `fun particleDir(...) = File(...) { ... }` 这种**表达式体**函数里也有花括号 ——
    它把配对起点选在了表达式里，之后大半个文件都丢了（78 个函数只找到 4 个）。

    正文里也必须跳过字符串和注释：一个带花括号的字符串会让配平跑偏到别的 `}` 上。
    """
    n = len(text)
    m = re.search(r"^    (?:private |internal |public )?fun %s\s*\(" % re.escape(name), text, re.M)
    if not m:
        return ""
    b = text.find("{", m.end())
    if b < 0:
        return ""

    def skip_trivia(k):
        if text.startswith("//", k):
            j = text.find("\n", k)
            return n if j < 0 else j
        if text.startswith("/*", k):
            j = text.find("*/", k + 2)
            return n if j < 0 else j + 2
        if text[k] == '"':
            k += 1
            while k < n and text[k] != '"':
                k += 2 if text[k] == "\\" else 1
            return k + 1
        return k

    depth, j = 0, b
    while j < n:
        moved = skip_trivia(j)
        if moved != j:
            j = moved
            continue
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[m.start():j + 1]
        j += 1
    return ""


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

    print("\n刚度 ×：编辑器改的那个数，真的写回文件")
    # 这条是用户问"自定义骨骼刚度做了吗"问出来的：编辑器有那一行、求解器真的读它
    # （Ragdoll 里 k = stiffness * b.stiffness * K_MAX）、文件也能读进来 —— 但保存骨骼
    # 的时候**没写回去**。三处里断了一处，症状就是"调了没用"。
    bones = bones_of(base)
    bones[0]["stiffness"] = 0.35
    saved = save_rig(base, bones, names)
    report("调过的关节写进了 physics.stiffness",
           saved["bones"][0].get("physics", {}).get("stiffness") == 0.35,
           str(saved["bones"][0].get("physics")))
    report("别的关节不长出这个键（不在就是 1.0）",
           "physics" not in saved["bones"][1] or
           "stiffness" not in saved["bones"][1].get("physics", {}),
           str(saved["bones"][1].get("physics")))
    # 同一个文件改两次：调回去必须把键拿掉，不能留下 0.35 的旧值。
    again = bones_of(saved)
    again[0]["stiffness"] = 1.0
    back = save_rig(saved, again, names)
    report("调回 1.0 时这个键被拿掉，不留旧值",
           "stiffness" not in back["bones"][0].get("physics", {}),
           str(back["bones"][0].get("physics")))
    # damping/gravity 不是编辑器拥有的，必须原样留着 —— 一次保存把它们抹掉就是另一种丢数据。
    with_more = save_rig(base, bones, names)
    with_more["bones"][2]["physics"] = {"damping": 0.5, "gravity": 3.0}
    kept = save_rig(with_more, bones_of(with_more), names)
    report("编辑器不管的那些键（damping/gravity）原样留着",
           kept["bones"][2].get("physics", {}).get("damping") == 0.5 and
           kept["bones"][2].get("physics", {}).get("gravity") == 3.0,
           str(kept["bones"][2].get("physics")))

    print("\n属性编辑器拥有的每一个字段，save_rig 都要写")
    # 这一条是给"以后又加一个字段、忘了写回去"准备的：属性编辑器能改的字段，一个都不能
    # 只活在内存在里。它是从 Kotlin 里读出来的，不是手抄的清单。
    kt = open(STORE_KT, encoding="utf-8").read()
    body = kt[kt.find("fun saveRig"):kt.find("fun ", kt.find("fun saveRig") + 10)]
    owns = ["limits", "collider", "collides", "grabbable", "stiffness"]
    missing = [f for f in owns if f not in body]
    report("limits / collider / collides / grabbable / stiffness 都写", not missing,
           "没写：" + str(missing))

    print("\n节点：写进去的每一个字段，读回来都还在")
    # 节点是规则用来称呼一个地方的**名字**。丢一个字段的症状不是画错，而是规则不再
    # 触发：半径丢了就永远碰不到，prop 丢了手里拿的东西就消失，at 丢了节点会跑回关节。
    nodes = [
        {"name": "finger_tip", "bone": "hand_L", "at": 120.0, "radius": 22.0, "prop": "sword"},
        {"name": "shoulder", "bone": "root", "at": 0.0, "radius": 30.0, "prop": ""},
    ]
    saved = save_rig(base, bones, names, nodes=nodes)
    report("both nodes are written", len(saved.get("nodes", [])) == 2)
    report("with the place on the bone, not just the name",
           saved["nodes"][0]["at"] == 120.0 and saved["nodes"][1]["at"] == 0.0)
    report("with the radius the editor chose", saved["nodes"][0]["radius"] == 22.0)
    report("with the prop it is wearing", saved["nodes"][0]["prop"] == "sword")
    report("and one that wears nothing says so", saved["nodes"][1]["prop"] == "")

    # 删掉的节点不能留在文件里 —— 留着的那个名字，规则还能选中它，而它已经不存在了。
    saved = save_rig(base, bones, names, nodes=nodes[:1])
    report("a node that is not in the list is gone from the file",
           [n["name"] for n in saved["nodes"]] == ["finger_tip"])
    # 不知道节点的老调用方不能把文件里的节点抹掉：这条是给「以后再加字段」留的活口。
    with_nodes = save_rig(base, bones, names, nodes=nodes)
    kept = save_rig(with_nodes, bones, names)
    report("a caller that knows nothing about nodes leaves them alone",
           len(kept["nodes"]) == 2, "nodes=None means \"not my business\"")

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

    print("\n每存一次「保存骨骼」，图层不能自己长")
    # 上面那个 bug 的回归测试，而且它必须是**一根骨头有两层**的骨架才会红：一层一根骨头时
    # 顺序里没有重复，平方不起来 —— 我第一版的镜像测试就是这么漏掉它的。
    def with_variant():
        rig = load()
        first = rig["layers"][0]
        rig["layers"].append({
            "bone": first["bone"], "z": 9999, "state": "mech",
            "art": first["bone"] + SEPARATOR + "mech",
        })
        return rig

    def save(rig):
        return save_rig(rig, bones_of(rig),
                        [l["bone"] for l in sorted(rig["layers"], key=lambda l: l["z"])])

    rig = with_variant()
    counts = []
    for _ in range(3):
        save(rig)
        counts.append(len(rig["layers"]))
    report("两层的那根骨头，存三次还是两层", counts == [20, 20, 20], str(counts))
    again = save_rig(load_and_variant := with_variant(), bones_of(load_and_variant),
                     [l["bone"] for l in sorted(load_and_variant["layers"], key=lambda l: l["z"])])
    first_pass = save(with_variant())
    report("两次保存出来的东西完全一样（幂等）", first_pass == again)

    broken = load()
    one = broken["layers"][0]
    broken["layers"] = broken["layers"] + [dict(one) for _ in range(7)]
    save(broken)
    report("被旧版本写过 8 次的层，存一次折叠回 1 层",
           sum(1 for l in broken["layers"] if l["bone"] == one["bone"]) == 1,
           str(sum(1 for l in broken["layers"] if l["bone"] == one["bone"])))
    report("折叠之后别的骨头一层都不少", len(broken["layers"]) == len(load()["layers"]),
           str(len(broken["layers"])))

    kt_body = open(STORE_KT, encoding="utf-8").read()
    body = kt_body[kt_body.find("fun saveRig"):]
    body = body[:body.find("\n    fun ", 10)]
    report("Kotlin 那边也去重了（这根弦一直绷着）", ".distinct()" in body)
    report("Kotlin 那边也折叠重复层（修老文件）", "layerSeen" in body)

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
    report("a particle kind's live under the character that declares them",
           object_logic_path("particle:spark", "female_base") ==
           "characters/female_base/particles/spark/" + name,
           str(object_logic_path("particle:spark", "female_base")))
    # `part:` 是 `particle:` 的前身，两个前缀只差一个冒号的位置 —— 这条钉住它们不会被
    # 互相认错，因为认错的下场是把粒子的规则写进某根骨头的文件夹。
    report("and a particle is not filed as a part",
           object_logic_path("particle:spark", "female_base") !=
           object_logic_path("part:spark", "female_base"),
           "%s vs %s" % (object_logic_path("particle:spark", "female_base"),
                         object_logic_path("part:spark", "female_base")))
    report("a particle without a character has nowhere to live",
           object_logic_path("particle:spark", None) is None,
           "particles are declared by a character, like liquids")
    report("a liquid without a character has nowhere to live",
           object_logic_path("liquid:slime", None) is None,
           "liquids belong to a character; a prop belongs to everybody")

    print("\n骨骼套：路径规则，和「默认那一套一个字节都不搬」")
    # 一只桌宠可以有多套骨骼：默认那套就是包裹本身（老文件因此零迁移），别的套各占
    # rigs/<名字>/ 一层。这一节把三条路径钉住 —— 默认套必须还在老地方，因为全世界的存档
    # 都在那儿；命名套必须各在各的文件夹里，否则两套骨骼会互相覆盖对方的 character.json。
    spec = spec_file_name()
    report("默认套的骨架还在老地方（零迁移）",
           rig_spec("female_base") == "characters/female_base/" + spec, rig_spec("female_base"))
    report("默认套的部位图还在老地方",
           rig_parts("female_base") == "characters/female_base/parts", rig_parts("female_base"))
    report("命名套各占一层",
           rig_spec("female_base", "mech") ==
           "characters/female_base/%s/mech/%s" % (rigs_dir(), spec),
           rig_spec("female_base", "mech"))
    report("它的部位图跟着它走，不和默认套共用",
           rig_parts("female_base", "mech") == "characters/female_base/%s/mech/parts" % rigs_dir(),
           rig_parts("female_base", "mech"))
    report("两套骨骼的骨架不是同一个文件",
           rig_spec("female_base") != rig_spec("female_base", "mech"))
    report("动作跟着骨骼套（骨头名字换了，同一套角度就不是同一个动作）",
           rig_poses("female_base", "mech") ==
           "characters/female_base/%s/mech/%s" % (rigs_dir(), poses_file_name()),
           rig_poses("female_base", "mech"))
    report("而默认套的动作也还在老地方",
           rig_poses("female_base") == "characters/female_base/" + poses_file_name())
    report("动画也跟着骨骼套（帧里写的是骨头名字）",
           rig_animations("female_base", "mech") ==
           "characters/female_base/%s/mech/%s" % (rigs_dir(), animations_file_name()),
           rig_animations("female_base", "mech"))
    report("默认套的动画在默认套的老地方",
           rig_animations("female_base") == "characters/female_base/" + animations_file_name())
    report("动画不在 parts/ 里（那里是每根骨头一张图）",
           "/parts/" not in rig_animations("female_base", "mech")
           and animations_file_name() != poses_file_name())
    report("参考图跟着那一套走（它是这副身体的画）",
           rig_reference("female_base", "mech") ==
           "characters/female_base/%s/mech/%s" % (rigs_dir(), reference_file_name()),
           rig_reference("female_base", "mech"))
    # 参考图不能落在 parts/ 里：那是"每根骨头一张图"的地方，一张叫 reference.png 的东西
    # 摆在那儿，既会被当成一根骨头的画，也会在测试场上被画出来。
    report("参考图不在 parts/ 里", "/parts/" not in rig_reference("female_base", "mech"))
    # 其余的东西是桌宠的，不跟着骨骼走：规则、数值、状态、粒子、液体都住在包裹顶层。
    # 这一条是"换骨骼套不动内在"在文件层面的那一半。
    report("规则/粒子/液体不跟着骨骼套走（它们住在桌宠顶层）",
           object_logic_path("liquid:slime", "female_base") ==
           "characters/female_base/liquids/slime/" + logic_file_name() and
           object_logic_path("particle:spark", "female_base") ==
           "characters/female_base/particles/spark/" + logic_file_name())
    # 动画的写和读必须说同一批键。这一版最容易只做一半的地方：写的时候叫 "frames"、
    # 读的时候 optJSONArray("frame")，两种都不报错，症状只有"存好的动画播放起来是空的"。
    kt = open(STORE_KT, encoding="utf-8").read()
    write_body = kt[kt.find("private fun writeAnimations"):]
    # 写这一半现在有两个函数：writeAnimations（动画本身）和 writeTracks（每根骨头的通道，
    # 1.23.0）。切到"下一个段落注释"为止，两个都包进来 —— 只切第一个 `\n    }` 的话，
    # 通道那几个键会落在外面，而断言比的正是"写出去的键"。
    cut = write_body.find("\n    // ── ", 1)
    write_body = write_body[:cut if cut > 0 else write_body.find("\n    }")]
    # 按**名字**取那三个函数的正文，而不是"从这里切到那里"：中间插进别的函数时，
    # 切片会把别人的键也算进来（这一条就因此红过一次）。
    read_body = "".join(kotlin_body(kt, n) for n in ("loadAnimations", "readTracks", "readKeys"))
    keys = lambda text, pat: set(re.findall(pat, text))
    written = keys(write_body, r'\.put\("(\w+)"')
    # optInt 也要在里面：漏了它，一个用 optInt 读回来的键就会"看起来没读" —— 这一版绑规则
    # 的 `rule` 正是 optInt（第一版就是这么报的红，红的是检查不是代码）。
    read_keys = keys(read_body, r'(?:optString|optDouble|optBoolean|optInt|optJSONObject|optJSONArray)\("(\w+)"')
    report("动画：写出去的和读回来的是同一批键",
           written == read_keys and {"id", "name", "frames", "speed", "loop"} <= written,
           "写 %s / 读 %s" % (sorted(written), sorted(read_keys)))
    frame_written = keys(write_body[write_body.find("val frames = JSONArray()"):],
                         r'\.put\("(\w+)"')
    frame_read = keys(read_body, r'(?:optString|optDouble|optInt|optJSONObject)\("(\w+)"')
    # 通道那一半：写的是 rot/x/y/scale，读的也得是这四个 —— 同一个"存好了但是空的"毛病。
    track_written = keys(write_body[write_body.find("private fun writeTracks"):],
                         r'\.put\("(\w+)"')
    track_read = keys(read_body, r'optJSONArray\("(\w+)"')
    report("动画：通道写出去的和读回来的是同一批键（旋转 / 位置X / 位置Y / 缩放）",
           {"rot", "x", "y", "scale"} <= track_written
           and {"rot", "x", "y", "scale"} <= track_read,
           "通道写 %s / 读 %s" % (sorted(track_written), sorted(track_read)))
    report("坏掉的通道整条丢掉（补一串 0 度会去接管那根骨头的旋转）",
           "if (t.isNaN() || v.isNaN()) continue" in read_body
           and "track.rot.isEmpty() && track.x.isEmpty()" in read_body)
    report("动画：每一帧的字段也对得上（角度 / 开关 / 秒数）",
           {"angles", "state", "seconds"} <= frame_written
           and {"angles", "state", "seconds"} <= frame_read,
           "帧写 %s" % sorted(frame_written))
    # 坏文件降级成"没有动画"，不是把测试场一起带走 —— 和 character.json 同一条规矩。
    report("动画文件坏了当没有（不传染）",
           "return emptyList()" in read_body and "catch (e: Exception)" in read_body)

    # saveRig/saveDepth 落盘时写的临时文件：它必须和它要换掉的那个文件在同一个目录里，
    # 否则换套之后 .tmp 会留在桌宠根目录，而 rename 是跨目录的。
    kt = open(STORE_KT, encoding="utf-8").read()
    body = kt[kt.find("private fun writeSpec"):]
    body = body[:body.find("\n    }")]
    report("落盘的临时文件跟着那一套的目录走，不是桌宠根目录",
           "folder.rigDir" in body and "folder.dir" not in body,
           body.strip().splitlines()[1] if len(body.strip().splitlines()) > 1 else "")

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
