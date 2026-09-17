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

#: 两份实现都必须有的**机制** —— 不是一个数，而是一段做法。
#:
#: 这是这个检查原来最大的漏洞，而且它不是假想的：v1.5.0 就是这么发出去的。抖动在
#: tools/ragdoll.py 里诊断、修好、量过（交替比 1.16 → 0.71）、写了提交、发了 Release，
#: 而手机里跑的 app/.../physics/Ragdoll.kt **一行没动**。两份文件的常数本来就一模一样，
#: 所以这个检查全程绿灯，还给了一个"两份是一致的"的假象。
#:
#: 它自己的文档里预言过这个失败模式（"a behaviour that no longer matches its own test
#: suite"），只是它没有能力发现 —— 常数对得上，做法对不上。
#:
#: 每一条给两份源码各写一个记号。记号在一边找得到、另一边找不到，就是那一边没这个机制。
#: 记号是**故意写得又笨又具体**的：它要能扛住无关的重构，又不能在机制被删掉之后还留着。
PAIRED_MECHANISMS = [
    (
        "pin 的转角预算是每帧一份（不是每次迭代各拿一份）",
        r"MAX_IK_RATE \* dt - \(ikSpent\[b\.name\]",
        r"MAX_IK_RATE \* dt - self\.ik_spent\.get\(b\.name",
    ),
    (
        "地面一遍：同一根骨头每帧只转一次",
        r"val turned = HashSet<String>\(\)",
        r"^\s+turned = set\(\)",
    ),
    (
        "地面修正没让渗透变浅就回滚，交给根节点",
        r"colliderLow\(bone\) - floor > pen - 0\.05f",
        r"collider_low\(bone\) - self\.floor > pen - 0\.05",
    ),
    (
        "每帧开始处重置那份预算",
        r"ikSpent\.clear\(\)",
        r"self\.ik_spent = \{\}",
    ),
    (
        "右墙是 App 的房间宽度 worldWidth，不是画布宽度",
        r"wallRight = spec\.worldWidth",
        r"wall_right = float\(physics\.get\(\"worldWidth\"",
    ),
    (
        "pin 的转角就是这根关节下一帧的速度（不是加在原来的速度上）",
        r"anglePrev\[b\.name\] = turned - got",
        r"self\.ang_prev\[b\.name\] = turned - got",
    ),
]


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)


def kotlin_constants():
    text = open(KT, encoding="utf-8").read()
    # The f suffix is optional: an Int constant in Kotlin does not have one.
    #
    # `var` counts as well as `const val`, and PIN_JOINT_GAIN is why: the drag's two rate
    # numbers are moved while the app runs (the bench's tuning panel), and a `const val`
    # cannot be moved. What is read here is the DECLARED value, which is also the value the
    # app starts on, so the declaration is still the right thing to compare -- but a pattern
    # that only knew `const val` would have dropped both of them out of the comparison in
    # silence, and silence is this check's whole failure mode: two implementations that both
    # still run and no longer agree.
    return dict((m.group(1), float(m.group(2)))
                for m in re.finditer(
                    r"(?:const val|var) ([A-Z_]+)\s*=\s*([0-9.]+(?:[eE]-?[0-9]+)?)f?", text))


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
    print("同一套做法，两份实现里都得有")
    kt_text = open(KT, encoding="utf-8").read()
    py_text = open(PY, encoding="utf-8").read()
    for label, kt_pat, py_pat in PAIRED_MECHANISMS:
        in_kt = re.search(kt_pat, kt_text, re.M) is not None
        in_py = re.search(py_pat, py_text, re.M) is not None
        if in_kt and in_py:
            report(label, True)
        elif in_kt:
            report(label, False, "Kotlin 有、Python 没有 —— 参考实现漏了这个机制")
        elif in_py:
            # 这就是 v1.5.0 那一版：修在了参考实现里，App 里没有。
            report(label, False, "Python 有、Kotlin 没有 —— App 里没有这个机制（就是这一条漏掉过一次）")
        else:
            report(label, False, "两边都没有 —— 机制被删掉了，或者记号过期了，去看一眼")

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
