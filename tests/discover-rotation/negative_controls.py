"""Prove selected safety regressions fail using isolated copies, never mutate live Kotlin."""
import json
import subprocess
import sys
from pathlib import Path

repo = Path(__file__).resolve().parents[2]
source = repo / 'launcher/app/src/main/kotlin/fi/jommi/rivi/DiscoverRotation.kt'
evidence = Path('E:/Development/tcl-tv-tools/snapshots/home-control-2026-11-11/discover-rotation')
original = source.read_text(encoding='utf-8')
controls = {
    'negative-repeat-seen': (
        'eligible.asSequence().filter { it !in seen }.take(batchSize).toList()',
        'eligible.asSequence().take(batchSize).toList()'),
    'negative-undo-resurrection': (
        'for(index in past.indices)past[index]=past[index].copy(keys=past[index].keys.filter { it in eligible })',
        '// Negative control: retain removed identities in old Undo entries.'),
    'negative-background-churn': (
        'if(!populated && eligible.isNotEmpty()) {',
        'if(eligible.isNotEmpty()) {'),
}
for label, (old, new) in controls.items():
    assert original.count(old) == 1, label
    path = evidence / 'controls' / label / 'DiscoverRotation.kt'
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(original.replace(old, new), encoding='utf-8')
    result = subprocess.run([sys.executable, str(Path(__file__).with_name('run_checks.py')),
                             '--label', label, '--source', str(path)], cwd=repo, check=False,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
    summary = json.loads((evidence / label / 'summary.json').read_text(encoding='utf-8'))
    tests = (evidence / label / 'tests.log').read_text(encoding='utf-8')
    print(label + ': ' + next(line for line in tests.splitlines() if line.startswith('Tests run:')))
    assert result.returncode != 0 and summary['compile_exit'] == 0 and summary['test_exit'] == 1, summary
    assert 'FAILURES!!!' in tests, tests
assert source.read_text(encoding='utf-8') == original, 'Live source changed during controls'
print('All isolated negative controls compiled and failed assertions; live source unchanged.')
