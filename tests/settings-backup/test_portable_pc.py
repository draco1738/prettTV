"""PC utility regressions; no ADB is executed. Real shared Kotlin validator and E: filesystem."""
import argparse
import base64
import importlib.util
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
OUT = Path("E:/Development/tcl-tv-tools/snapshots/home-control-2026-11-11/portable-backup")
spec = importlib.util.spec_from_file_location("portable_backup", REPO / "tools/portable-settings-backup.py")
backup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backup)


class PortablePcChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.classpath = Path(os.environ.get("PORTABLE_BACKUP_VALIDATOR_CLASSPATH_FILE", str(OUT / "host/validator-classpath.txt"))).read_text(encoding="utf-8")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pc-fixture-", dir=OUT)
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.args = argparse.Namespace(
            java="E:/Development/tcl-tv-tools/jdk/jdk-17.0.20.1+1/bin/java.exe",
            validator_classpath=self.classpath, scratch=str(self.folder),
            adb="E:/Development/tcl-tv-tools/platform-tools/adb.exe", port=5038,
            serial="fixture-only:5555", expected_manufacturer="Fixture maker",
            expected_model="Fixture model", expected_device="Fixture device",
            expected_hardware_serial="Fixture hardware serial",
            output=str(self.folder / "settings.snapshot"))
        self.document = {"version": 1, "home": {
            "historySource": {"type": "string", "value": "THIS_TV_ONLY"}},
            "apps": {}, "playback": {}, "prett-personalization": {}}
        # The shared validator is authoritative about the namespace, not this Python test.
        self.calls = []

    def encoded(self, document=None):
        return base64.b64encode(json.dumps(self.document if document is None else document).encode())

    def fake_adb(self, command, limit, scratch, input_bytes=None):
        self.calls.append(command)
        if command[0].lower().endswith("adb.exe"):
            self.assertEqual(command[1:5], ["-P", "5038", "-s", self.args.serial])
            if command[5:7] == ["shell", "getprop"]:
                prop = command[7]
                key = next(key for key, value in backup.PROPERTIES.items() if value == prop)
                return (getattr(self.args, "expected_" + key) + "\n").encode()
            self.assertEqual(command[5:], ["shell", "dumpsys", "activity",
                "dev.zane.pretttv/fi.jommi.rivi.MainActivity", "--export-local-settings"])
            return b"ACTIVITY fixture\n  PRETTTV_SETTINGS=" + self.encoded() + b"\r\n"
        return backup.run_bounded(command, limit, scratch, input_bytes)

    def test_full_export_and_offline_verify_real_shared_validator(self):
        result = backup.export_backup(self.args, self.fake_adb)
        self.assertTrue(result["readback_verified"])
        data = backup.read_bounded(Path(self.args.output))
        self.assertTrue(data.startswith(b"PRETTBK1"))
        self.args.input = self.args.output
        self.assertEqual(result["sha256"], backup.verify_backup(self.args)["sha256"])
        self.assertFalse(any("--restore-local-settings" in call for call in self.calls))
        self.assertEqual(9, sum(call[0].lower().endswith("adb.exe") for call in self.calls))
        # Separate evidence fixture is synthetic and labeled, never a real TV backup.
        fixture = OUT / "synthetic-interoperability.snapshot"
        fixture.write_bytes(data)
        Path(str(fixture) + ".manifest.json").write_text(json.dumps(dict(result,
            source="synthetic-host-test-not-a-TV-backup"), indent=2), encoding="utf-8")

    def weather_document(self, raw=None):
        document = json.loads(json.dumps(self.document))
        document["home"].update(
            weatherEnabled={"type": "boolean", "value": True},
            weatherUnits={"type": "string", "value": "F"},
            weatherLocation={"type": "string", "value": raw if raw is not None else
                '{"zip":"10001","city":"New York","region":"NY","latitude":40.75,"longitude":-73.99}'})
        return document

    def test_configured_weather_export_and_local_snapshot_roundtrip(self):
        self.document = self.weather_document()
        result = backup.export_backup(self.args, self.fake_adb)
        self.assertTrue(result["readback_verified"])
        snapshot = backup.read_bounded(Path(self.args.output))
        self.assertEqual(snapshot, backup.validate(snapshot, "snapshot", self.args))
        self.args.input = self.args.output
        self.assertEqual(result["sha256"], backup.verify_backup(self.args)["sha256"])
        # PRETTBK1 wraps the exact four-store local snapshot payload.
        import struct
        size = struct.unpack(">I", snapshot[20:24])[0]
        self.assertEqual(self.document, json.loads(base64.b64decode(snapshot[24:24 + size])))

    def test_configured_weather_rejects_malformed_json(self):
        raw = self.weather_document()["home"]["weatherLocation"]["value"]
        for label, invalid in (
            ("duplicate", raw[:-1] + ',"zip":"10001"}'),
            ("wrong-number-type", raw.replace('40.75', '"40.75"')),
            ("wrong-string-type", raw.replace('"10001"', '10001')),
            ("trailing", raw + '{}'),
            ("truncated", raw[:-1]),
            ("lenient-syntax", raw.replace('"zip"', 'zip')),
        ):
            with self.subTest(case=label), self.assertRaises(ValueError):
                backup.validate(self.encoded(self.weather_document(invalid)), "diagnostic", self.args)

    def test_shared_validator_rejects_missing_store_unknown_field_and_version(self):
        for mutate in (
            lambda doc: doc.pop("apps"), lambda doc: doc.update(version=2),
            lambda doc: doc["home"].update(accountToken={"type": "string", "value": "not-a-secret"}),
            lambda doc: doc["home"].update(historySource={"type": "string", "value": "UNKNOWN"}),
        ):
            document = json.loads(json.dumps(self.document))
            mutate(document)
            with self.assertRaises(ValueError):
                backup.validate(self.encoded(document), "diagnostic", self.args)

    def test_create_new_will_not_overwrite_snapshot_or_manifest(self):
        output = Path(self.args.output)
        output.write_bytes(b"keep")
        with self.assertRaises(ValueError):
            backup.export_backup(self.args, self.fake_adb)
        self.assertEqual(b"keep", output.read_bytes())
        self.assertEqual([], self.calls)
        output.unlink()
        Path(str(output) + ".manifest.json").write_bytes(b"keep-manifest")
        with self.assertRaises(ValueError):
            backup.export_backup(self.args, self.fake_adb)
        self.assertFalse(output.exists())

    def test_identity_mismatch_never_exports_or_creates_output(self):
        def wrong(command, limit, scratch, input_bytes=None):
            return b"wrong-device\n"
        with self.assertRaises(ValueError):
            backup.export_backup(self.args, wrong)
        self.assertFalse(Path(self.args.output).exists())

    def test_identity_change_after_capture_never_publishes(self):
        count = 0
        def changed(command, limit, scratch, input_bytes=None):
            nonlocal count
            if "getprop" in command:
                count += 1
                if count > 4:
                    return b"different\n"
            return self.fake_adb(command, limit, scratch, input_bytes)
        with self.assertRaises(ValueError):
            backup.export_backup(self.args, changed)
        self.assertFalse(Path(self.args.output).exists())

    def test_duplicate_busy_malformed_or_oversized_diagnostic_fails(self):
        for raw in (b"PRETTTV_SETTINGS_EXPORT_BUSY", b"PRETTTV_SETTINGS=!?",
                    b"PRETTTV_SETTINGS=AAAA\nPRETTTV_SETTINGS=AAAA\n",
                    b"PRETTTV_SETTINGS=" + b"A" * (backup.MAX_PAYLOAD + 1)):
            with self.assertRaises(ValueError):
                backup.diagnostic_payload(raw)

    def test_subprocess_output_bound_timeout_and_nonzero_exit(self):
        for code, limit, timeout in (("print('A'*4096)", 32, 5),
                                     ("import time;time.sleep(10)", 32, .15),
                                     ("raise SystemExit(2)", 32, 5)):
            with self.assertRaises(ValueError):
                backup.run_bounded([sys.executable, "-c", code], limit, self.folder, timeout=timeout)

    def test_manifest_or_snapshot_tampering_is_rejected(self):
        backup.export_backup(self.args, self.fake_adb)
        self.args.input = self.args.output
        sidecar = Path(self.args.output + ".manifest.json")
        meta = json.loads(sidecar.read_text())
        meta["sha256"] = "0" * 64
        sidecar.write_text(json.dumps(meta))
        with self.assertRaises(ValueError):
            backup.verify_backup(self.args)
        data = bytearray(Path(self.args.output).read_bytes())
        data[30] ^= 1
        Path(self.args.output).write_bytes(data)
        with self.assertRaises(ValueError):
            backup.verify_backup(self.args)

    def test_readback_mismatch_never_writes_success_manifest(self):
        with self.assertRaises(ValueError):
            backup.create_verified(Path(self.args.output), b"fixture", {}, lambda _: b"different")
        self.assertEqual(b"", Path(self.args.output + ".manifest.json").read_bytes())

    def test_explicit_selector_and_e_drive_fail_closed(self):
        self.args.serial = "-d"
        with self.assertRaises(ValueError):
            backup.adb_prefix(self.args)
        with self.assertRaises(ValueError):
            backup.e_path("C:/backup.snapshot")
        with self.assertRaises(SystemExit):
            backup.parser().parse_args(["export"])


if __name__ == "__main__":
    os.environ["PYTHONDONTWRITEBYTECODE"] = "1"
    unittest.main(verbosity=2)
