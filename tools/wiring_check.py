#!/usr/bin/env python3
"""The controls nothing listens to, and the functions nothing calls.

Two bugs this is for, and only one of them is worth failing a build over:

  * a CONTROL that no Kotlin names at all -- a button in the layout that nothing wired.
    The user taps it, nothing happens, and nothing anywhere says why. That fails.
  * a function that was written, is correct, and is called from nowhere. Sometimes that is
    a feature whose wire is missing, and sometimes it is deliberate API. It is printed,
    not failed: a checker that cries wolf gets ignored, and this one has to be believed.

    python3 tools/wiring_check.py

The dead controls are the ones that matter. Every other line here is a reading list.
"""
import os, re, sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SRC = os.path.join(REPO, "app/src/main/java")
RES = os.path.join(REPO, "app/src/main/res")

#: Tags that exist to be interacted with. A dead one of these is a control that does
#: nothing; a dead TextView is usually just a label with static text, and a dead
#: FrameLayout is a container, and neither is a bug.
CONTROL_TAGS = {
    "Button", "ImageButton", "EditText", "CheckBox", "RadioButton", "Switch", "ToggleButton",
    "SeekBar", "RatingBar", "Spinner", "AutoCompleteTextView", "CheckedTextView",
    "FloatingActionButton", "Chip", "MaterialButton", "TextInputEditText", "SearchView",
    "RecyclerView", "ViewPager", "WebView", "VideoView",
}

FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)
    return ok


def info(label):
    print("   ·   " + label)


def layout_ids():
    """Every android:id in every layout: its tag, and whether it has children."""
    out = {}
    for base, _, names in os.walk(os.path.join(RES, "layout")):
        for n in names:
            if not n.endswith(".xml"):
                continue
            path = os.path.join(base, n)
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError as e:
                report("layout %s parses" % n, False, str(e))
                continue
            for parent in root.iter():
                for child in parent:
                    vid = child.get("{http://schemas.android.com/apk/res/android}id") or ""
                    if not vid.startswith("@+id/"):
                        continue
                    name = vid[len("@+id/"):]
                    tag = child.tag.split("}")[-1]
                    out[name] = (tag, len(list(child)) > 0, n)
    return out


def kotlin_text():
    parts = {}
    for base, _, names in os.walk(SRC):
        for n in names:
            if n.endswith(".kt"):
                p = os.path.join(base, n)
                parts[p] = open(p, encoding="utf-8").read()
    return parts


def rail_items():
    """The rail's tappable items, read from both sides: (layout order, menuItems order).

    "Is this id mentioned in the Kotlin" is NOT the same question as "does anything happen
    when you tap it". The rail attaches its listener by iterating one list, so an item the
    layout has and that list does not is a button that does nothing -- from every other angle
    it looks perfectly wired.

    menuParticles shipped exactly like that: the layout declared it, select() had a branch for
    it, the id appeared in the Kotlin, so the check below was satisfied -- and no listener was
    ever attached. Tapping it did nothing, and nothing anywhere said why.
    """
    layout_order = []
    for base, _, names in os.walk(os.path.join(RES, "layout")):
        for n in names:
            if not n.endswith(".xml"):
                continue
            try:
                root = ET.parse(os.path.join(base, n)).getroot()
            except ET.ParseError:
                continue
            for el in root.iter():
                if el.get("style") != "@style/MenuItem":
                    continue
                vid = el.get("{http://schemas.android.com/apk/res/android}id") or ""
                if vid.startswith("@+id/"):
                    layout_order.append(vid[len("@+id/"):])

    list_order = []
    for _, text in kotlin_text().items():
        m = re.search(r"menuItems\s*=\s*listOf\(", text)
        if not m:
            continue
        # 从 listOf( 起按括号配平扫到收尾。每一项都是 findViewById(...)，所以一个非贪婪的
        # `(.*?)\)` 会在第一个 `)` 就停下，只捞到第一项 —— 那会让这条检查永远"通过"，
        # 因为它比的是「第一项在不在」。
        i, depth = m.end(), 1
        while i < len(text) and depth > 0:
            if text[i] == "(":
                depth += 1
            elif text[i] == ")":
                depth -= 1
            i += 1
        list_order = re.findall(r"R\.id\.(\w+)", text[m.end():i])
        break
    return layout_order, list_order


