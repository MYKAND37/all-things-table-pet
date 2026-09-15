
"""The app's own settings: a tiny file that must never be able to stop it starting.

Mirrors dev.atp.pet.data.Settings. What is worth testing is not the reading, it is the
TOLERANCE: a key that is missing, a value that is nonsense, a file that is not ours at all.
A settings file is the one file in an app most likely to be hand-edited and the last one
that should be able to brick it.

    python3 tools/settings_check.py
"""
import json, os, sys

FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)
    return ok


# ------------------------------- mirror of Settings.kt -------------------------------

MIN_GRAVITY = 0.2
MAX_GRAVITY = 3.0

DEFAULTS = {
    "gravityScale": 1.0,
    "defaultStiffness": 0.0,
    "showGrid": True,
    "showGround": True,
    "showBalance": True,
    "showBones": False,
    "particles": True,
    "liquid": True,
    "followPet": True,
}


def clamp(v, lo, hi):
    if v != v:                      # NaN
        return lo
    return max(lo, min(hi, v))


def parse(text):
    try:
        o = json.loads(text)
        if not isinstance(o, dict):
            return dict(DEFAULTS)
    except Exception:
        return dict(DEFAULTS)
    out = dict(DEFAULTS)

    def num(key, fallback):
        """One key being nonsense costs that key, not the file. See Settings.from."""
        try:
            return float(o.get(key, fallback))
        except (TypeError, ValueError):
            return fallback

    out["gravityScale"] = clamp(num("gravityScale", 1.0), MIN_GRAVITY, MAX_GRAVITY)
    out["defaultStiffness"] = clamp(num("defaultStiffness", 0.0), 0.0, 1.0)
    for key in ("showGrid", "showGround", "showBalance", "showBones", "particles", "liquid",
                "followPet"):
        if key in o:
            out[key] = bool(o[key])
    return out


def to_json(s):
    return json.dumps(s, indent=2)


def main():
    print("a file that says nothing gets the defaults")
    d = parse("{}")
    report("every key is there", set(d) == set(DEFAULTS), str(sorted(d)))
    report("and they are the defaults", d == DEFAULTS)

    print("\na file that is not a settings file at all does not break anything")
    for junk in ("", "not json at all", "[1, 2, 3]", "null", "{ \"a\": [ }"):
        got = parse(junk)
        report("%-18s -> defaults" % (repr(junk)[:18]), got == DEFAULTS)

    print("\nnonsense values are clamped, not obeyed")
    report("gravity below the floor", parse('{"gravityScale": 0}')["gravityScale"] == MIN_GRAVITY,
           str(parse('{"gravityScale": 0}')["gravityScale"]))
    report("gravity above the ceiling",
           parse('{"gravityScale": 99}')["gravityScale"] == MAX_GRAVITY)
    report("negative gravity is not anti-gravity",
           parse('{"gravityScale": -5}')["gravityScale"] == MIN_GRAVITY)
    report("stiffness above one", parse('{"defaultStiffness": 7}')["defaultStiffness"] == 1.0)
    report("stiffness below zero", parse('{"defaultStiffness": -2}')["defaultStiffness"] == 0.0)
    report("a value that is a string is still read",
           parse('{"gravityScale": "1.5"}')["gravityScale"] == 1.5)
    report("a value that is nonsense text is the default",
           parse('{"gravityScale": "heavy"}')["gravityScale"] == 1.0,
           str(parse('{"gravityScale": "heavy"}')["gravityScale"]))
    # And only THAT key: a typo on one line must not throw away the rest of the file.
    typo = parse('{"gravityScale": "heavy", "defaultStiffness": 0.9, "particles": false}')
    report("and the other keys survive the typo",
           typo["defaultStiffness"] == 0.9 and typo["particles"] is False,
           "stiffness %.2f particles %s" % (typo["defaultStiffness"], typo["particles"]))

    print("\nunknown keys are kept out of the way")
    d = parse('{"somethingElse": true, "gravityScale": 2}')
    report("what we know is read", d["gravityScale"] == 2.0)
    report("what we do not know is ignored", "somethingElse" not in d)

    print("\nthe round trip")
    s = dict(DEFAULTS)
    s["gravityScale"] = 2.5
    s["defaultStiffness"] = 0.75
    s["showGrid"] = False
    s["liquid"] = False
    again = parse(to_json(s))
    report("what was written is what comes back", again == s, str(again))

    print("\nthe switches the bench reads are all booleans")
    # A toggle that reads as "not False" instead of "is True" is a switch that cannot be
    # turned off, which is the classic version of this bug.
    for key in ("showGrid", "showGround", "showBalance", "showBones", "particles", "liquid",
                "followPet"):
        on = parse(json.dumps({key: True}))[key]
        off = parse(json.dumps({key: False}))[key]
        report("%-13s true/false both survive" % key, on is True and off is False,
               "%s / %s" % (on, off))

    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        for f in FAILURES:
            print("  " + f)
        return 1
    print("all settings tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
