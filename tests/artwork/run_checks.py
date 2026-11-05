"""Scoped Kotlin checks, no Gradle, APK, signing, device or app launch.
Uses existing E: compiler/Android jars; JSON-java is an isolated JVM test runtime only.
"""
import hashlib
import os
import subprocess
import sys
import urllib.request
from pathlib import Path

repo=Path(__file__).resolve().parents[2]
root=repo.parent
out=root/'snapshots/balanced-artwork-2026-11-05/checks'
out.mkdir(parents=True,exist_ok=True)
cache=root/'gradle-cache/caches/modules-2/files-2.1'
def jar(pattern):
    rows=sorted(cache.rglob(pattern))
    if not rows: raise RuntimeError('Missing cached tool '+pattern)
    return rows[0]
compiler=[jar('kotlin-compiler-embeddable-2.1.0.jar'),jar('kotlin-stdlib-2.1.0.jar'),jar('kotlin-script-runtime-2.1.0.jar'),jar('kotlin-reflect-1.6.10.jar'),jar('trove4j-*.jar'),jar('kotlinx-coroutines-core-jvm-1.6.4.jar'),jar('annotations-13.0.jar')]
jsonjar=out/'json-20240303.jar'
if not jsonjar.exists():
    urllib.request.urlretrieve('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',jsonjar)
digest=hashlib.sha256(jsonjar.read_bytes()).hexdigest()
assert digest=='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed', 'Unexpected JSON test runtime bytes'
print('Test-only JSON runtime SHA256:',digest)
java=root/'jdk/jdk-17.0.20.1+1/bin/java.exe'
android=root/'android-sdk/platforms/android-34/android.jar'
existing=repo/'launcher/app/build/intermediates/compile_app_classes_jar/release/bundleReleaseClassesToCompileJar/classes.jar'
assert existing.exists(), 'Existing release compile classes required for unchanged dependencies (no build is run)'
testcp=[jar('kotlin-stdlib-2.1.0.jar'),jar('annotations-13.0.jar'),jar('junit-4.13.2.jar'),jar('hamcrest-core-1.3.jar'),jsonjar,existing,android]
source=repo/'launcher/app/src/main/kotlin/fi/jommi/rivi'
owned=['ContentModel.kt','ContentRepository.kt','RecommendationPolicy.kt','BalancedArtworkPolicy.kt','TvmazeArtworkParser.kt','PresentationArtworkResolver.kt']
files=[source/name for name in owned if (source/name).exists()]
files.append(repo/'launcher/app/src/test/kotlin/fi/jommi/rivi/PresentationArtworkChecks.kt')
files.append(Path(__file__).parent/'PresentationArtworkEvidence.kt')
classes=out/'classes'; classes.mkdir(exist_ok=True)
cp=lambda paths: ';'.join(map(str,paths))
env=os.environ.copy(); env.update(TEMP=str(out),TMP=str(out))
cmd=[str(java),'-Djava.io.tmpdir='+str(out),'-cp',cp(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp(testcp),'-Xfriend-paths='+str(existing),'-d',str(classes),*map(str,files)]
result=subprocess.run(cmd,env=env,cwd=repo,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=False)
(out/'compile.log').write_bytes(result.stdout)
print(result.stdout.decode('utf-8','replace'))
if result.returncode: raise SystemExit(result.returncode)
result=subprocess.run([str(java),'-Djava.io.tmpdir='+str(out),'-cp',cp([classes,*testcp]),'org.junit.runner.JUnitCore','fi.jommi.rivi.PresentationArtworkChecks'],env=env,cwd=repo,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=False)
(out/'tests.log').write_bytes(result.stdout)
print(result.stdout.decode('utf-8','replace'))
if result.returncode==0 and '--evidence' in sys.argv:
    result=subprocess.run([str(java),'-Djava.io.tmpdir='+str(out),'-cp',cp([classes,*testcp]),'fi.jommi.rivi.PresentationArtworkEvidenceKt',str(out.parent)],env=env,cwd=repo,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=False)
    (out/'public-replay.log').write_bytes(result.stdout)
    print(result.stdout.decode('utf-8','replace'))
raise SystemExit(result.returncode)
