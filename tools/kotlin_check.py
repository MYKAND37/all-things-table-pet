
"""Static checks over the Kotlin sources, for the bugs a Python mirror cannot see.

The mirrors in this directory test the MATHS: they run the same algorithms in Python and
compare the answers. That catches physics. It cannot catch a Kotlin-API mistake, and one of
those shipped: org.json's one-argument optDouble answers NaN for a key that is not in the
object, NaN is not null, so

    val air = phys?.optDouble("roomAir")?.toFloat() ?: derived()

hands NaN to everything downstream -- the floor, every bone position, the view transform --
and the pet is drawn at coordinates that are not numbers, which is to say not drawn. The
bench was empty except for the HUD, and no amount of Python could have told us.

    python3 tools/kotlin_check.py

Three checks, all of them cheap and all of them things that have actually gone wrong here:
  1. the optDouble trap, and its siblings optInt/optLong/optString-with-one-argument
  2. bracket balance per file, so a bad edit is caught before a five-minute CI round trip
  3. every R.string / R.drawable / R.id the Kotlin names is one the resources define
"""
import os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SRC = os.path.join(REPO, "app/src/main/java")
RES = os.path.join(REPO, "app/src/main/res")

FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)
    return ok


def kotlin_files():
    for base, _, names in os.walk(SRC):
        for n in names:
            if n.endswith(".kt"):
                yield os.path.join(base, n)


#: optDouble(name) with no fallback, then an elvis: NaN when the key is missing, and NaN is
#: not null, so the elvis never fires. The "?." chain in the middle is the point -- the bug
#: this exists for was written as optDouble("roomAir")?.toFloat() ?: derived(), and a checker
#: that only looked at what came IMMEDIATELY after the call would have missed it.
OPT_ONE_ARG = re.compile(r"\.opt(Double|Int|Long)\([^,()]+\)(?:\?\.\w+\(\))*\s*\?:")


def check_opt_defaults():
    """A one-argument opt* must never be followed by ?: -- the elvis cannot catch NaN."""
    bad = []
    for path in kotlin_files():
        for i, line in enumerate(open(path, encoding="utf-8").read().split("\n"), 1):
            code = line.split("//")[0]
            if OPT_ONE_ARG.search(code):
                bad.append("%s:%d  %s" % (os.path.basename(path), i, line.strip()[:90]))
    report("no optDouble(k) ?: fallback (NaN is not null)", not bad, "\n         ".join(bad))


def check_balance():
    """Brackets per file, ignoring strings, chars and comments."""
    bad = []
    for path in kotlin_files():
        text = open(path, encoding="utf-8").read()
        depth = {"(": 0, "{": 0, "[": 0}
        pairs = {")": "(", "}": "{", "]": "["}
        i, n = 0, len(text)
        in_str = in_char = in_line = in_block = False
        in_raw = False
        while i < n:
            c = text[i]
            two = text[i:i + 2]
            three = text[i:i + 3]
            if in_line:
                if c == "\n":
                    in_line = False
            elif in_block:
                if two == "*/":
                    in_block = False
                    i += 1
            elif in_raw:
                if three == '"""':
                    in_raw = False
                    i += 2
            elif in_str:
                if c == "\\":
                    i += 1
                elif c == '"':
                    in_str = False
            elif in_char:
                if c == "\\":
                    i += 1
                elif c == "'":
                    in_char = False
            else:
                if two == "//":
                    in_line = True
                    i += 1
                elif two == "/*":
                    in_block = True
                    i += 1
                elif three == '"""':
                    in_raw = True
                    i += 2
                elif c == '"':
                    in_str = True
                elif c == "'":
                    in_char = True
                elif c in depth:
                    depth[c] += 1
                elif c in pairs:
                    depth[pairs[c]] -= 1
                    if depth[pairs[c]] < 0:
                        bad.append("%s: unmatched %s" % (os.path.basename(path), c))
                        break
            i += 1
        else:
            left = {k: v for k, v in depth.items() if v != 0}
            if left:
                bad.append("%s: %s" % (os.path.basename(path), left))
    report("every file's brackets balance", not bad, "; ".join(bad))


