"""Offline two-source Kotlin/JUnit checks; never Gradle, ADB or downloads."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--label', default='green')
parser.add_argument('--source', type=Path, help='Optional isolated negative-control source')
args = parser.parse_args()
if not args.label.replace('-', '').replace('_', '').isalnum():
    parser.error('label must be alphanumeric with dashes/underscores')
repo = Path(__file__).resolve().parents[2]
root = Path('E:/Development/tcl-tv-tools')
out = root / 'snapshots/home-control-2026-11-11/discover-rotation' / args.label
out.mkdir(parents=True, exist_ok=True)
cache = root / 'gradle-cache/caches/modules-2/files-2.1'


def jar(pattern):
    matches = sorted(cache.rglob(pattern))
    if not matches:
        raise RuntimeError('Missing cached tool: ' + pattern)
    return matches[0]


compiler = [jar(p) for p in [
    'kotlin-compiler-embeddable-2.1.0.jar', 'kotlin-stdlib-2.1.0.jar',
    'kotlin-script-runtime-2.1.0.jar', 'kotlin-reflect-1.6.10.jar',
    'trove4j-*.jar', 'kotlinx-coroutines-core-jvm-1.6.4.jar', 'annotations-13.0.jar',
]]
testcp = [jar(p) for p in [
    'kotlin-stdlib-2.1.0.jar', 'annotations-13.0.jar', 'junit-4.13.2.jar', 'hamcrest-core-1.3.jar',
]]
java = root / 'jdk/jdk-17.0.20.1+1/bin/java.exe'
source = args.source or repo / 'launcher/app/src/main/kotlin/fi/jommi/rivi/DiscoverRotation.kt'
test = repo / 'launcher/app/src/test/kotlin/fi/jommi/rivi/DiscoverRotationTest.kt'
for path in (source, test):
    shutil.copy2(path, out / path.name)
classes = out / 'classes'
if classes.exists():
    shutil.rmtree(classes)
classes.mkdir()
env = os.environ.copy()
env.update(TEMP=str(out), TMP=str(out), TMPDIR=str(out))


def cp(paths):
    return os.pathsep.join(map(str, paths))


def run(name, command):
    result = subprocess.run(command, cwd=repo, env=env, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, check=False, timeout=120)
    (out / (name + '.log')).write_bytes(result.stdout)
    print(result.stdout.decode('utf-8', 'replace'), flush=True)
    return result.returncode


compile_code = run('compile', [str(java), '-Djava.io.tmpdir=' + str(out), '-cp', cp(compiler),
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
    '-classpath', cp(testcp), '-d', str(classes), str(source), str(test)])
test_code = None
if compile_code == 0:
    test_code = run('tests', [str(java), '-Djava.io.tmpdir=' + str(out), '-cp', cp([classes, *testcp]),
                            'org.junit.runner.JUnitCore', 'fi.jommi.rivi.DiscoverRotationTest'])
summary = {'label': args.label, 'compile_exit': compile_code, 'test_exit': test_code,
           'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
           'test_sha256': hashlib.sha256(test.read_bytes()).hexdigest(),
           'scope': 'Two pure Kotlin sources, cached compiler/JUnit only; no Android/Gradle/network.'}
(out / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
print(json.dumps(summary, indent=2))
raise SystemExit(compile_code or test_code or 0)
