# ESC Configurator for Android

An unofficial Android app of [ESC Configurator](https://esc-configurator.com) (BLHeli_S, Bluejay,
AM32). The web app is packaged into the APK, so it works without an internet connection, and it
talks to the flight controller through the phone's USB port (OTG).

## How it works

| Part | Where | What it does |
| --- | --- | --- |
| Web app | `web/` ([stylesuxx/esc-configurator](https://github.com/stylesuxx/esc-configurator) v0.32.1 + `patches/`) | The whole user interface and ESC logic |
| JS bridge | `android/app/src/main/assets/www/android-bridge.js` | Provides `navigator.serial`, which an Android WebView does not have; file saving; phone layout fixes; theme switching |
| USB | `UsbSerialBridge.kt` | USB serial (CDC / STM32 VCP, CP210x, FTDI, CH34x) through [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) |
| Firmware | `OfflineStore.kt` | Firmware files from the APK → from the cache on the phone → from the internet |
| AM32 theme | `android/app/src/main/assets/www/am32-theme.css` | Look of the AM32 configurator while AM32 ESCs are connected |

### Firmware without internet

- The APK bundles the newest stable release of Bluejay and AM32, and BLHeli_S 16.7
  (`tools/fetch_firmware.py`).
- With an internet connection the app offers all versions and downloads them straight from GitHub.
  Whatever has been downloaded once stays on the phone.
- Without a connection the firmware list only contains the versions that are on the phone.
- Flashing a local `.hex` file always works.

The app only connects to GitHub (the firmware sources). There are no analytics, every other host is
blocked.

### AM32 theme

When the ESCs that have been read run AM32, the app switches to the look of the
[AM32 configurator](https://am32.ca): near black background, red accent, settings groups as
outlined boxes, slate ESC cards with a green ring. It switches back after disconnecting or when
other firmware is found. Everything is in `am32-theme.css`, scoped to the `am32` class that
`android-bridge.js` puts on `<html>`.

### Changes to the web app (`patches/`)

`patches/am32-eeprom-v3-v4.patch` adds support for AM32 firmware 2.19 and up (EEPROM version 3
and 4), which the original web app does not have:

- settings are read and written with the new layout (bytes 5 - 14 no longer hold a name but
  settings: ramp rate, minimum duty cycle, current limiter PID, brake modes and more; the EEPROM is
  192 bytes long);
- bytes the app does not know (reserved bytes, CAN settings) are written back exactly as they have
  been read;
- flashing no longer overwrites settings with the text "NOT READY" (the original app destroys the
  settings of 2.19+ that way);
- sliders support fractional steps, texts without a translation are shown in English.

Layout and value ranges come from the AM32 firmware (`Inc/eeprom.h`, `loadEEpromSettings()`) and
the official [am32-configurator](https://github.com/am32-firmware/am32-configurator). Tests:
`web/src/sources/AM32/__tests__/extendedLayout.test.js`.

## Using the app

1. Install the APK (the phone has to allow installing from unknown sources).
2. Connect the flight controller with an OTG cable and let the app use the USB device.
3. From there on it works like the website: *Connect* → *Read Settings* → …

The screen stays on while a connection is open. Do not switch to another app while flashing, and
do not unplug the cable in the middle of a session: the flight controller stays in ESC passthrough
mode and has to be power cycled before it answers again.

## Building (Windows)

```powershell
.\build.ps1              # web app + APK (release)
.\build.ps1 -SkipWeb     # APK only, uses the existing web\build
.\build.ps1 -Firmware    # refresh the bundled firmware pack first
.\build.ps1 -Debug       # debuggable variant (WebView shows up in chrome://inspect)
```

The finished APK is copied to `dist\`.

Needed: Node 18 (with yarn), JDK 17, Android SDK (platform 35; its path goes into
`android\local.properties`) and Python 3. If there is a `.toolchain\` folder (portable copies of
Node and the JDK), the script uses it.

**exFAT drives.** The build creates tens of thousands of small files (`node_modules`, Gradle
caches), which takes hours on an exFAT drive. All of that can be moved to a work directory on an
NTFS drive: `.\build.ps1 -WorkDir D:\some\path`, or put the path into `.toolchain\workdir.txt`.

Test of the JS bridge (no phone needed, simulated USB device):

```powershell
node tools\bridge-test.js android\app\src\main\assets\www\android-bridge.js
```

The release APK is signed with the *debug* key: fine for installing it yourself, but it needs a
key of your own before distributing it (`android/app/build.gradle.kts`).

### Getting and updating the web app

`web/` is a clone of upstream with `patches/` applied; it is not part of this repository.

```powershell
git clone https://github.com/stylesuxx/esc-configurator.git web
cd web; git checkout v0.32.1; git apply ..\patches\am32-eeprom-v3-v4.patch; cd ..
.\build.ps1 -Firmware
```

When moving to a newer upstream version, re-apply the patch and run the tests (`yarn test` in the
web folder). If upstream changes how firmware URLs are built, `OfflineStore.kt` and
`tools/fetch_firmware.py` need a look - both use the same URL → file rule.

## License

The Android wrapper in this repository is licensed under the GPL-3.0, see `LICENSE`.

The web app it packages is AGPL-3.0 (© stylesuxx and contributors), and so is
`patches/am32-eeprom-v3-v4.patch`, which is a modification of the web app. An APK built from this
repository contains the web app: whoever distributes it has to provide the source code - this
repository plus the web app sources - under those terms.
