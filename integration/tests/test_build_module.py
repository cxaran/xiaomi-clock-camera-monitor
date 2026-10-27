"""APK-derived version pins and actual StockBootstrap version gate regressions.

Run: python integration/tests/test_build_module.py
Only synthetic APK containers and HOST JVM classes are generated. No module,
release APK, emulator, device, network service or RF transmission is used.
"""
from pathlib import Path
import importlib.util
import json
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("module_builder", ROOT / "integration/build_module.py")
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)


def manifest(code=3, name="0.1.0-rc.2", package="org.x04g.home", utf8=True, name_type=3, duplicate=False):
    """Small independent AXML fixture with the same attribute types as aapt2."""
    namespace = "http://schemas.android.com/apk/res/android"
    strings = ["manifest", "package", "versionCode", "versionName", "versionCodeMajor", namespace, package, name]
    index = {value: n for n, value in enumerate(strings)}
    offsets, body = [], b""
    for value in strings:
        offsets.append(len(body)); raw = value.encode("utf-8" if utf8 else "utf-16-le")
        units = len(value.encode("utf-16-le")) // 2
        if utf8:
            def length(n):
                return bytes([n]) if n < 128 else bytes([0x80 | (n >> 8), n & 255])
            body += length(units) + length(len(raw)) + raw + b"\0"
        else:
            body += struct.pack("<H", units) + raw + b"\0\0"
    body += b"\0" * ((-len(body)) % 4)
    start = 28 + 4 * len(strings)
    pool = struct.pack("<HHIIIIII", 1, 28, start + len(body), len(strings), 0, 0x100 if utf8 else 0, start, 0)
    pool += struct.pack("<" + "I" * len(offsets), *offsets) + body
    attrs = [(None, "package", 3, index[package]), (namespace, "versionCode", 0x10, code & 0xffffffff),
             (namespace, "versionName", name_type, index[name])]
    if code >> 32:
        attrs.append((namespace, "versionCodeMajor", 0x10, code >> 32))
    if duplicate:
        attrs.append(attrs[2])
    chunk = struct.pack("<HHIII", 0x102, 16, 36 + 20 * len(attrs), 1, 0xffffffff)
    chunk += struct.pack("<IIHHHHHH", 0xffffffff, index["manifest"], 20, 20, len(attrs), 0, 0, 0)
    for ns, key, kind, value in attrs:
        raw = value if kind == 3 else 0xffffffff
        chunk += struct.pack("<IIIHBBI", 0xffffffff if ns is None else index[ns], index[key], raw, 8, 0, kind, value)
    ending = struct.pack("<HHIIIII", 0x103, 16, 24, 1, 0xffffffff, 0xffffffff, index["manifest"])
    return struct.pack("<HHI", 3, 8, 8 + len(pool) + len(chunk) + len(ending)) + pool + chunk + ending


def apk(path, raw):
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("AndroidManifest.xml", raw)
    return path


STUBS = {
    "android/content/Context.java": '''package android.content;import android.content.pm.*;
public class Context {public ApplicationInfo getApplicationInfo(){return null;}public PackageManager getPackageManager(){return null;}}''',
    "android/app/Activity.java": '''package android.app;public class Activity extends android.content.Context {public boolean isFinishing(){return false;}public String getPackageName(){return "";}}''',
    "android/app/Application.java": "package android.app;public class Application extends android.content.Context {}",
    "android/content/pm/ApplicationInfo.java": "package android.content.pm;public class ApplicationInfo {public int uid;public String sourceDir;}",
    "android/content/pm/PackageInfo.java": '''package android.content.pm;public class PackageInfo {public long testVersionCode;public String versionName;public SigningInfo signingInfo;public long getLongVersionCode(){return testVersionCode;}}''',
    "android/content/pm/PackageManager.java": '''package android.content.pm;public class PackageManager {public static final int GET_SIGNING_CERTIFICATES=0x08000000;public ApplicationInfo getApplicationInfo(String name,int flags){return null;}public PackageInfo getPackageInfo(String name,int flags){return null;}}''',
    "android/content/pm/Signature.java": "package android.content.pm;public class Signature {public byte[] toByteArray(){return new byte[0];}}",
    "android/content/pm/SigningInfo.java": "package android.content.pm;public class SigningInfo {public boolean hasMultipleSigners(){return false;}public Signature[] getApkContentsSigners(){return null;}}",
    "android/os/Looper.java": "package android.os;public class Looper {public static Looper getMainLooper(){return null;}public static Looper myLooper(){return null;}}",
    "android/os/Process.java": "package android.os;public class Process {public static int myUid(){return 10000;}}",
    "android/util/Log.java": "package android.util;public class Log {public static int i(String tag,String value){return 0;}}",
    "org/x04g/bootstrap/RuntimeDiagnostics.java": "package org.x04g.bootstrap;final class RuntimeDiagnostics {static void start(){}}",
    "org/x04g/bootstrap/OptionalProvisioning.java": "package org.x04g.bootstrap;final class OptionalProvisioning {static void observe(android.app.Activity activity){}}",
}


