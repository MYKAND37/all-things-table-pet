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


def body_of(text, declaration):
    """取一个成员函数的正文：从 [declaration] 之后到下一个"四空格缩进的 fun/字段"为止。

    为什么要一个助手：这一版我在三个地方各写了一遍"从这里切到那里"，三次都切错（从声明**自身**
    开始找下一个 `private fun `，切出空串；或者切到注释里的 `/**`）。切错不会报错，只会让断言
    **看不见代码**，于是它悄悄变成一条永远为真的断言 —— 比没有断言更糟。
    """
    at = text.find(declaration)
    if at < 0:
        return ""
    start = text.find("\n", at) + 1
    rest = text[start:]
    nxt = re.search(r"\n    (?:@\w+\n    )?(?:private |internal |override |open )*(?:fun|val|var|class|object) ",
                    rest)
    return rest[:nxt.start()] if nxt else rest


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


def code_only(text):
    """去掉注释行和行尾注释 —— 断言不许 grep 到注释里。

    这是这一仓库里犯过的错（见 docs/JOURNAL.md 第五节）：一条"某个写法不再出现"的断言命中了
    **我自己解释旧写法的那行注释**。所以凡是要数"某句话还在不在"的，都先过这一层。
    """
    out = []
    for line in text.split("\n"):
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
            continue
        out.append(line.split("//")[0])
    return "\n".join(out)


def layout_parents():
    """id -> 父 id（布局里的从属关系）。"谁是谁的孩子"这件事只能这么问。"""
    out = {}
    base = os.path.join(RES, "layout")
    for n in os.listdir(base):
        if not n.endswith(".xml"):
            continue
        try:
            root = ET.parse(os.path.join(base, n)).getroot()
        except ET.ParseError:
            continue
        parent = {}
        for p in root.iter():
            for c in p:
                parent[c] = p
        for el in root.iter():
            vid = el.get("{http://schemas.android.com/apk/res/android}id") or ""
            if not vid.startswith("@+id/"):
                continue
            name = vid[len("@+id/"):]
            pid = ""
            cur = el
            while cur in parent:
                cur = parent[cur]
                pv = cur.get("{http://schemas.android.com/apk/res/android}id") or ""
                if pv.startswith("@+id/"):
                    pid = pv[len("@+id/"):]
                    break
            out[name] = pid
    return out


def layout_visibility():
    """id -> 布局里自己写着的 visibility（没写 = 跟着父容器）。"""
    out = {}
    base = os.path.join(RES, "layout")
    for n in os.listdir(base):
        if not n.endswith(".xml"):
            continue
        try:
            root = ET.parse(os.path.join(base, n)).getroot()
        except ET.ParseError:
            continue
        for el in root.iter():
            vid = el.get("{http://schemas.android.com/apk/res/android}id") or ""
            if not vid.startswith("@+id/"):
                continue
            vis = el.get("{http://schemas.android.com/apk/res/android}visibility")
            if vis:
                out[vid[len("@+id/"):]] = vis
    return out


