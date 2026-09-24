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
    report("活粒子画在宠物、道具、钉子、等待提示之后",
           live_at > max(bench.find("drawCharacter(canvas, sk)"), bench.find("drawProps(canvas)"),
                         bench.find("drawNails(canvas)"), bench.find("drawWaiting(canvas)")))
    report("印子画在一切之前（它算地面的一部分）",
           0 <= stains_at < bench.find("drawFluid(canvas)"))
    report("气泡和平衡读数仍在粒子上面（一个是台词，一个是调试读数）",
           bench.find("drawBubble(canvas, sk)") > live_at)
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
    report("面板有一张开关表：主体自己的 + 全局的 + 别的部件的",
           "Subjects.stateTag(part, s.id)" in switches
           and "store.loadLogic(folder.id).states" in switches
           and "store.loadObjectLogic(folder)[Subjects.part(bone)]?.states" in switches)
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
    report("替换：原来那层被标成「状态关着时画」",
           'l.put("state", "!" + tag)' in store_text and "if (!overlay &&" in store_text)
    report("叠加：原来那层一个字都不动（两张都画）",
           "if (!overlay && l.getString(\"bone\") == bone" in store_text.replace("\n", " ")
           or "!overlay && l.getString" in store_text)
    report("新层贴着它那张图（baseZ + 1），不是压在所有东西上面",
           "baseZ + 1" in store_text and "baseZ = maxOf(baseZ" in store_text)
    report("已经做好的图能在两种关系之间反悔（一键切换）",
           "fun setVariantOverlay(" in store_text and "part_variant_to_overlay" in activity
           and "part_variant_to_replace" in activity)
    report("切换只改那一个标记（不碰图、不碰 z、不碰别的骨头）",
           "l.put(\"state\", \"\")" in store_text and "hasVariant(" in store_text)
    report("没有那张图就不给「改成替换」（不然等于把图藏起来）",
           "s2.isEmpty() && hasVariant(arr, bone, tag)" in store_text)

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
    report("左边是一段和它的帧：动画列表 + 关键帧横带 + 末尾的「＋ 帧」",
           "@+id/animFrames" in layout_text and "private fun buildFrameStrip(" in activity
           and "private fun paintFrameChips(" in activity and "@+id/animFrameAdd" in layout_text)
    report("选中一帧就摆上去，而且姿势取的是 Anim.framePose（和播放器同一刻，不是另算一套）",
           "animView.applyPose(Anim.framePose(anim, animFrameIndex))" in activity
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
    report("旋转 / 放大 / 平移都在：视图旋转、双指旋转的增量、以及聚焦时的反变换",
           "var viewRotation = 0f" in skel_kt and "fun rotateBy(" in skel_kt
           and "private fun angleOf(" in skel_kt and "private fun anchorAt(" in skel_kt)
    report("旋转是视图的（骨架/图/参考图一起转），所以画布只转一次",
           "canvas.rotate(viewRotation, pivotX, pivotY)" in skel_kt and "val turned = viewRotation != 0f" in skel_kt)
    # 看的是 resetView 的**函数体**：旋转归零、重新算 fit、重新摆位，三件事都得在它里面。
    reset_body = skel_kt[skel_kt.find("fun resetView()"):]
    reset_body = reset_body[:reset_body.find("\n    }")]
    report("「摆正视角」把旋转、缩放、偏移一起收回来",
           "applyViewRotation(0f)" in reset_body and "computeFit(" in reset_body
           and "placeFitted()" in reset_body)
    report("工作台里也能播（不是只能跑去测试场）",
           "private fun toggleStudioPlay(" in activity and "private val studioTick" in activity
           and "Anim.startSeconds(anim, animFrameIndex)" in activity)
    report("关键帧四件事各有一个人：加、存、删、改时长",
           "private fun captureStudioFrame(" in activity and "private fun saveStudioFrame(" in activity
           and "private fun dropStudioFrame(" in activity and "private fun nudgeStudioFrame(" in activity)
    report("加帧是插在**这一帧后面**，不是永远加在末尾",
           "(animFrameIndex + 1).coerceAtMost(anim.frames.size)" in activity)
    report("存这一帧写的是整只的姿势（抓帧本来就抓一个完整的姿势）",
           "angles = animView.currentAngles()" in activity)
    report("时长两个方向各 0.1 秒，并且夹在界面那把尺子里",
           "nudgeStudioFrame(-0.1f)" in activity and "nudgeStudioFrame(0.1f)" in activity
           and "coerceIn(MIN_FRAME_SECONDS, MAX_FRAME_SECONDS)" in activity
           and "const val MIN_FRAME_SECONDS = 0.1f" in activity)
    report("底下那条每个 chip 都挂上了自己那一件事",
           all(("R.id.%s" % i) in activity and ("setOnClickListener { %s" % fn) in activity
               for i, fn in (("animPlay", "toggleStudioPlay()"), ("animFrameAdd", "captureStudioFrame()"),
                             ("animSaveFrame", "saveStudioFrame()"), ("animSlower", "nudgeStudioFrame(-0.1f)"),
                             ("animLonger", "nudgeStudioFrame(0.1f)"), ("animDropFrame", "dropStudioFrame()"),
                             ("animResetPose", "animView.resetPose()"), ("animTurn", "turnStudio(90f)"),
                             ("animFit", "animView.resetView()"), ("animBones", "toggleStudioBones()"),
                             ("animEditBones", "toggleStudioBoneMode()"))))
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
    report("每帧按时间采样，姿势交给求解器当目标",
           "Anim.sample(anim, playClock)" in step_anim and "rag.applyPose(s.angles)" in step_anim)
    report("不循环的走完就停在最后一帧（图不弹回默认）",
           "!anim.loop && playClock * Anim.speedOf(anim) >= Anim.duration(anim)" in step_anim)
    report("当前帧的开关只进画图那张表（可以有好几个，拆开写进去）",
           "for (tag in Anim.statesOf(animState)) out[tag] = true" in bench
           and "private fun mergedStates()" in bench and "animState = s.state" in step_anim)
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

    print("== 执行器之间能插计时器：盒子在空档里，插入不是替换 ==")
    # 「在一个逻辑中如果有多个执行器，应能在执行器中间插入计时器，第一个执行器前也可以」。
    # 引擎那一半早就有（一个在中间的 wait 会把剩下的动作记下来、到点接着跑，logic_check 里
    # 几条断言盯着），缺的是编辑器：行尾那个「＋动作」只会追加，所以计时器只能落在最后一个
    # 执行器后面 —— 那里它什么也等不到。这一版加的是**空档里的盒子**。
    graph_kt = next((t for p, t in files.items() if p.endswith("LogicGraphView.kt")), "")
    report("图里多了一个「空档」角色（＋计时器），否则/分支各一个",
           "const val TIMER = 6" in graph_kt and "const val TIMER_ELSE = 7" in graph_kt)
    report("就 / 否则 / 每个分支的执行器之间都放了它",
           activity.count("timerNode(") >= 6 and "branch.actions.size - 1" in activity
           and "rule.elseActions.size - 1" in activity and "rule.actions.size - 1" in activity)
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

    print()
    if FAILURES:
        print("%d failure(s)" % len(FAILURES))
        return 1
    print("no dead controls")
    return 0


if __name__ == "__main__":
    sys.exit(main())