def check_enums():
    """
    Enum entries that appear twice.

    Kotlin reports this as "Conflicting declarations: enum entry LIQUIDS, enum entry LIQUIDS"
    plus a cascade of impossible-looking errors in every when() that uses the enum, which is
    a confusing way to be told that a paste went in twice. Cheap to check, annoying to read.
    """
    bad = []
    for path in kotlin_files():
        text = open(path, encoding="utf-8").read()
        for m in re.finditer(r"enum class \w+[^{]*\{([^}]*)\}", text, re.S):
            # Only the entry list: everything after the first ";" is the enum's members, and
            # a member that mentions an entry by name is not a second declaration.
            body = m.group(1).split(";")[0]
            names = re.findall(r"\b([A-Z][A-Z0-9_]{2,})\s*[(,;]|\b([A-Z][A-Z0-9_]{2,})\s*$",
                               body, re.M)
            flat = [a or b for a, b in names]
            seen, dupes = set(), set()
            for n in flat:
                if n in seen:
                    dupes.add(n)
                seen.add(n)
            if dupes:
                bad.append("%s: %s" % (os.path.basename(path), ", ".join(sorted(dupes))))
    report("no enum entry is declared twice", not bad, "; ".join(bad))


def defined_resources():
    strings, drawables, ids = set(), set(), set()
    for base, _, names in os.walk(RES):
        for n in names:
            if n.endswith(".xml"):
                text = open(os.path.join(base, n), encoding="utf-8", errors="replace").read()
                strings |= set(re.findall(r'<string name="([^"]+)"', text))
                ids |= set(re.findall(r'android:id="@\+id/([^"]+)"', text))
            if base.endswith("drawable"):
                drawables.add(os.path.splitext(n)[0])
    return strings, drawables, ids


def check_references():
    strings, drawables, ids = defined_resources()
    missing = []
    for path in kotlin_files():
        text = open(path, encoding="utf-8").read()
        for kind, known in (("string", strings), ("drawable", drawables)):
            for name in set(re.findall(r"R\." + kind + r"\.(\w+)", text)):
                if name not in known:
                    missing.append("%s R.%s.%s" % (os.path.basename(path), kind, name))
        for name in set(re.findall(r"R\.id\.(\w+)", text)):
            if name not in ids:
                missing.append("%s R.id.%s" % (os.path.basename(path), name))
    report("every R.* the code names exists", not missing, "; ".join(missing[:8]))


#: A call to one of the chip painters, with its literal first-argument list in the capture.
CHIP_CALL = re.compile(r"paint(Chips|Swatches)\([^,]+,\s*listOf\(([^)]*)\)")


def check_folder_constants_qualified():
    """
    A constant of CharacterFolder's companion has to be written `CharacterFolder.NAME` from
    outside it.

    This is the second time this exact mistake cost a CI round trip: `POSES_FILE` was reached
    bare from CharacterStore once, and 1.17.0 did it again with the new `ANIMATIONS_FILE`
    (`Unresolved reference: ANIMATIONS_FILE`). It is invisible locally -- only CI compiles --
    and it is mechanical, which is exactly what a check is for.

    Narrow on purpose: it knows about ONE class (the folder, where the layout constants live),
    about SCREAMING_CASE names, and about the region after that class ends. It is not a type
    checker and does not pretend to be one.
    """
    path = os.path.join(SRC, "dev/atp/pet/data/CharacterStore.kt")
    if not os.path.isfile(path):
        report("CharacterFolder 的常数在类外都带了前缀", True, "no CharacterStore.kt")
        return
    text = open(path, encoding="utf-8").read()
    start = text.find("class CharacterFolder")
    end = text.find("\n}", start)
    if start < 0 or end < 0:
        report("CharacterFolder 的常数在类外都带了前缀", True, "class not found")
        return
    inside, outside = text[start:end], text[end:]
    names = re.findall(r"\b(?:const )?val ([A-Z][A-Z0-9_]*) =", inside)
    bad = []
    for name in names:
        for m in re.finditer(r"(?<![.\w])" + name + r"\b", outside):
            line = outside[:m.start()].count("\n") + text[:end].count("\n") + 2
            bad.append("%s:%d  %s 少了 CharacterFolder. 前缀" % ("CharacterStore.kt", line, name))
    report("CharacterFolder 的常数在类外都带了前缀",
           not bad, "; ".join(bad[:4]))

    # 反向的那一半：这些名字**确实**还在（改名的镜像会红，但这一句说的是"检查没白跑"）。
    report("而且这一个检查真的找到了那些常数", len(names) >= 3, "%d 个：%s"
           % (len(names), ", ".join(names[:6])))


