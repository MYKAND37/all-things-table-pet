"""Tests for the pet package: what goes in, and what is refused on the way back out.

Mirrors dev.atp.pet.data.PetPackage. A package is a FILE THAT CAME FROM SOMEWHERE ELSE -- a
friend's phone, a chat app, a download -- so the interesting failures are not about layout, they
are about what a hostile (or merely odd) zip is allowed to say:

  * an entry called `../../databases/x` is a path out of the folder;
  * an entry that unpacks to a gigabyte is a phone with no space left;
  * a zip with no `character.json` in it is not a pet, whatever else it contains;
  * a package whose name is already taken must NOT be unpacked over the pet that has it.

The mirror never writes anything: it answers the same questions the Kotlin answers, on the same
input, so the decisions can be argued about here instead of on somebody's phone.

    python3 tools/package_check.py
"""
import io, json, os, re, sys, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
PKG_KT = os.path.join(REPO, "app/src/main/java/dev/atp/pet/data/PetPackage.kt")
STORE_KT = os.path.join(REPO, "app/src/main/java/dev/atp/pet/data/CharacterStore.kt")

FAILURES = []


def report(label, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + label + ("   " + detail if detail else ""))
    if not ok:
        FAILURES.append(label)


def kotlin_number(name, fallback):
    """One numeric constant, read back out of the Kotlin -- expression and all.

    `512L * 1024 * 1024` is how a byte cap should be written, and the mirror has to end up with
    the number it means rather than with the 512 in front of it.
    """
    text = open(PKG_KT, encoding="utf-8").read()
    m = re.search(r"const val %s = ([0-9L* ]+)" % name, text)
    if not m:
        return fallback
    expr = m.group(1).replace("L", "").strip()
    try:
        return int(eval(expr))
    except Exception:
        return fallback


def kotlin_text(name, fallback):
    text = open(PKG_KT, encoding="utf-8").read()
    m = re.search(r'const val %s = "([^"]+)"' % name, text)
    return m.group(1) if m else fallback


#: Read out of the Kotlin rather than typed in twice: a reader that disagrees with the writer
#: about the format number is a reader that refuses every file the app itself produces.
FORMAT = kotlin_number("FORMAT", 1)
MAX_ENTRIES = kotlin_number("MAX_ENTRIES", 4000)
MAX_BYTES = kotlin_number("MAX_BYTES", 512 * 1024 * 1024)
MANIFEST = kotlin_text("MANIFEST", "pack.json")
EXTENSION = kotlin_text("EXTENSION", "atppet")

SPEC_FILE = "character.json"


def safe_entry_path(name):
    """Mirror of PetPackage.safeEntryPath. None means "this entry is not written anywhere"."""
    clean = name.replace("\\", "/").strip()
    if not clean or len(clean) > 240:
        return None
    if clean.startswith("/"):
        return None
    if ".." in clean:
        return None
    if ":" in clean:
        return None
    if "\x00" in clean:
        return None
    return clean.lstrip("/")


def common_folder(names):
    """Mirror of PetPackage.commonFolder: the deepest folder everything is inside, or "".

    The deepest, not the first segment: a package that went through an unzip-and-rezip lands at
    `pets/小白/…`, and answering `pets/` leaves the spec one level down -- where the reader then
    says "no pet in here" about a package that is nothing but a pet.
    """
    names = [n for n in (safe_entry_path(x) for x in names) if n and n != MANIFEST]
    if not names:
        return ""
    first = names[0]
    cut = first.rfind("/")
    prefix = first[:cut + 1] if cut >= 0 else ""
    while prefix and not all(n.startswith(prefix) for n in names):
        cut = prefix[:-1].rfind("/")
        prefix = prefix[:cut + 1] if cut >= 0 else ""
    return prefix


