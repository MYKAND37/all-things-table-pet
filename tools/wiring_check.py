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