def check_upper_case_names():
    """
    A bare SCREAMING_CASE name that is one or two letters away from a name this tree declares
    is a typo, and a typo is a build that does not compile.

    The fourth of the same family (private companion member, folder constant, cross-package
    object): mechanical, invisible locally, only CI compiles -- and this one has cost two builds
    (`ANIMATIONS_FILE` reached bare, `PROP_KINDS` written where the map was called `PROPKINDS`).

    Why "close to a declared name" rather than "declared at all": the framework has constants
    this tree never declares (`Service.START_NOT_STICKY`, which a subclass may write bare) and
    hex colour literals look like constants too (`0xFF171528`). Those must not be reported. A
    name that is a couple of edits from one of OUR names is a different animal: it is somebody
    meaning to write that name. Two edits is the whole tolerance -- exactly the distance between
    PROP_KINDS and PROPKINDS.
    """
    declared = set()
    files = list(kotlin_files())
    for path in files:
        text = open(path, encoding="utf-8").read()
        declared |= set(re.findall(r"\b(?:const )?val ([A-Z][A-Z0-9_]*)\b", text))
        declared |= set(re.findall(r"\b(?:object|class) ([A-Z][A-Z0-9_]*)\b", text))
        for body in re.findall(r"enum class \w+[^{]*\{([^}]*)\}", text, re.S):
            declared |= set(re.findall(r"([A-Z][A-Z0-9_]*)\s*[,()\n]", body))
    by_length = {}
    for name in declared:
        by_length.setdefault(len(name), []).append(name)

    def close_to(name):
        """A declared name within two edits, or ""."""
        for length in range(len(name) - 2, len(name) + 3):
            for other in by_length.get(length, ()):
                if edits(name, other) <= 2:
                    return other
        return ""

    def edits(a, b):
        """Bounded Levenshtein; the bound is 3 because anything further is not interesting."""
        if a == b:
            return 0
        prev = list(range(len(b) + 1))
        for i, ca in enumerate(a, 1):
            cur = [i]
            for j, cb in enumerate(b, 1):
                cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
            if min(cur) > 3:
                return 9
            prev = cur
        return prev[-1]

    bad = []
    for path in files:
        text = open(path, encoding="utf-8").read()
        imported = set(re.findall(r"^import\s+[\w.]*\b([A-Z][A-Z0-9_]*)\s*$", text, re.M))
        seen = set()
        for i, line in enumerate(text.split("\n"), 1):
            code = line.split("//")[0]
            if code.lstrip().startswith(("*", "/*")):
                continue
            for m in re.finditer(r"(?<![\w.])[A-Z][A-Z0-9_]{2,}\b", code):
                name = m.group(0)
                if name in declared or name in imported or name in seen:
                    continue
                seen.add(name)
                near = close_to(name)
                if near:
                    bad.append("%s:%d 用了 %s —— 是不是想写 %s？"
                               % (os.path.basename(path), i, name, near))
    report("全大写名字没有拼错的（离本树某个名字只有一两个字母的名字）",
           not bad, "; ".join(bad[:5]))


