# Portable settings backup

Portable snapshots preserve prettTV's **four app-owned preference stores**: Home,
app arrangement, local playback, and personalization (including Watchlist). They
exclude Android settings, other apps' private data, helper configuration, signing
keys and account/credential stores. Files contain private viewing records and may
include local launch destinations. **They are not encrypted.** SHA-256 detects
corruption, not malicious replacement or authenticity; restore only your own
trusted backup. Do not publish snapshots, diagnostic exports or manifests.

A copy retained on a separate PC's **E:** drive survives uninstalling prettTV.
The TV's private saved/pre-restore files and app-specific staging file **do not**
survive uninstall or clear-data. Keep the PC original, not just the TV staging copy.

## TV controls and firmware limits

- **Export portable snapshot…** captures/validates current settings on the bounded
  worker before opening the system `ACTION_CREATE_DOCUMENT` chooser. It does not
  replace either private recovery slot. A completed write is called verified only
  after closing it and reading back identical bytes. Readback unavailable and
  mismatching readback have separate, explicit NOT verified messages.
- **Import portable snapshot…** uses `ACTION_OPEN_DOCUMENT`. Content bytes are read
  with a streaming size cap on the worker, then fully checksummed and schema
  validated before showing a readable preview. Selecting a file never restores it.
- Buttons require a resolvable system chooser. The known TCL
  `com.android.tv.frameworkpackagestubs` placeholder is explicitly rejected.
  The parent's read-only probe found only `DocumentsStub` handlers and no mounted
  USB public volume. This is **not USB support evidence**. No storage permission,
  network server, custom file browser or package installation is added.
- **Review PC copy** is always available once writer integration is enabled. It
  reads only `getExternalFilesDir(null)/portable-settings-import.snapshot`.
  It accepts no filename, restore flag or URI from an incoming intent.
- Preview includes creation time, format/checksum status, layout, interface size,
  history source, Watchlist/follow counts, hidden-title/playback counts, four store
  sizes and SHA-256. Parsing and hashing occur on the worker, not the UI thread.
- **Cancel is selected first.** A fresh press is required, Restore stays disabled
  for 1.2 seconds, and consent expires after two minutes. Pause/Back revokes it.
  The restored plan is exactly the immutable validated preview, even if a PC,
  provider or user changes the underlying file after preview.
- Chooser departure/result state is separate from consent and is not saved in a
  Bundle. A legitimate chooser result can return after Pause; an unexpected
  callback or a file-open intent cannot authorize a restore. Process recreation
  safely discards the pending operation and requires another explicit choice.

## PC export and verification

`tools/portable-settings-backup.py` uses the existing **read-only** authorized
`--export-local-settings` diagnostic. It does not install, launch, connect,
change settings, invoke diagnostic restore, or auto-import. Home must already
be present and able to export; absent/busy output is rejected.

All arguments are explicit: ADB executable, server port, serial, expected
manufacturer/model/device/hardware serial, output, Java executable and shared
validator classpath. Device properties are checked **before and after** capture.
Output is bounded while the child runs, with a timeout. The output and manifest
are **create-new**, synced, and read back. Existing files are never overwritten.
A failed attempt may leave partial files; they are not verified backups.

First prepare the offline validator using the existing E: toolchain:

```text
python tests/settings-backup/run_checks.py
```

No Gradle, network or ADB is used by that harness. It compiles changed production
sources plus Android-check sources, runs host regressions, and writes
`E:/Development/tcl-tv-tools/snapshots/home-control-2026-11-11/portable-backup/host/validator-classpath.txt`.
It uses the cached Kotlin/JDK/Android/JUnit/JSON jars and unchanged dependency
classes from the sibling production checkout. A complete release build remains
parent-owned. The harness records source SHA-256 hashes with its logs.

PowerShell usage (replace **every** identity placeholder with the independently
verified device identity; these are not guessed credentials):

```powershell
$cp = Get-Content -Raw 'E:/Development/tcl-tv-tools/snapshots/home-control-2026-11-11/portable-backup/host/validator-classpath.txt'
python tools/portable-settings-backup.py export `
  --adb 'E:/Development/tcl-tv-tools/platform-tools/adb.exe' `
  --port 5038 --serial '<verified-adb-serial>' `
  --expected-manufacturer '<exact-ro.product.manufacturer>' `
  --expected-model '<exact-ro.product.model>' `
  --expected-device '<exact-ro.product.device>' `
  --expected-hardware-serial '<exact-ro.serialno>' `
  --java 'E:/Development/tcl-tv-tools/jdk/jdk-17.0.20.1+1/bin/java.exe' `
  --validator-classpath $cp `
  --scratch 'E:/Development/tcl-tv-tools/snapshots/home-control-2026-11-11/portable-backup' `
  --output 'E:/Development/tcl-tv-tools/snapshots/home-control-2026-11-11/portable-backup/tv-settings.snapshot'

python tools/portable-settings-backup.py verify `
  --java 'E:/Development/tcl-tv-tools/jdk/jdk-17.0.20.1+1/bin/java.exe' `
  --validator-classpath $cp `
  --scratch 'E:/Development/tcl-tv-tools/snapshots/home-control-2026-11-11/portable-backup' `
  --input 'E:/Development/tcl-tv-tools/snapshots/home-control-2026-11-11/portable-backup/tv-settings.snapshot'
```

