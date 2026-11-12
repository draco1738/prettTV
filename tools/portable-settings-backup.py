#!/usr/bin/env python3
"""Explicit read-only ADB export -> shared Kotlin validation -> create-new E: snapshot.

No device restore command, launch, install, connection change or credential collection.
Requires the offline validator classpath produced by tests/settings-backup/run_checks.py.
"""
import argparse
import contextlib
import hashlib
import json
import os
import re
import subprocess
import tempfile
import threading
from datetime import datetime, timezone
from pathlib import Path

MAX_PAYLOAD = 16 * 1024 * 1024
MAX_FILE = MAX_PAYLOAD + 56
MAX_DUMP = MAX_PAYLOAD + 64 * 1024
PROPERTIES = {
    "manufacturer": "ro.product.manufacturer",
    "model": "ro.product.model",
    "device": "ro.product.device",
    "hardware_serial": "ro.serialno",
}
VALIDATOR = "fi.jommi.rivi.PortableSettingsBackupValidator"


def e_path(value):
    path = Path(value).resolve()
    if path.drive.upper() != "E:":
        raise ValueError("Backup, scratch and tool paths must resolve to E:")
    return path


def run_bounded(command, limit, scratch, input_bytes=None, timeout=45):
    """Bound child output while reading; timer kills a child blocked on stdout or input."""
    with contextlib.ExitStack() as stack:
        source = subprocess.DEVNULL
        if input_bytes is not None:
            source = stack.enter_context(tempfile.TemporaryFile(dir=scratch))
            source.write(input_bytes)
            source.seek(0)
        process = subprocess.Popen(command, stdin=source, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, shell=False)
        expired = threading.Event()

        def expire():
            expired.set()
            try:
                process.kill()
            except OSError:
                pass

        timer = threading.Timer(timeout, expire)
        timer.daemon = True
        timer.start()
        output = bytearray()
        try:
            while True:
                chunk = process.stdout.read(min(8192, limit - len(output) + 1))
                if not chunk:
                    break
                output.extend(chunk)
                if len(output) > limit:
                    raise ValueError("Command output exceeded the bounded export limit")
            code = process.wait(timeout=5)
            if expired.is_set() or code:
                raise ValueError("Command failed or timed out; no verified backup produced")
            return bytes(output)
        finally:
            timer.cancel()
            if process.poll() is None:
                process.kill()
            process.wait(timeout=5)
            process.stdout.close()


def read_bounded(path, limit=MAX_FILE):
    with path.open("rb") as source:
        data = source.read(limit + 1)
    if not data or len(data) > limit:
        raise ValueError("File empty or exceeds the bounded snapshot limit")
    return data


def validate(data, mode, args, run=run_bounded):
    java = e_path(args.java)
    if not java.is_file():
        raise ValueError("Explicit Java executable does not exist")
    # No downloads or classpath guessing. Reject missing/out-of-drive dependencies.
    paths = args.validator_classpath.split(os.pathsep)
    if not paths or any(not path or not e_path(path).exists() for path in paths):
        raise ValueError("Shared Kotlin validator classpath is incomplete")
    return run([str(java), "-Djava.io.tmpdir=" + str(e_path(args.scratch)),
                "-cp", args.validator_classpath, VALIDATOR, mode], MAX_FILE,
               e_path(args.scratch), input_bytes=data)


def adb_prefix(args):
    adb = e_path(args.adb)
    if not adb.is_file() or not 1 <= args.port <= 65535:
        raise ValueError("Explicit ADB executable and valid server port required")
    if not re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.:\[\]-]*", args.serial):
        raise ValueError("Invalid explicit ADB serial")
    return [str(adb), "-P", str(args.port), "-s", args.serial]


def check_identity(args, run=run_bounded):
    prefix = adb_prefix(args)
    expected = {key: getattr(args, "expected_" + key) for key in PROPERTIES}
    if any(not value or value != value.strip() for value in expected.values()):
        raise ValueError("Every expected device identity field must be explicit and exact")
    observed = {}
    for key, prop in PROPERTIES.items():
        observed[key] = run(prefix + ["shell", "getprop", prop], 4096,
                            e_path(args.scratch)).decode("utf-8", "strict").strip()
    if observed != expected:
        raise ValueError("Device identity mismatch; export refused")
    return observed