def check_local_function_scope():
    """
    A function declared INSIDE another function cannot be called from a member function.

    The fifth of the same family (private companion member, folder constant, cross-package
    object, misspelled constant): mechanical, invisible locally, only CI compiles. It cost a
    build in 1.20.0 -- twice, in one commit -- because the settings pane keeps its helper as a
    LOCAL function (`fun put(next: Settings)` inside buildSettingsPane, which is where the
    "redraw this page" habit lives) and two new call sites reached for it from member
    functions. Kotlin says `Unresolved reference: put`, which is a confusing way to say
    "you are in the wrong scope".

    How it decides: walk each file with a brace-depth stack, remembering which `fun` each line
    is inside (the enclosing declaration, by depth). A name declared as a local function and
    NOT declared as a member anywhere in the file may only be called from inside the function
    that declares it. Local functions that shadow a member of the same name are left alone --
    those are legal, and flagging them would be crying wolf.
    """
    bad = []
    for path in kotlin_files():
        text = open(path, encoding="utf-8").read()
        lines = text.split("\n")
        # 成员函数：4 空格缩进的 fun（含 override/private 等修饰）。
        members = set(re.findall(r"^    (?:@\w+\s+)?(?:private |internal |override |open )*fun\s+(\w+)",
                                 text, re.M))
        # 局部函数：8 空格或更深缩进的 fun，以及它在哪个函数里面。
        stack = []          # (depth, enclosing function name)
        depth = 0
        owner_of_line = []
        # 局部函数名 -> **一组**声明它的函数。一个名字可以在两个函数里各有一个局部版本
        # （`row` 就是这样），所以这里必须是集合，不能是"最后一个赢"。
        local_decl = {}
        for i, line in enumerate(lines, 1):
            code = line.split("//")[0]
            stripped = code.lstrip()
            if code.lstrip().startswith(("*", "/*")):
                owner_of_line.append(stack[-1][1] if stack else "")
                continue
            m = re.match(r"^(\s*)(?:private |internal |override |open )*fun\s+(\w+)", code)
            owner_of_line.append(stack[-1][1] if stack else "")
            if m and len(m.group(1)) >= 8 and code.strip().endswith("{"):
                # owner 是空的时候，这不是"某个函数里的局部函数"，而是 **companion object /
                # object 里的成员**（它们的缩进也是 8）。那类成员本来就能被实例方法调用。
                if stack:
                    local_decl.setdefault(m.group(2), set()).add(stack[-1][1])
                stack.append((depth, m.group(2)))
            elif m and len(m.group(1)) >= 4 and code.strip().endswith("{"):
                stack.append((depth, m.group(2)))
            depth += code.count("{") - code.count("}")
            while stack and depth <= stack[-1][0]:
                stack.pop()
        for i, line in enumerate(lines, 1):
            code = line.split("//")[0]
            if code.lstrip().startswith(("*", "/*")):
                continue
            for name, homes in local_decl.items():
                if name in members or re.search(r"fun\s+" + name + r"\b", code):
                    continue
                if re.search(r"(?<![\w.])" + name + r"\s*\(", code):
                    here = owner_of_line[i - 1]
                    # 只在"确实身处某个**成员**函数体内、而那个函数不是有这个名字的函数"时
                    # 才报。花括号的深度对字符串里的 `{}` 是数不准的，所以这一条宁可漏，
                    # 不可误报：`here` 是个局部函数名时说明跟踪已经不可信，那就不说话。
                    if here in members and here not in homes:
                        bad.append("%s:%d 调了 %s()，而它是 %s() 里的局部函数"
                                   % (os.path.basename(path), i, name, "/".join(sorted(homes))))
    report("局部函数没有被别处调用（只有编译器看得见的那种作用域错）",
           not bad, "; ".join(bad[:4]))