The validator is not an independent Python approximation of the Android schema:
`PortableSettingsBackupValidator` invokes the **same** `prepareSnapshot` and binary
codec as the TV. The diagnostic's base64 JSON is strictly validated and wrapped
into a normal Android-importable `.snapshot`. It is not a JSON-only fallback.
The envelope timestamp is PC capture time, not the time each preference changed.
`verify` validates the complete snapshot again and compares the manifest's length
and SHA-256. The manifest is alongside it as `.snapshot.manifest.json`.

## Explicit PC-to-TV staging (not a restore command)

After independently verifying the device identity and the PC file, the parent /
authorized operator may copy it using explicit ADB selectors. The expected primary
emulated-storage destination is:

```text
/sdcard/Android/data/dev.zane.pretttv/files/portable-settings-import.snapshot
```

The parent must verify this firmware's actual `getExternalFilesDir(null)` mapping
and ADB write/readback behavior. Choose **Review PC copy** once to create the app-specific
staging directory if needed (the expected missing-file message restores nothing). Then use the identity-checked ADB executable,
`-P <server-port> -s <serial> push <verified-E:-snapshot> <verified-fixed-path>`.
Compare the staged copy's bytes/hash with the PC original before relying on the
transfer. This utility deliberately does not push automatically.

On the TV choose **Review PC copy**, read the validated summary, then explicitly
choose **Restore these local settings** (or Cancel). ADB push, file-open intents,
and Activity extras never restore. The manifest is a PC-side integrity record;
Android verifies the embedded checksum and full schema, without trusting a sidecar.

## Guarded APIs and recovery

- `LocalSettingsBackup.exportPortable(cancel): ByteArray`: worker-only capture
  under writer exclusion; no private slot replacement; refuses during recovery.
- `PortableSettingsBackup.readBounded(input, cancel)` and `decode(bytes, cancel)`:
  bounded immutable snapshot creation, no preference editors.
- Provider document open/read/write/close runs on a separate bounded lane, with a 15-second caller deadline and cancellation checks. Native opens receive `CancellationSignal`; cleanup attempts signal cancellation and stream close away from both UI and preference/rollback workers. A provider can ignore both. One process-wide lease then keeps further document operations fail-fast until it drains; private snapshots, PC-copy review and recovery remain usable. Cancellation cannot retract bytes a provider has already written. Completed writes with unavailable/timed-out readback are explicitly unverified.
- `known[].localSnapshot` and `providerProgressBaseline` must be complete typed program objects. Nested numeric/string coercion, unknown nested fields and nonboolean `providerAbsent` are rejected before consent. Older exports without these optional objects remain valid.
- `LocalSettingsBackup.restore(snapshot, source = null, cancel)`: portable import
  through the same exclusion, epoch invalidation, commits and rollback engine as
  local restores. Call only after explicit one-use TV consent.
- `LocalSettingsTransfer.prepareSnapshot(data)`: all four stores required plus a
  known-field whitelist. `home/historySource` must be string `COMBINED` or
  `THIS_TV_ONLY`; omission is compatible with the consumer's default. Unknown
  values/types are rejected on import, not silently converted. Legacy generic
  diagnostic `prepare` compatibility remains separate.
- Ordinary portable restore first writes, syncs, rereads, schema-validates and
  atomically publishes `before-restore.snapshot` before any preference editor.
  During explicit recovery, a separate `before-recovery.snapshot` captures the
  current pre-attempt state without replacing the retained original recovery copy.
  A failed retry remains `RECOVERY_REQUIRED`, even if its rollback succeeded.
  Only a fully verified restore unfreezes writers. Existing saved snapshots stay
  untouched by portable import/export.

No MainActivity export integration is needed for the PC utility: it consumes the
existing `PRETTTV_SETTINGS=` protocol. Do not route its output through
`--restore-local-settings`; use the fixed staging route and TV confirmation.

Four preference commits are not a crash-atomic transaction. Cancellation stops
further admitted work but cannot retract an OS write already in progress. Rollback
must drain under exclusion. Recovery state remains process-local; power-loss and
physical disk-failure behavior are not proven by host fault injection.

## Instrumentation integration (parent-owned)

Register in the **stability manifest only**:

```xml
<provider
    android:name="fi.jommi.rivi.PortableSettingsBackupTestProvider"
    android:authorities="${applicationId}.portable-backup-fixture"
    android:exported="false"
    android:grantUriPermissions="false" />
```

Add `PortableSettingsBackupAndroidChecks.run(this, ::test)` to the parent's full
and focused backup runners. The new provider is in `src/stability`, never release.
The checks cover real native provider readback, cancelled chooser,
Pause → chooser result → Cancel-first preview, unexpected result / intent refusal,
PC preview pause revocation, changed-file immutable restore with a verified private
copy, corrupt PC staging, blocked open/read/write with Pause, the native open deadline, and malformed nested programs rejected before consent. They write only named fixture preferences/files in
`dev.zane.pretttv.stability` and preserve pre-existing fixture contents.

These cases executed in the final 202-check isolated native run. The first post-audit attempt failed after 22 checks because the fixture's inherited asset-file overload discarded `CancellationSignal`; the fixture now forwards it through both typed/untyped asset opens. Cancellation and worker-availability assertions were retained.

A real owner backup on E: passed byte/hash readback and the tightened validator. Its actual app-external staging path was verified on the TCL, then Review PC copy displayed the same checksum and all four validated stores with Cancel focused. The preview was cancelled, temporary staging removed and preferences retained. Production restore was not performed; isolated fixture restores are separate evidence. Synthetic host artifacts are never substitutes for the owner backup. USB remains unverified.
