"""Offline scoped Kotlin history checks: no Gradle, network, APK or device."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("--label", default="green")
parser.add_argument("--repository", action="store_true", help="Also compile ContentRepository/model")
parser.add_argument("--negative-control", choices=("hero-fallback", "provider-source", "match-metadata", "observed-metadata", "session-duration"),
                    help="Mutate an isolated source copy; the JUnit run MUST fail")
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
tools = Path("E:/Development/tcl-tv-tools")
out = tools / "snapshots/home-control-2026-11-11/history" / args.label
cache = tools / "gradle-cache/caches/modules-2/files-2.1"
assert repo.drive.upper() == "E:" and tools.is_dir()
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
classpath = [jar("kotlin-stdlib-2.1.0.jar"), jar("annotations-13.0.jar"),
             jar("junit-4.13.2.jar"), jar("hamcrest-core-1.3.jar"), json_jar, existing, android]
source = repo / "launcher/app/src/main/kotlin/fi/jommi/rivi"
files = [source / name for name in (
    "PlaybackHistory.kt", "ViewingHistoryMode.kt", "ProviderPlaybackPolicy.kt", "SettingsBackupGuard.kt", "PlaybackTracker.kt",
    "PersonalizationStore.kt", "PersonalizationPolicy.kt", "LocalSettingsTransfer.kt",
)]
if args.repository:
    files.extend(source / name for name in ("ContentRepository.kt", "ContentRefreshState.kt", "ContentModel.kt", "OfflineSnapshotStore.kt"))
if args.negative_control:
    assert args.repository, "Negative controls require --repository"
    name, old, new = {
        "hero-fallback": ("ContentModel.kt", "if(viewingHistoryMode==ViewingHistoryMode.COMBINED)", "if(true)"),
        "provider-source": ("PlaybackHistory.kt", "if(mode==ViewingHistoryMode.COMBINED)result", "if(true)result"),
        "match-metadata": ("PlaybackHistory.kt", "return titleOnly(matching.first())", "return matching.first()"),
        "observed-metadata": ("PlaybackHistory.kt", "recordLocal(titleOnly(p),position,duration,eventAt,settingsGeneration)",
                              "recordLocal(p,position,duration,eventAt,settingsGeneration)"),
        "session-duration": ("PlaybackTracker.kt", "duration?.takeIf { it>0 } ?: 0L", "duration?.takeIf { it>0 } ?: p.duration"),
    }[args.negative_control]
    original = source / name
    text = original.read_text(encoding="utf-8")
    assert text.count(old) == 1, "Negative-control seam changed"
    copy = out / name
    copy.write_text(text.replace(old, new), encoding="utf-8")
    files[files.index(original)] = copy
    print("NEGATIVE CONTROL:", args.negative_control, "(isolated copy only)")
tests = ["ViewingHistoryModeTest", "PlaybackPolicyTest", "ProviderPlaybackPolicyTest"]
if args.repository:
    tests.append("ViewingHistoryRepositoryTest")
files.extend(repo / "launcher/app/src/test/kotlin/fi/jommi/rivi" / (name + ".kt") for name in tests)
(out / "inputs.json").write_text(json.dumps({
    "sources_sha256": {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in files},
    "unchanged_dependency_jar": str(existing),
    "unchanged_dependency_sha256": hashlib.sha256(existing.read_bytes()).hexdigest(),
    "negative_control": args.negative_control,
}, indent=2), encoding="utf-8")
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


run([str(java), "-Djava.io.tmpdir=" + str(out), "-cp", cp(compiler),
     "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
     "-jvm-target", "17", "-classpath", cp(classpath),
     "-Xfriend-paths=" + str(existing), "-d", str(classes), *map(str, files)], "compile.log")
run([str(java), "-Djava.io.tmpdir=" + str(out), "-cp", cp([classes, *classpath]),
     "org.junit.runner.JUnitCore", *("fi.jommi.rivi." + name for name in tests)], "tests.log")
print("Host JVM only; no Android media sessions, UI, provider IPC or full Gradle build.")
print("Evidence:", out)
