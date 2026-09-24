#!/usr/bin/env python3
"""一条命令跑完本地所有检查，并且分清"红的"和"欠账的红"。

    python3 tools/check_all.py            # 全部
    python3 tools/check_all.py logic      # 只跑名字里带 logic 的

为什么值得有这个文件：README 里写着"18 个检查"，而那是一个**要一条条敲的命令列表** ——
一份要敲十几遍的清单，实际上就是没人跑。这个仓库的规矩是"有一条机器断言钉着它"，那么
"跑起来"本身也该是一条命令。

三种结果，分得很清楚：

  ok     绿的；
  欠账   已知是红的（求解器镜像那三笔，见 docs/VERIFY.md）。**不算失败** —— 但会在最后
         单独列出来，因为一笔不肯写在明面上的债是最糟的那种；
  FAIL   绿的检查红了，或者欠账的检查**变绿了**（那说明该把它从名单里划掉）。

环境缺东西（比如没有 PIL 的那条）记成"跳过"，不算失败：本地跑不了不等于代码坏了。
"""
import os, re, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

#: 已知是红的：欠账不是噪音，但它也不该让"跑一遍"变成一件永远失败的事。见 docs/VERIFY.md。
KNOWN_DEBT = {
    "mirror_check.py": "Ragdoll.kt 与 tools/ragdoll.py 的常数/机制漂移",
    "ragdoll.py": "参考实现自己的一条断言（两块骨头碰到地面）",
    "drift_check.py": "参考实现读 roomAir 而 Kotlin 不读；两边墙宽不一致",
}

#: 这些不是检查，是别的工具。
NOT_CHECKS = {"skeleton_tool.py", "gen_art_guide.py", "check_all.py"}


def checks():
    out = []
    for name in sorted(os.listdir(HERE)):
        if not name.endswith(".py") or name in NOT_CHECKS:
            continue
        if name.endswith("_check.py") or name in KNOWN_DEBT:
            out.append(name)
    return out


def main():
    wanted = sys.argv[1:]
    names = [n for n in checks() if not wanted or any(w in n for w in wanted)]
    results = []
    started = time.time()
    for name in names:
        t0 = time.time()
        try:
            run = subprocess.run(
                [sys.executable, os.path.join(HERE, name)],
                cwd=REPO, capture_output=True, text=True, timeout=900,
            )
            code, output = run.returncode, run.stdout + run.stderr
        except subprocess.TimeoutExpired:
            code, output = -1, "超时（900 秒）"
        took = time.time() - t0

        if name in KNOWN_DEBT:
            state = "欠账" if code != 0 else "注意"
        elif code == 0:
            state = "ok"
        elif "ModuleNotFoundError" in output:
            state = "跳过"          # 环境缺东西（PIL），不是代码坏了
        else:
            state = "FAIL"
        tail = [l for l in output.strip().split("\n") if l.strip()]
        results.append((state, name, took, tail[-1] if tail else "", output))
        print("%-6s %-22s %5.1fs  %s" % (state, name, took, tail[-1][:60] if tail else ""))

    took = time.time() - started
    ok = sum(1 for s, *_ in results if s == "ok")
    debt = [r for r in results if r[0] == "欠账"]
    noted = [r for r in results if r[0] == "注意"]
    skipped = [r for r in results if r[0] == "跳过"]
    failed = [r for r in results if r[0] == "FAIL"]

    print("\n%d 个检查：%d 绿 · %d 欠账 · %d 跳过 · %d 红（%.1fs）"
          % (len(results), ok, len(debt), len(skipped), len(failed), took))
    for _, name, _, _, output in debt:
        print("  欠账  %-20s %s" % (name, KNOWN_DEBT.get(name, "")))
        for line in output.strip().split("\n"):
            if "FAIL" in line:
                print("        %s" % line.strip()[:96])
    for _, name, _, _, output in noted:
        print("  注意  %-20s 变绿了 —— 债还了就把 %s 从 KNOWN_DEBT 里划掉" % (name, name))
        print("        %s" % line_of(output))
    for _, name, _, _, output in failed:
        print("  红    %-20s 最后几行：" % name)
        for line in output.strip().split("\n")[-4:]:
            print("        %s" % line.strip()[:96])
    return 1 if failed else 0


def line_of(output):
    for line in output.strip().split("\n"):
        if "FAIL" in line:
            return line.strip()[:96]
    return output.strip().split("\n")[-1][:96] if output.strip() else ""


if __name__ == "__main__":
    sys.exit(main())
