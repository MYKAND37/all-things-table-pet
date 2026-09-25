#!/usr/bin/env python3
"""英文资源：和中文那份对不对得上，以及"还剩多少没翻"。

这一版加的是**应用自己的功能词**的英文（控件、段落名、引擎词汇、骨头名、数字）。
内容（角色美术、用户自己的规则、出厂默认角色的文案、那些长提示语）**故意不翻** ——
应用是给人自己打包用的，内容是他的。所以这个检查要回答两个问题，而且都要用数字回答：

  1. **两份文件是不是同一批键**：`values-en` 里多一个键（拼错了）或者少一个键（漏了）都要红。
     一个只在英文里存在的键，Android 会当它不存在 —— 界面回落到中文，而没人会发现。
  2. **占位符一模一样**：`%1$s` 在翻译里弄丢了，用户看到的是"Sprayed " 后面什么都没有，
     而那是一个运行期才炸的东西。所以它在这里比。
  3. 剩多少条还只有中文（长文案），**列出来但不红**：它是路线图，不是失败。
     如果哪天它变成 0，这一版就真的做完了。

    python3 tools/english_check.py
"""
import os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
ZH = os.path.join(REPO, "app/src/main/res/values/strings.xml")
EN = os.path.join(REPO, "app/src/main/res/values-en/strings.xml")

FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)


def read(path):
    if not os.path.isfile(path):
        return {}
    text = open(path, encoding="utf-8").read()
    return dict(re.findall(r'<string name="([^"]+)">(.*?)</string>', text, re.S))


def placeholders(value):
    """`%1$s` / `%2$d` / `%%` —— 顺序不管，缺一个都不行。"""
    return sorted(re.findall(r"%\d+\$[sd]|%%", value))


def main():
    zh, en = read(ZH), read(EN)
    report("中文那份读得到", bool(zh), "%d 条" % len(zh))
    report("英文那份读得到", bool(en), "%d 条" % len(en))
    if not zh or not en:
        print("\n1 FAILED")
        return 1

    print("\n两份文件对不对得上")
    unknown = sorted(set(en) - set(zh))
    report("英文里的每个键中文那份都有（没有拼错的键）", not unknown, str(unknown[:6]))
    missing = sorted(set(zh) - set(en))
    # 没翻的是"内容与长文案"，那是明说的范围之外，所以这里只报数字，不判红。
    print("   ·   %d 条还没有英文（长文案与内容；它们回落到中文）" % len(missing))

    print("\n占位符")
    bad = [(k, placeholders(zh[k]), placeholders(en[k]))
           for k in en if placeholders(zh.get(k, "")) != placeholders(en[k])]
    report("每个键的 %1$s / %d / %% 和中文那份一模一样", not bad,
           "; ".join("%s %s vs %s" % b for b in bad[:3]))

    print("\n英文里没有漏掉的中文")
    # 一条"英文"里还剩汉字，通常是从中文那份复制过来忘了改（或者只改了半句）。
    leftovers = [(k, v) for k, v in en.items() if re.search(r"[\u4e00-\u9fff]", v)]
    report("英文那份里没有汉字", not leftovers,
           "; ".join("%s=%s" % (k, v[:18]) for k, v in leftovers[:3]))

    print("\n资源里没有会让 aapt2 报错的东西")
    # 裸单引号：`Where %1$s's drawing ranks` 这种，Android 的资源编译器直接失败
    # （CI 报 "Invalid unicode escape sequence in string"）。第一版就是这么红的。
    bare = [(k, v[:40]) for k, v in list(zh.items()) + list(en.items())
            if any(ch == "'" and (i == 0 or v[i - 1] != "\\")
                   for i, ch in enumerate(v))]
    report("没有裸的单引号（要写成 \\'）", not bare, str(bare[:3]))

    print("\n功能词这一类必须翻完")
    # 这一版的范围就是"应用自己的功能词"：控件文案（短标签）+ 引擎词汇 + 骨头名 + 刚度档。
    # 长文案（>16 字）不在范围内，所以按同一把尺子量：短的必须都有英文。
    short = [k for k, v in zh.items() if len(v) <= 16]
    un = [k for k in short if k not in en]
    report("所有短标签（≤16 字，也就是控件与功能词）都有英文", not un,
           "%d 条短标签；缺 %s" % (len(short), un[:6]))

    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        for f in FAILURES:
            print("  " + f)
        return 1
    print("all english checks passed（%d/%d 条有英文）" % (len(en), len(zh)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
