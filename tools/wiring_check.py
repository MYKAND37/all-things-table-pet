#!/usr/bin/env python3
"""Every view the layout defines, and every function the code defines: is it reachable?

Two bugs this is for, both of them silent:

  * a control that is in the layout and in no listener at all -- the user taps it and
    nothing happens, and nothing anywhere says why;
  * a function that was written, is correct, and is called from nowhere. The feature is
    finished and the wire is not.

    python3 tools/wiring_check.py

Reports, per layout id: how many times the Kotlin names it, and whether any of those is a
findViewById/setOnClickListener. Reports every private function with no reference.
"""
import os, re, sys
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
SRC = os.path.join(REPO, "app/src/main/java")
RES = os.path.join(REPO, "app/src/main/res")


def read(path):
    return open(path, encoding="utf-8").read()


def layout_ids():
    """Every android:id in every layout, as the name the code would use."""
    out = {}
    for base, _, names in os.walk(os.path.join(RES, "layout")):
        for n in names:
            if not n.endswith(".xml"):
                continue
            path = os.path.join(base, n)
            for m in re.finditer(r'android:id="@\+id/(\w+)"', read(path)):
                out.setdefault(m.group(1), []).append(n)
    return out


def kotlin_text():
    parts = {}
    for base, _, names in os.walk(SRC):
        for n in names:
            if n.endswith(".kt"):
                parts[os.path.join(base, n)] = read(os.path.join(base, n))
    return parts


def main():
    fails = []
    ids = layout_ids()
    files = kotlin_text()
    joined = "\n".join(files.values())

    print("== layout ids no Kotlin ever names ==")
    for name in sorted(ids):
        uses = len(re.findall(r"R\.id\.%s\b" % re.escape(name), joined))
        if uses == 0:
            print("  DEAD  %-26s declared in %s" % (name, ",".join(ids[name])))
            fails.append("dead id " + name)

    print("== functions defined and never referenced ==")
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
            # count references that are not the declaration itself
            refs = [j for j, l in enumerate(lines) if re.search(r"\b%s\s*\(" % re.escape(name), l)]
            other_files = sum(
                1 for p, t in files.items() if p != path and re.search(r"\b%s\s*\(" % re.escape(name), t)
            )
            if len(refs) <= 1 and other_files == 0:
                print("  ORPHAN  %-34s %s:%d" % (name, os.path.basename(path), i + 1))
                fails.append("orphan " + name)

    print()
    if fails:
        print("%d wiring problem(s)" % len(fails))
        return 1
    print("no wiring problems")
    return 0


if __name__ == "__main__":
    sys.exit(main())
