"""Offline scoped Kotlin/Android-API compilation; NOT an APK build or native test run.
No Gradle, ADB, downloads, generated Android stubs or production checkout artifacts.
Requires the existing E: tool cache. All outputs and Java temp files stay on E:.
"""
import hashlib
import os
import subprocess
import sys
from pathlib import Path
from tempfile import TemporaryDirectory

REPO = Path(__file__).resolve().parents[2]
TOOLS = Path("E:/Development/tcl-tv-tools")
OUT = TOOLS / "snapshots/readability-startup-reliability/scoped"
CACHE = TOOLS / "gradle-cache/caches/modules-2/files-2.1"


def jar(pattern):
    matches = sorted(CACHE.rglob(pattern))
    if not matches:
        raise RuntimeError(f"Missing cached jar: {pattern}; this harness never downloads")
    return matches[0]


def main():
    if not TOOLS.is_dir() or REPO.drive.lower() != "e:":
        raise RuntimeError("E: repository/tool storage is required")
    OUT.mkdir(parents=True, exist_ok=True)
    java = TOOLS / "jdk/jdk-17.0.20.1+1/bin/java.exe"
    android = TOOLS / "android-sdk/platforms/android-34/android.jar"
    compiler = [jar(p) for p in (
        "kotlin-compiler-embeddable-2.1.0.jar", "kotlin-stdlib-2.1.0.jar",
        "kotlin-script-runtime-2.1.0.jar", "kotlin-reflect-1.6.10.jar", "trove4j-*.jar",
        "kotlinx-coroutines-core-jvm-1.6.4.jar", "annotations-13.0.jar")]
    cp = [android, jar("kotlin-stdlib-2.1.0.jar"), jar("annotations-13.0.jar")]
    jvm_checks = "--jvm-checks" in sys.argv
    if jvm_checks:
        cp += [jar("junit-4.13.2.jar"), jar("hamcrest-core-1.3.jar")]
    names = ["ContentRepository", "ContentModel", "ContentRefreshState", "ProgramFeedParser",
             "PresentationArtworkResolver", "TvmazeArtworkParser", "BalancedArtworkPolicy",
             "RecommendationPolicy", "CatalogSnapshot", "SharedFeedSnapshot", "OfflineSnapshotStore",
             "EpisodeCatalog", "SportsCatalog", "SportsModel", "PersonalizationStore", "PersonalizationPolicy",
             "PlaybackHistory", "ProviderPlaybackPolicy", "ProgramPresentation", "SettingsBackupGuard"]
    source = REPO / "launcher/app/src/main/kotlin/fi/jommi/rivi"
    sources = [source / f"{name}.kt" for name in names]
    sources += [REPO / "launcher/app/src/stability/kotlin/fi/jommi/rivi/CardFocusTestActivity.kt"]
    sources += list((REPO / "launcher/app/src/androidTest/kotlin/fi/jommi/rivi").glob("StartupReliability*Checks.kt"))
    if jvm_checks:
        sources += [REPO / "launcher/app/src/test/kotlin/fi/jommi/rivi/PresentationArtworkChecks.kt"]
    classes = OUT / "classes"
    classes.mkdir(exist_ok=True)
    env = os.environ.copy()
    env.update(TEMP=str(OUT), TMP=str(OUT), TMPDIR=str(OUT))
    command = [str(java), f"-Djava.io.tmpdir={OUT}", "-cp", ";".join(map(str, compiler)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
        "-classpath", ";".join(map(str, cp)), "-d", str(classes), *map(str, sources)]
    result = subprocess.run(command, cwd=REPO, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace", check=False)
    (OUT / "compile.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    print(result.stdout + result.stderr)
    print(f"Scoped Android-API compile exit={result.returncode}; sources={len(sources)}; log={OUT / 'compile.log'}")
    print("This does not execute Android instrumentation or establish native transport timings.")
    if result.returncode == 0 and jvm_checks:
        json_jar = jar("json-20240303.jar")
        digest = hashlib.sha256(json_jar.read_bytes()).hexdigest()
        assert digest == "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"
        with TemporaryDirectory(prefix="jvm-fixtures-", dir=OUT) as temporary:
            result = subprocess.run([str(java), f"-Djava.io.tmpdir={temporary}", "-cp",
                ";".join(map(str, [classes, json_jar, *cp])), "org.junit.runner.JUnitCore",
                "fi.jommi.rivi.PresentationArtworkChecks"], cwd=REPO, env=env,
                capture_output=True, text=True, encoding="utf-8", errors="replace", check=False)
        (OUT / "jvm-checks.log").write_text(result.stdout + result.stderr, encoding="utf-8")
        print("Existing host-JVM artwork policy/resolver regressions (not Android evidence):")
        print(result.stdout + result.stderr)
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
