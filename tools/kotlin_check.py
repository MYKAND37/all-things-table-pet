
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


#: Things the checker must and must not complain about. A checker that never fails is a
#: checker nobody should trust, and the case it exists for is one line long.
SELF_TEST = [
    ('val air = phys?.optDouble("roomAir")?.toFloat() ?: derived()', True),
    ('val z = o.optInt("z") ?: 0', True),
    ('val air = phys?.optDouble("roomAir", 0.0).toFloat()', False),
    ('if (phys.has("roomAir")) num(phys, "roomAir", 0f) else derived()', False),
    ('// optDouble("roomAir") ?: 0 in a comment is not code', False),
]


def self_test():
    ok = True
    for line, expected in SELF_TEST:
        code = line.split("//")[0]
        got = bool(OPT_ONE_ARG.search(code))
        ok &= report("%-58s %s" % (line[:58], "flagged" if expected else "allowed"),
                     got == expected)
    return ok


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
    check_balance()
    check_references()
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