def logic_kt_text(files):
    """LogicSpec.kt 的正文（液体和粒子的字段写在它里面）。"""
    return next((t for p, t in files.items() if p.endswith("engine/logic/LogicSpec.kt")), "")


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
        # 1.32.0：并行分支删了（用户的原话是"跟新建一条规则没有区别"），换成 if/else-if/else
        # 和「就」的加权「或者」这三样 —— 每一样都要有一个点得开的入口。
        ("否则如果", "logic_module_else_if"),
        ("或者（加权备选）", "logic_module_alt"),
        ("「否则」那一支", "logic_else_edit"),
        ("沙盒放道具", "sandbox_props"),
        ("加骨骼", "rig_add_bone"),
        ("骨骼列表", "rig_bone_list"),
        ("画板的撤销", "paint_undo"),
        ("调骨骼时的参考图", "rig_reference"),
        ("重置骨骼", "rig_reset_bones"),
        ("召唤到桌面", "pet_summon"),
        # 「部件也可以测全局的状态」：挑状态那张表就是入口（「如果」那一排 chip 和
        # 「就 → 改变状态」共用它）。
        ("部件测全局的状态", "logic_pick_state"),
        # 「在桌面上也要能摆预设动作」：入口是长按召唤按钮弹出的那张菜单里的这一行。
        ("桌面上的摆动作", "pet_summon_pose"),
        # 独立的动画管理页：侧栏那一项。
        ("动画管理（侧栏）", "menu_anims"),
        # 状态图叠加 / 替换：入口在部位文件夹那一行（"状态版本"与"改成叠加"）。
        ("状态图的叠加与替换", "part_variant_overlay"),
        # 调骨骼碰撞时看得见范围：入口在属性弹窗里。
        ("碰撞范围可见", "rig_collider_show_all"),
        # 免责声明那道门：入口就是启动时的弹窗（设置里也留了一处"重新阅读"）。
        ("免责声明确认", "disclaimer_agree"),
        # 应用背景图（主题）：入口在全局设置里那一段。
        ("应用背景图（主题）", "settings_theme_pick"),
        # 「每个部位能有自己的状态」：入口就是那个状态弹窗（它现在会说自己在看哪一份）。
        ("部件的局部状态", "logic_states_scope_part"),
        # 动画：三个入口各一条 —— 测试场那张动作列表、规则里的动作、桌面上的长按菜单。
        ("动画（测试场）", "anim_title"),
        ("规则里播放动画", "logic_pick_anim"),
        ("桌面上播放动画", "pet_summon_anim"),
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

    print("== 新侦测器与深度动作：接上了才算数 ==")
    # 两种新条件（部位比位置、绳子连没连着）的答案不在角色身上，在世界里：引擎问、
    # 测试场答。所以三句：引擎有那个接口、测试场把世界交给**每一个**引擎（角色 + 每个
    # 客体）、以及"被连着"问的确实是绳子。
    engine_kt = next((t for path, t in files.items() if path.endswith("RuleEngine.kt")), "")
    renderer_kt = next((t for path, t in files.items() if path.endswith("PartRenderer.kt")), "")
    report("引擎把两种新条件交给世界（interface Facts）",
           "interface Facts" in engine_kt and "var facts: Facts?" in engine_kt)
    report("测试场把世界交给每一个引擎（角色 + 每个客体）",
           bench.count("it.facts = worldFacts") >= 2 and "worldFacts" in bench)
    report("「被绳子连着」问的是绳子（不是钉子）",
           "line.a.bone" in bench and "line.b.bone" in bench)
    report("改变部位深度是运行时的覆盖（有 setDepth，也有回到文件顺序的 clearDepth）",
           "fun setDepth(" in renderer_kt and "setDepth(" in bench and "clearDepth()" in bench)
    report("桌面模式不平移镜头（世界就是屏幕）", "if (!desktop)" in bench)

    print("== 召唤到桌面：窗口、权限、通知，一样都不能少 ==")
    # 悬浮桌宠是三样东西拼起来的，任何一样掉了它都"看起来做了但用不了"：一个真悬浮窗、
    # 一条系统设置里的权限、以及一个前台服务（Android 只允许这样的窗口活在"有东西在明显
    # 运行"的时候）。第四句是这一版的规矩：长按菜单通过**命令**跟桌面上那只说话。
    manifest = open(os.path.join(REPO, "app/src/main/AndroidManifest.xml"), encoding="utf-8").read()
    service = next((t for path, t in files.items() if path.endswith("PetOverlayService.kt")), "")
    layout = open(os.path.join(REPO, "app/src/main/res/layout/activity_main.xml"), encoding="utf-8").read()
    report("悬浮窗是真悬浮（TYPE_APPLICATION_OVERLAY）",
           "TYPE_APPLICATION_OVERLAY" in service)
    report("它挂在前台服务上，通知上有收回",
           "startForeground(" in service and 'foregroundServiceType="specialUse"' in manifest and
           "ACTION_STOP" in service)
    report("「显示在其他应用上层」在清单里，而且按钮先问再召唤",
           "SYSTEM_ALERT_WINDOW" in manifest and "canDrawOverlays" in activity and
           "ACTION_MANAGE_OVERLAY_PERMISSION" in activity)
    report("长按菜单用命令跟桌面上那只说话（不去掏另一个实例的内存）",
           "ACTION_PROP" in service and "ACTION_PROP" in activity)
    report("召唤按钮在侧栏最下面（最后一个菜单项之后）",
           layout.find("@+id/petSummon") > layout.find("@+id/menuSettings"))

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
    # 最安静的一种是"行根本不在表里"：图画好了、有状态名，而这张表里没有它。1.34.0 起这件事
    # 不再是"这一页内存里补一行"（那时用户不按保存就没了，部位页上仍旧是一行「未使用」，
    # 右边的「改成叠加」永远不出现）—— 现在按文件名**写回文件**，而且两页调同一个函数。
    store_src = next((t2 for p2, t2 in files.items() if p2.endswith("data/CharacterStore.kt")), "")
    report("画好但没排进表的图会被挂回去（部位页和图层与深度调的是同一个函数）",
           "fun adoptOrphanDrawings(folder: CharacterFolder): Int" in store_src
           and activity.count("adoptOrphanDrawings(folder)") == 3)

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
    # 刚度那一档现在从资源里取（Labels.stiffness），所以这里盯的是它的**取用点**，
    # 而不是那张中文字表 —— 表已经搬进 strings.xml 了（1.19.0）。
    report("顶部先给的是「哪一只」，不是松垮/僵硬", activity.find("for (folder in characters)") <
           activity.find("Labels.stiffness(this, stiffnessStep)"))

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

    print("== 液滴大小 / 透明度 / 白色：写进文件、喷得出来、画得淡 ==")
    # 三个开关都很容易做成"只有对话框里那个数字会动"：编辑器的 stepper 改了内存里的一份
    # 临时值，喷出来的每一滴却还是老样子。所以四句分开盯：文案在编辑器里、白色在色板里、
    # 保存时进了 spec、以及每一次喷（按钮 / 规则 / 发射器）都把这两个数交给了液体。
    report("液体编辑器有「液滴大小」和「透明度」两行",
           "logic_liquid_size" in activity and "logic_liquid_opacity" in activity)
    palette = re.search(r"val LIQUID_PALETTE = listOf\((.*?)\n\s*\)", activity, re.S)
    body = palette.group(1) if palette else ""
    report("白色是色板里的一员（九个色板，白在最右边）",
           "0xFFFFFFFF.toInt()" in body and body.count("0x") == 9,
           "%d 个色板，白色%s" % (body.count("0x"),
                                 "在" if "0xFFFFFFFF.toInt()" in body else "不在"))
    report("保存时写进 spec，不是只改了对话框",
           "size = sizeOf(), opacity = opacityOf()" in activity and "LiquidSpec(" in activity)
    spills = bench.count("size = liquid.size, opacity = liquid.opacity")
    report("每一次喷都带着这两个数（按钮 + 规则 + 发射器）", spills >= 3,
           "%d 处传了 size/opacity" % spills)
    report("透明度乘进两次绘制（光晕和本体一起变淡，不是只淡主体）",
           "(120f * d.alpha)" in bench and "(215f * d.alpha)" in bench)
    # 而大小必须真的改半径：改半径就改了地面/墙/身体的碰撞，这才是"液滴变大"而不是"贴图
    # 放大"。同一句断言也盯住 crowdScale —— 半径变了而人群间距没变，大液滴会互相穿模。
    fluid_kt = next((t for p, t in files.items() if p.endswith("engine/fluid/Fluid.kt")), "")
    report("液滴半径和人群间距都由 size 决定",
           "RADIUS * size.coerceIn(MIN_SIZE, MAX_SIZE)" in fluid_kt
           and "widest / RADIUS" in fluid_kt)
    report("透明度在 spec 解析时会读回（旧文件没有这两个键也不报错）",
           'optDouble("size", 1.0)' in next(
               (t for p, t in files.items() if p.endswith("LogicSpec.kt")), "")
           and '"opacity", 1.0' in next(
               (t for p, t in files.items() if p.endswith("LogicSpec.kt")), ""))

    print("== 粒子画在最上层，印子画在最下层 ==")
    # 「粒子效果应能显示在最高层」：原来的粒子是**一层**，画在角色之前，所以火花、汗、血
    # 全都被宠物和道具盖住。分成两层之后，"在上面"是一个顺序问题 —— 而顺序是那种改一行
    # 就悄悄变回去的东西。
    report("粒子分成两层（印子 / 活粒子）",
           "particles.drawStains(" in bench and "particles.drawLive(" in bench)
    stains_at = bench.find("particles.drawStains(")
    live_at = bench.find("particles.drawLive(")
    # 1.26.0 起粒子分两层：`behind = true` 的那一半画在地面之后、角色之前，
    # `behind = false` 的那一半照旧在最上面。这一句钉的是**前面那一半**的位置。
    report("活粒子（前面那一半）画在宠物、道具、钉子、等待提示之后",
           bench.find("particles.drawLive(canvas, worldPaint, behind = false)")
           > max(bench.find("drawCharacter(canvas, sk)"), bench.find("drawProps(canvas)"),
                 bench.find("drawNails(canvas)"), bench.find("drawWaiting(canvas)")))
    report("后面那一半画在角色**之前**（灰尘、烟那种在它后面的空气）",
           bench.find("particles.drawLive(canvas, worldPaint, behind = true)")
           < bench.find("drawCharacter(canvas, sk)"))
    report("印子画在一切之前（它算地面的一部分）",
           0 <= stains_at < bench.find("drawFluid(canvas, behind = true)"))
    report("气泡和平衡读数仍在粒子上面（一个是台词，一个是调试读数）",
           bench.find("drawBubble(canvas, sk)") > live_at)
    report("液体也分两层：角色之前一趟、道具之后一趟",
           bench.find("drawFluid(canvas, behind = true)") < bench.find("drawCharacter(canvas, sk)")
           and bench.find("drawFluid(canvas, behind = false)")
           > bench.find("drawCharacter(canvas, sk)"))
    # 两半各自都要把 alpha 还回去。它原来是一个函数，结尾复位一次；拆成两半之后只留一处，
    # 后面每个用同一支笔的画法（拖尾、绳子、角色）就会继承最后一块印子的透明度 ——
    # 一个**只在有印子的时候**才出现的 bug。
    particles_kt = next((t for p, t in files.items() if p.endswith("render/Particles.kt")), "")
    report("两层各自把 paint 的 alpha 复位（不然下一层继承印子的透明度）",
           particles_kt.count("paint.alpha = 255") >= 2,
           "%d 处复位" % particles_kt.count("paint.alpha = 255"))

    print("== 节点也是主语：名字 → 位置只有一个入口，而且它认得节点 ==")
    # 「如果选节点作为触发主语，那生成的粒子和液体都应在节点位置」。逻辑面板把骨头和节点
    # 列成同一张部位表（partNames），而 `Skeleton.find` 只认骨头 —— 一个节点名在那儿得到
    # null，然后每个调用方掉进自己的兜底：喷出来的东西落在宠物家位上方 400px、推一下推的
    # 是整只、断开什么都不做。所以盯的是"名字 → 位置"这条路只有一条，而且这条路上有节点。
    skeleton_kt = next((t for p, t in files.items() if p.endswith("engine/skeleton/Skeleton.kt")), "")
    report("骨架有「名字 → 位置」这一个入口，先骨头再节点",
           "fun place(name: String): Vec2?" in skeleton_kt
           and "byName[name]?.let" in skeleton_kt and "nodePoint(node)" in skeleton_kt)
    report("也有「名字 → 哪一节」（推一下、断开要的是骨头不是点）",
           "fun boneFor(name: String): Bone?" in skeleton_kt
           and "return byName[node.bone]" in skeleton_kt)
    # 前提：节点真的会被列成主语，不然"选节点"这件事根本不存在。
    report("测试场把节点也当部件交给规则（Subjects.part(节点的名字)）",
           "Subjects.part(node.name)" in bench and "for (node in sk.nodes)" in bench)
    subject_pt = bench[bench.find("private fun subjectPoint"):]
    subject_pt = subject_pt[:subject_pt.find("\n    }")]
    point_of = bench[bench.find("private fun pointOf"):]
    point_of = point_of[:point_of.find("\n    }")]
    report("主语的位置走 place（部件和节点都算）",
           "skeleton?.place(Subjects.partId(subject))" in subject_pt
           and "find(Subjects.partId" not in subject_pt)
    report("事件里的 part 也走 place（被点一下 · 指尖 喷在指尖上）",
           "skeleton?.place(name)" in point_of and "sk.find(name)" not in point_of)
    report("「如果」问世界也走 place（指尖比肩膀高）",
           "skeleton?.place(bone)" in bench and "skeleton?.find(bone)?.worldPosition" not in bench)
    report("要骨头的地方走 boneFor（推一下不再是推整只）",
           "sk.boneFor(a.bone.ifEmpty" in bench and "sk.boneFor(name) ?: return" in bench)

    print("== 部件也能测全局的状态：引擎留住自己的，别的问世界 ==")
    engine_kt = next((t for p, t in files.items() if p.endswith("RuleEngine.kt")), "")
    # 「然后部件也可以测全局的状态」。三半缺一不可：引擎那半（自己声明的看自己的 map，
    # 别的名字问世界）、世界那半（按标签找主人）、以及面板那半（那张表里真的有全局的）。
    report("Facts 上多了一对开关的问答（读 + 写）",
           "fun switchOn(tag: String): Boolean? = null" in engine_kt
           and "fun setSwitch(tag: String, on: Boolean): Boolean = false" in engine_kt)
    report("引擎先看自己声明的那些，别的名字问世界",
           "private fun switchOf(name: String): Boolean" in engine_kt
           and "facts?.switchOn(name) ?: false" in engine_kt
           and 'val on = switchOf(c.state)' in engine_kt)
    # 写：改之前是 `states[name] = on`，谁声明过都照写 —— 一个只活在本地 map 里的开关，
    # 图层看不到、别的规则也看不到，而规则看起来是成功的。
    report("写的时候也是：自己的直接改，别人的交给世界",
           "facts?.setSwitch(name, on) != true" in engine_kt
           and "states[name] = on" in engine_kt)
    report("世界不认识的开关要在日志里说出来（不然是一个「成功」的空操作）",
           "这个状态，没有改" in engine_kt)
    report("测试场按标签找开关的主人，并且不凭空造开关",
           "override fun switchOn(tag: String): Boolean?" in bench
           and "override fun setSwitch(tag: String, on: Boolean): Boolean" in bench
           and "if (id !in e.states) return false" in bench)
    # 面板：一张表给「如果」和「就」共用，主体自己的在前，然后是全局的、别的部件的。
    switches = activity[activity.find("private fun switchChoices()"):]
    switches = switches[:switches.find("\n    private fun buildPetChooser")]
    # 1.31.2：第三段原来走 boneNames（只骨骼），节点上的状态因此在这一栏里选不到。
    report("面板有一张开关表：主体自己的 + 全局的 + 别的部件的（骨骼和节点都算）",
           "Subjects.stateTag(part, s.id)" in switches
           and "store.loadLogic(folder.id).states" in switches
           and "store.loadObjectLogic(folder)[Subjects.part(name)]?.states" in switches
           and "partNames(folder)" in switches)
    report("「如果」和「就」共用这一张表（能问的就能改）",
           "switchChoices().choices" in activity and "options = switchChoices().choices" in activity)
    report("重名够不着的那些会说一声，不装作不存在",
           "shadowed" in switches and "logic_state_shadowed" in activity)
    report("状态那一段有说明文字（哪个是全局、哪个是这一节的）",
           "logic_state_scope" in activity)

    print("== 桌面上的那一只也要会摆动作：动作表跟着实例走 ==")
    # 「在桌面上时，桌宠没能正常做预设的动作」。成因有三个，都在这条路上：
    #  1. 动作表是**另设**的（setPoseNames），测试场设了，桌面那一只（第二个实例）没设 ——
    #     于是「摆动作 挥手」查不到名字，而查不到就是 null，null 是"回到瘫软"；
    #  2. 桌面上没有任何入口能摆动作（长按菜单里没有这一行）；
    #  3. 动作是**按骨骼套**存的，而桌面那一只开出来永远是默认那套身体，动作名自然对不上。
    overlay = next((t for p, t in files.items() if p.endswith("PetOverlayService.kt")), "")

    print("== 桌面那只的窗口：屏幕多大由系统说了算（1.34.0） ==")
    # 用户报「桌宠在桌面上时有时候会掉出屏幕外」。窗口尺寸原来写的是服务起来那一刻
    # `resources.displayMetrics` 的宽高 —— 一张**快照**：屏幕转一下、系统栏收起/展开、
    # 分屏改变可用区域，窗口还是旧尺寸，宠物就站在"旧屏幕"的底边上，而那一条边已经在屏幕
    # 外面了。现在窗口满屏交给系统（MATCH_PARENT），视图再把它量给世界（Ragdoll.cageTo）。
    rag_src = next((t2 for p2, t2 in files.items() if p2.endswith("physics/Ragdoll.kt")), "")
    report("窗口是 MATCH_PARENT（不是那一瞬间的宽高快照）",
           "WindowManager.LayoutParams.MATCH_PARENT" in overlay
           and "metrics.widthPixels" not in overlay
           and "val metrics = resources.displayMetrics" not in overlay)
    report("桌面那只的世界跟着视图量出来的尺寸走（不是自己的画布）",
           "rag.cageTo(left, right, top, bottom)" in bench
           and "fun cageTo(left: Float, right: Float, top: Float, bottom: Float)" in rag_src)
    # 1.35.1：地板也搬，而且**三样一起** —— 少搬一个，宠物就站在半空或者陷进地板。
    report("地板/墙搬的是同一组数（宠物 + 道具 + 液体）",
           "world?.setBounds(bottom, right)" in bench
           and "fluid?.setBounds(bottom, right)" in bench)

    load_fn = bench[bench.find("fun load("):]
    load_fn = load_fn[:load_fn.find("): Boolean {")]
    report("动作是 load 的参数（没有默认值：忘了就编译不过）",
           "poses: Map<String, Map<String, Float>>," in load_fn)
    report("load 把它装进这一只的动作表", "poseByName = poses" in bench)
    swap_fn = bench[bench.find("fun swapRig("):]
    swap_fn = swap_fn[:swap_fn.find("): Boolean {")]
    report("换骨骼套也要一起换（动作和动画都跟着套走）",
           "poses: Map<String, Map<String, Float>>," in swap_fn
           and "animations: List<AnimationSpec>," in swap_fn
           and bench.count("poseByName = poses") >= 2
           and bench.count("this.animations = animations") >= 2)
    report("两个实例都交动作表（测试场 + 桌面那一只）",
           "poses.associate { it.name to it.angles }" in activity
           and "poses.associate { it.name to it.angles }" in overlay)
    # 「查不到就什么都不做」：以前是 applyPose(poseByName[名字])，null 是**清空动作** ——
    # 一个打错的名字、或者换套之后留下的旧名字，看起来像宠物瘫了。
    play = bench[bench.find("fun playPose("):]
    play = play[:play.find("\n    }")]
    report("按名字摆动作：查不到就不动，而且告诉调用方",
           "val angles = poseByName[name] ?: return false" in play
           and "applyPose(angles, home)" in play and "return true" in play)
    # 断言要看**代码**，不是注释：这行注释就在解释"以前这里是 applyPose(poseByName[名字])"。
    bench_code = [l for l in bench.split("\n") if not l.lstrip().startswith(("//", "*", "/*"))]
    report("规则里的「摆动作」走 playPose，不再直接拿 map",
           '"pose" -> if (!playPose(a.text))' in bench
           and not any("applyPose(poseByName[" in l for l in bench_code))
    report("名字不存在时会在日志里说一句（世界也可以写日志）",
           "engine?.note(" in bench and "fun note(text: String)" in engine_kt)
    # 入口：长按召唤按钮 → 摆动作… → ACTION_POSE → 桌面那一只摆出来。
    report("长按菜单里有「摆动作」，发的是 ACTION_POSE",
           "pet_summon_pose" in activity and "PetOverlayService.ACTION_POSE" in activity)
    report("服务收得到，而且名字不在这一套身体里会说一句",
           "ACTION_POSE ->" in overlay and "playPose(name, home = true)" in overlay
           and "pet_summon_pose_missing" in overlay)
    # 测试场那一侧：动作文件改了（存/改名/删），手里那份名单要跟着改 —— 改动作不重建宠物。
    report("存 / 改名 / 删动作之后名单会刷新",
           "private fun refreshPoseNames(" in activity
           and activity.count("refreshPoseNames(folder)") >= 3)
    # 骨骼套跟着宠物过去：动作是按套存的，身体记错了名字就全对不上。
    report("召唤时连它穿的那套骨骼一起记下来",
           "KEY_RIG" in overlay and "rememberPet(this, wanted.id, rig = wanted.rig)" in activity
           and "rememberPet(this, pet.id, activePose, pet.rig)" in activity)
    report("桌面那一只开出来就是那套身体（不在了就退回默认，不是不出来）",
           "rig in folder.rigs()" in overlay and "folder.withRig(rig)" in overlay)
    report("换套之后记性跟着走（新套 + 动作名属于旧套，清掉）",
           "rememberRig(this, name)" in overlay
           and activity.count("PetOverlayService.rememberPet(this, pet.id, null, folder.rig)") >= 2)

    print("== 状态图可以叠加（1.21.0）：替换和叠加只差一个标记 ==")
    # 「希望部位状态不一定是替换一张图，而是叠加一张图上去」。两种关系的**层**是同一对，
    # 区别只在一处：原来那层挂着 `!状态`（替换）还是空着（叠加）。所以这里盯的是：
    # 两种都能选、能反悔（一键切换）、以及新层贴着它那张图（不是压在最上面）。
    store_text = next((t for p2, t in files.items() if p2.endswith("data/CharacterStore.kt")), "")
    report("加变体时能选两种关系（叠加 / 替换）",
           "fun addVariant(" in store_text and "overlay: Boolean = false" in store_text
           and "part_variant_overlay" in activity and "part_variant_replace" in activity)
    # 1.26.0：标记是**追加**的（`!a+!b`）。老实现只在 state 为空时才写，
    # 于是第二件、第三件替换再也没让底图让位 —— 用户报的"三个状态只显示第一个"。
    report("替换：原来那层被标成「这些状态关着时画」（追加，不是覆盖）",
           'l.put("state", addStateTag(l.optString("state", ""), "!" + tag))' in store_text
           and "if (!overlay &&" in store_text and "private fun isBaseLayer(" in store_text)
    report("叠加：原来那层一个字都不动（两张都画）",
           "if (!overlay && l.getString(\"bone\") == bone" in store_text.replace("\n", " ")
           or "!overlay && l.getString" in store_text)
    report("新层贴着它那张图（baseZ + 1），不是压在所有东西上面",
           "baseZ + 1" in store_text and "baseZ = maxOf(baseZ" in store_text)
    report("已经做好的图能在两种关系之间反悔（一键切换）",
           "fun setVariantOverlay(" in store_text and "part_variant_to_overlay" in activity
           and "part_variant_to_replace" in activity)
    report("切换只改那一个标记（不碰图、不碰 z、不碰别的骨头）",
           'Anim.statesOf(s2).filter { it != "!" + tag }' in store_text
           and 'l.put("state", addStateTag(s2, "!" + tag))' in store_text
           and "hasVariant(" in store_text)
    report("没有那张图就不给「改成替换」（不然等于把图藏起来）",
           "isBaseLayer(s2) && hasVariant(arr, bone, tag)" in store_text)

    print("== 调骨骼碰撞时看得见范围（1.21.0）==")
    # 「调骨骼碰撞时可以看见范围」。要害是**画出来的就是撞上的那个**：半径必须和求解器共用
    # 同一个函数，形状必须和求解器那两句一样，而且"对世界不存在"的骨头不能画。
    spec_text = next((t for p2, t in files.items() if p2.endswith("skeleton/CharacterSpec.kt")), "")
    ragdoll_text = next((t for p2, t in files.items() if p2.endswith("physics/Ragdoll.kt")), "")
    view_text = next((t for p2, t in files.items() if p2.endswith("ui/SkeletonView.kt")), "")
    report("半径只有一处判断，求解器和编辑器都用它",
           "fun colliderRadiusOf(bone: BoneSpec)" in spec_text
           and "spec.colliderRadiusOf(s)" in ragdoll_text
           and "parsed.colliderRadiusOf(bone)" in view_text)
    report("形状跟着求解器：胶囊是圆头粗线、圆画在中点",
           "colliderType == \"circle\"" in view_text and "strokeCap = Paint.Cap.ROUND" in view_text
           and "canvas.drawLine(vx(head), vy(head), vx(tip), vy(tip), colliderStroke)" in view_text)
    report("「对世界不存在」的骨头不画（画了会让人以为开关没生效）",
           "if (!bone.collides) continue" in view_text)
    report("正在调的那一节亮着画，关掉弹窗就撤掉",
           "var colliderFocus: String?" in view_text
           and "skeletonView.colliderFocus = bone.name" in activity
           and "setOnDismissListener { skeletonView.colliderFocus = null }" in activity)
    report("还能一键看全部（用来对位）",
           "var showColliders = false" in view_text and "fun setShowColliders(" in view_text
           and "rig_collider_show_all" in activity)
    report("半径写在图旁边，自动算的会说明是自动",
           "rig_collider_radius_label" in view_text and "rig_collider_auto" in view_text)

    print("== 独立的动画管理页（1.21.0）==")
    # 「加一个独立的动画管理」。它是一条侧栏项，所以 rails 那两句话也管着它（顺序都要对）；
    # 内容上和测试场那张表共用编辑器（after 回调），但用途不同：那边是"现在演一遍"。
    layout_text = open(os.path.join(REPO, "app/src/main/res/layout/activity_main.xml"),
                       encoding="utf-8").read()
    report("侧栏多了「动画管理」，而且有自己的一页",
           "@+id/menuAnims" in layout_text and "@+id/animScroll" in layout_text
           and "R.id.menuAnims ->" in activity and "Pane.ANIMS" in activity)
    report("按 MenuItem 的规矩进 menuItems（rail 检查会盯着顺序）",
           "findViewById(R.id.menuAnims)," in activity)
    report("页面自己建（buildAnimList），而且工作台和测试场共用那个编辑器（withFrames 分岔）",
           "private fun buildAnimList(" in activity
           and "askAnimation(folder, anim, withFrames = false)" in activity)
    report("编辑器不再绑定某一个容器（改完叫回调，谁开的重画谁）",
           "existing: AnimationSpec?," in activity
           and "withFrames: Boolean = true," in activity
           and "after()" in activity)

    print("== 动画工作台：右边是角色，左边是帧（1.22.0）==")
    # 「动画管理那一页右边应能对角色进行预览，然后动画应集成骨骼调整，部位状态切换等功能，
    # 还要有调整关键帧，旋转，放大，平移等」。这一节每一条都钉那句话里的一个词，因为"我加了
    # 一个功能"和"用户能找到、能用上那个功能"在这个应用里已经不止一次不是同一件事。
    skel_kt = next((t for p, t in files.items() if p.endswith("ui/SkeletonView.kt")), "")
    # 引擎那一份（下面「动画：帧、速度」那一节也会再取一次，两边看的是同一个文件）。
    anim_kt = next((t for p, t in files.items() if p.endswith("engine/anim/Animation.kt")), "")
    report("右边真的是一只角色（第二个骨骼视图），不是又一个列表",
           "dev.atp.pet.ui.SkeletonView" in layout_text and "@+id/animView" in layout_text
           and "animView = findViewById(R.id.animView)" in activity
           and "animView.load(folder)" in activity)
    report("左边是动画列表，下面那条是时间轴（1.23.0 取代了原来那条帧带）",
           "@+id/animList" in layout_text and "@+id/animTimeline" in layout_text
           and "dev.atp.pet.ui.TimelineView" in layout_text
           and "private fun refreshTimeline(" in activity and "animTimeline.setData(" in activity)
    # 平板横屏把这个问题顶出来了：屏幕矮的时候，浮在内容上面的底栏正好压住时间轴。
    # 所以这一页改成"纵向排下来"的一整页（工作台 / 底栏 / 状态行），而且根布局那条状态行
    # 在这一页是藏起来的 —— 一条断言直接问布局："谁是谁的孩子"。
    parents = layout_parents()
    report("动画页的底栏与状态行在**这一页的纵向流里**，不是浮在内容上面的那一层",
           parents.get("animBarScroll") == "animPane"
           and parents.get("animStatus") == "animPane"
           and parents.get("animRow") == "animPane"
           and parents.get("animPane") == "content"
           and "layout_gravity" not in layout_text.split('@+id/animBarScroll')[0].split("<HorizontalScrollView")[-1])
    # 这一条是用户报的 bug 逼出来的：「怎么按钮都不见了」—— 底栏从根布局搬进动画页时，
    # 我只搬了父子关系，**忘了它自己还写着 visibility="gone"**（原来由 show() 打开它，现在
    # 只开关整页）。所以"谁是谁的孩子"不够，还得问"它自己藏没藏起来"。
    vis = layout_visibility()
    report("动画页的子控件没有自己藏着（整页开关之外没人再写 gone）",
           vis.get("animBarScroll") is None and vis.get("animStatus") is None
           and vis.get("animRow") is None and vis.get("animPane") == "gone",
           str({k: v for k, v in vis.items() if k.startswith("anim")}))
    report("根布局那条状态行在动画页藏起来（否则它盖底栏、底栏盖时间轴）",
           "animPane.visibility = if (pane == Pane.ANIMS)" in activity
           and not re.search(r"pane == Pane\.ANIMS\s*\n\s*\) View\.VISIBLE", activity))
    report("工作台的状态都写进它自己那一条",
           activity.count("animStatus.text") >= 12 and "animView.onInfo = { animStatus.text = it }" in activity)

    report("「＋锚点」在底栏（1.24.0 起它按**播放头**落点：帧开头插后面、帧中间断开）",
           "@+id/animFrameAdd" in layout_text and "addStudioAnchor()" in activity
           and "anim_anchor_add" in activity)

    print("== 时间轴：可拖的播放头、可拖的菱形、每根骨头一条通道 ==")
    tl_kt = next((t for p, t in files.items() if p.endswith("engine/anim/Timeline.kt")), "")
    tl_layout = next((t for p, t in files.items() if p.endswith("engine/anim/TimelineLayout.kt")), "")
    view_kt = next((t for p, t in files.items() if p.endswith("ui/TimelineView.kt")), "")
    renderer_kt = next((t for p, t in files.items() if p.endswith("render/PartRenderer.kt")), "")
    ragdoll_kt = next((t for p, t in files.items() if p.endswith("physics/Ragdoll.kt")), "")
    report("引擎那一半没有 Android，几何那一半也没有（所以两边都能本地镜像）",
           "android" not in tl_kt and "android" not in tl_layout
           and "object Timeline {" in tl_kt and "object TimelineLayout {" in tl_layout)
    report("三种关键帧通道都在：旋转 / 位置 / 缩放（位置分 X 与 Y）",
           "const val ROTATION = 0" in tl_kt and "const val POSITION_X = 1" in tl_kt
           and "const val POSITION_Y = 2" in tl_kt and "const val SCALE = 3" in tl_kt
           and "data class BoneTrack(" in tl_kt)
    report("播放头能拖：标尺那一条接的是 onScrub，宿主拿它当演到这一刻",
           "var onScrub: ((Float) -> Unit)?" in view_kt and "onScrub?.invoke(t)" in view_kt
           and "animTimeline.onScrub" in activity and "private fun scrubStudioTo(" in activity)
    report("菱形能拖：按到才接这一下（没按到就不抢外面的滚动）",
           "if (hit < 0) return false" in view_kt
           and "onKeyPicked?.invoke(bone, hit)" in view_kt
           and "private fun pickStudioKey(" in activity)
    report("拖的时候立刻动、抬手才落盘（一秒几十次写文件是拿电池换中间状态）",
           "onKeyMoved?.invoke(bone, dragKey, t, v, false)" in view_kt
           and "onKeyMoved?.invoke(bone, index, dragT, dragV, true)" in view_kt
           and "if (!done) {" in activity and "animPending" in activity)
    report("时间轴不做时间轴以外的编辑（加帧/换图/播放在底栏）",
           "TimelineView" in view_kt and "private fun addStudioKey(" in activity
           and "private fun dropStudioKey(" in activity)
    report("换通道会清掉选中的关键帧（下标在另一条通道上指的是别的东西）",
           "animKeyIndex = -1" in activity
           and "private fun cycleStudioChannel(" in activity and "@+id/animChannel" in layout_text)
    report("帧和通道是同一个时间轴的两半：插帧/删帧/改时长都动了通道",
           activity.count("Timeline.shifted(") >= 2 and "Timeline.dropped(" in activity
           and "Timeline.withKey(track.rot, at, angles[bone] ?: 0f)" in activity)
    report("烘培有入口，而且无损那一半由 timeline_check.py 逐点盯着",
           "private fun bakeStudioTimeline(" in activity and "Timeline.bake(anim)" in activity
           and "@+id/animBake" in layout_text and "fun bake(spec: AnimationSpec)" in tl_kt)
    report("位置与缩放**只改画面**：渲染器认它们，求解器一个字都不知道",
           "animOffsetX" in renderer_kt and "private fun animated(" in renderer_kt
           and "animOffsetX" not in ragdoll_kt and "animScale" not in ragdoll_kt)
    print("== 姿态锚点：小方块、绑规则、播到就响（1.24.0）==")
    logic_kt = next((t for p, t in files.items() if p.endswith("engine/logic/RuleEngine.kt")), "")
    tl_layout_kt = next((t for p, t in files.items() if p.endswith("engine/anim/TimelineLayout.kt")), "")
    graph_kt = next((t for p, t in files.items() if p.endswith("LogicGraphView.kt")), "")
    report("锚点就是帧：一帧本来就带一整套姿势，锚点只是它在时间轴上的画法",
           "val rule: Int = -1" in anim_kt and "private fun addStudioAnchor(" in activity)
    report("锚点那一行画的是**时刻**（小方块），图那一行画的是**时长**（块有多宽）",
           "private fun drawAnchorRow(" in view_kt and "private fun anchorLane(" in view_kt
           and "private fun drawArtRow(" in view_kt and "ANCHOR_ROW_DP" in view_kt)
    report("点小方块 = 跳播放头 + 载入那一套姿势（独立回调，说话不一样）",
           "var onAnchorPicked: ((Int) -> Unit)?" in view_kt
           and "animTimeline.onAnchorPicked" in activity and "private fun pickStudioAnchor(" in activity)
    report("＋锚点落在**播放头**那一刻：帧开头插后面、帧中间就把那一帧断开",
           "val t = animTimeline.playhead" in activity
           and "next[i] = here.copy(seconds = first)" in activity
           and "next.add(insertedAt, AnimFrame(pose, state, rest))" in activity)
    report("断开不改总时长，所以那种情况下一个关键帧都不用挪",
           "tracks = trackedKeysAt(anim, t, pose)" in activity
           and activity.count("Timeline.shifted(") >= 2)
    store_kt = next((t for p, t in files.items() if p.endswith("data/CharacterStore.kt")), "")
    report("绑的是**第几条**，和「跳到规则」同一个身份（不另造一套 id）",
           '.put("rule", f.rule)' in store_kt and 'rule = f.optInt("rule", -1)' in store_kt
           and "val rule: Int = -1" in anim_kt
           and "R.string.logic_rule_n" in activity)
    report("引擎多了一个入口：按序号跑一条规则（走的是同一个 fire）",
           "fun runRule(index: Int): List<ActionSpec>" in logic_kt
           and "return fire(index, spec.rules[index], mutableSetOf())" in logic_kt)
    report("触发只免掉「当」：如果 / 冷却 / 只一次 一个字都不放宽",
           "fun hasRule(index: Int): Boolean" in logic_kt
           and "这份文件里已经没有它了" in logic_kt)
    # 1.31.0 起"第几遍/响过没有"是**每一段自己**的（同时播两段时共用一份会让其中一段漏响）。
    report("播到那一格才响，而且**一遍只响一次**（循环每绕一圈再响）",
           "Timeline.passIndex(anim, slot.clock)" in bench and "slot.fired.add(s.frame)" in bench
           and "slot.fired.clear()" in bench
           and "fun passIndex(spec: AnimationSpec, seconds: Float): Int" in tl_kt)
    report("响完抬起来的信号当场发出去（不然要等下一次有人碰它）",
           "engine?.runRule(bound)" in bench and "drainSignals()" in bench)
    report("绑规则有入口，而且说清楚规则只在测试场和桌面上才会响",
           "@+id/animBind" in layout_text and "private fun bindStudioRule(" in activity
           and "anim_bind_where" in activity)

    print("== 关键帧的单位：弧度和帧一致（1.27.0 修的那条）==")
    code = code_only(activity)
    key_fn = code.split("private fun studioKeyValue")[1].split("private fun")[0]
    report("关键帧的值来自 currentAngles（和帧同一个单位），不是 currentDegrees",
           "animView.currentAngles()[bone] ?: Timeline.defaultValue(animChannel)" in key_fn
           and "currentDegrees" not in key_fn)
    report("currentDegrees 只用在**给人看的**那一处（关节范围的最小/最大）",
           code.count("currentDegrees") == 1 and "skeletonView.currentDegrees(it)" in code)
    view_code = code_only(view_kt)
    slop_block = view_code.split("if (!dragMoved &&")[1].split("}")[0] if "if (!dragMoved &&" in view_code else ""
    report("点一个菱形是**选中**：过了 8dp 才算拖（否则手指落下几毫米就把它挪走并落盘）",
           "const val TOUCH_SLOP_DP = 8f" in view_code
           and "hypot(" in slop_block and "TOUCH_SLOP_DP" in slop_block
           and view_code.count("dragMoved = true") == 1)
    report("选一个关键帧不动播放头（播放头是「我在看哪一刻」）",
           "每点一下就把它拽到别处" in activity
           and "animTimeline.setPlayhead(key.t)" not in code_only(activity)
           and "拖菱形**不拖播放头**" in activity)
    report("拖完保留选中（原来落盘时清成 -1，那是选中不了的另一半）",
           "选中**留着**" in activity
           and "animKeyIndex = -1\n        // 挪的只是" not in activity)
    report("状态栏上才把弧度换成度（写进文件的一律是弧度）",
           '"%.0f°".format(Math.toDegrees(v.toDouble()))' in activity)

    print("== 图层深度：改的是哪一套骨架 + 排到某一节前/后（1.26.2 / 1.27.0）==")
    report("深度页自己记住它在改哪一套（不再拿 opened，否则会改一套存另一套）",
           "private var depthFolder: CharacterFolder? = null" in activity
           and "depthFolder = folder" in activity
           and activity.count("val folder = depthFolder ?: return") >= 4
           and activity.count("val folder = opened ?: return") >= 1)
    report("排到某一节的前/后：渲染器有 placeDepth，而且按说的顺序应用",
           "fun placeDepth(bone: String, anchor: String, after: Boolean)" in renderer_kt
           and "placements.add(Triple(bone, anchor, after))" in renderer_kt
           and "for ((bone, anchor, after) in placements)" in renderer_kt)
    report("动作里两个方向都接上了（before/after 走 placeDepth，最前/最后走 setDepth）",
           '"before" -> renderer?.placeDepth(bone, a.bone2, after = false)' in bench
           and '"after" -> renderer?.placeDepth(bone, a.bone2, after = true)' in bench
           and 'else -> renderer?.setDepth(bone, a.text != "back")' in bench)
    report("编辑器四选一，选完还要选排到哪一节（不能选它自己）",
           "R.string.logic_depth_before" in activity and "R.string.logic_depth_after" in activity
           and "R.string.logic_depth_anchor" in activity
           and ".filter { it != bone }" in activity)
    report("第二节骨头是**自己的字段**（bone2），不是挤进 text",
           "val bone2: String = \"\"" in next(
               (t for p, t in files.items() if p.endswith("engine/logic/LogicSpec.kt")), "")
           and 'optString("bone2", "")' in next(
               (t for p, t in files.items() if p.endswith("engine/logic/LogicSpec.kt")), "")
           and '.put("bone", a.bone).put("bone2", a.bone2)' in next(
               (t for p, t in files.items() if p.endswith("engine/logic/LogicSpec.kt")), ""))
    report("动作在图上说得出来（原来落到原始的 id `depth`）",
           '// 「改变部位深度」原来没有这一支' in activity
           and '"排到" + partText(a.bone2) + "前面"' in activity)
    report("部位列表标出场上是哪一套，存档时也说了另一套",
           "R.string.rig_on_bench" in activity and "R.string.depth_saved_other_rig" in activity
           and "it.rig != folder.rig" in activity)

    print("== 部位状态好几套：让位标记 + 权重（1.26.0）==")
    spec_kt = next((t for p, t in files.items() if p.endswith("engine/skeleton/CharacterSpec.kt")), "")
    store_kt = next((t for p, t in files.items() if p.endswith("data/CharacterStore.kt")), "")
    report("一串开关是与：`!a+!b` 要两个都关着（底图才画）",
           "val states: List<String> get() = Anim.statesOf(state)" in spec_kt
           and "for (one in this.states)" in spec_kt)
    report("让位标记是**追加**不是覆盖（老实现只在 state 为空时写，所以第二件之后全失效）",
           "private fun isBaseLayer(state: String): Boolean" in store_kt
           and 'l.put("state", addStateTag(l.optString("state", ""), "!" + tag))' in store_kt)
    report("同一根骨头只有最高权重那一档会画（叠加和底图同档，所以不打断叠加）",
           "val prio: Int = 0" in spec_kt and "topPrio[layer.bone] != layer.prio" in renderer_kt)
    report("新加的替换图排在最后一件上面，权重能在部位那一页调",
           "val prio = if (overlay) 0 else topPrio + 1" in store_kt
           and "fun setVariantPriority(" in store_kt
           and "R.string.part_variant_prio" in activity and "part_variant_prio_title" in activity)
    report("权重跟着保存骨骼和两种关系的转换一起走",
           'if (layer.prio != 0) o.put("prio", layer.prio)' in store_kt
           and "l.put(\"prio\", top)" in store_kt and "l.put(\"prio\", 0)" in store_kt)

    print("== 液体画在前还是在后（1.26.0）==")
    fluid_kt = next((t for p, t in files.items() if p.endswith("engine/fluid/Fluid.kt")), "")
    report("液体有一层属性，默认**在后面**（= 一直以来的样子）",
           "val behind: Boolean = true" in fluid_kt
           and 'behind = l.optBoolean("behind", true)' in logic_kt_text(files)
           and '.put("behind", l.behind)' in logic_kt_text(files))
    report("洒/倒出来那一刻抄到每一滴上（半空中的不会因为改设置突然换层）",
           "val behind: Boolean = true," in fluid_kt and "behind = behind," in fluid_kt
           and bench.count("behind = liquid.behind") >= 4)
    report("世界按它画两趟：角色之前一趟、道具之后一趟",
           "drawFluid(canvas, behind = true)" in bench and "drawFluid(canvas, behind = false)" in bench
           and "if (d.behind != behind) continue" in bench)
    report("编辑器里有这个开关，而且打开时画对（paintDepth 真的被调用）",
           "R.string.liquid_behind" in activity and "private fun paintDepth" in activity
           or "val depthChip" in activity)

    print("== 粒子的那一层 + 节点能不能拖 + 显示节点（1.26.0）==")
    report("粒子也有这一层（默认**在前面**，和液体相反：各按各的老行为）",
           "val behind: Boolean = false" in next(
               (t for p, t in files.items() if p.endswith("engine/particle/ParticleSpec.kt")), "")
           and "particles.drawLive(canvas, worldPaint, behind = true)" in bench
           and "particles.drawLive(canvas, worldPaint, behind = false)" in bench)
    report("节点有一开关：不能拖的点照样挂道具、系绳子、有碰撞，只是手指拽不走它",
           "var draggable: Boolean = true" in spec_kt
           and "if (!node.draggable) return@run" in bench
           and 'put("draggable", n.draggable)' in store_kt
           and "R.string.rig_node_drag" in activity)
    report("全局设置里有「显示节点」，关掉只是不画（拖动照旧）",
           "val showNodes: Boolean = true" in next(
               (t for p, t in files.items() if p.endswith("data/Settings.kt")), "")
           and "if (!settings.showNodes) return" in bench
           and "R.string.settings_show_nodes" in activity)

    print("== 撤销 / 重做（1.25.0）==")
    report("两个 chip 在底栏，而且是**动作**不是装饰（空的时候压暗 + 说一句）",
           "@+id/animUndo" in layout_text and "@+id/animRedo" in layout_text
           and "private fun undoStudio(" in activity and "private fun redoStudio(" in activity
           and "R.string.anim_undo_none" in activity and "private fun paintStudioHistory(" in activity)
    report("存的是**整个工作台的样子**（动画 + 播放头 + 选中的骨头/关键帧/帧/通道）",
           "private class StudioStep(" in activity and "val playhead: Float," in activity
           and "val channel: Int," in activity and "private fun studioStep(" in activity)
    report("每一次**写文件之前**记一笔（写完之后再记就晚了）",
           activity.count("pushStudioUndo(anim)") >= 9
           and "private fun pushStudioUndo(anim: AnimationSpec)" in activity)
    report("拖播放头不记历史（它一个字都不改，而且撤销一部「看」没有意义）",
           "拖播放头**不是**改动" in activity
           and activity.split("private fun scrubStudioTo")[1].split("private fun ")[0].count(
               "pushStudioUndo") == 0)
    report("新的改动会清空重做栈（新的分支开始了），换动画也重新记",
           "animRedo.clear()" in activity and "if (animHistoryId != anim.id)" in activity)
    report("撤销栈封顶，不是无限长",
           "while (animUndo.size > UNDO_LIMIT) animUndo.removeFirst()" in activity
           and "const val UNDO_LIMIT = 40" in activity)
    report("撤销之后连选择一起摆回去（回到刚才那一眼）",
           "private fun applyStudioStep(" in activity
           and "showStudioMoment(folder, step.spec, step.playhead)" in activity)

    print("== 关键帧用起来该有的样子（1.25.0，四条都是用户报的）==")
    report("「走完回第一帧」是一个开关，存在动画自己身上（1.30.0）",
           "val returnHome: Boolean = false" in anim_kt
           and 'returnHome = o.optBoolean("returnHome", false)' in store_text
           and '.put("returnHome", a.returnHome)' in store_text
           and "R.string.anim_return_home" in activity and "homeChip" in activity)
    report("回家那一段的长度和总时长都在引擎里算（播放器只问走完了没有）",
           "fun totalSeconds(a: AnimationSpec): Float" in anim_kt
           and "fun returnSeconds(a: AnimationSpec): Float" in anim_kt
           and "slot.clock * Anim.speedOf(anim) >= Anim.totalSeconds(anim)" in bench
           and "animClock * Anim.speedOf(anim) >= Anim.totalSeconds(anim)" in activity)
    report("通道也跟着回家（不然姿势回去了、某根骨头留在原地）",
           "val homing = !spec.loop && Anim.returnSeconds(spec) > 0f && raw > total" in tl_kt
           and "channelValue(" in tl_kt)
    report("循环时最后一根关键帧平滑接回第一根（原来是停住再跳）",
           "loop: Boolean = false," in tl_kt and "if (!loop || total <= last.t) return last.v" in tl_kt)
    report("时间轴默认**只显示现在这一节**，想看全部才摊开（1.29.1）",
           "private var animRowsAll = false" in activity
           and "ordered.filter { it == animBone }.ifEmpty { ordered.take(1) }" in activity
           and "private fun toggleStudioRows(" in activity
           and "@+id/animRows" in layout_text and "R.string.anim_rows_one_hint" in activity)
    report("换一节的入口说清楚了（去右边拖它一下）",
           "拖哪儿就是哪一节" in activity or "拖哪儿就是哪一节" in open(
               os.path.join(REPO, "app/src/main/res/values/strings.xml"), encoding="utf-8").read())
    report("吸附可以关：关了之后播放头/关键帧能落在两个帧之间（1.29.0）",
           "var snapFrames = true" in view_kt and "fun setSnapFrames(on: Boolean)" in view_kt
           and view_kt.count("if (snapFrames)") == 2
           and "animTimeline.setSnapFrames(animSnap)" in activity
           and "@+id/animSnap" in layout_text and "R.string.anim_snap_hint" in activity)
    report("时间轴那一整块能收起来（烤完一长串骨头之后它挡预览）",
           "@+id/animTimelineToggle" in layout_text and "private fun toggleStudioTimeline(" in activity
           and "animTimelineScroll.visibility = if (animTimelineShown)" in activity
           and "R.string.anim_timeline_hidden" in activity)
    report("长按一段动画：改名/复制/删除，删除仍然要问一句",
           "row.setOnLongClickListener {" in activity
           and "private fun askAnimationActions(" in activity
           and "R.string.anim_row_copy" in activity and "R.string.anim_row_delete" in activity
           and "getString(R.string.anim_delete, anim.name)" in activity)
    report("复制是**整段**复制（帧、通道、图、绑的规则一起走）",
           "store.saveAnimation(folder, anim.copy(id = id, name = name))" in activity)
    report("拖关节之后，关键帧打的是**刚拖的那一节**（1.28.1 修的那条：animBone 原来会粘住）",
           "animView.onPoseEdited = {" in activity
           and "syncStudioBone()" in activity.split("animView.onPoseEdited")[1].split("}")[0]
           and "private fun syncStudioBone()" in activity
           and "val bone = animView.selected ?: return" in activity)
    report("而且时间轴上的选中跟着挪（高亮和要打给谁是同一个事实）",
           "animTimeline.setSelection(bone, -1, animFrameIndex)" in activity)
    report("只有一个关键帧时把话说出来（那样它不会动，用户需要知道为什么）",
           "R.string.anim_key_only_one" in activity)
    report("① 有关键帧也能存（不只是 ＋）：把现在的姿势写进选中的那一个",
           "private fun saveStudioKey(" in activity and "@+id/animKeySave" in layout_text
           and "Timeline.moved(keys, animKeyIndex, key.t, value)" in activity)
    report("② 编辑之后回到**播放头那一刻**，不是帧起点（否则姿势会自己变）",
           "private var animPlayhead = 0f" in activity
           and "private fun showStudioMoment(" in activity
           and "showStudioMoment(folder, anim, animPlayhead)" in activity)
    report("③ 菱形只在时间轴上拖：旋转通道竖拖不改值（值来自你把这一节摆成什么样）",
           "channel == Timeline.ROTATION" in view_kt
           and "fun hitKeyInRow(keys: List<AnimKey>, lane: Lane, px: Float, radiusPx: Float): Int" in tl_layout
           and "TimelineLayout.hitKeyInRow(" in view_kt)
    report("图那一行的方块能左右拖，拖出来的是这一帧到下一帧的时间（1.28.0）",
           "var onFrameStretched: ((Int, Float, Boolean) -> Unit)?" in view_kt
           and "animTimeline.onFrameStretched = { index, delta, done ->" in activity
           and "private fun stretchStudioFrame(index: Int, delta: Float, done: Boolean)" in activity
           and "fun secondsDelta(lane: Lane, dx: Float): Float" in tl_layout)
    report("拖动期间只改内存 + 重画，**抬手才落盘**（和拖关键帧同一条规矩）",
           "if (!done) {" in activity.split("private fun stretchStudioFrame")[1].split("private fun ")[0]
           and "animFrameDragBase = null\n        pushStudioUndo(base)" in activity)
    report("基准是**拖动开始那一刻**的动画（不然越拖越快）",
           "val base = animFrameDragBase ?: live.also { animFrameDragBase = it }" in activity)
    # 1.33.1 起边界是 frameEnds（"这一帧走完"），所以这里只认"调了 shifted"这件事本身，
    # 边界那一条在上面的 1.33.1 小节里单独钉（写死参数形状的断言会随改动失明）。
    report("时长变了，后面的关键帧跟着挪（和 −0.1s 那条按钮同一条规矩）",
           "Timeline.shifted(" in activity and "base.tracks" in activity
           and "MIN_FRAME_SECONDS, MAX_FRAME_SECONDS" in activity)
    report("拖完仍然能点一下选中那一帧（没过阈值就是点）",
           "} else if (!frameDragMoved) {" in view_kt
           and "onFramePicked?.invoke(frameDragIndex)" in view_kt)
    report("参考图在动画页也画（跟着用户的开关，默认画）",
           "reference = animShowReference" in activity
           and "private var animShowReference = true" in activity
           and "@+id/animReference" in layout_text
           and "toggleStudioReference()" in activity
           and "R.string.anim_reference_none" in activity)
    report("而且拖播放头不会把它关掉（这页原来硬写 reference = false）",
           "reference = false" not in code_only(activity)
           or "setPreview(map, true, reference = false)" not in code_only(activity))
    report("④ 帧模型改成每根骨头各自在提到过它的帧之间插值（跨帧不再瞬移）",
           "private fun poseAt(a: AnimationSpec, t: Float)" in anim_kt
           and "private fun valueOf(" in anim_kt and "private fun blend(" not in anim_kt)
    report("而且烘培不再用阶跃去凑（烘出来就是平滑的，仍然逐点无损）",
           "AnimKey.EASE_STEP" not in tl_kt.split("fun bake(")[1].split("fun trackedBones")[0]
           and "AnimKey.EASE_LINEAR," in tl_kt)

    report("两根骨头之间的插值在引擎里，界面不算数学",
           "fun valueAt(" in tl_kt and "keys: List<AnimKey>," in tl_kt
           and "fun ease(f: Float, kind: Int)" in tl_kt and "fun withKey(" in tl_kt)
    report("选中一帧/某一刻就摆上去，而且姿势取的是时间轴的采样（和播放器同一刻）",
           "val sample = Timeline.sample(anim, frameClock(anim, clamped)) ?: return" in activity
           and "private fun showStudioMoment(" in activity and "private fun frameClock(" in activity
           and "fun sample(spec: AnimationSpec, seconds: Float)" in tl_kt
           and "fun framePose(a: AnimationSpec, index: Int)" in anim_kt)
    report("改姿势就地改：拖关节 → onPoseEdited → 提示「存入这一帧」",
           "var onPoseEdited: (() -> Unit)?" in skel_kt
           and "animView.onPoseEdited =" in activity and "R.string.anim_dirty_hint" in activity)
    report("骨架本身也能在这页改（改骨骼 / 存骨骼两个 chip）",
           "@+id/animEditBones" in layout_text and "@+id/animSaveBones" in layout_text
           and "private fun toggleStudioBoneMode(" in activity
           and "animView.setBoneEditMode(animBoneMode)" in activity)
    # 这条是这一版最容易写错的地方：工作台演的是 summoned ?: opened，而骨骼页那个 saveBones()
    # 写的是 opened。"打开 A、放上场 B"的时候写错一只，事后极难发现（名字都对）。
    studio_save = activity[activity.find("private fun saveStudioBones("):]
    studio_save = studio_save[:studio_save.find("\n    }")]
    report("工作台里改骨骼写回**正在看的这一只**（不是 opened）",
           "store.saveRig(" in studio_save and "folder, bones" in studio_save
           and "opened" not in studio_save)
    report("点位状态切换：一帧可以同时开好几套图（+ 拼起来，拆开才生效）",
           "@+id/animStates" in layout_text and "private fun buildStateChips(" in activity
           and "Anim.joinStates(animLiveStates)" in activity
           and "fun statesOf(state: String)" in anim_kt and "fun joinStates(" in anim_kt)
    report("图开关的名单是**声明过的**状态（写一个不存在的名字，播放时谁都对不上）",
           "val keys = previewStateKeys(folder)" in activity)
    report("一帧什么开关都不开也是合法的一帧（有「底图」这条路回去）",
           "R.string.anim_state_plain" in activity and "animLiveStates.clear()" in activity)
    # 视角**定死**（1.25.0）：曾经能转，但骨头走 vx/vy、图走 canvas 变换，两边各转一次，
    # 骨头被转了两遍 —— 用户看到"骨骼和图片错位"。所以旋转整个拿掉，只剩缩放和平移。
    report("视角只剩缩放和平移：没有旋转、没有旋转手势、也没有那个按钮",
           "viewRotation" not in skel_kt and "rotateBy" not in skel_kt
           and "angleOf" not in skel_kt and "private fun anchorAt(" in skel_kt
           and "animTurn" not in layout_text)
    report("骨头和图共用同一套变换（所以不可能错位）",
           "private fun vx(p: Vec2): Float = offsetX + p.x * scale" in skel_kt
           and "canvas.rotate(" not in skel_kt and "private fun toCanvas(x: Float, y: Float): Vec2 =\n" in skel_kt)
    report("提示语也跟着改了（不再说双指缩放加旋转）",
           "双指缩放/旋转" not in skel_kt and "双指缩放" in skel_kt)
    # 看的是 resetView 的**函数体**：旋转归零、重新算 fit、重新摆位，三件事都得在它里面。
    reset_body = skel_kt[skel_kt.find("fun resetView()"):]
    reset_body = reset_body[:reset_body.find("\n    }")]
    report("「摆正视角」把缩放和偏移一起收回来",
           "computeFit(" in reset_body and "placeFitted()" in reset_body
           and "zoomed = false" in reset_body)
    report("工作台里也能播（不是只能跑去测试场）",
           "private fun toggleStudioPlay(" in activity and "private val studioTick" in activity
           and "Anim.startSeconds(anim, animFrameIndex)" in activity)
    report("帧与关键帧各有一个人：加锚点、存这一帧、存关键帧、删、改时长",
           "private fun addStudioAnchor(" in activity and "private fun saveStudioFrame(" in activity
           and "private fun saveStudioKey(" in activity and "private fun dropStudioFrame(" in activity
           and "private fun nudgeStudioFrame(" in activity)
    report("锚点落在播放头那一刻（不是永远加在末尾）",
           "val t = animTimeline.playhead" in activity
           and "val i = TimelineLayout.frameAt(anim, t)" in activity)
    report("存这一帧写的是整只的姿势（抓帧本来就抓一个完整的姿势）",
           "angles = animView.currentAngles()" in activity)
    report("时长两个方向各 0.1 秒，并且夹在界面那把尺子里",
           "nudgeStudioFrame(-0.1f)" in activity and "nudgeStudioFrame(0.1f)" in activity
           and "coerceIn(MIN_FRAME_SECONDS, MAX_FRAME_SECONDS)" in activity
           and "const val MIN_FRAME_SECONDS = 0.1f" in activity)
    report("底下那条每个 chip 都挂上了自己那一件事",
           all(("R.id.%s" % i) in activity and ("setOnClickListener { %s" % fn) in activity
               for i, fn in (("animUndo", "undoStudio()"), ("animRedo", "redoStudio()"),
                             ("animPlay", "toggleStudioPlay()"), ("animFrameAdd", "addStudioAnchor()"),
                             ("animBind", "bindStudioRule()"),
                             ("animSaveFrame", "saveStudioFrame()"), ("animKeySave", "saveStudioKey()"), ("animSlower", "nudgeStudioFrame(-0.1f)"),
                             ("animLonger", "nudgeStudioFrame(0.1f)"), ("animDropFrame", "dropStudioFrame()"),
                             ("animResetPose", "animView.resetPose()"),
                             ("animFit", "animView.resetView()"), ("animBones", "toggleStudioBones()"),
                             ("animEditBones", "toggleStudioBoneMode()"),
                             ("animReference", "toggleStudioReference()"))))
    # 「转 90°」那个按钮 1.22.1 删过一次（它和双指旋转共用一段漏了 canvas.save() 的代码，
    # 点了就闪退）；1.25.0 把旋转整个拿掉，于是"按钮"和"手势"两条路都没有了。
    report("旋转两条路都没了：没有那个按钮，也没有双指旋转的手势",
           "animTurn" not in layout_text and "animTurn" not in activity
           and "applyViewRotation" not in skel_kt and "twistAngle" not in skel_kt)
    report("离开这一页就把它那套图解掉（两个实例不同时压在内存里）",
           "private fun leaveAnimationStudio(" in activity and "animView.release()" in activity
           and "if (currentPane == Pane.ANIMS && pane != Pane.ANIMS) leaveAnimationStudio()" in activity)
    # 没有可编的那一段时，按底下那些 chip 不能什么都不发生：一个什么都不做的按钮和一个坏掉的
    # 按钮长得一模一样（这一条和测试场那句「播不了就说出来」是同一条规矩）。
    report("没有桌宠 / 没有动画时会说一句，不是按下去什么都不发生",
           "private fun studioEdit()" in activity and activity.count("studioEdit() ?: return") >= 5
           and "R.string.anim_need_pet" in activity and "R.string.anim_pick_one" in activity)
    report("改骨骼没存就离开会说一句（不悄悄丢掉用户拖了半天的骨架）",
           "R.string.anim_bones_unsaved" in activity and "if (animBoneMode) {" in activity)
    report("这一页也能自己播、自己停，且『停』和『碰一下』是两件事",
           "private fun haltStudioPlay(" in activity and "private fun stopStudioPlay(" in activity
           and "if (animPlaying) haltStudioPlay()" in activity)

    print("== 免责声明那道门：同意之前什么都不做，不同意就退出 ==")
    # 「将免责声明添加到用户打开的弹窗，确认后才能继续使用，不确认自己退出」（1.20.1）。
    # 一条门要成立，四件事都要在：不确认走不了、不同意真的退出、同意之前没有副作用、
    # 而且它记的是**版本号**（改了文案会再问一次）。
    gate = activity[activity.find("private fun askDisclaimer("):]
    gate = gate[:gate.find("\n    /**")]
    report("弹窗关了取消键与外点（返回键也关不掉）",
           "setCancelable(false)" in gate and "setNegativeButton(R.string.disclaimer_decline" in gate)
    report("不同意真的退出（不是留在空白页）",
           "finishAffinity()" in gate or "finishAffinity()" in activity)
    report("对话框被系统收走而人还没选，也算没同意",
           "setOnDismissListener" in gate and "if (!answered) onDone(false)" in gate)
    report("正文能滚（不然一段法律文本会把按钮顶出屏幕）",
           "scrolling(box)" in gate and "R.string.disclaimer_body" in gate)
    report("正文里三条都在：AI 开发 / 包的责任 / 不要分享包",
           all(k in activity for k in ("disclaimer_body", "disclaimer_agree", "disclaimer_decline")))
    # 门开在**别的启动动作之前**：同意之前不读角色、不召唤宠物。
    store_pos = activity.find("store.ensureSeeded()")
    gate_pos = activity.find("if (settings.disclaimerAccepted < Settings.DISCLAIMER_VERSION)")
    report("门开在启动动作之前（同意之前什么都不做）",
           0 < gate_pos < store_pos, "门在 %d，第一个启动动作在 %d" % (gate_pos, store_pos))
    report("同意过就跳过，而且记住的是版本号（不是 true/false）",
           "const val DISCLAIMER_VERSION = 1" in
           next((t for p2, t in files.items() if p2.endswith("data/Settings.kt")), "")
           and "settings.disclaimerAccepted < Settings.DISCLAIMER_VERSION" in activity)
    report("设置里能重新读一遍，并显示同意过第几版",
           "disclaimer_more" in activity and "disclaimer_accepted_at" in activity)

    print("== 应用背景图（主题）：挑图 → 缩小存起来 → 铺在面板下面 ==")
    # 「1.20.0 大版本新增用户上传应用的背景，也就是应用主题的功能」。四件事缺一不可：
    # 布局里有那一层、挑图那条路接得上、图存进应用自己的目录（要缩小）、以及"没有图就回到
    # 自带背景"（不自带第二套配色）。外加一条**安全**规矩：settings.json 能手改，而那个
    # 名字会被拼成路径。
    store_kt = next((t for p2, t in files.items() if p2.endswith("data/Settings.kt")), "")
    layout = open(os.path.join(REPO, "app/src/main/res/layout/activity_main.xml"),
                  encoding="utf-8").read()
    report("根布局里有背景图那一层（盖在自带极光之上、面板之下）",
           "R.id.themeBackground" in activity and "@+id/themeBackground" in layout
           and "scaleType=\"centerCrop\"" in layout and "@+id/themeScrim" in layout)
    report("挑图那条路接得上（模式 → 系统选择器 → 存起来）",
           "private var pickingBackground = false" in activity
           and "pickingBackground = true" in activity and "pickImage.launch(" in activity)
    report("背景图这条排在别的图前面（它不属于任何角色）",
           activity.find("if (pickingBackground)") < activity.find("if (pickingReference)"))
    report("图存在应用自己的目录里，不碰外部存储",
           'const val THEME_DIR = "theme"' in store_kt and "File(context.filesDir, Settings.THEME_DIR)" in store_kt)
    # 一张 4000×3000 的照片原样解码是几十 MB，而这个应用大部分时间在跑物理。
    report("存之前先缩小（长边有上限，而且是先读尺寸再决定采样率）",
           "const val MAX_BACKGROUND_PX = 1600" in store_kt
           and "inJustDecodeBounds" in store_kt and "inSampleSize = sample" in store_kt)
    report("写的是 .part 再改名（中途没电不留半张图）",
           'File(themeDir, name + ".part")' in store_kt and "temp.renameTo(target)" in store_kt)
    report("换背景不留旧图（不在手机上攒一堆照片）",
           "fun pruneBackgrounds(" in store_kt and "pruneBackgrounds(name)" in activity)
    report("没有图就回到自带背景（不自带第二套配色）",
           "themeBackground.visibility = View.GONE" in activity
           and "backgroundFile(settings.background)" in activity)
    # 安全那条：名字只收一个纯文件名，读和写走同一个函数。
    report("文件名是**一处**判断：只收纯文件名，读写都过它",
           "fun safeBackgroundName(raw: String): String" in store_kt
           and "safeBackgroundName(o.optString(\"background\", \"\"))" in store_kt
           and "safeBackgroundName(s.background)" in store_kt
           and "Settings.safeBackgroundName(name)" in store_kt)
    report("坏图坏文件降级（解码失败当没有，不让应用起不来）",
           "BitmapFactory.decodeFile(it.absolutePath)" in activity and "?.let {" in activity)
    report("设置里那一段有入口（挑图 / 浓淡 / 去掉）",
           # theme() 是 buildSettingsPane 里的**局部**函数（设置页那一套都是这个写法）。
           "\n        fun theme() {" in activity and "private fun askThemeDim()" in activity
           and "settings_theme_pick" in activity and "settings_theme_remove" in activity
           and "\n        theme()\n" in activity)
    report("改完立刻生效，不用重启",
           "applyTheme()" in activity and activity.count("applyTheme()") >= 3)

    print("== 两级状态：看得见自己在看哪一份，也走得过去 ==")
    # 「希望每个部位能处于不同状态，就是加入全局状态和局部状态」—— 这一版**没有加机制**：
    # 两级状态从 1.11 就在，机制完整（部件的状态住在它自己的文件里、按 `骨头:状态` 标记画图、
    # 引擎各管各的）。缺的只是"你现在看的这一份是谁的"：弹窗原来只写「状态」两个字，于是
    # "每个部位有自己的状态"这件事从外面看不存在。所以盯的是那三样**说得出来**的东西。
    report("状态弹窗的标题写清是**谁的**状态（角色 / 这一节）",
           "logic_states_scope_global" in activity and "logic_states_scope_part" in activity)
    report("说明分两句：全局那份、这一节那份（同名也不会画错）",
           "logic_states_hint_global" in activity and "logic_states_hint_part" in activity)
    report("有一行直接跳到另一层（角色 → 挑一节 / 这一节 → 回角色），不用去 chip 里找",
           "private fun showStatesDialog(" in activity
           and "logic_states_to_part" in activity and "logic_states_to_global" in activity)
    report("跳过去之后主体真的换了（openLogic + 段落跟着走）",
           "logicSection = LogicSection.PARTS" in activity
           and "logicSection = LogicSection.CHARACTER" in activity)
    report("部件那排 chip 就写着它自己有几个状态（不用点进去才知道）",
           "fun withStates(name: String, text: String)" in activity
           and "logic_states_count" in activity)
    # 而机制本身还在那三处 —— 这一版没动它们，但它们是"两级状态"的地基，掉了就等于没这个功能。
    report("机制仍在：部件的状态住在它自己的文件里，不是角色的那份",
           "store.saveObjectLogic(" in activity and "logicStates = spec.states.toMutableList()" in activity)
    report("机制仍在：一层一个引擎，标签 `骨头:状态` 是画图那把钥匙",
           "out[Subjects.stateTag(bone, id)] = on" in bench
           and "fun stateTag(bone: String, state: String)" in next(
               (t for p, t in files.items() if p.endswith("engine/logic/LogicSpec.kt")), ""))

    print("== 动画：帧、速度、两个实例都拿得到 ==")
    # 「加入动画功能，一个是纯演算动画（摆 A、摆 B，中间自己走），另一个是绘制动画（画不同的
    # 帧然后播放，帧之间还能调骨骼）」。两件事是**同一个模型**：一帧 = 可选姿势 + 可选开关。
    # 采样（插值、速度、循环）是纯数学，镜像在 tools/anim_check.py 里十六句；这里盯的是接线：
    # 谁拿得到动画表、播放器有没有真的每帧把姿势和图交出去、以及那条动作/入口通不通。
    anim_kt = next((t for p, t in files.items() if p.endswith("engine/anim/Animation.kt")), "")
    report("动画是一个纯引擎模块（没有 Android、可以被镜像）",
           "data class AnimFrame(" in anim_kt and "class AnimationSpec(" in anim_kt
           and "object Anim {" in anim_kt and "android" not in anim_kt)
    report("一帧有姿势、有图、有秒数三样（演算 / 绘制 / 半演算）",
           "val angles: Map<String, Float> = emptyMap()" in anim_kt
           and "val state: String = \"\"" in anim_kt
           and "val seconds: Float = Anim.DEFAULT_FRAME_SECONDS" in anim_kt
           and "const val DEFAULT_FRAME_SECONDS = 0.4f" in anim_kt)
    report("动画是 load / swapRig 的参数（第二个实例忘不掉）",
           "animations: List<AnimationSpec>," in load_fn and "animations: List<AnimationSpec>," in swap_fn)
    report("load 和换套都把它装进这一只，并且停掉正在播的",
           bench.count("this.animations = animations") >= 2 and bench.count("stopAnimation()") >= 2)
    report("两个实例都交动画表（测试场 + 桌面那一只）",
           activity.count("store.loadAnimations(") >= 3 and "store.loadAnimations(" in overlay)
    # 播放：每帧采样一次，姿势当**目标**交给求解器（所以半路被打一下接得回来），
    # 图只进"画图用的"那张开关表 —— 不进引擎，所以不会变成一只关不掉的开关。
    step_anim = bench[bench.find("private fun stepAnimation("):]
    step_anim = step_anim[:step_anim.find("\n    }")]
    # 一个动画只能有一条播放路径：宿主（工作台 + 测试场）都得走 Timeline.sample。直接调
    # Anim.sample 会拿到一个没有画面偏移的采样 —— 编译能过，但通道里的位置/缩放演不出来。
    report("两个宿主都只从 Timeline.sample 取这一刻（没有第二条播放路径）",
           "Anim.sample(" not in activity and "Anim.sample(" not in bench
           and activity.count("Timeline.sample(") >= 2 and "Timeline.sample(" in bench)
    # 姿势还是"当目标交给求解器"，但 1.31.0 起多了两步：几段先折成一份（Timeline.overlay），
    # 再交给 showPose（写目标、**不动底座** —— 见下面 1.31.0 那一节）。
    report("每帧按时间采样，姿势交给求解器当目标（没有通道时 Timeline.sample 就是 Anim.sample）",
           "Timeline.sample(anim, slot.clock)" in step_anim
           and "Timeline.overlay(merged, s, anim.additive, anim.base)" in step_anim
           and "rag.showPose(merged.angles)" in step_anim
           and "if (spec.tracks.isEmpty())" in tl_kt)
    report("不循环的走完就停（算上回家那一段；关着回家就是停在最后一帧，图不弹回默认）",
           "!anim.loop && slot.clock * Anim.speedOf(anim) >= Anim.totalSeconds(anim)" in step_anim
           # "停在最后一帧上"另一半：整场停，但姿势**冻进底座**（清掉槽之前先 applyPose），
           # 不然叠加的那几段一撤，姿势会跳回用户按着的那个动作。
           and "animSlots.clear()" in step_anim
           and "if (merged.angles.isNotEmpty()) rag.applyPose(merged.angles)" in step_anim)
    report("当前帧的开关只进画图那张表（可以有好几个，拆开写进去）",
           "for (tag in Anim.statesOf(animState)) out[tag] = true" in bench
           and "private fun mergedStates()" in bench and "animState = merged.state" in step_anim)
    report("用户按的停止会把图还回默认（和「走完」不一样）",
           "fun stopAnimation()" in bench and 'animState = ""' in bench)
    report("播不了就说出来（名字不认识 / 一帧都没有）",
           "没有「\" + a.text + \"」这个动画" in bench and "anim_empty_play" in activity)
    # 入口：测试场那张动作列表里的动画段 + 长按菜单 + 规则里的「播放动画」。
    report("动作列表里有动画段（播放 / 停止 / 新建 / 编辑）",
           "private fun fillAnimationList(" in activity and "private fun askAnimation(" in activity)
    # 「动画的入口在哪」——问这句话本身就是一条 bug 报告：按钮原来只数动作，一个只做了动画的
    # 桌宠上面写着「动作 (0)」，而入口在它里面。所以两处都要说：按钮和标题都得数上动画，
    # 而没有宠物在场上时也不能一声不响（那看起来就是"这个按钮坏了"）。
    report("入口按钮和标题都把动画算进去（不然入口等于藏起来了）",
           "val animCount = summoned?.let { store.loadAnimations(it).size }" in activity
           and "sandbox_actions_with_anims" in activity
           and "action_list_title_with_anims" in activity)
    report("没有桌宠在场上时说一句，而不是什么都不弹",
           "sandbox_actions_need_pet" in activity)
    report("抓帧抓的是测试场现在这一只的姿势",
           "sandboxView.currentAngles()" in activity and "fun currentAngles()" in bench)
    report("规则里能播放动画（动作种类 + 选它的地方 + 有人执行）",
           'PLAY_ANIM("playAnim", "播放动画", "anim")' in engine_kt.replace("RuleEngine.kt", "")
           or '"playAnim", "播放动画", "anim"' in next(
               (t for p, t in files.items() if p.endswith("LogicSpec.kt")), "")
           and '"anim" -> pickList(' in activity and '"playAnim" ->' in bench)
    report("桌面上也能播（长按菜单 + ACTION_ANIM + 服务处理）",
           "pet_summon_anim" in activity and "ACTION_ANIM" in overlay
           and "pet?.playAnimation(id)" in overlay)

    print("== 道具挂在节点上，位置由用户拖（1.33.0）==")
    # 「希望道具绑在节点上能由用户自己调节位置」。做法：节点上多一个偏移（本地坐标，所以跟着
    # 骨头转），骨骼页拖它。这一节盯**接线**（数学在 tools/rig_prop_check.py 逐点钉过）。
    spec_kt = logic_kt_text(files)
    skel_kt = next((t for p, t in files.items() if p.endswith("engine/skeleton/Skeleton.kt")), "")
    # 这一段原来先拿 LogicGraphView 的正文去 replace("graph","") 再找 —— 那是一次手滑
    # （大概是想写 skel_view），靠 or 兜住才不会误报。断言里这种"看着在查、其实查的是别的东西"
    # 的写法比没有断言更危险，所以这里直接问对的那份正文。
    skel_view = next((t for p, t in files.items() if p.endswith("ui/SkeletonView.kt")), "")
    report("骨骼页有「摆道具」这个模式：进得去、出得来、松手就存",
           "fun beginPropPlacement(node: String, art: Bitmap?)" in skel_view
           and "fun endPropPlacement()" in skel_view and "onPropMoved?.invoke()" in skel_view)
    report("而且拖的时候画的就是那张道具图（要对的是它看起来在哪儿）",
           "private var propArt: Bitmap? = null" in skel_view
           and "canvas.drawBitmap(" in skel_view and "fun nodePropPointOf(node: NodeSpec): Vec2" in skel_view)
    report("拖完退出这个模式（不然下一次想拖骨头会发现自己动的是剑）",
           "onPropMoved?.invoke()" in skel_view and "propNode = null" in skel_view)
    report("入口在节点那一行的属性弹窗里（调位置 / 回中间）",
           "R.string.rig_prop_place" in activity and "R.string.rig_prop_place_reset" in activity
           and "skeletonView.beginPropPlacement(n.name, art)" in activity)
    report("松手存盘：骨骼页的回调接到 persistRig 上",
           "skeletonView.onPropMoved = { persistRig() }" in activity)
    report("换一个道具就把偏移清回 0（不然新道具会莫名偏在一边）",
           "if (prop != existing.prop) {" in skel_view
           and "existing.propX = 0f" in skel_view)
    report("偏移是本地坐标（跟着骨头转），不是屏幕坐标",
           "fun nodePropPoint(node: NodeSpec): Vec2" in skel_kt
           and "fun propOffsetOf(node: NodeSpec, world: Vec2): Vec2" in skel_kt
           and "private fun axisCos(node: NodeSpec): Float" in skel_kt)
    report("穿在身上的道具走带偏移的那个点（诊断最安静的一种：调了不生效）",
           "val spot = sk.nodePropPoint(node)" in bench and "prop.position = spot" in bench)

    print("== 画出来的方块必须都点得开（1.32.1 自查：两个静默方块）==")
    # "点了一下什么都不发生"和"这个按钮坏了"在用户那儿是同一件事。用户报过一次"方块无法点击
    # 互动"，我在自查里又找到两个：最后那个「否则」那一行原来画了「如果（总是）」和「＋如果」，
    # 而「否则」按定义没有条件 —— 编辑器拿到它只会 `?: return`，于是两个方块静静地什么都不做。
    builder = body_of(activity, "private fun buildLogicPane(")
    add_module = body_of(activity, "private fun addModule(")
    report("「否则」那一行不画如果 / ＋如果（画了就是两个点不动的方块）",
           "if (step.kind != Step.ELSE) {" in builder
           and "editConditions(rule, step).orEmpty()" in builder)
    # ADD_ACTION 是那个兜底分支（它加的就是一个动作），另外几种必须点名。
    report("每一个「＋」方块加什么，addModule 都要管（五种点名 + 动作那种兜底）",
           all(("Node." + k) in add_module for k in
               ["ADD_CONDITION", "ADD_ELSE", "ADD_ELSE_IF", "ADD_ALT", "ADD_OR_ON"])
           and re.search(r"else -> \{\s*\n\s*val actions = \(editTarget\(rule, step\)",
                         add_module) is not None)
    report("而画出来的「＋」也就这六种（多画一种就会没人管）",
           set(re.findall(r"LogicGraphView\.Node\.(ADD_[A-Z_]+)", builder))
           <= {"ADD_CONDITION", "ADD_ACTION", "ADD_ELSE", "ADD_ELSE_IF", "ADD_ALT", "ADD_OR_ON"})

    print("== 「发一张图」：从枚举到气泡，每一处都要有人管（1.37.0）==")
    spec_kt = next((t2 for p2, t2 in files.items() if p2.endswith("engine/logic/LogicSpec.kt")), "")
    labels_kt = next((t2 for p2, t2 in files.items() if p2.endswith("ui/Labels.kt")), "")
    bubble_kt = next((t2 for p2, t2 in files.items() if p2.endswith("render/Bubble.kt")), "")
    # 枚举里有它（引擎认识）→ 词汇表里有它（界面有名字）→ 选择器里有它（能挑图）→
    # 摘要里有它（图上写着什么）→ 气泡真画得出来（Bubble + bubbleArt）。
    report("枚举里有它：`SHOW_IMAGE(\"showImage\", …)`",
           'SHOW_IMAGE("showImage"' in spec_kt)
    report("词汇表里有它（没有名字的动作在界面上是一串 id）",
           '"showImage" to R.string.vocab_action_showImage' in labels_kt)
    report("选择器里有它（从这一只的 images/ 里挑，而且空的时候说一声）",
           '"showImage" -> {' in activity and "store.imageFiles(" in activity
           and "logic_image_none" in activity)
    report("图上的字有它（「发图片 生气」）", '"showImage" -> "发图片 "' in activity)
    report("气泡那条路真的画得出来（说话和发图同一件事的两半）",
           "bubbleArt" in bench and "IMAGE_BUBBLE_SECONDS" in bench
           and "imageArt[a.text]" in bench and "Bubble.imageSize(" in bench)
    report("宠物卡片上有入口：导入 / 删掉 / 说清楚名字是干什么用的",
           "askImages(folder)" in activity and "awaitingImage" in activity
           and "store.saveImage(" in activity and "store.deleteImage(" in activity
           and "sandboxView.refreshImages()" in activity)
    report("图存在这一只宠物自己的文件夹里（不是骨骼套，也不是全局）",
           "IMAGES_DIR = \"images\"" in store_text and "fun imageFiles(" in store_text)
    # 「每一种角色都要有人管」这条老规矩，对**动作种类**也成立：枚举里的每一个 id 都要在
    # 词汇表里有一行 —— 少一个，界面上就会出现一个没有名字的动作。
    enum_block = spec_kt[spec_kt.index("enum class ActionKind"):]
    enum_block = enum_block[:enum_block.index("companion object")]
    kind_ids = set(re.findall(r'[A-Z_]+\("([a-zA-Z]+)", "', enum_block))
    vocab_ids = set(re.findall(r'"([a-zA-Z]+)" to R\.string\.vocab_action_', labels_kt))
    report("每一个动作种类在词汇表里都有名字（%d 个）" % len(kind_ids),
           kind_ids <= vocab_ids, "缺：" + str(sorted(kind_ids - vocab_ids))[:80])
    report("气泡的排版是纯函数（没有 Android，所以本地能逐条镜像）",
           "object Bubble {" in bubble_kt and "import android" not in bubble_kt)

    print("== 一条规则好几个「当」，之间是「或者」（1.35.0）==")
    # 用户的原话是「一条规则可以加多个『当』之间用或者链接」。图上每一个额外的当占**自己的
    # 一行**（和「或者」那一支同一个画法：向下的一行），因为"是第几个当"这件事一行里的盒子
    # 说不清 —— 点它要落到那一个当上。
    report("额外的「当」一行一个，吊在主行那个当盒子底下",
           "for ((oi, ev) in rule.orOns.withIndex())" in builder
           and "LogicGraphView.Drop(mainRow, 0)" in builder)
    report("那一行只有「或者」+ 当盒子（没有如果、没有就）",
           "LogicGraphView.Node.OR, listOf(Labels.join(this, Joins.OR))" in builder
           and "Step.orOn(oi)" in builder)
    report("点当盒子分得清「主行那个」和「额外的那个」",
           "if (step.kind == Step.OR_ON) askOrOn(rule, node.index) else askRuleSettings(rule)"
           in activity)
    report("额外的当没有「就」（不返回 null 就会落到否则的动作表上）",
           "Step.OR_ON -> null" in activity)
    report("改了/删了都写回文件，而且删到只剩一个当就回到老样子",
           "fun orOn(i: Int) = Step(OR_ON, i)" in activity
           and "putRule(index, rule.copy(orOns = ons))" in activity
           and "onDelete = {" in activity)

    print("== 行号 ≠ 规则号：图上点一个方块要落到**那一条规则**上（1.32.1）==")
    # 用户报的是"给第 1 条规则加『就』，结果加到第 2 条上；给最后一条加否则如果，结果加到第
    # 一条上；加完一条规则之后所有方块都没反应"。根因是**行号被当成了规则号**：旧版一条规则
    # 正好一行，两者恰好相等，这一版一条规则可以有好几行（否则如果一级一行、或者一支一行），
    # 于是错位；行数一多还会算出越界的规则号，而 `getOrNull(...) ?: return` 让"点了什么都不
    # 发生"看起来像"方块坏了"。
    report("行 → (规则, 步) 的名单只有一处往里加（和 graph 一起长，不可能不同步）",
           "fun logicPushRow(row: List<LogicGraphView.Node>, drop: LogicGraphView.Drop, ri: Int, step: Step)" in activity
           and activity.count("logicPushRow(") >= 3
           and "logicRows = rowMap" in activity)
    report("点击先查那张名单再动手（不许拿图报的行号当规则号）",
           # 只认"先查名单"这件事本身，不写死返回标签的名字（它叫 tapped 还是 onTap 是细节）。
           "val where = logicRows.getOrNull(row) ?: return@" in activity
           and "val rule = where.first" in activity and "val step = where.second" in activity)
    report("而那张名单只读不写（图重建一次就整个换掉）",
           "private var logicRows: List<Pair<Int, Step>> = emptyList()" in activity)
    report("「＋否则」的字和它加的东西是同一件（不让按钮说谎）",
           "getString(R.string.logic_module_else) to LogicGraphView.Node.ADD_ELSE" in activity
           and "getString(R.string.logic_module_else_if) to LogicGraphView.Node.ADD_ELSE_IF" in activity)
    report("「＋否则」跟在**如果**后面（不是整条规则的末尾）",
           "// 「否则」**就跟在如果后面**" in activity
           and activity.find("logic_module_if")) > 0 if False else (
           activity.find("getString(R.string.logic_module_if)") > 0
           and activity.find("elseBox") > activity.find("getString(R.string.logic_module_if)"))
    report("而「否则如果」是**插在这一级后面**（不是永远加在末尾）",
           "val at = if (step.kind == Step.MAIN) 0 else step.index + 1" in activity
           and "steps.add(\n                    at.coerceIn(0, steps.size)," in activity)

    print("== 否则如果链 + 就的加权或者：并行分支换成它们（1.32.0）==")
    # 用户要的三件事：删掉并行分支（"跟新建一条规则没有区别"）、每一个「如果」后面能加一个向下
    # 的「否则」（多个如果以并且/或者连着时，它就是那些如果的反方向 —— 由整组条件的真值算出来）、
    # 「就」后面能加按权重挑一支的「或者」。这一节盯的是**接线**，语义在 tools/logic_check.py。
    def code_of(text):
        return "\n".join(l.split("//")[0] for l in text.split("\n"))

    report("并行分支删干净了：模型里没有这个类型，读写两半都不认那个键",
           "BranchSpec" not in open(os.path.join(
               REPO, "app/src/main/java/dev/atp/pet/engine/logic/LogicSpec.kt"), encoding="utf-8").read()
           and ".branches" not in code_of(activity)
           and ".branches" not in code_of(bench)
           and "logic_branch" not in open(os.path.join(RES, "values/strings.xml"), encoding="utf-8").read())
    report("向下的行是通用的：图只知道「从哪一行的第几个方块吊下来」，不知道那是否则还是或者",
           "class Drop(val parent: Int, val box: Int)" in graph_kt
           and "fun setRules(next: List<List<Node>>, nextDrops: List<Drop> = emptyList())" in graph_kt
           and "private fun drawDrops(canvas: Canvas)" in graph_kt)
    report("否则吊在「如果」底下、或者吊在「就」底下（挂在哪一个方块下面是有意思的）",
           "LogicGraphView.Drop(fromRow, fromIf)" in activity
           and "LogicGraphView.Drop(rowOf, thenAt)" in activity)
    report("行 → 步 的映射只有一处（建图的时候一起建）",
           "private var logicRows: List<Pair<Int, Step>> = emptyList()" in activity
           and "logicRows = rowMap" in activity
           and "fun logicPushRow(row: List<LogicGraphView.Node>, drop: LogicGraphView.Drop, ri: Int, step: Step)" in activity)
    logic_spec_kt = logic_kt_text(files)
    report("「否则如果」是一级一行，而且每一级有自己的如果 / 就 / 或者",
           "data class ElseIfSpec(" in logic_spec_kt
           and "val elseIfs: List<ElseIfSpec> = emptyList()" in logic_spec_kt
           and "elseIfs = (0 until (elseIfArr?.length() ?: 0)).map" in logic_spec_kt
           and "data class ActionAlt(" in logic_spec_kt
           and "object Weights {" in logic_spec_kt)
    report("否则如果链在引擎里是「从头问、第一个成立的走」（logic_check 里有逐点断言）",
           "for ((ei, step) in rule.elseIfs.withIndex())" in open(os.path.join(
               REPO, "app/src/main/java/dev/atp/pet/engine/logic/RuleEngine.kt"), encoding="utf-8").read())
    report("「或者」有入口：加一支、改权重、删一支（三个都在）",
           "private fun addAlt(" in activity and "private fun setAltWeight(" in activity
           and "private fun removeAlt(" in activity and "private fun askAlt(" in activity)
    report("加一支「或者」时立刻问它做什么（和加一个动作同一个规矩）",
           "askAlt(index, step, at)" in activity or "if (at != null) askAlt(index, step, at)" in activity)
    report("权重能改的还有主「就」那一支（它不在 alts 里，所以另有一条路）",
           "private fun setStepWeight(" in activity and "copy(thenWeight = w)" in activity
           and "copy(weight = w)" in activity)
    # 用户报的那条："分支规则里的方块还不能点"。真因很可能是外层滚动容器把点击抢走了（图和
    # 滚动条在 DOWN 那一刻长得一样），修法是按下时不让父容器拦截。
    report("图上按下时不让外层滚动容器抢走这一串触摸（方块才点得动）",
           "requestDisallowInterceptTouchEvent(true)" in graph_kt
           and "requestDisallowInterceptTouchEvent(false)" in graph_kt)
    # 每一种方块都要有人在点击里管它 —— "点不动"的机器版本就是"有一种 role 没人接"。
    # 按 `logicGraph.onTap` 定位，不写死后面的 `= {`：1.32.1 给它加了显式标签
    # （`= tapped@ {`），写死形状的断言当场失明（三条一起红，而代码是对的）。
    at = activity.find("logicGraph.onTap")
    tap = activity[at:] if at >= 0 else ""
    tap = tap[:tap.find("\n        }")]
    # 需要**各自**处理的角色列在这里（"就"那种动作盒子走 else 那一条，因为点它们都是同一个
    # 动作编辑器）。加一个新角色时这里和点击那一段都要加一条 —— 一条注释守不住这件事，所以
    # 断言守的是"这一批现在都有人管"。
    must_handle = ["WHEN", "IF", "CONNECTOR", "ADD", "TIMER", "TIMER_ELSE", "OR", "ELSE"]
    unhandled = [r for r in must_handle if ("Node." + r) not in tap]
    report("图上每一种方块都有人在点击里管（没有点不开的角色）", not unhandled, ", ".join(unhandled))
    report("而那条兜底的分支是给动作盒子的（就 / 否则里的执行器）",
           "else -> askAction(" in tap)

    print("== 改一帧的时长：边界是「这一帧走完」，不是「下一帧的开始」（1.33.1）==")
    # 用户报："加长（加一帧）或拉长/拉短**最后一段**时关键帧会错位，拉长则整体前移、变短则整体
    # 后移"。真因：三处调用点算边界时写的是"下一帧的开始"，而**最后一帧没有下一帧** ——
    # `getOrElse(index + 1) { 0f }` 于是退回了 0，`from = 0` 意味着**每一个关键帧都被挪**
    # （连第一帧里的也挪）。正确边界是**这一帧走完**的时刻（TimelineLayout.frameEnds）。
    report("边界取自 frameEnds（一张 帧数+1 的表，最后一帧取到它自己的结束）",
           "fun frameEnds(spec: AnimationSpec): List<Float>" in tl_layout_kt
           and "TimelineLayout.frameEnds(base).getOrElse(index) { Anim.duration(base) }" in activity
           and "TimelineLayout.frameEnds(anim).getOrElse(animFrameIndex) { Anim.duration(anim) }" in activity
           and "TimelineLayout.frameEnds(anim).getOrElse(i) { Anim.duration(anim) }" in activity)
    report("而那个退回 0 的写法一处都不剩（它就是「整体都在动」的来源）",
           "frameStarts(base).getOrElse(index + 1)" not in activity
           and "frameStarts(anim).getOrElse(animFrameIndex + 1)" not in activity)
    report("插一帧的边界也是「这一帧走完」（它里面的关键帧不跟着走）",
           "TimelineLayout.frameEnds(anim).getOrElse(i) { Anim.duration(anim) }" in activity)

    print("== 执行器之间能插计时器：盒子在空档里，插入不是替换 ==")
    # 「在一个逻辑中如果有多个执行器，应能在执行器中间插入计时器，第一个执行器前也可以」。
    # 引擎那一半早就有（一个在中间的 wait 会把剩下的动作记下来、到点接着跑，logic_check 里
    # 几条断言盯着），缺的是编辑器：行尾那个「＋动作」只会追加，所以计时器只能落在最后一个
    # 执行器后面 —— 那里它什么也等不到。这一版加的是**空档里的盒子**。
    report("图里多了一个「空档」角色（＋计时器），否则/分支各一个",
           "const val TIMER = 6" in graph_kt and "const val TIMER_ELSE = 7" in graph_kt)
    # 1.32.0：能插计时器的地方从"就 / 否则 / 每个分支"变成"每一行执行器之间"（主行、每一级
    # 否则如果、最后的否则，还有每一支或者）—— 判据是那几行都从同一个地方取"动作表"。
    report("每一行执行器之间都放了它（主行 / 否则如果 / 否则 / 或者）",
           activity.count("timerNode(") >= 4
           and "if (ai < actions.size - 1) row.add(timerNode(ai + 1, rowIndex, elseLike))" in activity
           and "if (k < actions.size - 1) row.add(timerNode(k + 1, rowIndex, step.isElse))" in activity
           and "editTarget(rule, step).orEmpty()" in activity
           and "editTarget(rule, step.alt(ai + 1)).orEmpty()" in activity)
    report("点它问的是秒数，插的是「等一会儿」",
           "private fun askTimer(" in activity
           and "ActionSpec(ActionKind.WAIT.id, value = seconds)" in activity)
    # 插入和替换是两件事：putAction 是"第 i 个改成这个"，insertAction 是"这里多一个"。
    insert = activity[activity.find("private fun insertAction("):]
    insert = insert[:insert.find("\n    }")]
    report("插入是插入（后面的往后挪），不是替换",
           "list.add(at.coerceIn(0, list.size), action)" in insert
           and "list[actionIndex] = action" not in insert)
    report("插到最前面也算（第一个执行器之前）", "coerceIn(0, list.size)" in insert)
    report("空档盒子的文案真的被用上（＋ / 计时器）",
           "logic_module_timer" in activity and "logic_timer_seconds" in activity)

    print("== 桌面穿透：alpha 也要压下去（Android 12 的「不可信触摸」）==")
    # 「召唤到桌面上后无法穿透点到后面的屏幕，切换功能了也不行」。FLAG_NOT_TOUCHABLE 设了、
    # 按钮也切了、界面也变了 —— 手指还是穿不过去，因为 Android 12 起，从**不透明**的悬浮窗
    # 穿过去的触摸会被系统直接丢掉（logcat: "Untrusted touch due to occlusion by <包名>"），
    # 而"够不够透明"看的是窗口自己的 alpha：合成不透明度**大于 0.8** 就不放行。
    # 所以这一版把两个窗口的 alpha 一起压到 0.8 以下。
    report("穿透时把窗口 alpha 压到 0.8 以下",
           "PASS_THROUGH_ALPHA = 0.79f" in overlay
           and "p.alpha = if (on) 1f else PASS_THROUGH_ALPHA" in overlay)
    report("两个窗口都压（这条规则算的是一组系统警告窗）",
           "stripParams?.alpha = if (on) 1f else PASS_THROUGH_ALPHA" in overlay
           and "private var stripParams: WindowManager.LayoutParams? = null" in overlay)
    report("标志仍然照设（alpha 不是替代品，是另一半）",
           "FLAG_NOT_TOUCHABLE" in overlay)
    report("更新失败会重新挂一次窗口，而不是无声无息",
           "private fun applyParams(" in overlay and "window.removeView(view)" in overlay
           and "window.addView(view, params)" in overlay)
    report("切不过去就说出来，而且状态不假装已经切了",
           "val ok = applyParams(" in overlay and "touchable = if (ok) on else was" in overlay
           and "overlay_switch_failed" in overlay)
    report("初始状态也从同一个地方落地（两个窗口一个说法）",
           "setTouchable(touchable)" in overlay)

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

    print("== 部位状态的图不显示：真的 bug（1.31.2）==")
    # 用户报的是"开关能找到、能点亮，但宠物身上那张图不显示"。真因是**写文件的时候就写错了**：
    # 判断"这个状态是不是这一节自己声明的"那三处都调了 `loadObjectLogic()`（不传 folder），
    # 而那个默认值 null **不读任何部位文件** —— 一律判成全局状态，图层上写 `出汗`，开关点亮的
    # 却是 `hand_L:出汗`，两边永远对不上。写坏了的是文件，所以修代码之外还得修数据。
    report("判断「这一节自己声明的状态」时带上了这一只（不然部位状态永远判成全局）",
           "loadObjectLogic(folder)[Subjects.part(bone)]" in store_text
           and "loadObjectLogic()" not in store_text
           and "fun loadObjectLogic(folder: CharacterFolder?)" in store_text)
    report("那个会答错的默认值已经拿掉（参数必须写）",
           "fun loadObjectLogic(folder: CharacterFolder? = null)" not in store_text)
    report("老文件里被写坏的标签会修回来（不然用户装上新版还是不显示）",
           "fun repairPartStateTags(folder: CharacterFolder): Int" in store_text
           and "repairPartStateTags(folder)" in activity
           and "R.string.part_state_tag_fixed" in activity)
    # 两个入口各修一次：上测试场之前，和打开"部位"这一页之前（用户是在那一页看到"我那张图挂在
    # 出汗上"的）。修完是幂等的，所以多修一次没有代价。
    report("上测试场之前和打开部位页之前各修一次（修完幂等）",
           activity.count("repairPartStateTags(folder)") >= 2
           and "private fun repairPartStateTags(folder: CharacterFolder)" in activity)

    print("== 部位的状态：骨骼和节点都算（1.31.2）==")
    # "部位"在这个 App 里有两个来源：骨骼，和长在骨骼上的节点。好几处要"每一个部位"的地方
    # 各写各的 boneNames(...)，于是**节点上的状态在那些地方一律消失**（测试场那排开关、
    # 写规则的状态栏、把状态给某个部位），而逻辑页那一排 chip 却把节点和它的状态个数一起列着
    # —— 用户看到的正是"我刚给那个部位建的状态不见了"。名单只有一份，就没有第二次机会漏。
    bench_body = activity.split("private fun benchStates(")[1].split("private fun ")[0]
    switch_body = activity.split("private fun switchChoices(")[1].split("private fun ")[0]
    report("「每一个部位」只有一份名单（骨骼 + 节点）",
           "private fun partNames(folder: CharacterFolder): List<String> =\n        boneNames(folder) + nodeNames(folder)" in activity
           and "private fun partChoices(folder: CharacterFolder)" in activity)
    report("测试场那排状态开关列到节点上的状态（不然它永远不出现）",
           "for (part in partNames(folder))" in bench_body
           and "boneNames(folder)" not in bench_body
           and "Subjects.stateTag(part, state.id)" in bench_body)
    report("写规则时的「状态」那一栏也列到节点上的状态",
           "for (name in partNames(folder))" in switch_body
           and "boneNames(folder)" not in switch_body
           and "Subjects.stateTag(name, s.id)" in switch_body)
    report("「把状态给某个部位」也能给节点（不然节点根本拿不到状态）",
           "partNames(folder).map { name ->" in activity
           and "Subjects.part(name) to (" in activity
           and "private fun partChoices(" in activity)

    print("== 叠加：一段加在另一段上（1.31.0）==")
    # 这一句问的是"叠加能不能用上"，不是"加法对不对"（加法在 tools/anim_check.py 里逐点量过）。
    # 一个开关在引擎里存在、在界面上没有入口，是这个仓库里发生过不止一次的那种"做完了但用不上"。
    rag = next((t for p2, t in files.items() if p2.endswith("physics/Ragdoll.kt")), "")
    show_body = rag.split("fun showPose(")[1].split("fun ")[0] if "fun showPose(" in rag else ""
    apply_body = rag.split("fun applyPose(")[1].split("fun ")[0] if "fun applyPose(" in rag else ""
    report("「叠加」是动画自己的一个开关，存得住（引擎 + 文件 + 界面都要有）",
           "val additive: Boolean = false" in anim_kt
           and 'additive = o.optBoolean("additive", false)' in store_text
           and '.put("additive", a.additive)' in store_text
           and "R.string.anim_additive" in activity and "addChip" in activity)
    # 基准必须是**快照**：存名字的话，用户改了那个动作，一段已经摆好的动画会跟着变样。
    report("基准是快照（角度表），不是动作的名字",
           "val base: Map<String, Float> = emptyMap()" in anim_kt
           and 'base = readAngles(o.optJSONObject("base"))' in store_text
           and '.put("base", writeAngles(a.base))' in store_text
           and "anim.copy(base = angles)" in activity)
    # 这一条是这一版最要紧的不变量：动画每帧只写**目标**，不动底座。动了的话，第二帧就是在
    # 第一帧的结果上再加一次，姿势会一帧一帧往关节极限里爬（而且看起来像"求解器坏了"）。
    report("叠加的每一帧只写目标，不动底座（否则增量会一帧叠一帧）",
           "poseBase = angles" in apply_body and "poseBase = emptyMap()" in rag
           and "poseBase" not in show_body
           and "rag.showPose(merged.angles)" in bench
           and "rag.applyPose(merged.angles)" in bench)
    report("折的起点是**用户正按着的那个姿势**（不是上一段动画的最后一帧）",
           "Anim.floorFor(slots.first().spec, rag.poseBase)" in bench
           and "Timeline.overlay(merged, s, anim.additive, anim.base)" in bench
           and "fun floorFor(a: AnimationSpec, under: Map<String, Float>): Map<String, Float>" in anim_kt)
    report("一条规矩管四个通道（替换 = 整份接管；叠加 = 相加/相乘/并起来）",
           "fun overlay(" in tl_kt and "Anim.overlayPose(add.angles, base, cur.angles)" in tl_kt
           and "scale[bone] = (scale[bone] ?: 1f) * v" in tl_kt
           and "Anim.joinStates(Anim.statesOf(cur.state) + Anim.statesOf(add.state))" in tl_kt
           and "if (!additive) {" in tl_kt)
    # 同时播两段要的是"各自一个时钟"：一段一个槽（时钟 / 第几遍 / 响过哪几格）。
    report("两段各走各的时钟（一段一个槽，不是一个全局 playClock）",
           "private val animSlots = ArrayList<AnimSlot>()" in bench
           and "private class AnimSlot(val spec: AnimationSpec)" in bench
           and "slot.clock += dt" in bench
           and "fun playAnimation(id: String, overlay: Boolean = false)" in bench
           and "fun stopOverlay()" in bench)
    report("编辑器里叠加的段也拿基准补上没写到的骨头（摆的和演的一个样）",
           "Anim.overlayPose(sample.angles, anim.base, anim.base)" in activity
           and "private fun applyStudioSample(anim: AnimationSpec, sample: TrackSample)" in activity)
    report("「基准」有入口，而且名字是**比出来的**（不是记下来的）",
           "@+id/animBase" in layout_text and "private fun askStudioBase(" in activity
           and "private fun studioBaseLabel(" in activity
           and "private fun currentBaseIndex(" in activity
           and "R.string.anim_base_chip" in activity)
    report("测试场能同时起两段（「＋ 叠」/「撤掉叠加」/「停掉叠加」都在）",
           "sandboxView.playAnimation(anim.id, overlay = true)" in activity
           and "sandboxView.stopOverlay()" in activity
           and "R.string.anim_overlay_play" in activity
           and "fun playingIds(): List<String>" in bench)
    # 1.31.1：用户报的是"按了叠加却什么都没叠上"。原因是「＋ 叠」只管"加进播放表"，而
    # "加在别人身上"是那段动画自己的开关，默认关着 —— 于是"叠"进去的还是整份接管。
    # 这三条钉的是"按钮真的会叠"以及"看不出来的时候界面会说出来"。
    report("「＋ 叠」没打开叠加就先打开它（按钮写着叠，就得真的叠）",
           "if (!anim.additive &&\n                        store.saveAnimation(folder, anim.copy(additive = true))" in activity
           and "R.string.anim_overlay_turned_on" in activity)
    report("「正在演」那一行标出哪几段是叠加，并写出脚下踩着谁",
           "R.string.anim_now_add" in activity and "sandboxView.heldPoseName()" in activity
           and "R.string.anim_floor" in activity and "sandboxView.poseIsRest()" in activity)
    # 反查是**比角度**：名字记在旁边的话，动作被改名/改内容之后界面上那句"脚下：坐"就是假话。
    report("脚下是谁是**比角度**反查出来的，不是记了个名字",
           "fun heldPoseName(): String?" in bench and "if (angles == held) return name" in bench
           and "fun poseIsRest(): Boolean" in bench)
    report("整份替换的动画压着动作时，界面当场说出怎么办（不用用户自己猜）",
           "R.string.anim_replace_warn" in activity and "specs.firstOrNull { it != null && !it.additive }" in activity)
    report("叠了几段只有一处数（状态行和按钮不会各数各的）",
           "fun overlayCount(): Int = maxOf(0, animSlots.size - 1)" in bench
           and activity.count("sandboxView.overlayCount()") >= 1)
    # 顺手修的那个会丢数据的 bug：名字/速度弹窗原来是**造一段新的**，通道（1.23.0）会被它打回
    # 默认值 —— 也就是"烤完时间轴，进来调一下速度，关键帧就没了"。判据是"从原来那段 copy"。
    report("名字/速度弹窗改的是原来那一段（造新的会把通道抹掉）",
           "val base = existing ?: AnimationSpec(id, name)" in activity
           and "base.copy(" in activity)

    # 1.39.0，用户报的两件事：兜底那一支「否则」没有删除按钮、规则里"发一张图"没有上传入口。
    print("== 兜底那一支「否则」也能删（用户报的）==")
    report("弹窗里有一条「删掉这一支否则」，而且只在兜底那一支上出现",
           "R.string.logic_else_remove" in activity
           and "if (step.kind == Step.ELSE) {" in activity)
    report("删的是那一支自己的动作（elseIfs 一级都不动，最后一行退回「＋否则」）",
           "private fun removeElse(index: Int)" in activity
           and "rule.copy(elseActions = emptyList(), elseWeight = 1)" in activity)
    report("文案两种语言都有",
           "logic_else_remove" in open(os.path.join(RES, "values/strings.xml"), encoding="utf-8").read()
           and "logic_else_remove" in open(
               os.path.join(RES, "values-en/strings.xml"), encoding="utf-8").read())

    print("== 规则里「发一张图」能当场传一张（用户报的）==")
    report("挑图那个列表第一行是「上传一张图」（不是「没有图就只说一声」）",
           "R.string.logic_image_upload" in activity
           and "listOf(upload) + images.map" in activity)
    report("它有自己的一条路：导入完直接填进那一格动作（不用回来再挑一遍）",
           "awaitingImageForAction = index to actionIndex" in activity
           and "putAction(ri, ai, ActionSpec(ActionKind.SHOW_IMAGE.id, text = name))" in activity)
    report("从别处（桌宠管理那张卡）传的还是回那张管理表，两条路分得清",
           "if (forAction != null)" in activity and "askImages(folder)" in activity)
    report("还没有图的时候，提示换成「这里就能传一张」（不是把人支使到另一页）",
           "if (images.isEmpty()) getString(R.string.logic_image_none)" in activity)
    report("文案两种语言都有",
           all(k in open(os.path.join(RES, "values/strings.xml"), encoding="utf-8").read()
               for k in ("logic_image_upload", "logic_image_no_pet"))
           and all(k in open(os.path.join(RES, "values-en/strings.xml"), encoding="utf-8").read()
                   for k in ("logic_image_upload", "logic_image_no_pet")))

    print()
    if FAILURES:
        print("%d failure(s)" % len(FAILURES))
        return 1
    print("no dead controls")
    return 0


if __name__ == "__main__":
    sys.exit(main())
