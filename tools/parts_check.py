
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
    """LayerSpec.visible（1.26.0）：一串开关是**与**；`!x` 要 x 关着。

    一串（`+` 连接）是这一版加的：一根骨头可以有好几件替换衣服，而"平时那张"必须在
    **它们任何一个**开着的时候都让位。
    """
    if not state:
        return True
    for one in [s.strip() for s in state.split("+") if s.strip()]:
        on = states.get(one.lstrip("!"), False)
        if one.startswith("!"):
            if on:
                return False
        elif not on:
            return False
    return True


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
    """Back to front, the layers that actually draw. Library: which files exist.

    同一根骨头上**只有最高权重那一档会画**（1.26.0，镜像 PartRenderer.draw）：三件替换衣服
    同时开着时画权重最大的那件；"叠加"和底图同档（0），所以照旧一起画。旧数据全是 0。
    """
    order = sorted(layers, key=lambda l: l["z"])
    top = {}
    for layer in order:
        if art_key(layer) not in library:
            continue
        if not visible(layer.get("state", ""), states):
            continue
        prio = layer.get("prio", 0)
        if top.get(layer["bone"]) is None or prio > top[layer["bone"]]:
            top[layer["bone"]] = prio
    out = []
    for layer in order:
        if art_key(layer) not in library:
            continue
        if not visible(layer.get("state", ""), states):
            continue
        if layer.get("prio", 0) != top[layer["bone"]]:
            continue
        out.append(art_key(layer))
    return out


def variant_tag(folder_parts, global_ids, bone, state):
    """镜像 CharacterStore.variantTag（1.31.2）：部位自己声明的带骨头标签，全局的用 id。

    [folder_parts] 是**这一只**的部位文件（`part:hand_L -> [状态 id]`）。这里刻意把它当参数，
    因为那个 bug 就是**查询时没把这一只传进去** —— 传了个空表，于是每一处都判成"全局的"，
    图层上写 `出汗`、开关点亮 `hand_L:出汗`，永远对不上（灯亮着，图不画）。
    """
    own = folder_parts.get("part:" + bone, [])
    return bone + ":" + state if state in own else state


def repair_plan(layers, folder_parts, global_ids):
    """镜像 CharacterStore.repairPartStateTags：老文件里被写坏的那些标签怎么改回来。"""
    out = []
    for layer in layers:
        raw = layer.get("state", "")
        neg = raw.startswith("!")
        bare = raw[1:] if neg else raw
        if not bare or ":" in bare:
            continue
        if bare in global_ids:
            continue
        bone = layer.get("bone", "")
        if not bone or bare not in folder_parts.get("part:" + bone, []):
            continue
        out.append(("!" if neg else "") + bone + ":" + bare)
    return out


