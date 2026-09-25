
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

#: 背景图那两档的边界。0.85 之上等于把图藏起来了，不如直接去掉它。
MIN_DIM = 0.0
MAX_DIM = 0.85


def safe_background_name(raw):
    """Mirror of Settings.safeBackgroundName —— 只收一个纯文件名。

    这是这一版唯一一条**安全**规矩：settings.json 是能手改的，而这个名字会被拼成一条路径。
    一个手改出来的 "../../../databases/xxx" 会让"背景图"变成"删掉你手机里任意一个文件"。
    读的时候和写的时候走的是同一个函数（Kotlin 那边也是），所以这里测的就是那条规矩本身。
    """
    name = (raw or "").strip()
    if not name or len(name) > 96:
        return ""
    if "/" in name or "\\" in name or ".." in name:
        return ""
    return name

DEFAULTS = {
    "gravityScale": 1.0,
    "defaultStiffness": 0.0,
    "showGrid": True,
    "showGround": True,
    "showBalance": True,
    "showBones": False,
    "showNodes": True,
    "particles": True,
    "liquid": True,
    "followPet": True,
    "background": "",
    "backgroundDim": 0.35,
    "disclaimerAccepted": 0,
}

#: 免责声明那一版的号。改了文案就加一 —— 启动时那道门比的是 `accepted < 这个数`。
DISCLAIMER_VERSION = 1


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
    for key in ("showGrid", "showGround", "showBalance", "showBones", "showNodes",
                "particles", "liquid",
                "followPet"):
        if key in o:
            out[key] = bool(o[key])
    out["background"] = safe_background_name(str(o.get("background", "")))
    out["backgroundDim"] = clamp(num("backgroundDim", 0.35), MIN_DIM, MAX_DIM)
    # 手改出来的负数没有意义；999 也不该等于"同意了未来的某一版"，所以夹在 0..当前版本。
    out["disclaimerAccepted"] = max(0, min(DISCLAIMER_VERSION, int(num("disclaimerAccepted", 0))))
    return out


def to_json(s):
    # 写出去的时候名字也过一遍安检：内存里那份可能是从别处来的（导入、旧版本），
    # 而"写进去的必须是安全的"不该只在读的那一头。
    out = dict(s)
    out["background"] = safe_background_name(str(s.get("background", "")))
    return json.dumps(out, indent=2)


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
    for key in ("showGrid", "showGround", "showBalance", "showBones", "showNodes",
                "particles", "liquid",
                "followPet"):
        on = parse(json.dumps({key: True}))[key]
        off = parse(json.dumps({key: False}))[key]
        report("%-13s true/false both survive" % key, on is True and off is False,
               "%s / %s" % (on, off))

    print("\n应用背景图（主题）")
    # 名字是这一版唯一一条安全规矩：它会被拼成一条路径，而 settings.json 能手改。
    report("正常文件名收下", safe_background_name("bg-1699999999999.png") == "bg-1699999999999.png")
    report("带路径的名字一律拒掉（这是那条安全规矩）",
           safe_background_name("../../databases/app.db") == ""
           and safe_background_name("theme/bg.png") == ""
           and safe_background_name("..\\..\\windows.png") == "",
           "斜杠、反斜杠、上级目录都不收")
    report("空名字就是空（没有背景图）", safe_background_name("") == "" and safe_background_name("   ") == "")
    report("太长的名字也拒掉（不到 96 个字符以内）", safe_background_name("x" * 200) == "")
    report("缺键 → 没有背景图、默认压暗 0.35",
           parse("{}")["background"] == "" and abs(parse("{}")["backgroundDim"] - 0.35) < 1e-9)
    report("压暗越界被夹住",
           parse('{"backgroundDim": 9}')["backgroundDim"] == MAX_DIM
           and parse('{"backgroundDim": -4}')["backgroundDim"] == MIN_DIM)
    report("压暗是 NaN 也不崩（按最低算）",
           parse('{"backgroundDim": "heavy"}')["backgroundDim"] == 0.35
           or parse('{"backgroundDim": "heavy"}')["backgroundDim"] == MIN_DIM)
    # 手改出来的危险名字：读进来就是空，而不是被当成路径。
    report("文件里手改出来的危险名字读成「没有背景图」",
           parse('{"background": "../../../databases/x"}')["background"] == "")
    report("写出去的时候也过安检（内存里那份可能是别处来的）",
           json.loads(to_json({"background": "/etc/passwd"}))["background"] == "")
    report("来回一趟不丢这个键",
           json.loads(to_json(parse('{"background": "bg-1.png", "backgroundDim": 0.5}')))
           ["background"] == "bg-1.png")

    print("\n免责声明那道门")
    # 门比的是**版本号**，不是一个 true/false：改了文案就该再问一次。
    report("缺键 = 还没同意过（第一次打开会弹）", parse("{}")["disclaimerAccepted"] == 0)
    report("同意过当前这一版就不再问",
           parse('{"disclaimerAccepted": %d}' % DISCLAIMER_VERSION)["disclaimerAccepted"]
           >= DISCLAIMER_VERSION)
    report("签过旧版不算同意这一版",
           parse('{"disclaimerAccepted": 0}')["disclaimerAccepted"] < DISCLAIMER_VERSION)
    report("手改成 999 不等于同意了未来的版本",
           parse('{"disclaimerAccepted": 999}')["disclaimerAccepted"] == DISCLAIMER_VERSION)
    report("负数被夹回 0（没同意过）", parse('{"disclaimerAccepted": -5}')["disclaimerAccepted"] == 0)
    report("来回一趟不丢这个键",
           json.loads(to_json(parse('{"disclaimerAccepted": 1}')))["disclaimerAccepted"] == 1)

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