def read(data, free_id, max_bytes=MAX_BYTES, max_entries=MAX_ENTRIES, taken=()):
    """Mirror of PetPackage.read: (chosen id, {relative path: bytes}) or (None, {}).

    The Kotlin writes the files as it goes; this one collects them, because what a test wants to
    look at is the DECISIONS -- which entries are written where, and which package is refused.
    """
    try:
        z = zipfile.ZipFile(io.BytesIO(data))
    except Exception:
        # Not a zip at all is the most ordinary way to pick the wrong file, and it must be the
        # same answer as every other "this is not a package".
        return None, {}
    with z:
        entries = z.infolist()
        if len(entries) > max_entries:
            return None, {}
        manifest = None
        for e in entries:
            path = safe_entry_path(e.filename)
            if path and path.endswith(MANIFEST):
                try:
                    manifest = json.loads(z.read(e).decode("utf-8"))
                except Exception:
                    manifest = None
                break
        if manifest is None:
            return None, {}
        if manifest.get("format") != FORMAT or manifest.get("kind") != "table-pet":
            return None, {}
        prefix = common_folder([e.filename for e in entries])
        chosen = free_id(manifest.get("id", ""))
        if not chosen:
            return None, {}
        out, total = {}, 0
        for e in entries:
            if e.is_dir():
                continue
            path = safe_entry_path(e.filename)
            if path is None or path == MANIFEST:
                continue
            rel = path[len(prefix):] if prefix else path
            safe = safe_entry_path(rel)
            if not safe:
                continue
            if len(out) + 1 > max_entries:
                return None, {}
            blob = z.read(e)
            total += len(blob)
            if total > max_bytes:
                return None, {}
            out[safe] = blob
        if SPEC_FILE not in out:
            return None, {}
        return chosen, out


def make_package(entries, pet_id="小白", format_version=FORMAT, kind="table-pet", prefix=""):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(prefix + MANIFEST, json.dumps(
            {"format": format_version, "kind": kind, "id": pet_id, "files": len(entries)}
        ))
        for name, blob in entries.items():
            z.writestr(prefix + name, blob)
    return buf.getvalue()


def fresh_id(wanted, taken=()):
    """What CharacterStore.freeId does: the name, or the name with the first free number."""
    used = set(taken)
    base = (wanted or "pet").strip() or "pet"
    if base not in used:
        return base
    n = 2
    while "%s%d" % (base, n) in used:
        n += 1
    return "%s%d" % (base, n)