def adopt_plan(layers, bones, drawings, folder_parts, global_ids):
    """镜像 CharacterStore.adoptOrphanDrawings（1.34.0）：图在硬盘上、层没了，怎么挂回去。

    [drawings] 是每一节的图（`bone -> [artKey]`，**从目录读的**，所以没层的图也在里面 ——
    `partDrawings` 就是这么读的）。返回要追加的那些层；空列表 = 没什么可修的，那个函数
    因此一个字节都不写。

    挂成**叠加**：一个字都不动底图 —— 找回一个丢了的层，不该顺手把底图关掉。落点和
    addVariant 同一套规矩（贴着这一节"平时就画"的那一层，没有就全场最高 +10），权重 0。
    """
    top = max([l.get("z", 0) for l in layers] or [0])
    added = []
    for bone in bones:
        own = [l for l in layers if l.get("bone") == bone]
        known = set((l.get("art") or bone) for l in own)
        base_z = max([l.get("z", 0) for l in own
                      if not l.get("state", "").startswith("!")] or [None])
        for art in drawings.get(bone, []):
            if art in known:
                continue
            if art == bone:
                added.append({"bone": bone, "z": 0})
            else:
                added.append({
                    "bone": bone,
                    "z": (top + 10) if base_z is None else base_z + 1,
                    "state": variant_tag(folder_parts, global_ids, bone,
                                         art.split("__", 1)[1]),
                    "art": art,
                    "prio": 0,
                })
            known.add(art)
    return added


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

    print("\n一件骨头好几套替换图（1.26.0 修的 bug + 权重）")
    # 用户报的：「导入了三个部位状态结果它只显示第一个，不管开关与否」。
    # 根因是"平时那张"的让位标记只在 state 为空时才写 —— 第一次替换之后它就不是空的了，
    # 于是第二件、第三件再也没让它让位。所以这里量的是"三件里任意一件开着，屏幕上几张"。
    three = [
        {"bone": "hand", "z": 10, "state": "!衣A+!衣B+!衣C", "art": "hand", "prio": 0},
        {"bone": "hand", "z": 11, "state": "衣A", "art": "hand__a", "prio": 1},
        {"bone": "hand", "z": 12, "state": "衣B", "art": "hand__b", "prio": 2},
        {"bone": "hand", "z": 13, "state": "衣C", "art": "hand__c", "prio": 3},
    ]
    lib3 = {"hand", "hand__a", "hand__b", "hand__c"}
    report("三件都不开：画平时那张",
           drawn(three, {}, lib3) == ["hand"], str(drawn(three, {}, lib3)))
    for on, want in (("衣A", "hand__a"), ("衣B", "hand__b"), ("衣C", "hand__c")):
        report("只开 %s：只画那一件（底图让位）" % on,
               drawn(three, {on: True}, lib3) == [want], str(drawn(three, {on: True}, lib3)))
    report("两件同时开：只画**权重大的**那件",
           drawn(three, {"衣A": True, "衣B": True}, lib3) == ["hand__b"],
           str(drawn(three, {"衣A": True, "衣B": True}, lib3)))
    report("三件全开：还是只画权重最大的那一件",
           drawn(three, {"衣A": True, "衣B": True, "衣C": True}, lib3) == ["hand__c"],
           str(drawn(three, {"衣A": True, "衣B": True, "衣C": True}, lib3)))
    report("一串让位标记是**与**：任意一件开着，底图就不画",
           not visible("!衣A+!衣B+!衣C", {"衣B": True})
           and visible("!衣A+!衣B+!衣C", {"衣A": False, "衣B": False, "衣C": False}))
    # 老文件的形状（一个 `!`、权重缺键）必须一个像素都不变。
    legacy = [
        {"bone": "arm", "z": 10, "state": "!机械", "art": "arm"},
        {"bone": "arm", "z": 11, "state": "机械", "art": "arm__mech"},
    ]
    report("老文件（单个 !、没有权重）和以前一模一样",
           drawn(legacy, {"机械": True}, lib) == ["arm__mech"]
           and drawn(legacy, {}, lib) == ["arm"])
    report("叠加那一类和底图同档，所以照旧一起画（权重不打断叠加）",
           drawn([{"bone": "arm", "z": 10, "art": "arm", "prio": 0},
                  {"bone": "arm", "z": 11, "state": "绷带", "art": "arm__bandage", "prio": 0}],
                 {"绷带": True}, lib) == ["arm", "arm__bandage"])

    print("\n部位状态：它挂在哪个开关上（1.31.2 修的那条）")
    parts = {"part:hand_L": ["出汗"], "part:head": ["汗"]}
    report("这一节自己声明的状态带骨头标签",
           variant_tag(parts, [], "hand_L", "出汗") == "hand_L:出汗")
    report("角色声明的全局状态就是它自己的名字（同名也不歧义）",
           variant_tag(parts, ["出汗"], "hand_L", "别的") == "别的"
           and variant_tag({}, ["出汗"], "hand_L", "出汗") == "出汗")
    # 这一条就是那个 bug：查询时没把这一只传进去（等于传空表），于是部位状态被判成全局的。
    report("**查询时必须带上这一只**：空表会把部位状态判成全局状态（图就永远不画）",
           variant_tag({}, [], "hand_L", "出汗") == "出汗"
           and variant_tag(parts, [], "hand_L", "出汗") != "出汗")

    print("\n老文件里被写坏的标签：修回来（只修错得毫无歧义的）")
    broken = [
        {"bone": "hand_L", "state": "出汗", "art": "hand_L__出汗"},
        {"bone": "hand_L", "state": "!出汗", "art": "hand_L"},
        {"bone": "hand_L", "state": "", "art": "hand_L"},
    ]
    report("写坏的那两层都改成带标签（`!` 也留着）",
           repair_plan(broken, parts, []) == ["hand_L:出汗", "!hand_L:出汗"])
    report("平时就画的那一层不动（空状态本来就不该改）",
           len(repair_plan([{"bone": "hand_L", "state": ""}], parts, [])) == 0)
    report("已经带标签的不重复加（修两次和修一次一样）",
           repair_plan([{"bone": "hand_L", "state": "hand_L:出汗"}], parts, []) == [])
    report("角色也声明了同名的 → 不碰（那可能是他真的要的全局状态）",
           repair_plan([{"bone": "hand_L", "state": "出汗"}], parts, ["出汗"]) == [])
    report("这一节没声明这个名字 → 不碰（宁可留着让人自己看，也不猜）",
           repair_plan([{"bone": "hand_L", "state": "别的"}], parts, []) == [])
    # 判据的来源：那三处查询必须把这一只传进去（`loadObjectLogic(folder)`）。
    store_kt = open(os.path.join(REPO, "app/src/main/java/dev/atp/pet/data/CharacterStore.kt"),
                    encoding="utf-8").read()
    report("Kotlin 里没有一处**不带这一只**的查询（那个默认值已经拿掉了）",
           "loadObjectLogic()" not in store_kt
           and "fun loadObjectLogic(folder: CharacterFolder?)" in store_kt
           and "loadObjectLogic(folder)[Subjects.part(bone)]" in store_kt)
    # 三个地方要回答同一个问题（加变体 / 改叠加 / 改权重），而**判断本身只有一份**
    # （variantTag 里那一句）：抄成三份的话，下一次改语义就会漏掉其中一两份。
    report("三处都走同一个判断，而判断只有一份（抄一遍就多一个会忘的参数）",
           store_kt.count("val tag = variantTag(folder, bone, state)") == 3
           and store_kt.count("?.states?.any { it.id == state }") == 1)

    print("\n图在硬盘上、层没了：按文件名挂回去（1.34.0，用户报的「叠加不见了」）")
    # 用户报的现场：`hand_L__出汗.png` 在目录里，而 layers 里没有任何一层指着它。部位页那一行
    # 于是写「未使用」，右边那个「改成叠加 / 改成替换」也不出现 —— 它的判据正是"这一行落在哪一
    # 层上"（叠加还是替换看的就是那层的 `!状态`），**没有层就没有判据**。用户看到的因此是
    # "叠加这个功能被删了"，而唯一的出路是删掉重加（等于重画）。
    orphan = [{"bone": "hand_L", "z": 10, "art": "", "state": ""}]
    files = {"hand_L": ["hand_L", "hand_L__出汗"]}
    added = adopt_plan(orphan, ["hand_L"], files, parts, [])
    report("孤立的变体图挂回去了：图就是那个文件、状态带这一节的标签",
           [a.get("art") for a in added] == ["hand_L__出汗"]
           and [a.get("state") for a in added] == ["hand_L:出汗"])
    report("挂成叠加：底图一个字都没动，所以两张都会画",
           not [a for a in added if a.get("state", "").startswith("!")]
           and visible("", {"hand_L:出汗": True}))
    report("落点贴着这一节平时就画的那一层（和 addVariant 同一套规矩）",
           bool(added) and added[0]["z"] == 11, str([a.get("z") for a in added]))
    report("修两次和修一次一样（没有孤立的图就没有要写的）",
           adopt_plan(orphan + added, ["hand_L"], files, parts, []) == [])
    report("底图那一层整个没了的也补（`art` 空 = 就叫这根骨头的名字）",
           adopt_plan([], ["hand_L"], {"hand_L": ["hand_L"]}, parts, [])[0]["z"] == 0)
    report("骨架里没有这一节 → 不挂（挂上去也没人画）",
           adopt_plan([], ["head"], {"hand_L": ["hand_L__出汗"]}, parts, []) == [])
    report("修的是**文件**，而且只在真的有孤儿时才写盘",
           "fun adoptOrphanDrawings(folder: CharacterFolder): Int" in store_kt
           and "if (added > 0) {" in store_kt)
    activity = open(os.path.join(REPO, "app/src/main/java/dev/atp/pet/MainActivity.kt"),
                    encoding="utf-8").read()
    report("部位页和图层与深度调的是同一个函数（同一件事写两遍，就是两处会各自漂）",
           # 两页各一次，加上助手自己里面那一次（`store.adoptOrphanDrawings(folder)`）。
           activity.count("adoptOrphanDrawings(folder)") == 3
           and "private fun adoptOrphanDrawings(folder: CharacterFolder)" in activity
           and "depthLayers.add(LayerSpec(bone, 0, state = d.state" not in activity)
    report("声明名单也只有一份（「这个开关还在不在」两页问的是同一句话）",
           activity.count("declaredStateTags(folder)") == 2
           and "depthDeclared = declaredStateTags(folder)" in activity)
    report("部位页那一行会说出「这个开关没人声明」（挂在没人开的开关上 = 图永远不画）",
           "private fun declaresNothing(" in activity
           and "declaresNothing(layer.state, declared)" in activity)

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
