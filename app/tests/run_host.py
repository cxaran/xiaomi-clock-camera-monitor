"""Compile and run synthetic Java tests. No Android/ADB, LAN probes, RF or cameras."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
SRC = ROOT / "app/src/org/x04g/home"
sys.path.insert(0, str(ROOT / "scripts"))
from toolchain import resolve


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def dependencies(no_download: bool) -> dict:
    lock = json.loads((ROOT / "tools/test-dependencies.json").read_text("utf-8"))["dependencies"]
    cache = ROOT / ".cache/test-dependencies"; cache.mkdir(parents=True, exist_ok=True)
    result = {}
    for name, entry in lock.items():
        path = cache / entry["filename"]
        if not path.is_file() or sha(path) != entry["sha256"]:
            if no_download:
                raise SystemExit("Missing verified host dependency: " + name + "; run once without --no-download.")
            with urllib.request.urlopen(entry["url"], timeout=30) as response:
                data = response.read(4 * 1024 * 1024 + 1)
            if len(data) > 4 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != entry["sha256"]:
                raise SystemExit("Host dependency hash mismatch: " + name)
            path.write_bytes(data)
        result[name] = path
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--no-download", action="store_true", help="Require already cached/pinned Maven test dependencies")
    args = parser.parse_args()
    if os.name != "posix":
        parser.error("Run the complete suite from Linux/WSL: storage fixtures require POSIX file permissions.")
    sdk, deps = resolve(), dependencies(args.no_download)
    out = ROOT / "build/tests"; out.mkdir(parents=True, exist_ok=True)
    before = {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted((ROOT / "app/src").rglob("*.java"))}
    reports = []

    def run(argv):
        completed = subprocess.run([str(v) for v in argv], stdin=subprocess.DEVNULL, capture_output=True, timeout=90)
        if completed.returncode:
            error = completed.stderr.decode("utf-8", "replace")
            raise RuntimeError(error[:3500] + ("\n...\n" + error[-1500:] if len(error) > 3500 else ""))
        return completed.stdout.decode("utf-8", "replace").strip()

    with tempfile.TemporaryDirectory(prefix="host-", dir=out) as temporary:
        stage = Path(temporary)

        def compile_suite(name: str, sources: list[Path], fixtures: list[Path], classpath: list[Path] | None = None) -> Path:
            folder = stage / name; folder.mkdir()
            java_fixtures = []
            for fixture in fixtures:
                leaf = fixture.name.split(".java")[0] + ".java"
                target = folder / leaf; shutil.copyfile(fixture, target); java_fixtures.append(target)
            # Android's compile stubs also contain java.*. Java 8 language mode
            # avoids treating that classpath as a second Java module system.
            language = "-1.8" if classpath and sdk["android"] in classpath else "-17"
            command = [sdk["java"], "-jar", sdk["ecj"], language, "-encoding", "UTF-8", "-d", folder]
            if classpath:
                command += ["-classpath", os.pathsep.join(str(p) for p in classpath)]
            run(command + sources + java_fixtures)
            return folder

        def execute(suite, folder, classname, classpath=None, arguments=None):
            output = run([sdk["java"], "-cp", os.pathsep.join(str(p) for p in [folder] + (classpath or [])),
                          classname, *(arguments or [])])
            try:
                result = json.loads(output)
            except json.JSONDecodeError:
                result = {"output": output[:2000]}
            reports.append({"suite": suite, "pass": True, "result": result})

        protocol = compile_suite("camera", [p for p in sorted((SRC / "camera").glob("*.java")) if p.name != "RtspCameraPlayer.java"],
                                 [HERE / "camera/CameraProtocolFixtures.java.fixture"])
        execute("camera-protocol", protocol, "org.x04g.home.camera.CameraProtocolFixtures")

        config = [SRC / "config" / (name + ".java") for name in
                  ["ConfigException", "CameraDraft", "CameraConfig", "ConfigSnapshot", "ConfigRules", "AtomicConfigFile", "VaultCipher", "OnvifDiscovery"]]
        pure = compile_suite("config", config, [HERE / "config/ConfigSelfTest.java.txt"])
        execute("configuration-validation-crypto", pure, "org.x04g.home.config.ConfigSelfTest")
        cp = [deps["json"], sdk["android"]]
        repository = compile_suite("repository", sorted((SRC / "config").glob("*.java")),
                                   [HERE / "config/RepositorySelfTest.java.txt"], cp)
        execute("configuration-repository", repository, "org.x04g.home.config.RepositorySelfTest", cp)

        gate = compile_suite("gate", sorted((SRC / "gate").glob("*.java")), [HERE / "gate/GateFixtures.java.fixture"], [deps["json"]])
        execute("optional-gate-memory-transport", gate, "org.x04g.home.gate.GateFixtures", [deps["json"]],
                [HERE / "gate/synthetic_vectors.json"])

        web_sources = [SRC / "adminweb" / name for name in ["HttpMessage.java", "JsonBounds.java", "PairingSession.java"]]
        web_sources += [SRC / "adminweb/tls" / name for name in ["DerCertificates.java", "CertificateKeyManager.java", "KeyAuthorizations.java"]]
        fixtures = sorted((HERE / "adminweb").glob("*.java.fixture"))
        web = compile_suite("adminweb", web_sources, fixtures)
        for suite, classname, arguments in [
            ("http-framing", "org.x04g.home.adminweb.HttpFixture", []),
            ("pairing-policy", "org.x04g.home.adminweb.PairingFixture", []),
            ("der-certificates", "org.x04g.home.adminweb.fixtures.PkiFixture", [stage / "synthetic-certs"]),
            ("tls-in-memory", "org.x04g.home.adminweb.fixtures.TlsFixture", []),
            ("key-authorizations", "org.x04g.home.adminweb.fixtures.KeyPolicyFixture", [])]:
            execute(suite, web, classname, arguments=arguments)

        qr = compile_suite("qr", sorted((SRC / "qr").glob("*.java")) + [SRC / "ui/adminweb/WebAdminRules.java"],
                           [HERE / "qr/WebAdminQrFixture.java.fixture"], [deps["zxing"]])
        execute("http-public-url-and-independent-qr", qr, "org.x04g.home.ui.adminweb.WebAdminQrFixture", [deps["zxing"]])

        bridge = ROOT / "integration/java/org/x04g/bootstrap"
        bridge_classes = compile_suite("bridge", [bridge / "DiagnosticRules.java", bridge / "ProvisionRules.java"],
                                      sorted((ROOT / "integration/tests").glob("*.java")), [deps["json"]])
        execute("bootstrap-diagnostic-redaction", bridge_classes, "org.x04g.bootstrap.DiagnosticRulesHostTest", [deps["json"]])
        execute("bootstrap-provisioning-file-policy", bridge_classes, "org.x04g.bootstrap.ProvisionRulesHostTest", [deps["json"]])

    after = {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted((ROOT / "app/src").rglob("*.java"))}
    if before != after:
        raise RuntimeError("production_sources_changed_during_tests")
    report = {"schema": "x04g.host.tests.v1", "pass": True, "suites": reports, "source_hashes": before,
              "sources_unchanged": True, "device_or_LAN_operations": False,
              "test_dependencies": {key: sha(value) for key, value in deps.items()},
              "limitations": ["Synthetic tests do not run MediaCodec, AudioTrack, AndroidKeyStore or OEM lifecycle.",
                              "No camera or L20 connection, RF emission, Android emulator or physical device was used."]}
    (out / "host-report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"pass": True, "suites": len(reports), "report": "build/tests/host-report.json"}))


if __name__ == "__main__":
    main()
