#!/usr/bin/env python3
"""Do the Kotlin and the Python say the same numbers?

tools/ragdoll.py is the reference implementation the physics is written against, and the two
are edited by hand. A constant that drifts between them is invisible in both: the Python tests
keep passing, because they test the Python, and the app keeps running, because it runs the
Kotlin. The only symptom is a behaviour that no longer matches its own test suite.

    python3 tools/mirror_check.py

Compares every constant the two files share, and lists the ones only one of them has -- those
need a reason, not a value, so they are printed rather than failed.
"""
import os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
KT = os.path.join(REPO, "app/src/main/java/dev/atp/pet/engine/physics/Ragdoll.kt")
PY = os.path.join(HERE, "ragdoll.py")

FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)


def kotlin_constants():
    text = open(KT, encoding="utf-8").read()
    # The f suffix is optional: an Int constant in Kotlin does not have one.
    return dict((m.group(1), float(m.group(2)))
                for m in re.finditer(r"const val ([A-Z_]+)\s*=\s*([0-9.]+(?:[eE]-?[0-9]+)?)f?", text))


def python_constants():
    text = open(PY, encoding="utf-8").read()
    return dict((m.group(1), float(m.group(2)))
                for m in re.finditer(r"^([A-Z_]{3,})\s*=\s*([0-9.]+(?:e-?[0-9]+)?)", text, re.M))


def main():
    kt, py = kotlin_constants(), python_constants()
    shared = sorted(set(kt) & set(py))
    print("%d constants in both files" % len(shared))
    drifted = []
    for name in shared:
        if abs(kt[name] - py[name]) > 1e-9:
            drifted.append("%s: Kotlin %s, Python %s" % (name, kt[name], py[name]))
    report("every shared constant is the same number", not drifted, "; ".join(drifted))
    report("there are enough of them to be worth checking", len(shared) >= 12,
           "%d shared" % len(shared))

    print("")
    print("only in Ragdoll.kt (Kotlin-only by design, or a mirror that is missing):")
    for name in sorted(set(kt) - set(py)):
        print("   ·   %-18s %s" % (name, kt[name]))
    print("only in ragdoll.py (the reference, or a constant the app does not have yet):")
    for name in sorted(set(py) - set(kt)):
        print("   ·   %-18s %s" % (name, py[name]))

    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        for f in FAILURES:
            print("  " + f)
        return 1
    print("the two implementations agree")
    return 0


if __name__ == "__main__":
    sys.exit(main())
