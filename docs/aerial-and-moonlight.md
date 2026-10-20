# Aerial Views and Moonlight

[Documentation](README.md) · [Installation](installation.md) · [Using prettTV](usage.md)

**Aerial Views** adds a video screensaver. **Moonlight** streams games and the desktop from a PC running **Sunshine**. These are optional third-party apps, installed separately from prettTV. Their APKs and the PC host are not included in the prettTV bundle, and prettTV remains the Home launcher.

## Official downloads

| Component | Runs on | Version selected for the October 20, 2026 setup |
| --- | --- | --- |
| [Aerial Views](https://github.com/theothernt/AerialViews/releases/tag/1.8.5) | Android / Google TV | 1.8.5 · `com.neilturner.aerialviews` |
| [Moonlight](https://github.com/moonlight-stream/moonlight-android/releases/tag/v12.2) | Android / Google TV | 12.2 · `com.limelight` |
| [Sunshine](https://github.com/LizardByte/Sunshine/releases/tag/v2026.914.233613) | Gaming PC | 2026.914.233613 |

Use the TV's Play Store or the upstream releases above. For a manual download, verify its checksum against the publisher's release asset before installation. Device-specific credentials, pairing records and configuration backups stay outside this repository.

## Aerial Views

Open Aerial Views from prettTV's Library. Its settings provide a lightweight starting point with larger, readable overlays:

| Setting | Suggested value |
| --- | --- |
| Sources → Apple / Amazon → Video quality | 1080p HEVC |
| Settings → Overlays → Clock → Size / Weight | 36sp / Medium |
| Settings → Overlays → Weather → Current Weather → Size / Weight | 32sp / Medium |
| Weather → Location | Choose your city |
| Weather → Units | Choose Fahrenheit or Celsius |
| Overlays → Bottom Row → Bottom left → Upper Slot | Weather: Now |
| Bottom left → Lower Slot | Clock |
| Appearance → Gradients → Show bottom gradient | On |

Weather needs a selected location and a weather overlay slot. It uses Aerial Views' own OpenWeather integration; prettTV's weather configuration is separate. The app streams its selected video sources over the Internet unless you configure local media. Use **Test screensaver settings** to check playback and overlay readability.

### Select it as the system screensaver

On Google TV devices that do not expose a screensaver picker, follow the [upstream ADB instructions](https://github.com/theothernt/AerialViews#readme). First record the current component so you can restore it:

```text
adb -s TV_SERIAL_FROM_ADB_DEVICES shell settings get secure screensaver_components
adb -s TV_SERIAL_FROM_ADB_DEVICES shell settings put secure screensaver_components com.neilturner.aerialviews/.ui.screensaver.DreamActivity
```

On supported TCL TVs running Android 14 or newer, the upstream guide also requires **Auto Launch** access. Inspect the current app operation before changing it:

```text
adb -s TV_SERIAL_FROM_ADB_DEVICES shell appops get com.neilturner.aerialviews
adb -s TV_SERIAL_FROM_ADB_DEVICES shell appops set com.neilturner.aerialviews AUTO_START allow
```

If that operation is unavailable, use the TCL permission interface described upstream. These screensaver settings do not require changing the Home launcher.

Check the TV's idle timer and any existing launcher ambient mode together. Upstream documents a minimum system timeout of six minutes on modern Google TV; an earlier launcher ambient screen can otherwise appear first. Test automatic activation as well as the manual preview. To revert, restore the component recorded before setup.

## Moonlight and Sunshine

Install Sunshine on the gaming PC using its [official setup guide](https://docs.lizardbyte.dev/projects/sunshine/latest/md_docs_2getting__started.html). Keep its administrative web interface accessible only from the PC, leave UPnP off for a local-network setup, and scope the host firewall access to the intended TV. Hardware encoding should use a supported GPU; NVIDIA hosts can use NVENC.

Open Moonlight on the TV, select the PC, and enter the displayed pairing PIN in Sunshine's local administration page. If discovery does not find the PC, use Moonlight's add-PC control with the PC's local address. Both devices must be reachable on the same local network for this setup.

Start with:

| Moonlight setting | Suggested value |
| --- | --- |
| Resolution | 1080p |
| Frame rate | 60 FPS |
| Video bitrate | 20 Mbps |
| Video frame pacing | Balanced |
| HDR | Off / SDR |
| Show performance stats while streaming | Off |

These are starting settings, not a performance guarantee. Leave automatic host display changes disabled while establishing a working stream. First check Desktop video and audio, then test a game and the controller. A controller paired with the TV needs compatible host gamepad support; Windows input-driver choices are covered in Sunshine's guide.

The **PC must be on and awake**, with Sunshine running and a capturable display available. Moonlight does not run the PC games on the TV itself. The [Moonlight setup guide](https://github.com/moonlight-stream/moonlight-docs/wiki/Setup-Guide) and [Sunshine configuration reference](https://docs.lizardbyte.dev/projects/sunshine/latest/md_docs_2configuration.html) cover pairing and further tuning.

Choose **Desktop** or **Steam** after opening the paired PC in Moonlight. Hold the remote's **Back** button to leave a stream. A short press can be sent to the streamed application instead.