def main():
    print("包本身：常量来自 Kotlin")
    report("格式号从 Kotlin 读出来", FORMAT == 1, str(FORMAT))
    report("清单文件名从 Kotlin 读出来", MANIFEST == "pack.json", MANIFEST)
    report("扩展名从 Kotlin 读出来", EXTENSION == "atppet", EXTENSION)
    report("上限从 Kotlin 读出来", MAX_ENTRIES > 0 and MAX_BYTES > 0,
           "%d entries / %d bytes" % (MAX_ENTRIES, MAX_BYTES))

    print("\n一个条目能写到哪儿：只允许「文件夹里面的名字」")
    # 这几条是这个文件存在的理由：zip 是一串路径，而恶意的那一串里一半的条目是"出去的路"。
    report("`../../databases/x` 被拒（爬出文件夹）", safe_entry_path("../../databases/x") is None)
    report("`a/../../b` 被拒（半路爬出去也算）", safe_entry_path("a/../../b") is None)
    report("`/etc/hosts` 被拒（绝对路径）", safe_entry_path("/etc/hosts") is None)
    report("`C:\\x` 被拒（盘符）", safe_entry_path("C:" + "\\" + "x") is None)
    report("名字里带 NUL 被拒", safe_entry_path("a\x00b") is None)
    report("超长名字被拒", safe_entry_path("x" * 300) is None)
    report("普通的名字照常放行", safe_entry_path("parts/hand_L.png") == "parts/hand_L.png")
    report("反斜杠也算分隔符",
           safe_entry_path("parts" + "\\" + "hand_L.png") == "parts/hand_L.png")

    print("\n包里套了一层文件夹也认")
    report("平铺的包没有前缀", common_folder([SPEC_FILE, "parts/a.png"]) == "")
    report("都在一个文件夹里就用那个前缀",
           common_folder(["pets/x/" + SPEC_FILE, "pets/x/parts/a.png"]) == "pets/x/")
    # 这一条是镜像抓出来的真 bug：第一版只取第一段（"pets/"），于是解压再压缩一遍的包
    # ——里面的 spec 在 x/ 下面——会被判成"里面没有桌宠"。
    report("套了两层就取两层（不是只取第一段）",
           common_folder(["pets/x/y/" + SPEC_FILE, "pets/x/y/parts/a.png"]) == "pets/x/y/")
    # 一半在顶层、一半在文件夹里：拼出来的包，猜哪一半是宠物就是猜。
    report("一半在里面一半在外面就不猜",
           common_folder([SPEC_FILE, "pets/x/parts/a.png"]) == "")

    print("\n一个正常的包装进来")
    nested = make_package({SPEC_FILE: b'{"id":"x"}', "parts/hand_L.png": b"PNG"}, prefix="pets/x/")
    got, files = read(nested, lambda w: fresh_id(w))
    report("解压再压缩过（套了一层）也进得来", got == "小白" and SPEC_FILE in files,
           "%s / %s" % (got, sorted(files)))
    pack = make_package({SPEC_FILE: b'{"id":"x"}', "parts/hand_L.png": b"PNG"})
    got, files = read(pack, fresh_id)
    report("进得来，名字是包里写的那个", got == "小白", str(got))
    report("文件按相对路径落位", sorted(files) == [SPEC_FILE, "parts/hand_L.png"], str(sorted(files)))
    report("清单本身不落盘", MANIFEST not in files)

    print("\n不是包的东西一律不进")
    report("没有清单 = 不是我们的文件", read(make_package({SPEC_FILE: b"{}"})[0:0] + b"not a zip",
                                      fresh_id) == (None, {}))
    report("格式号对不上就不进",
           read(make_package({SPEC_FILE: b"{}"}, format_version=99), fresh_id) == (None, {}))
    report("kind 不对也不进",
           read(make_package({SPEC_FILE: b"{}"}, kind="something-else"), fresh_id) == (None, {}))
    report("没有 character.json = 里面没有桌宠",
           read(make_package({"parts/a.png": b"PNG"}), fresh_id) == (None, {}))
    report("空 zip 不进", read(make_package({}), fresh_id) == (None, {}))

    print("\n危险的和太大的")
    sneaky = make_package({SPEC_FILE: b"{}", "../evil.png": b"x", "parts/a.png": b"y"})
    got, files = read(sneaky, fresh_id)
    report("带「..」的条目被跳过，不是被写出去",
           got == "小白" and "../evil.png" not in files and "parts/a.png" in files,
           str(sorted(files)))
    big = make_package({SPEC_FILE: b"{}", "parts/big.png": b"x" * 5000})
    report("超过总量上限就整个拒掉（不是截断着用）",
           read(big, fresh_id, max_bytes=1000) == (None, {}))
    many = make_package(dict([(SPEC_FILE, b"{}")] +
                             [("parts/p%d.png" % i, b"x") for i in range(50)]))
    report("条目数超过上限也整个拒掉",
           read(many, fresh_id, max_entries=10) == (None, {}))

    print("\n名字撞了：导入永远不覆盖")
    # 「导入永远不覆盖」是导入这一侧唯一一条不能商量的规矩：两只桌宠同名不是弄丢一只的理由。
    report("重名就用 freeId 的答案（小白 -> 小白2）",
           read(make_package({SPEC_FILE: b"{}"}),
                lambda w: fresh_id(w, taken=["小白"]))[0] == "小白2")
    report("没重名就照用包里的名字",
           read(make_package({SPEC_FILE: b"{}"}), lambda w: fresh_id(w))[0] == "小白")
    seen = []
    read(make_package({SPEC_FILE: b"{}"}, pet_id="阿黄"), lambda w: (seen.append(w), w)[1])
    report("freeId 拿到的就是包里那个名字", seen == ["阿黄"], str(seen))

    print("\n两层实现说的是同一件事（源码层面）")
    kt = open(PKG_KT, encoding="utf-8").read()
    report("Kotlin 里也有「..」这一条", '".."' in kt)
    report("Kotlin 里也有总量封顶", "MAX_BYTES" in kt and "total > MAX_BYTES" in kt)
    report("Kotlin 里导入校验 character.json 在不在",
           "CharacterFolder.SPEC_FILE" in kt)
    store = open(STORE_KT, encoding="utf-8").read()
    report("导入用 freeId 挑名字（不是直接照抄）", "freeId(wanted" in store)
    report("导出写的是整个文件夹（spec 在里面）",
           "PetPackage.write(folder.dir" in store)

    print("")
    if FAILURES:
        print("%d FAILED" % len(FAILURES))
        for f in FAILURES:
            print("  " + f)
        return 1
    print("all package tests passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
