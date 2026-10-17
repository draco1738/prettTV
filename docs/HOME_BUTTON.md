> Upstream Rivi reference, retained for provenance. This describes Rivi v0.15, not the modified prettTV implementation. See the root README, THIRD_PARTY_NOTICES.md and docs/validation.md for prettTV.

# The Home button

## The problem

Google TV picks the Home app by intent-filter priority. Its own launcher
(`com.google.android.apps.tv.launcherx`) declares priority 2, Google's setup app
(`com.google.android.tungsten.setupwraith`) priority 1, and third-party apps are capped at 0.
`cmd package set-home-activity` and the usual "choose a Home app" dialog therefore do nothing,
and the settings offer no alternative.

## What Rivi does

`HomeKeyService` is an accessibility service that is off until the owner switches it on under
Settings → System → Accessibility. When on, it does two things:

1. **Direct path.** It consumes the Home key and starts Rivi. The Google home screen is never
   started.
2. **Redirect path.** When the Google *home screen* window appears by itself (after boot, when an
   app exits to Home, or when the key did not reach the service), it starts Rivi on top.

It declares `canRetrieveWindowContent="false"`, listens only to window changes of the stock
launcher package, and has no polling or timers. Other screens of the stock launcher, such as the
quick-settings panel, the profile chooser and re-authentication, are deliberately not redirected.
MENU → "Open Google TV home" shows the stock home screen until Home is pressed again.

## What was observed on the JMGO N1S

* Physical remote: Home presses were consumed directly (counters after three presses:
  `homeKeys=3 redirects=0`).
* 20 of 20 cycles of "open a streaming app, press Home" returned to Rivi with one launcher
  process throughout and no crash.
* Key presses injected with `adb shell input keyevent` bypass Android's accessibility key filter,
  so automated tests always exercise the redirect path. There, Rivi was started 182 ms after
  the system began handling Home (one trace).
* `am force-stop` or Settings → Apps → Force stop on Rivi makes Android switch the service
  off. Updating the app with `adb install -r` keeps it on.

The counters are visible with `adb shell dumpsys activity fi.jommi.rivi` (lines starting `RIVI`).

## Why not disable the Google launcher instead

That is the common advice, and it works: with both packages disabled, Android falls back to the
remaining Home app.

```
pm disable-user --user 0 com.google.android.apps.tv.launcherx
pm disable-user --user 0 com.google.android.tungsten.setupwraith
```

This project does not do it, for these reasons, all read from the device's package dumps:

* The launcher package also hosts the quick-settings side panel, a listener for the remote's
  global app buttons, the sleep timer, the TV notification listener, and content providers that
  other Google apps read. Cast was seen reading one of them.
* The setup package is involved in system updates, adding a Google account, remote re-pairing and
  device reset.
* If the replacement launcher then fails, Home leads to a blank screen until adb or Settings
  re-enables the packages.

What disabling would buy is memory, not CPU:

| Stock launcher | on screen | hidden behind Rivi |
|---|---|---|
| CPU (% of one core) | 55 | 0.3 |
| SurfaceFlinger CPU caused by its redraws | 33 | 0 |
| Memory (PSS, both processes) | 196 MB | 100–145 MB, cached |

Killing the hidden launcher's UI process from the adb shell (`am kill`) freed about 115 MB of RAM
and swap, and it stayed away until the stock home screen was next requested. An app cannot do the
same: on Android 14 `killBackgroundProcesses()` only affects the caller's own processes (tried,
no effect, removed).

To undo the two commands above, should you have run them from another guide:
`pm enable --user 0 <package>` for each.
