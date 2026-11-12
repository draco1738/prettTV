"""Offline scoped Kotlin compile/JUnit checks. No Gradle, network, APK or device.

Requires the already-cached E: JDK/compiler/Android/JUnit/JSON jars and unmodified
release classes from the sibling luma-tv checkout. Those classes supply existing
validators/styles only; all changed production sources are compiled from this tree.
"""
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

repo = Path(__file__).resolve().parents[2]
tools = Path("E:/Development/tcl-tv-tools")
out = Path(os.environ.get("PORTABLE_BACKUP_EVIDENCE", str(tools / "snapshots/home-control-2026-11-11/portable-backup/host")))
cache = tools / "gradle-cache/caches/modules-2/files-2.1"
assert repo.drive.upper() == "E:" and out.resolve().drive.upper() == "E:" and tools.is_dir(), "All files must stay on E:"
out.mkdir(parents=True, exist_ok=True)


def jar(pattern):
    rows = sorted(cache.rglob(pattern))
    if not rows:
        raise RuntimeError("Missing cached jar (no download attempted): " + pattern)
    return rows[0]


compiler = [jar(pattern) for pattern in (
    "kotlin-compiler-embeddable-2.1.0.jar", "kotlin-stdlib-2.1.0.jar",
    "kotlin-script-runtime-2.1.0.jar", "kotlin-reflect-1.6.10.jar",
    "trove4j-*.jar", "kotlinx-coroutines-core-jvm-1.6.4.jar", "annotations-13.0.jar",
)]
java = tools / "jdk/jdk-17.0.20.1+1/bin/java.exe"
android = tools / "android-sdk/platforms/android-34/android.jar"
existing = tools / "luma-tv/launcher/app/build/intermediates/runtime_app_classes_jar/release/bundleReleaseClassesToRuntimeJar/classes.jar"
assert existing.is_file(), "Existing unchanged dependency classes required"
json_jar = jar("json-20240303.jar")
assert hashlib.sha256(json_jar.read_bytes()).hexdigest() == "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"
# SDK android.util.JsonReader is a throwing JVM stub. Replace only that host API
# with a pinned parser adapter; production validators and Android dependencies stay unchanged.
gson = jar("gson-2.10.1.jar")
assert hashlib.sha256(gson.read_bytes()).hexdigest() == "4241c14a7727c34feea6507ec801318a3d4a90f070e4525681079fb94ee4c593"
host_runtime = out / "host-runtime-classes"
host_sources = sorted((repo / "tests/settings-backup/host-runtime").rglob("*.java"))
classpath = [host_runtime, gson, jar("kotlin-stdlib-2.1.0.jar"), jar("annotations-13.0.jar"),
             jar("junit-4.13.2.jar"), jar("hamcrest-core-1.3.jar"), json_jar, existing, android]
source = repo / "launcher/app/src/main/kotlin/fi/jommi/rivi"
files = [source / name for name in (
    "LocalSettingsTransfer.kt", "SettingsBackupGuard.kt", "LocalSettingsBackup.kt", "PortableSettingsBackup.kt",
    "SettingsBackupActivity.kt", "HomeInterfaceSize.kt", "AppReadability.kt",
)]
files.append(repo / "launcher/app/src/test/kotlin/fi/jommi/rivi/SettingsBackupChecks.kt")
files.append(repo / "launcher/app/src/androidTest/kotlin/fi/jommi/rivi/SettingsBackupAndroidChecks.kt")
files.append(repo / "launcher/app/src/test/kotlin/fi/jommi/rivi/PortableSettingsBackupChecks.kt")
files.append(repo / "launcher/app/src/androidTest/kotlin/fi/jommi/rivi/PortableSettingsBackupAndroidChecks.kt")
files.append(repo / "launcher/app/src/stability/kotlin/fi/jommi/rivi/PortableSettingsBackupTestProvider.kt")
classes = out / "classes"
if classes.exists():
    shutil.rmtree(classes)
classes.mkdir()
env = os.environ.copy()
env.update(TEMP=str(out), TMP=str(out), TMPDIR=str(out), PYTHONDONTWRITEBYTECODE="1")


def cp(paths):
    return ";".join(map(str, paths))


def run(command, log):
    result = subprocess.run(command, cwd=repo, env=env, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=180, check=False)
    (out / log).write_bytes(result.stdout)
    print(result.stdout.decode("utf-8", "replace"))
    if result.returncode:
        raise SystemExit(result.returncode)


if host_runtime.exists():
    shutil.rmtree(host_runtime)
host_runtime.mkdir()
run([str(java.with_name("javac.exe")), "--release", "17", "-cp", str(gson),
     "-d", str(host_runtime), *map(str, host_sources)], "host-runtime-compile.log")
run([str(java), "-Djava.io.tmpdir=" + str(out), "-cp", cp(compiler),
     "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
     "-jvm-target", "17", "-classpath", cp(classpath),
     "-Xfriend-paths=" + str(existing), "-d", str(classes), *map(str, files)], "compile.log")
run([str(java), "-Djava.io.tmpdir=" + str(out), "-cp", cp([classes, *classpath]),
     "org.junit.runner.JUnitCore", "fi.jommi.rivi.SettingsBackupChecks", "fi.jommi.rivi.PortableSettingsBackupChecks"], "tests.log")
(out / "validator-classpath.txt").write_text(cp([classes, *classpath]), encoding="utf-8")
(out / "source-sha256.json").write_text(json.dumps({str(path.relative_to(repo)): hashlib.sha256(path.read_bytes()).hexdigest()
    for path in [*files, *host_sources, Path(__file__), repo / "tests/settings-backup/test_portable_pc.py"]}, indent=2, sort_keys=True), encoding="utf-8")
env["PORTABLE_BACKUP_VALIDATOR_CLASSPATH_FILE"] = str(out / "validator-classpath.txt")
run([sys.executable, "-B", str(repo / "tests/settings-backup/test_portable_pc.py")], "pc-tests.log")
print("Scoped compile includes Activity; JVM tests do not render Android Views or prove device filesystem behavior.")
print("Evidence:", out)
