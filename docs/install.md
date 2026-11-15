# Install

1. Download `nihil-launcher-v….apk` from the
   [latest release](https://github.com/poligle/Nihil-Launcher/releases/latest). The
   `.sha256` next to it checks the download: `sha256sum -c nihil-launcher-v….apk.sha256`.
2. Open it on the phone and let your browser or file manager install apps when Android
   asks.
3. Open **NIHIL** from the app list and tap **Home app**, or Settings → Apps → Default
   apps → Home app.

The light on the frame needs notification access; the double tap, and the same light over
other apps, the accessibility service. NIHIL's settings take you to both. The first time
NIHIL is the home app it puts on its own wallpaper, the night in LEDs, as the desktop
starts; the studio changes it. Installed from an APK file, Android 13 and
later may grey them out as a *restricted setting*: App info → ⋮ → **Allow restricted
settings**.

## Build

JDK 17 and the Android SDK: platform 35, build-tools 35.0.0.

```sh
git clone https://github.com/poligle/Nihil-Launcher nihil-launcher
cd nihil-launcher
```

**Android Studio** — open the folder and press Run.

**Nix** — the flake has a dev shell with the JDK, the SDK and `adb`:

```sh
nix develop
./gradlew installDebug      # the phone on USB or wireless debugging
./gradlew test              # the pure Kotlin core on the JVM
```

On recent NixOS (systemd 258) USB access works out of the box; on older releases, use
`programs.adb.enable`. Wireless debugging works everywhere: Developer options →
Wireless debugging → *Pair device with pairing code*, then `adb pair IP:PORT` and
`adb connect IP:PORT` with the port of the main screen.

`./gradlew assembleRelease` builds a minified APK, signed with the debug key unless you
pass your own. Every push runs the tests and a build on CI; a tag publishes a signed
release ([RELEASING.md](../RELEASING.md)).