def strip_code(text):
    """把字符串字面量和注释去掉（花括号计数必须只看**代码**）。

    第一版没做这件事，于是 `Pane.PET_LOGIC -> if (…) … else …` 那种 arm 让计数走偏、正文提前
    截断 —— 检查于是**在正确的代码上报错**。这比不检查更糟，所以先把这一层补上。
    """
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
        elif c == '"':
            if text.startswith('"""', i):
                j = text.find('"""', i + 3)
                i = n if j < 0 else j + 3
            else:
                i += 1
                while i < n and text[i] != '"':
                    i += 2 if text[i] == "\\" else 1
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def check_enum_when_exhaustive():
    """
    A `when` over an enum has to name every entry — and adding an enum value is what breaks it.

    The sixth of the same family (private companion member, folder constant, cross-package
    object, misspelled constant, local-function scope). It cost a build in 1.21.0: adding ANIMS
    to `Pane` left `when (pane)` non-exhaustive, which is
    `error: 'when' expression must be exhaustive` — a message that names the missing branch.

    Two earlier versions of THIS check were wrong, and both are worth remembering:

      * judging by "the body mentions some of the enum's entries" flagged `when (item.id)`,
        whose arms call `show(Pane.LIQUIDS)` — a when over menu ids has nothing to do with Pane;
      * counting braces without stripping strings and comments truncated the body early, so it
        reported a missing branch in code that had it.

    What it does now: only a `when (x)` where **x is declared with an enum type** is judged
    (`pane: Pane`, `val kind: ActionKind`…). That is the one case where the compiler's rule
    applies, and it cannot be confused by a `when` that merely mentions enum values.
    """
    bad = []
    for path in kotlin_files():
        text = strip_code(open(path, encoding="utf-8").read())
        enums = {}
        for m in re.finditer(r"enum class (\w+)[^{]*\{([^}]*)\}", text, re.S):
            entries = re.findall(r"([A-Z][A-Z0-9_]*)\s*[,()\n]", m.group(2))
            if len(entries) >= 2:
                enums[m.group(1)] = entries
        if not enums:
            continue
        # 变量 -> 它的枚举类型：参数、属性、`val x: Enum`，以及 `val x = Enum.ENTRY`。
        typed = {}
        for m in re.finditer(r"\b(\w+)\s*:\s*(\w+)\b", text):
            if m.group(2) in enums:
                typed[m.group(1)] = m.group(2)
        for m in re.finditer(r"\b(?:val|var)\s+(\w+)\s*=\s*(\w+)\.", text):
            if m.group(2) in enums:
                typed[m.group(1)] = m.group(2)
        lines = text.split("\n")
        for i, line in enumerate(lines, 1):
            m = re.match(r"^(\s*)when\s*\(\s*(\w+)\s*\)\s*\{?\s*$", line)
            if not m or m.group(2) not in typed:
                continue
            entries = enums[typed[m.group(2)]]
            depth, body = 0, []
            for l in lines[i - 1:]:
                body.append(l)
                depth += l.count("{") - l.count("}")
                if depth <= 0 and l.strip().endswith("}"):
                    break
            blob = "\n".join(body)
            if re.search(r"^\s*else\s*(->|:)", blob, re.M):
                continue
            named = [e for e in entries if re.search(r"\b" + e + r"\b", blob)]
            if len(named) != len(entries):
                bad.append("%s: when (%s: %s) 少写了 %s，而且没有 else"
                           % (os.path.basename(path), m.group(2), typed[m.group(2)],
                              ", ".join(e for e in entries if e not in named)))
    report("枚举的 when 是穷尽的（加了新枚举值就该有人提醒）", not bad, "; ".join(bad[:4]))


def report_hardcoded_text():
    """
    界面里还硬写着的中文：**报数，不判红**。

    1.19.0 把应用自己的功能词搬进了资源并给了英文（控件、段落名、引擎词汇、骨头名），但界面
    里还有一批**拼在行里的短句**（「未导入」「 · 播放中」「低于」）是硬写的 —— 它们要能被翻译，
    得先一条一条搬进资源，那是下一批的活。

    所以这里只报数字和文件，不失败：一份"还剩多少"的清单，比一句"基本都翻了"有用。
    引擎那边（日志与 GameEvent.describe）也列出来，但那是**有意的**：调试读数保持中文。
    """
    cjk = re.compile(r'"[^"]*[\u4e00-\u9fff][^"]*"')
    per_file = []
    for base, _, names in os.walk(SRC):
        for n in sorted(names):
            if not n.endswith(".kt"):
                continue
            path = os.path.join(base, n)
            count = 0
            for line in open(path, encoding="utf-8").read().split("\n"):
                stripped = line.strip()
                if stripped.startswith(("//", "*", "/*")):
                    continue
                count += len(cjk.findall(line))
            if count:
                per_file.append((count, os.path.relpath(path, SRC)))
    ui = [(c, f) for c, f in per_file if f.startswith("dev/atp/pet/ui/") or f.endswith("MainActivity.kt")]
    engine = [(c, f) for c, f in per_file if (c, f) not in ui]
    print("   ·   界面里还硬写着的中文 %d 处（下一批要搬进资源）："
          % sum(c for c, _ in ui))
    for c, f in sorted(ui, reverse=True):
        print("        %4d  %s" % (c, f))
    print("   ·   引擎里（日志 / describe，有意保持中文）%d 处" % sum(c for c, _ in engine))


