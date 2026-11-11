"""Offline sizing checks; no Gradle, device, installation, or network access.

Uses the existing E: toolchain. All compiler/classes/temp output is isolated in
launcher/app/build/readability-verification. Not a substitute for the native suite.
"""
from pathlib import Path
import os
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
TOOLS = Path("E:/Development/tcl-tv-tools")
OUT = ROOT / "launcher/app/build/readability-verification"
OUT.mkdir(parents=True, exist_ok=True)
TEMP = OUT / "temp"
TEMP.mkdir(exist_ok=True)
env = dict(os.environ, TMP=str(TEMP), TEMP=str(TEMP), TMPDIR=str(TEMP))
CACHE = TOOLS / "gradle-cache/caches/modules-2/files-2.1"


def jar(package):
    matches = sorted((CACHE / package).glob("**/*.jar"))
    if len(matches) != 1:
        raise RuntimeError(f"Expected one cached jar for {package}: {matches}")
    return str(matches[0])


stdlib = jar("org.jetbrains.kotlin/kotlin-stdlib/2.1.0")
annotations = jar("org.jetbrains/annotations/13.0")
compiler = [jar("org.jetbrains.kotlin/kotlin-compiler-embeddable/2.1.0"),
            stdlib, annotations, jar("org.jetbrains.kotlin/kotlin-script-runtime/2.1.0"),
            jar("org.jetbrains.intellij.deps/trove4j/1.0.20200330"),
            jar("org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.6.4")]
junit = jar("junit/junit/4.13.2")
hamcrest = jar("org.hamcrest/hamcrest-core/1.3")
android = str(TOOLS / "android-sdk/platforms/android-34/android.jar")
java = str(TOOLS / "jdk/jdk-17.0.20.1+1/bin/java.exe")
main = ROOT / "launcher/app/src/main/kotlin/fi/jommi/rivi"
test = ROOT / "launcher/app/src/test/kotlin/fi/jommi/rivi"
mode = sys.argv[1] if len(sys.argv) > 1 else "unit"
classes = OUT / mode
classes.mkdir(exist_ok=True)
classpath = [stdlib, annotations, android, junit, hamcrest]
if mode == "unit":
    sources = [main / "HomeInterfaceSize.kt", test / "AppReadabilitySizingTest.kt"]
    if (main / "AppReadability.kt").exists():
        sources.append(main / "AppReadability.kt")
elif mode == "compile":
    sources = sorted(main.glob("*.kt"))
    # Real AGP-produced resource/BuildConfig outputs from the parent's build, read only.
    parent = TOOLS / "luma-tv/launcher/app/build"
    r = parent / "intermediates/compile_and_runtime_not_namespaced_r_class_jar/release/processReleaseResources/R.jar"
    if not r.exists():
        raise RuntimeError("Parent's generated R.jar is required for source compilation")
    classpath.append(str(r))
    # This app does not reference BuildConfig; include it only if AGP generated it.
    sources += list(parent.glob("generated/source/buildConfig/release/**/BuildConfig.java"))
    native = ROOT / "launcher/app/src/androidTest/kotlin/fi/jommi/rivi/AppReadabilitySizingAndroidChecks.kt"
    if native.exists():
        sources += [native, ROOT / "launcher/app/src/stability/kotlin/fi/jommi/rivi/CardFocusTestActivity.kt"]
else:
    raise RuntimeError("Mode must be unit or compile")

args = ["-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", ";".join(classpath),
        "-d", str(classes), *map(str, sources)]
argfile = OUT / f"{mode}.args"
argfile.write_text("\n".join('"' + x.replace("\\", "/") + '"' for x in args), encoding="utf-8")
result = subprocess.run([java, f"-Djava.io.tmpdir={TEMP}", "-cp", ";".join(compiler),
                         "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "@" + str(argfile)],
                        env=env, cwd=ROOT, capture_output=True, text=True)
(OUT / f"{mode}-compile.log").write_text(result.stdout + result.stderr, encoding="utf-8")
if result.returncode:
    print(result.stdout + result.stderr)
else:
    warnings = sum(": warning:" in line for line in result.stderr.splitlines())
    print(f"Compiled {len(sources)} source files; {warnings} warnings (full compiler log retained).")
print(f"{mode} compiler exit={result.returncode}; output={OUT}")
if result.returncode:
    raise SystemExit(result.returncode)
if mode == "unit":
    result = subprocess.run([java, f"-Djava.io.tmpdir={TEMP}", "-cp", ";".join([str(classes), *classpath]),
                             "org.junit.runner.JUnitCore", "fi.jommi.rivi.AppReadabilitySizingTest"],
                            env=env, cwd=ROOT, capture_output=True, text=True)
    (OUT / "unit-results.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    print(result.stdout + result.stderr)
    raise SystemExit(result.returncode)
