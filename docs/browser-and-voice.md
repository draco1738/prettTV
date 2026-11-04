# Browser, ad blocking and remote voice

[Documentation](README.md) · [Installation](installation.md) · [Privacy](privacy.md)

TV Bro is an optional browser. The remote's Google voice service is separate from both TV Bro and prettTV. You can use them while keeping prettTV as Home.

## Add TV Bro

Install **TV Bro** by **Phlox Development** through the TV's Play Store, or use the APKs in the [official TV Bro releases](https://github.com/truefedex/tv-bro/releases). Its Android package is `com.phlox.tvwebbrowser`. TV Bro is not included in the prettTV release bundle.

For a lightweight installation on a TV with a working Android System WebView, the upstream **Gecko-excluded** build uses that existing engine. Keep Android System WebView updated through the TV's supported update mechanism.

The build checked for this setup was:

| Item | Value |
| --- | --- |
| Upstream release | [TV Bro 2.1.6](https://github.com/truefedex/tv-bro/releases/tag/v2.1.6) |
| APK | `tvbro-2.1.6-generic-geckoExcluded.apk` |
| Android version code | `69` |
| SHA-256 | `d8634edfe8d94b4fb9a52005d68a090a7f1e85b7f39c2cf01a25dc9dd60942b2` |

The downloaded APK matched GitHub's published asset digest and passed Android's APK signature verification. These checks establish the downloaded artifact and its signature integrity; they are not a full security audit.

For that exact APK, check the hash before installing:

```powershell
$apk = '.\tvbro-2.1.6-generic-geckoExcluded.apk'
$expectedHash = 'd8634edfe8d94b4fb9a52005d68a090a7f1e85b7f39c2cf01a25dc9dd60942b2'
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $apk -ErrorAction Stop).Hash -eq $expectedHash) {
    adb -s 'TV_SERIAL_FROM_ADB_DEVICES' install -r $apk
} else {
    throw 'TV Bro checksum mismatch; installation stopped.'
}
```

Replace the device placeholder with the authorized device shown by `adb devices`. The `-r` option updates a matching existing installation while preserving its data. If Android reports a signing-certificate mismatch, investigate the installed app's source rather than uninstalling it to bypass the check.

TV Bro appears in prettTV's Library as a normal installed TV app. Hiding it from **Your apps** does not remove it from Library. Browser microphone, camera or location permission is not needed for the TV remote's system voice assistant.

### Optional website tiles

The separate [7Movies shortcut](../launcher/webshortcut/README.md) adds a dark app tile that opens the website's Discover page in TV Bro. It reuses the selected tab and the browser's existing sessions and blocking settings. The shortcut requests no permissions and finishes after opening the browser; it is not a standalone streaming client or an official website app. It is built separately and is not added to the prettTV + RBTV bundle.

The separate [Globe TV shortcut](../launcher/globetv/README.md) opens [Globe TV's US live-TV page](https://globetv.app/tv?country=united-states), with other countries available through the website's selector. It also uses TV Bro's selected tab, existing sessions and filtering. Its dark globe artwork is original to the shortcut. It requests no permissions, does not include a player, and is not an official Globe TV app. Installing it adds an independent **Globe TV** entry to the TV app library; the 7Movies shortcut and prettTV launcher remain separate.

## Browser ad blocking

In TV Bro, open **Settings → Main**, check **Toggle ads blocking**, and use **Update ad-blocker list**. Version 2.1.6 enables its global blocker by default and uses [EasyList](https://easylist.to/). The browser also has popup controls.

Press Back from an ordinary page to show the browser controls. The bottom toolbar's blocker button changes blocking for the current tab and reloads it; check that control if one tab behaves differently from the global setting. A blocked-request count is more useful evidence than the list's update timestamp alone.

The blocker filters browser requests. It does not remove every ad, and it does not filter other streaming apps. Private DNS filtering is a separate device setting: it can filter supported domains, but cannot remove all ads delivered from the same hosts as content.

prettTV's APK and installer do not configure Private DNS or TV Bro. Existing device-specific DNS changes remain part of the private [stock recovery record](stock-recovery.md). No personal DNS account or recovery configuration belongs in the public repository.

## Keep remote voice working

Use the TV's supplied Google voice service and the paired remote's microphone button. Check the TV's supported updates, Internet connection, Google sign-in, remote Bluetooth pairing and voice permissions. Google TV's **Apps-only mode** disables Assistant/search functionality.

The optional voice search inside TV Bro is a separate feature. Granting TV Bro microphone access does not repair or enable the remote's system assistant.

Google controls whether a particular device and profile receive Gemini. Its current requirements include Android TV OS 14 or newer, at least 2 GB of RAM, a supported country/language and an eligible adult profile. Meeting the hardware minimums alone does not confirm availability. See [Google's Gemini setup guidance](https://support.google.com/googletv/answer/16522213?hl=en), [TCL's remote voice setup](https://support.tcl.com/en_US/set-up-and-use-the-google-assistant-on-your-tcl-google-tv) and [Google's Apps-only explanation](https://support.google.com/googletv/answer/10267283?hl=en).

Google also requires the TV and its profile to belong to the same Google Home, with Gemini setup acknowledged for that Home. Check **Settings → Accounts & Profiles → your account → Voice assistant** for the eligible profile's setup. Google Home membership is separate from choosing prettTV as the TV's launcher.

### Preserve prettTV as Home

**Google-free Home configuration:** on the November 4 test TV, the owner chose to disable Google TV Home and its fallback launcher rather than retain Gemini results. Android then resolved Home directly to prettTV, without an accessibility redirect. The existing Home compatibility helper and remote key filter were both off. The default ambient screensaver remained available and its Home exit returned directly to prettTV.

On this TCL, the physical gear/notification button is wired to Google's dashboard and loses that destination when Google Home is disabled. Use **prettTV Settings → TV & audio → TV settings** for the native controls. Do not re-enable a global key filter to compensate without testing held volume; this firmware previously lost native repeats while key filtering was active. Google's package also supplies Gemini movie/conversation result screens, so this configuration deliberately gives up those results. Other Google services and the Play Store are separate packages.

This is a device configuration choice, not an automatic installer action. The double-confirmed stock restore remains the recovery path. Keep Home compatibility off when Google Home is disabled; no redirect is needed.

Enabling stock launcher packages can change what the Home button opens. On the tested TCL, Google TV Home (`com.google.android.apps.tv.launcherx`) and its recovery launcher (`com.google.android.tungsten.setupwraith`) took priority over prettTV even when Android's Home role still named prettTV.

Do not enable or disable those packages as a generic voice troubleshooting step. Keep the device's working Home configuration, and verify the **actual Home button** after any launcher-related change. Use [stock recovery](stock-recovery.md) only when you intend to restore the stock setup.

**0.4.4 compatibility:** the November 3 diagnosis found that Google's current assistant recognized speech but attempted to open `com.google.android.apps.tv.launcherx/.gemini.chat.ChatActivity`. With that package disabled, Android rejected the result screen. The earlier successful weather check does not establish that today's Gemini movie/conversation results can work with the whole package disabled.

The optional **prettTV Home compatibility** service lets Google's package remain enabled on TVs that ignore the selected Home role. Open **prettTV Settings → Support → Home compatibility settings** to reach Android's Accessibility controls. This helper observes only stock launcher window names and returns the two recognized Google Home screens to prettTV. It leaves Gemini, profile, settings and other activities open. It has no key-filter capability and cannot read window content. A brief stock-Home transition may be visible; this is a window redirect, not a replacement Google results interface. The app and installer never enable this helper or change Google packages automatically.

This is separate from the **Remote buttons** helper. Leave that older key-filtering helper off on the tested TCL to preserve normal held-volume repeats. The new Home helper pauses during the double-confirmed stock restore and remains paused after successful recovery. See [versioned validation](validation.md) for actual device results and remaining limits.

Keep Bluetooth on when using the remote microphone: [TCL documents that the voice remote uses Bluetooth for its microphone](https://support.tcl.com/en_US/set-up-and-use-the-google-assistant-on-your-tcl-google-tv). You can keep TV speakers selected while Bluetooth remains on. Turning the entire radio off also disconnects the voice remote.

In the October 20, 2026 device check, the original disabled states of both stock launcher packages were restored, prettTV again resolved as Home, and the user confirmed that the remote answered a weather request and opened its forecast. This verifies that remote voice worked with prettTV on that setup; it does not verify every Gemini conversation feature. TV Bro 2.1.6 was installed, and its toolbar subsequently reported four blocked requests on a loaded page. This confirms reported filtering activity; a controlled blocking-on/off test remains pending.

The November 3 repair also restored the original/default state of Google's setup support package after result requests repeatedly failed to obtain its CastInfoProvider. Google's Home initialization was allowed to complete once, then the compatibility helper was restored. Final text requests displayed full movie details and Gemini responses while Home returned to prettTV. Fresh physical microphone confirmation remained pending; see the versioned validation record.