def check_object_imports():
    """
    Using `Something.member` from another PACKAGE without importing `Something` does not compile.

    The third member of the same family as the two checks above (a private companion, a folder
    constant): mechanical, invisible locally -- only CI compiles -- and it has already cost a
    build once (`Labels` was written in `ui/`, used from `MainActivity` in `dev.atp.pet`, and
    nothing local noticed).

    Deliberately narrow: only top-level `object`s declared in this source tree, only a bare
    `Name.` that is not preceded by a dot (so `Foo.Bar.baz` is not mistaken for `Bar.baz`), and
    only when the file has no `import` line ending in that name. It is not a type checker.
    """
    owners = {}
    for path in kotlin_files():
        text = open(path, encoding="utf-8").read()
        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        for name in re.findall(r"^object\s+(\w+)", text, re.M):
            owners[name] = (pkg.group(1) if pkg else "", os.path.basename(path))

    bad = []
    for path in kotlin_files():
        text = open(path, encoding="utf-8").read()
        pkg_m = re.search(r"^package\s+([\w.]+)", text, re.M)
        pkg = pkg_m.group(1) if pkg_m else ""
        imported = set(re.findall(r"^import\s+[\w.]*\b(\w+)\s*$", text, re.M))
        imported |= set(re.findall(r"^import\s+[\w.]*\b(\w+)", text, re.M))
        for name, (home, where) in owners.items():
            if home == pkg or name in imported:
                continue
            for i, line in enumerate(text.split("\n"), 1):
                code = line.split("//")[0]
                # KDoc/块注释里的 `Mirrors Liquids.of` 不是代码（ParticleSpec 就有一句）。
                if code.lstrip().startswith(("*", "/*")):
                    continue
                if re.search(r"(?<![\w.])" + name + r"\s*\.", code) and \
                        not re.match(r"\s*(object|val|var|fun|class)\s+" + name, code):
                    bad.append("%s:%d 用了 %s.%s 但没有 import %s"
                               % (os.path.basename(path), i, name, "", name))
                    break
    report("跨包用到的 object 都 import 了", not bad, "; ".join(bad[:4]))


def check_chip_lists():
    """
    paintChips takes strings, paintSwatches takes colours, and both take a list.

    The mistake this exists for is one line long and cost a CI round trip: a boolean switch was
    painted with

        paintChips(listOf(collideChip), listOf(true), { collides })

    which does not compile, because the second argument is a List<Boolean> where a List<String>
    belongs. A switch is not a chip among equals and wants its own two lines; the checker's job
    is only to notice the shape, so it looks at literal listOf(...) arguments and nothing else.
    A list built by .map { it.id } is left alone -- guessing about those would make this a
    checker that cries wolf.
    """
    bad = []
    for path in kotlin_files():
        for i, line in enumerate(open(path, encoding="utf-8"), 1):
            code = line.split("//")[0]
            for m in CHIP_CALL.finditer(code):
                kind, items = m.group(1), m.group(2).strip()
                if not items:
                    continue
                first = items.split(",")[0].strip()
                # Only the unambiguous literals are judged: a boolean or a number where the
                # chip painter wants a String id (or a quoted string where it wants a colour).
                # An identifier like Joins.AND is left alone, because resolving what it holds
                # is a compiler's job and a checker that guesses is a checker nobody reads.
                literal_bool_or_number = bool(re.match(r"^(true|false|-?[0-9]|0x)", first))
                quoted = first.startswith('"')
                if kind == "Chips" and literal_bool_or_number:
                    bad.append("%s:%d  paintChips with %s" % (os.path.basename(path), i, first))
                if kind == "Swatches" and quoted:
                    bad.append("%s:%d  paintSwatches with %s" % (os.path.basename(path), i, first))
    report("the chip painters get the kind of list they take", not bad, "; ".join(bad[:4]))


#: The one file allowed to call the strict parse: the one it lives in. Everyone else has a
#: screen to keep alive and goes through parseOrNull.
STRICT_PARSE = re.compile(r"CharacterSpec\.parse\s*\(")


