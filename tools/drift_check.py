#!/usr/bin/env python3
"""Do the two implementations READ the same things?

tools/mirror_check.py compares the constants the two files share. That is not enough, and
this session proved it three times over:

  * PIN_JOINT_GAIN existed in tools/ragdoll.py and NOT in Ragdoll.kt. Every measurement
    taken to tune it described a knob the app does not have. mirror_check listed it under
    "only in ragdoll.py" -- a reading list, not a failure -- and that list went unread.
  * `giving` was `not self.pinned` in the Kotlin and had drifted to `not self.hanging()`
    in the Python. A CONDITION, and mirror_check cannot see a condition: it compares
    numbers.
  * the right wall was `spec.worldWidth` (3072) in the Kotlin and `canvas["width"]` (1024)
    in the Python. Same physical quantity, read from two different places -- so the
    reference had the pet walking into a wall the app does not have, and every horizontal
    drag measured through it was measured against that wall.

The third one is what this file is for, and it is the one that is machine-checkable: **the
two implementations must read the same KEYS.** Same number of constants, same names,
and then quietly `canvas.width` against `worldWidth`.

    python3 tools/drift_check.py

A key read by only one side FAILS unless it is listed in ALIASES below with a reason.
Legitimately one-sided things are named there; anything else is drift until somebody says
otherwise in writing.
"""
import copy
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
KT = os.path.join(REPO, "app/src/main/java/dev/atp/pet/engine/physics/Ragdoll.kt")
PY = os.path.join(HERE, "ragdoll.py")
CHARACTER = os.path.join(REPO, "app/src/main/assets/characters/female_base/character.json")
sys.path.insert(0, HERE)
from ragdoll import Ragdoll  # noqa: E402
from skeleton_tool import bake  # noqa: E402

#: Keys one side reads and the other does not, with the reason. Every entry is a claim that
#: the two are the same thing under different names, or that one side genuinely has no use
#: for it. Add here only with a sentence explaining which.
ALIASES = {
    # name in Kotlin -> name in Python (or None when Python has no equivalent)
    "standOffset": "roomAir",
    "roomAir": "standOffset",
    # The Kotlin reads the room's own width; the Python reached for the canvas width, which
    # is the drift this file exists because of. They are the same quantity, and after the
    # fix both read the room.
    "worldWidth": "worldWidth",
    "canvas": "worldWidth",
}

FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)


def keys_read(path, style):
    text = open(path, encoding="utf-8").read()
    if style == "kt":
        found = set(re.findall(r"\bspec\.(\w+)", text))
        found |= set(re.findall(r'physics\.get\("(\w+)"', text))
    else:
        found = set(re.findall(r'spec\["(\w+)"\]', text))
        found |= set(re.findall(r'physics\.get\("(\w+)"', text))
        found |= set(re.findall(r'physics\["(\w+)"\]', text))
    return found


def main():
    kt, py = keys_read(KT, "kt"), keys_read(PY, "py")
    print("%d keys read by the Kotlin, %d by the reference" % (len(kt), len(py)))

    shared = sorted(kt & py)
    report("they read some of the same things", len(shared) >= 4, ", ".join(shared))

    unresolved = []
    for key in sorted(kt - py):
        if ALIASES.get(key) not in py:
            unresolved.append("Kotlin reads %s, the reference does not" % key)
    for key in sorted(py - kt):
        if ALIASES.get(key) not in kt:
            unresolved.append("the reference reads %s, the Kotlin does not" % key)

    report("nothing is read by only one side without a written reason",
           not unresolved, "; ".join(unresolved))

    # And the specific one that cost this session the most: whatever the wall is called, the
    # two must agree about how wide the room is.
    print("")
    print("the room is the same size on both sides")
    kt_src = open(KT, encoding="utf-8").read()
    py_src = open(PY, encoding="utf-8").read()
    report("the Kotlin walls the figure at the room's width",
           re.search(r"wallRight\s*=\s*spec\.worldWidth", kt_src) is not None)
    report("and so does the reference",
           re.search(r'wall_right\s*=\s*float\(physics\.get\("worldWidth"', py_src) is not None,
           "canvas width is not the room width: the app has no wall there")

    # AND THE VALUE, because the two reports above are text patterns and the line that was
    # wrong matched them anyway. Verified by putting the old line back:
    #
    #     self.wall_right = float(physics.get("worldWidth", spec["canvas"]["width"]))
    #
    # -- the fallback that silently returns the artwork's 1024 the moment a spec stops stating
    # its worldWidth -- and running this file: still GREEN. The alias above is satisfied (the
    # reference does read "canvas", and that is a name for the room's width) and the pattern
    # still matches, so the one path that actually matters is the one nothing here reads.
    #
    # It matters because the shipped file DOES state its width, so the fallback never runs in
    # the suite: a reference that quietly walls the pet at the artwork's edge during the one
    # drag a character package forgets to size would pass every test in this repo. Read the
    # number instead, with the key and without it.
    spec = json.load(open(sys.argv[1] if len(sys.argv) > 1 else CHARACTER, encoding="utf-8"))
    app_width = float(spec["physics"].get("worldWidth",
                                          float(spec["canvas"]["width"]) * 3.0))
    silent = copy.deepcopy(spec)
    silent["physics"].pop("worldWidth", None)
    walls = []
    for label, source in (("the file says", spec), ("the file is silent", silent)):
        s = copy.deepcopy(source)
        by_name, order = bake(s["bones"])
        walls.append((label, float(Ragdoll(s, by_name, order, stiffness=0.0).wall_right)))
    report("the wall the reference builds is the app's room either way",
           all(abs(w - app_width) < 1e-9 for _, w in walls),
           "; ".join("%s %.0f px" % (label, w) for label, w in walls)
           + " (app %.0f, artwork %.0f)" % (app_width, spec["canvas"]["width"]))

    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        for f in FAILURES:
            print("  " + f)
        return 1
    print("the two implementations read the same things")
    return 0


if __name__ == "__main__":
    sys.exit(main())
