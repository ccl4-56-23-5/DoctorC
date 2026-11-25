"""Exercise the signed build's storage flow on the dedicated Android 15 QA emulator."""

import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


ADB, SERIAL = sys.argv[1:3]
assert SERIAL.startswith("emulator-"), "Use a dedicated QA emulator"
PKG = "local.sevenzip.android"
COMPONENT = PKG + "/com.wirelessalien.zipxtract.activity.MainActivity"
DUMP = "/data/local/tmp/zipdoctorc-storage-qa.xml"
FIXTURE = f"ZipDoctorC-storage-qa-{int(time.time())}"
MEDIA = "/sdcard/Android/media/" + FIXTURE
OP = f"appops get --uid {PKG} MANAGE_EXTERNAL_STORAGE"


def adb(*args):
    return subprocess.check_output([ADB, "-s", SERIAL, *args], timeout=30)


def shell(command):
    return adb("shell", command).decode("utf-8", errors="replace").strip()


def snapshot():
    if "dumped to" not in shell(f"uiautomator dump {DUMP}"):
        return []
    return list(ET.fromstring(adb("exec-out", "cat", DUMP)).iter("node"))


def node(value, attr="text"):
    nodes = []
    for _ in range(6):
        nodes = snapshot()
        for current in nodes:
            if current.get(attr) == value:
                return current
    raise AssertionError((value, attr, [(n.get("text"), n.get("content-desc")) for n in nodes
                                       if n.get("text") or n.get("content-desc")]))


def tap(value, attr="text"):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node(value, attr).get("bounds")))
    shell(f"input tap {(x1+x2)//2} {(y1+y2)//2}")
    time.sleep(.35)


def back():
    shell("input keyevent 4")
    time.sleep(.45)


def storage_mode():
    # A UID override is listed before the older per-package record and takes precedence.
    match = re.search(r"MANAGE_EXTERNAL_STORAGE: (\w+)", shell(OP))
    return match.group(1) if match else "default"


original = storage_mode()
try:
    shell(f"mkdir {MEDIA} && printf qa > {MEDIA}/sample.txt")
    shell(f"appops set --uid {PKG} MANAGE_EXTERNAL_STORAGE deny; am force-stop {PKG}; wm dismiss-keyguard")
    assert "Status: ok" in shell(f"am start -W -n {COMPONENT}")
    node("允许访问内部存储")
    tap("更多选项", "content-desc")
    tap("Android 文件夹")
    assert node("Allow access to manage all files").get("package") == "com.android.settings"
    assert node("true", "checkable").get("checked") == "false"
    back()
    node("允许访问内部存储")
    assert storage_mode() != "allow"
    print("System permission page opens for ZipDoctorC; backing out leaves access denied.", flush=True)

    tap("允许访问内部存储")
    tap("Allow access to manage all files")
    assert node("true", "checkable").get("checked") == "true"
    assert storage_mode() == "allow"
    back()
    node("media")
    assert not any(n.get("content-desc") == "新建文件夹" for n in snapshot())
    print("User grant returns directly to the requested Android folder.", flush=True)

    tap("media")
    tap(FIXTURE)
    node("sample.txt")
    tap("新建文件夹", "content-desc")
    tap("android.widget.EditText", "class")
    shell("input text qa-created")
    node("qa-created")
    tap("创建")
    assert shell(f"test -d {MEDIA}/qa-created && echo written") == "written"
    node("qa-created")
    back()
    back()
    print("Android/media: read existing file and create a real subfolder.", flush=True)

    tap("data")
    node("系统限制访问")
    node("其他应用的 Android/data 文件夹无法通过“所有文件访问”授权。")
    tap("ZipDoctorC 文件夹")
    node("files")
    back()
    back()
    back()
    tap("obb")
    node("系统限制访问")
    node("其他应用的 Android/obb 文件夹无法通过“所有文件访问”授权。")
    print("Android/data opens the app's own files; other apps' data and obb show the system limit.", flush=True)

    tap("更多选项", "content-desc")
    tap("存储访问权限")
    assert node("true", "checkable").get("checked") == "true"
    tap("Allow access to manage all files")
    assert node("true", "checkable").get("checked") == "false"
    back()
    if not any(n.get("text") == "允许访问内部存储" for n in snapshot()):
        # Some Android releases terminate the app process when the app-op changes.
        shell(f"am start -W -n {COMPONENT}")
    node("允许访问内部存储")
    print("Revoking access restores the permission prompt.", flush=True)
finally:
    shell(f"appops set --uid {PKG} MANAGE_EXTERNAL_STORAGE {original}; "
          f"rm -f {MEDIA}/sample.txt; rmdir {MEDIA}/qa-created {MEDIA} 2>/dev/null; rm -f {DUMP}")
