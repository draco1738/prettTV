# Return to stock for troubleshooting

At the bottom of prettTV Settings, **Return to stock Google TV** restores the original Home app, the TCL/Google apps disabled by the recorded setup, and the previous private DNS settings. Two separate confirmation screens must be accepted before restoration starts. Cancel or Back leaves the TV unchanged.

This is a troubleshooting restore, not a factory reset. prettTV stays installed, and installed streaming apps, their data and logins are retained. Apps disabled outside the recorded setup are not included. Restoring the original apps and DNS can bring back stock recommendations, advertising and background services.

## Set up the private recovery record

A new prettTV installation cannot know which apps or DNS settings were changed previously. Full recovery is unavailable until the original setup record is imported. Do not guess a baseline from the TV's current state.

Keep the original `changes.json` and its snapshot directory outside this repository. The snapshot must contain `device-identity.json` and `home-activity.txt`. The journal's `snapshot` property points to that directory; a relative path is resolved beside the journal. These files contain device-specific information and must not be uploaded to GitHub or included in a release.

With the TV connected and authorized in ADB, open prettTV and run:

```powershell
./tools/configure-stock-recovery.ps1 `
  -JournalPath 'E:\PrivateTvBackup\changes.json' `
  -Tv 'TV_ADDRESS:5555' `
  -Adb 'E:\Android\platform-tools\adb.exe' `
  -AdbPort 5037
```

Use the serial or address shown by `adb devices`; `AdbPort` is the computer's ADB server port, not the TV's port. The script reads the saved serial number, firmware fingerprint and original Home activity. It checks the connected TV and foreground user before importing the record into prettTV's private storage. It does not enable packages, change DNS, switch Home, or write a copy of the record into the repository. Its output reports status without printing device identifiers.

The importer accepts completed user 0 `disable-user` entries only for the following packages. Google TV Home is required; unrecorded packages are left unchanged.

- TCL TV+: `com.tcl.tv.plus`
- TCL Browser: `com.tcl.browser`
- TCL Alexa: `com.tcl.smartalexa`
- TCL e-sticker: `com.tcl.esticker`
- TCL Interactive: `com.tcl.interactive`
- TCL LogKit: `com.tcl.logkit`
- Google Feedback: `com.google.android.feedback`
- TCL Channel Plus: `com.tcl.channelplus`
- Google TV setup: `com.google.android.tungsten.setupwraith`
- Google TV Home: `com.google.android.apps.tv.launcherx`

Each entry restores its exact recorded enabled state (0 through 4); the saved Google TV Home state must be default or enabled. The importer also requires the completed AdGuard changes to `global/private_dns_mode` and `global/private_dns_specifier`. A saved `null` value means the setting was absent and is restored by deleting that setting. No literal `null` hostname is written. Unsupported, duplicate or pending changes are rejected.

## Use the button

Android protects package state, DNS and the default Home app. The in-app restore therefore needs an authorized ADB connection to the TV itself at `127.0.0.1:5555`. It does not connect to another device or use the computer's private ADB key. TCP debugging must already be enabled and remain available when the button is used; the feature does not enable it automatically or grant itself privileges. If debugging is later disabled, a computer may be needed to make it available again. Android may ask to authorize prettTV's own key on first connection.

After both confirmations, recovery checks the saved identity, recorded states and stock Home before changing anything. It verifies the restore results and reports failures instead of claiming success. A missing baseline, unavailable debugging connection, firmware mismatch or conflicting later changes needs to be resolved first. Keep the original computer-side backup for recovery if prettTV cannot run.

The second confirmation expires after two minutes without starting a restore. If a restore is interrupted after some settings change, already-restored apps stay available and the result reports incomplete recovery. Reopen the button to recheck and retry; no automatic restore runs at startup.

These are actual captures of the two confirmation screens on the TCL. Both start on Cancel:

![First confirmation](screenshots/recovery-confirmation-1.png)

![Second confirmation](screenshots/recovery-confirmation-2.png)

After a successful restore, prettTV's optional playback tracking and game handoff helpers pause. If you return to prettTV later, **Resume prettTV helpers** in its Settings reactivates them using their existing Android permissions. Choosing prettTV as the default Home is a separate step.

The older repository `restore.sh --stock-home` is a separate uninstall helper. It does not restore the full disabled-app and DNS baseline, and it removes prettTV. Do not use it as a substitute for this troubleshooting button.
