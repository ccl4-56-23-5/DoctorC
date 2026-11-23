"""Android 15 emulator smoke test: navigate into another app's shared folders and copy bytes."""
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

adb, serial, package = sys.argv[1:4]
assert serial.startswith("emulator-")
root = "/storage/emulated/0"
dump = "/data/local/tmp/zipdoctor-tree.xml"
other = "com.android.vending"
fixture = f"ZipDoctorC-tree-qa-{int(time.time())}"


def run(*args):
    return subprocess.check_output([adb, "-s", serial, *args], timeout=30)


def shell(command):
    return run("shell", command).decode("utf-8", "replace").strip()


def tree():
    if "dumped to" not in shell(f"uiautomator dump {dump}"):
        return []
    return list(ET.fromstring(run("exec-out", "cat", dump)).iter("node"))


def find(label, attribute="text"):
    nodes = []
    for _ in range(6):
        nodes = tree()
        for node in nodes:
            if node.get(attribute) == label:
                return node
    raise AssertionError((label, [n.get("text") or n.get("content-desc") for n in nodes
                                 if n.get("text") or n.get("content-desc")]))


def tap(label, attribute="text"):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", find(label, attribute).get("bounds")))
    shell(f"input tap {(x1+x2)//2} {(y1+y2)//2}")
    time.sleep(.45)


def navigate(path):
    assert "Status: ok" in shell(
        f"am start -W -n {package}/com.wirelessalien.zipxtract.activity.MainActivity "
        "-a com.wirelessalien.zipxtract.ACTION_OPEN_DIRECTORY "
        "--es com.wirelessalien.zipxtract.EXTRA_DIRECTORY_PATH " + shlex.quote(path)
    )


assert shell("settings get global adb_wifi_enabled") in ("0", "null")
assert "shizuku" not in shell("pm list packages").lower()
assert "MANAGE_EXTERNAL_STORAGE: allow" in shell(f"appops get --uid {package} MANAGE_EXTERNAL_STORAGE")
try:
    shell(f"am force-stop {package}")
    assert "Status: ok" in shell(f"am start -W -n {package}/com.wirelessalien.zipxtract.activity.MainActivity")
    tap("文件", "content-desc")
    find("我的手机")
    assert not any(n.get("text") == "Android" for n in tree())
    print("The file hub has no standalone Android entry.", flush=True)
    for kind in ("data", "obb"):
        folder = f"{root}/Android/{kind}/{other}/{fixture}"
        name = f"{fixture}-{kind}.txt"
        expected = f"zipdoctor-{kind}-bytes"
        shell(f"mkdir -p {folder} && printf {expected} > {folder}/{name}")
        navigate(f"{root}/Android")
        tap(kind)
        tap(other)
        tap(fixture)
        find(name)
        tap("更多", "content-desc")
        tap("复制")
        tap("存储位置")
        tap("内部存储")
        tap("Download")
        tap("选择此文件夹")
        for _ in range(10):
            if shell(f"test -f {root}/Download/{name} && cat {root}/Download/{name}") == expected:
                break
            time.sleep(.5)
        else:
            raise AssertionError(f"Copied bytes do not match for Android/{kind}")
        print(f"Android/{kind}: opened another app's nested folder, copied the file, verified its bytes.", flush=True)
    navigate(f"{root}/Android")
    tap("文件", "content-desc")
    # A fresh file hub can be reached from the archives tab; neither menu has a special Android shortcut.
    tap("压缩", "content-desc")
    tap("更多选项", "content-desc")
    menu = tree()
    assert not any(n.get("text") == "Android 文件夹" for n in menu)
    assert any(n.get("text") == "存储访问权限" for n in menu)
    print("Storage permission remains available; no Android shortcut in the options menu.", flush=True)
finally:
    for kind in ("data", "obb"):
        folder = f"{root}/Android/{kind}/{other}/{fixture}"
        name = f"{fixture}-{kind}.txt"
        shell(f"rm -f {folder}/{name} {root}/Download/{name}; rmdir {folder} 2>/dev/null; true")
    shell(f"rm -f {dump}")