class ModuleVersionTest(unittest.TestCase):
    def test_identity_from_binary_manifest_utf8_and_utf16(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "synthetic.apk"
            for utf8 in [True, False]:
                for code, name in [(2, "0.1.0-rc.1"), (3, "0.1.0-rc.2"), ((2 << 32) | 7, "2.4.0-next.7")]:
                    with self.subTest(utf8=utf8, version_code=code):
                        result = BUILDER.apk_identity(apk(path, manifest(code, name, utf8=utf8)))
                        self.assertEqual(result, dict(package="org.x04g.home", version_code=code, version_name=name))

    def test_invalid_or_ambiguous_identity_rejected_without_echoing_input(self):
        fixtures = [manifest(package="org.other.application"), manifest(code=0), manifest(code=1 << 63),
                    manifest(name='";invalid'), manifest(name="a" * 97), manifest(name_type=1), manifest(duplicate=True),
                    manifest()[:-1], b"not AXML"]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "synthetic.apk"
            for raw in fixtures:
                with self.subTest(fixture_bytes=len(raw)):
                    with self.assertRaises(ValueError) as error:
                        BUILDER.apk_identity(apk(path, raw))
                    self.assertEqual(str(error.exception), "apk_manifest_identity_invalid")
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("other", "synthetic")
            with self.assertRaisesRegex(ValueError, "^one_bounded_apk_manifest_required$"):
                BUILDER.apk_identity(path)

    def test_builder_and_runtime_use_apk_derived_pins(self):
        builder = (ROOT / "integration/build_module.py").read_text("utf-8")
        bridge = (ROOT / "integration/java/org/x04g/bootstrap/StockBootstrap.java").read_text("utf-8")
        self.assertIn("identity = apk_identity(apk)", builder)
        self.assertIn('"apk_version_code": identity["version_code"]', builder)
        self.assertIn('"apk_version_name": identity["version_name"]', builder)
        self.assertIn("!companionVersionMatches(signing)", bridge)
        self.assertNotIn("0.1.0-candidate", bridge)
        self.assertIn("BuildPins.OWN_VERSION_CODE", bridge)
        self.assertIn("BuildPins.OWN_VERSION_NAME", bridge)
        self.assertIn("!signerMatches(signing, BuildPins.OWN_SIGNER_SHA)", bridge)

    def test_actual_stock_bridge_accepts_only_generated_version(self):
        sys.path.insert(0, str(ROOT / "scripts"))
        from toolchain import resolve
        tools = resolve()
        out = ROOT / "build/tests/module-version"; out.mkdir(parents=True, exist_ok=True)
        reports = []
        with tempfile.TemporaryDirectory(prefix="version-", dir=out) as temporary:
            stage = Path(temporary); base_sources = []
            for relative, text in STUBS.items():
                path = stage / relative; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text, encoding="utf-8"); base_sources.append(path)
            base_sources += [ROOT / "integration/java/org/x04g/bootstrap/StockBootstrap.java",
                             Path(__file__).parent / "version/StockBootstrapVersionHostTest.java"]
            for code, name in [(2, "0.1.0-rc.1"), (3, "0.1.0-rc.2"), ((2 << 32) | 7, "2.4.0-next.7")]:
                identity = BUILDER.apk_identity(apk(stage / "synthetic.apk", manifest(code, name)))
                pins = stage / "BuildPins.java"
                generated = BUILDER.build_pins("1" * 64, "2" * 64, 1234, identity)
                self.assertNotIn(str(stage), generated)
                pins.write_text(generated, encoding="utf-8")
                classes = stage / ("classes-" + str(code)); classes.mkdir()
                subprocess.run([str(x) for x in [tools["java"], "-jar", tools["ecj"], "-1.8", "-encoding", "UTF-8", "-d", classes, *base_sources, pins]], check=True, capture_output=True, timeout=90)
                result = subprocess.run([str(x) for x in [tools["java"], "-cp", classes, "org.x04g.bootstrap.StockBootstrapVersionHostTest", str(code), name]], check=True, capture_output=True, text=True, timeout=30)
                reports.append(dict(version_code=code, version_name=name, **json.loads(result.stdout)))
        (out / "report.json").write_text(json.dumps({"pass": True, "scenarios": reports,
            "limits": "Actual StockBootstrap version gate on HOST facade only; APK/OEM signature/hash/UID/lifecycle checks need runtime validation."}, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    unittest.main()