def check_strict_parse():
    """
    Only CharacterSpec.kt may call the throwing parse.

    A character file can be half-written by a full disk or truncated by a crash, and this
    parse is strict on purpose -- it is the one place that decides what a valid character IS.
    Every screen that reads a character has to go through parseOrNull and decide what to show
    instead. The alternative shipped: a truncated character.json meant an app that closed on
    startup, on every startup, with no way back in short of clearing its data.
    """
    bad = []
    for path in kotlin_files():
        if os.path.basename(path) == "CharacterSpec.kt":
            continue
        for i, line in enumerate(open(path, encoding="utf-8"), 1):
            if STRICT_PARSE.search(line.split("//")[0]):
                bad.append("%s:%d  %s" % (os.path.basename(path), i, line.strip()[:80]))
    report("only CharacterSpec.kt calls the throwing parse()", not bad, "\n         ".join(bad))


#: A reference to a member of somebody else's class: ClassName.MEMBER.
FOREIGN_MEMBER = re.compile(r"\b([A-Z]\w*)\.([A-Z][A-Z0-9_]{2,})\b")


def check_private_companions():
    """
    A constant in a private companion object cannot be reached as ClassName.MEMBER.

    This is the one class of mistake the Python mirrors can never see and the local checks
    could not either: it does not compile, and only CI compiles. It cost a build --
    PartLibrary.load reached for CharacterStore.VARIANT_SEPARATOR, which sits in a private
    companion, and finding that out costs five minutes and a red release.

    Deliberately narrow: it knows about private COMPANIONS and about SCREAMING_CASE members,
    because that is the shape the mistake had. It is not a type checker and does not pretend
    to be one.
    """
    owners = {}
    for path in kotlin_files():
        text = open(path, encoding="utf-8").read()
        for m in re.finditer(r"private companion object\s*\{([^}]*)\}", text, re.S):
            for name in re.findall(r"\b(?:const )?val (\w+)", m.group(1)):
                owners[name] = os.path.basename(path)

    bad = []
    for path in kotlin_files():
        mine = os.path.basename(path)
        for i, line in enumerate(open(path, encoding="utf-8"), 1):
            code = line.split("//")[0]
            for klass, member in FOREIGN_MEMBER.findall(code):
                if member in owners and owners[member] != mine:
                    bad.append("%s:%d  %s.%s is private to %s"
                               % (mine, i, klass, member, owners[member]))
    report("no private companion member is reached from another file",
           not bad, "; ".join(bad[:4]))


#: Things the checker must and must not complain about. A checker that never fails is a
#: checker nobody should trust, and the case it exists for is one line long.
SELF_TEST = [
    ('val air = phys?.optDouble("roomAir")?.toFloat() ?: derived()', True),
    ('val z = o.optInt("z") ?: 0', True),
    ('val air = phys?.optDouble("roomAir", 0.0).toFloat()', False),
    ('if (phys.has("roomAir")) num(phys, "roomAir", 0f) else derived()', False),
    ('// optDouble("roomAir") ?: 0 in a comment is not code', False),
]

#: And the same for the parse rule, which has exactly one thing to get right.
PARSE_SELF_TEST = [
    ("val parsed = CharacterSpec.parse(folder.specText())", True),
    ("val parsed = CharacterSpec.parseOrNull(folder.specText())", False),
    ("// CharacterSpec.parse(folder.specText()) in a comment", False),
]


def self_test():
    ok = True
    for line, expected in SELF_TEST:
        code = line.split("//")[0]
        got = bool(OPT_ONE_ARG.search(code))
        ok &= report("%-58s %s" % (line[:58], "flagged" if expected else "allowed"),
                     got == expected)
    for line, expected in PARSE_SELF_TEST:
        code = line.split("//")[0]
        got = bool(STRICT_PARSE.search(code))
        ok &= report("%-58s %s" % (line[:58], "flagged" if expected else "allowed"),
                     got == expected)
    return ok