def main():
    ids = layout_ids()
    files = kotlin_text()
    joined = "\n".join(files.values())
    activity = "".join(t for p, t in files.items() if p.endswith("MainActivity.kt"))

    print("== controls the layout defines and no Kotlin names ==")
    dead_controls = 0
    for name in sorted(ids):
        tag, has_children, layout = ids[name]
        if re.search(r"R\.id\.%s\b" % re.escape(name), joined):
            continue
        if tag in CONTROL_TAGS and not has_children:
            dead_controls += 1
            report("control %s (%s in %s) is wired" % (name, tag, layout), False)
        else:
            info("%-16s %-14s %s -- container or label, not a control"
                 % (name, tag, layout))
    if dead_controls == 0:
        report("every control in the layout is named by the code", True)

    print("== the rail: what the layout has vs what the list has ==")
    layout_order, list_order = rail_items()
    missing = [i for i in layout_order if i not in list_order]
    extra = [i for i in list_order if i not in layout_order]
    report("every rail item in the layout is in menuItems (so it has a listener)",
           not missing, "no listener attached: " + str(missing) if missing else "")
    report("and menuItems names nothing the layout does not have",
           not extra, "not in the layout: " + str(extra) if extra else "")
    # 顺序也要一致：menuItems.first() 是启动时默认选中并 show() 的那一项。
    report("in the same order as the layout",
           [i for i in layout_order if i in list_order] == list_order,
           "layout %s vs list %s" % (layout_order, list_order))

    print("== every action a rule can be given is performed by somebody ==")
    # ActionKind is the menu; the engine and the bench are the two places an action can
    # actually happen. A kind that appears in neither is a menu entry that does nothing --
    # and the one that got away (a liquid's 落地, which no code ever delivered) is why this
    # family of check exists. The engine's own actions are the ones it applies to numbers and
    # states; anything visible has to be in the bench.
    spec_text = "".join(t for p, t in files.items() if p.endswith("LogicSpec.kt"))
    engine_text = "".join(t for p, t in files.items() if p.endswith("RuleEngine.kt"))
    bench_text = "".join(t for p, t in files.items() if p.endswith("PhysicsSandboxView.kt"))
    kinds = re.findall(r'^\s{4}[A-Z_]+\("([a-zA-Z]+)",', spec_text, re.M)
    missing = []
    for k in kinds:
        handled = re.search(r'"%s"' % re.escape(k), engine_text) is not None \
            or re.search(r'"%s"' % re.escape(k), bench_text) is not None
        if not handled:
            missing.append(k)
    report("every action kind is handled by the engine or the bench", not missing,
           "nothing performs: " + str(missing) if missing else "%d kinds" % len(kinds))

    print("== every kind of subject that can hold logic is handed events ==")
    # The bug this exists for: 液体·血 could be picked in 逻辑管理, could be written on, could
    # be saved -- and its rules never ran, because nothing on the bench ever called
    # fireTo(Subjects.liquid(...)). The README promised 落地 to liquids the whole time. A
    # subject that can hold rules but hears nothing is invisible: no error, no log line,
    # just a rule that does not happen.
    bench = "".join(t for p, t in files.items() if p.endswith("PhysicsSandboxView.kt"))
    for kind, maker in (("道具", "prop"), ("液体", "liquid"), ("粒子", "particle"),
                        ("部位", "part")):
        # Delivered by name -- fireTo(Subjects.<maker>(...)) -- or, for the parts, by the one
        # loop that hands every part the figure's own events.
        by_name = re.search(r"fireTo\(\s*Subjects\.%s\(" % maker, bench) is not None
        by_loop = kind == "部位" and re.search(r"Subjects\.isPart\(subject\)", bench) is not None
        report("%s 主体有人给它送事件" % kind, by_name or by_loop,
               "" if (by_name or by_loop) else
               "没有任何 fireTo(Subjects.%s(...))：写在它上面的规则永远不跑" % maker)

    print("== 每个功能都有一个人能找到的入口 ==")
    # 「以后写功能一定要写对应入口」——这句话得是一条断言，不然它只是我下次又会忘的
    # 一句话。每一条给一个**功能名**和它入口上必须出现的那句文案；检查两件事：这句话
    # 在 MainActivity 里真的被用到，而且它附近挂上了点击（setOnClickListener / onClick /
    # setPositiveButton）。一条做出来却点不到的入口，从外面看和没做出来一模一样。
    ENTRIES = [
        ("骨骼节点", "rig_node_list"),
        ("节点的加按钮", "rig_node_add"),
        ("拖尾", "prop_trail_draw"),
        ("粒子图案", "particle_draw"),
        ("绳子图案", "prop_rope_draw"),
        ("并行分支", "logic_branch"),
        ("分支自己的当", "logic_branch_when"),
        ("沙盒放道具", "sandbox_props"),
        ("加骨骼", "rig_add_bone"),
        ("骨骼列表", "rig_bone_list"),
        ("画板的撤销", "paint_undo"),
        ("调骨骼时的参考图", "rig_reference"),
        ("重置骨骼", "rig_reset_bones"),
    ]
    missing = []
    for name, key in ENTRIES:
        at = activity.find("R.string." + key)
        if at < 0:
            missing.append(name + "（文案根本没被用到：" + key + "）")
            continue
        window = activity[at:at + 900]
        # 四种挂法：setOnClickListener（普通按钮）、row(...) { }（规则卡片里那种一行一个
        # 入口的写法，闭包直接传进去）、对话框的确定/取消键。
        if not re.search(
            r"setOnClickListener|onClick|setPositiveButton|setNegativeButton|\)\s*\{", window
        ):
            missing.append(name + "（用了 " + key + " 但附近没有挂点击）")
    report("每个功能都有一个挂上点击的入口", not missing, "; ".join(missing))

    print("== 变身的两个机制：延到下一帧做，而且有速度上限 ==")
    # 「A 出现时变成 B、B 出现时变成 A」是两行就能写出来的东西。没有限速它就是每帧重新
    # 装载一次；在动作表中间直接换世界，则会让这一帧剩下的部分跑在一个已经被换掉的世界
    # 上。两条都不是类型错，编译器一句话都不会说。
    report("变身先记账，帧首再做", "pendingMorph" in bench)
    report("而且两次变身之间有最短间隔", "MORPH_PERIOD" in bench)

    print("== 换骨骼套：同样的两个机制，外加「不动这只桌宠」 ==")
    # 换骨骼套比变身便宜（它是同一只桌宠的另一个身体），但"一帧里换好几次"和"在动作表
    # 中间换掉身体"这两个坑是一样的，所以两个机制一个都不能少。第三条是这一版真正要的
    # 东西：换完之后，这一只的规则、数值、状态、粒子和液体必须还在。
    report("换骨骼套先记账，帧首再做", "pendingRig" in bench)
    report("两次换骨骼套之间有最短间隔", "RIG_PERIOD" in bench)
    # 一个函数的正文：从它的签名到下一个四空格缩进的右括号为止（里面的大括号都更深）。
    swap = bench[bench.find("fun swapRig"):]
    end = swap.find("\n    }")
    swap = swap[:end] if end >= 0 else swap
    report("而且它不重新造引擎（数值和状态留在这一只身上）", "RuleEngine(" not in swap)
    report("也不清粒子（它们也是这只桌宠的）", "particles.clear()" not in swap)
    # 液体和道具只在"新那套的屋子不一样"时才重建 —— 那是唯一一条会丢东西的路，而它是
    # 有意的：道具会跟着搬过去，水不会（它的每一滴都在旧屋子的坐标里）。
    report("液体只在房间真的换了的时候才重建",
           swap.count("fluid = ") == 1 and "old.floorY != parsed.floorY" in swap)

    print("== 图层深度：状态多了先分组 ==")
    # 「状态一多，改图层深度就很乱」：二十行里找三行，而 ▲▼ 一次只走一格，走的那一格还可能
    # 是别的状态的。两条断言：这一页先给"你要调哪一组"，以及过滤时 ▲▼ 在**组内**换位。
    report("深度页有状态过滤（先问调哪一组）",
           "depthFilter" in activity and "private fun depthShows(" in activity)
    report("▲▼ 按看得见的行换位，不是按整张表走一格",
           "private fun moveDepth(" in activity and "shown.getOrNull(here + step)" in activity)
    # 而它必须是**视图**，不是第二份顺序：写回文件的仍然只有 depthLayers 那一份。
    report("过滤不产生第二份顺序（落盘的还是那一份列表）",
           "store.saveDepth(folder, depthLayers, depthRules)" in activity)

    print("== 用不上的图层：自己冒出来，而且点得动 ==")
    # 「有时候图层会不被使用，用户可以再定义它处于啥状态时使用」：一个没人用的图层是**安静**的
    # （不报错、不画），所以两件事缺一不可 —— 它得自己说出来，以及它得能被改。
    report("能判断「这一层永远画不出来」",
           "private fun depthUnusedReason(" in activity)
    report("不用的层有一个过滤器能找到它们",
           "depthUnusedOnly" in activity and "depth_filter_unused" in activity)
    report("点它有得改（换状态 / 重建状态 / 导入图 / 删掉）",
           "private fun askFixLayer(" in activity and "private fun recreateState(" in activity)
    # 最安静的一种是"行根本不在表里"：图画好了、有状态名，而这张表里没有它。
    report("画好但没排进表的图会被列出来（变体图最容易这样）",
           "store.partDrawings(folder, bone)" in activity and "d.state" in activity)

    print("== 哪一只在场上：是用户说了算 ==")
    # 「选择场上存在哪一只桌宠」这一版加的东西里，最容易被悄悄破坏的一条：复制一只、导入一个包、
    # 改个名字之后，站在桌上的那只不能变成别人。`reloadCharacters` 里必须有"留住现在这只"的
    # 那一步 —— 它原来是 `characters.first()`，也就是每次刷新都把宠物换成列表里的第一只。
    activity = next((t for path, t in files.items() if path.endswith("MainActivity.kt")), "")
    reload = activity[activity.find("private fun reloadCharacters"):]
    end = reload.find("\n    }")
    reload = reload[:end] if end >= 0 else reload
    report("刷新列表时留住场上那一只（不是跳回第一只）",
           "val keep" in reload and "keep ?:" in reload)
    report("挑一只上场只有一个入口（summon）",
           activity.count("private fun summon(") == 1 and "summon(folder)" in activity)
    report("顶部先给的是「哪一只」，不是松垮/僵硬", activity.find("for (folder in characters)") <
           activity.find("STIFFNESS_LABELS[stiffnessStep]"))

    print("== 重置骨骼：只回骨架，不是把这一套删了重来 ==")
    # 「重置骨骼」要是把部位图一起删了，那它和"删掉这套骨骼重画"没有区别 —— 而后者用户
    # 已经会了。三句断言盯住这件事：写的是这一套的 spec，而且一个文件都不删。
    store = next((t for path, t in files.items() if path.endswith("CharacterStore.kt")), "")
    reset = store[store.find("fun resetRig"):]
    end = reset.find("\n    }")
    reset = reset[:end] if end >= 0 else reset
    report("只写这一套的 character.json", "folder.specFile" in reset)
    report("不碰这一套的部位图", "partsDir" not in reset)
    report("不删任何东西", "deleteRecursively" not in reset)

    print("== 调骨骼时看得见参考图：两半都要在 ==")
    # 「在调骨骼的时候看不到参考图了」有两个成因，两句断言各盯一个。
    # 1) 拼起来的那一整只只画"状态允许"的图层（LayerSpec.visible），而这个界面从来没人
    #    给过它状态表 —— 于是画在「穿着」「机械」后面的图在测试场里看得见、在这里看不见。
    # 2) 从外面拿进来的那张图（上传的参考图）必须真的被画出来。
    view = next((t for path, t in files.items() if path.endswith("SkeletonView.kt")), "")
    report("编辑器把状态表交给了渲染器（不然被状态挡住的图永远不画）",
           "renderer?.states" in view)
    report("上传的参考图会在骨架下面画出来",
           "reference" in view and "drawBitmap" in view)

    print("== functions defined and called from nowhere (a reading list) ==")
    orphans = 0
    decl = re.compile(
        r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:private |internal )?(?:suspend )?"
        r"fun\s+(?:<[^>]+>\s*)?(\w+)\s*\(")
    for path, text in sorted(files.items()):
        lines = text.split("\n")
        for i, line in enumerate(lines):
            m = decl.match(line)
            if not m:
                continue
            name = m.group(1)
            if name in ("main", "onCreate", "toString", "equals", "hashCode"):
                continue
            refs = [j for j, l in enumerate(lines)
                    if re.search(r"\b%s\s*\(" % re.escape(name), l)]
            elsewhere = sum(1 for p, t in files.items()
                            if p != path and re.search(r"\b%s\s*\(" % re.escape(name), t))
            if len(refs) <= 1 and elsewhere == 0:
                orphans += 1
                info("%-22s %s:%d" % (name, os.path.basename(path), i + 1))
    if orphans == 0:
        report("every function is called from somewhere", True)

    print()
    if FAILURES:
        print("%d failure(s)" % len(FAILURES))
        return 1
    print("no dead controls")
    return 0


if __name__ == "__main__":
    sys.exit(main())
