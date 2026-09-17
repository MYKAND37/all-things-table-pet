
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
    check_chip_lists()
    check_view_resources()
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