def check_view_resources():
    """
    A View is not a Context: a bare `getString(...)` inside one does not resolve.

    This is here because it SHIPPED. The bench reporting why a tap was thrown away was written
    as `getString(R.string.sandbox_tap_too_slow, ...)` -- right in an Activity, wrong in a
    View -- and the only thing that could tell us was a three-minute CI round trip ending in
    `e: Unresolved reference: getString`. Every other resource read in these files already
    goes through `context.`; this check makes the next one local.

    The View files are found by the supertype in their constructor, and a file that declares
    its own getString() is left alone.
    """
    bad = []
    for path in kotlin_files():
        text = open(path, encoding="utf-8").read()
        if not re.search(r"\)\s*:\s*\w*View\w*\(", text):
            continue
        if re.search(r"fun\s+getString\s*\(", text):
            continue
        for i, line in enumerate(text.split("\n"), 1):
            code = line.split("//")[0]
            for name in ("getString", "getResources", "getColor", "getDrawable",
                         "getDimension", "getText"):
                if re.search(r"(?<![\w.])%s\s*\(" % name, code):
                    bad.append("%s:%d  %s" % (os.path.basename(path), i, line.strip()[:80]))
                    break
    report("a View reads resources through context.*", not bad, "\n         ".join(bad))


def check_one_home_for_a_file_name():
    """
    A file the app writes and reads has to be named in exactly ONE place.

    The particle shape is written by the board and read by the bench. Two spellings of
    "shape.png" would not fail to compile, would not fail at run time either -- the write would
    quietly land somewhere the read does not look, which looks exactly like a drawing that
    saved and did nothing. It is the same failure CharacterStore keeps its file names in one
    companion for, so this checks the habit rather than the one file.
    """
    bad = []
    for name in ("shape.png", "logic.json", "character.json"):
        homes = []
        for path in kotlin_files():
            for i, line in enumerate(open(path, encoding="utf-8").read().split("\n"), 1):
                code = line.split("//")[0]
                if '"%s"' % name in code:
                    homes.append("%s:%d" % (os.path.basename(path), i))
        if len(homes) > 1:
            bad.append("%s is named in %d places: %s" % (name, len(homes), ", ".join(homes)))
        elif not homes:
            bad.append("%s is named nowhere" % name)
    report("every file name the app writes has one home", not bad, "; ".join(bad))


def check_dialog_bodies_scroll():
    """
    A dialog body longer than the screen has to scroll, or its last rows do not exist.

    AlertDialog does NOT scroll its own view. Every dialog in this app is a column of rows, and
    several of them are longer than a phone: the bone list has nineteen bones before it reaches
    the node section, the prop editor has the trail under five other rows, and pickList shows
    twenty-odd bones and nodes. Two of those buttons were reported as MISSING by the person
    using the app -- they were built, laid out, and sitting below the bottom edge of the screen
    with no way to reach them, which from the outside is a feature that was never written.

    The rule is mechanical, so it is checked mechanically: a dialog body goes through
    scrolling(...), or it is a one-line input that cannot grow.
    """
    bad = []
    for path in kotlin_files():
        text = open(path, encoding="utf-8").read()
        if ".setView(" not in text:
            continue
        for i, line in enumerate(text.split("\n"), 1):
            code = line.split("//")[0]
            m = re.search(r"\.setView\(([^)]*)\)", code)
            if not m:
                continue
            what = m.group(1).strip()
            if what.startswith("scrolling(") or what in ("input", "board", "view"):
                continue
            bad.append("%s:%d  .setView(%s)" % (os.path.basename(path), i, what))
    report("every dialog body scrolls (or is a single line)", not bad,
           "\n         ".join(bad))


def main():
    if "--self-test" in sys.argv:
        print("the checker's own cases")
        if not self_test():
            return 1
        print("")
        print("self-test passed")
        return 0
    print("Kotlin sources: %d files" % len(list(kotlin_files())))
    check_opt_defaults()
    check_enums()
    check_balance()
    check_references()
    check_strict_parse()
    check_private_companions()
    check_folder_constants_qualified()
    check_object_imports()
    check_upper_case_names()
    check_local_function_scope()
    check_enum_when_exhaustive()
    report_hardcoded_text()
    check_chip_lists()
    check_view_resources()
    check_one_home_for_a_file_name()
    check_dialog_bodies_scroll()
    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        for f in FAILURES:
            print("  " + f.split("\n")[0])
        return 1
    print("all kotlin checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