def diagnostic_payload(raw):
    matches = re.findall(rb"(?m)^[ \t]*PRETTTV_SETTINGS=([A-Za-z0-9+/=]+)\r?$", raw)
    if len(matches) != 1 or len(matches[0]) > MAX_PAYLOAD:
        raise ValueError("Expected exactly one bounded settings export; Home may be absent or busy")
    return matches[0]


def create_verified(output, payload, manifest, validator):
    """Create-new only. Read back both exact targets; failures never count as success."""
    sidecar = Path(str(output) + ".manifest.json")
    # Open both exclusively before writing. Do not delete on failure: leave evidence,
    # and never remove a target another process could have replaced.
    with output.open("xb") as target, sidecar.open("xb") as meta:
        target.write(payload)
        target.flush()
        os.fsync(target.fileno())
        actual = read_bounded(output)
        if actual != payload or validator(actual) != payload:
            raise ValueError("Saved file readback or shared schema validation failed")
        manifest = dict(manifest, bytes=len(actual), sha256=hashlib.sha256(actual).hexdigest(),
                        format="prettTV-PRETTBK1-v1", readback_verified=True,
                        encrypted=False, credentials_included=False)
        text = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("utf-8")
        meta.write(text)
        meta.flush()
        os.fsync(meta.fileno())
        if read_bounded(sidecar, 64 * 1024) != text:
            raise ValueError("Checksum manifest readback failed")
    return manifest


def export_backup(args, run=run_bounded):
    output = e_path(args.output)
    scratch = e_path(args.scratch)
    if not output.parent.is_dir() or not scratch.is_dir():
        raise ValueError("Explicit E: output parent and scratch directories must exist")
    if output.exists() or Path(str(output) + ".manifest.json").exists():
        raise ValueError("Output already exists; create-new backup will not overwrite it")
    identity = check_identity(args, run)
    raw = run(adb_prefix(args) + ["shell", "dumpsys", "activity",
                                  "dev.zane.pretttv/fi.jommi.rivi.MainActivity", "--export-local-settings"],
              MAX_DUMP, scratch)
    payload = validate(diagnostic_payload(raw), "diagnostic", args, run)
    if check_identity(args, run) != identity:
        raise ValueError("Device identity changed during export")
    return create_verified(output, payload, {
        "captured_utc": datetime.now(timezone.utc).isoformat(),
        "source": "explicit-authorized-diagnostic-export",
        "device": identity, "adb_serial": args.serial, "adb_server_port": args.port,
        "restore": "TV Backup > Import portable snapshot > review > explicit Restore; never file-open auto-restore",
    }, lambda data: validate(data, "snapshot", args, run))


def verify_backup(args, run=run_bounded):
    path = e_path(args.input)
    data = read_bounded(path)
    if validate(data, "snapshot", args, run) != data:
        raise ValueError("Shared validator did not return identical snapshot bytes")
    manifest = json.loads(read_bounded(Path(str(path) + ".manifest.json"), 64 * 1024))
    if (manifest.get("format") != "prettTV-PRETTBK1-v1"
            or manifest.get("bytes") != len(data)
            or manifest.get("sha256") != hashlib.sha256(data).hexdigest()):
        raise ValueError("Checksum manifest mismatch")
    return {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "verified": True}


def parser():
    root = argparse.ArgumentParser(description=__doc__)
    commands = root.add_subparsers(dest="command", required=True)
    for name in ("export", "verify"):
        command = commands.add_parser(name)
        command.add_argument("--java", required=True)
        command.add_argument("--validator-classpath", required=True)
        command.add_argument("--scratch", required=True)
        if name == "verify":
            command.add_argument("--input", required=True)
        else:
            command.add_argument("--adb", required=True)
            command.add_argument("--port", type=int, required=True)
            command.add_argument("--serial", required=True)
            for key in PROPERTIES:
                command.add_argument("--expected-" + key.replace("_", "-"), required=True)
            command.add_argument("--output", required=True)
    return root


def main():
    args = parser().parse_args()
    try:
        result = export_backup(args) if args.command == "export" else verify_backup(args)
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (OSError, ValueError, subprocess.SubprocessError):
        # No exception data or raw dumps: they may contain private viewing data.
        print("Backup/verification failed. No restore was attempted. Check explicit identity, paths, shared validator and file integrity; never rely on a partial output.")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
