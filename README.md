# NIHIL for Android

```
·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·
N I H I L                                                       ex nihilo
·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·  ·
```

A home screen in the NIHIL language — the same one as the
[desktop](https://nihilshell.com): black, greys and a dot matrix; red only
when something interrupts.

No ads. No tracking. **No internet permission**: nothing leaves the phone.
Kotlin on the Android framework alone — no AndroidX, no libraries, nothing
but the Kotlin standard library.

Experimental, made for a Galaxy A35, Android 12 or newer.

**[Download the APK](https://github.com/poligle/Nihil-Launcher/releases/latest)**
· [nihilshell.com/android](https://nihilshell.com/android/)
· the desktop: [Nihil-Shell](https://github.com/poligle/Nihil-Shell)

## The screen

**The frame.** The screen is black and the wallpaper is a rounded card set
into it, like the desktop's frame. The status bar and the gesture bar live on
the frame; the card follows the screen's own rounded corners.

**The drawer is the frame growing.** Swipe up and the card's bottom edge
rises with your finger until the screen is all black. The content switches on
at the end, like LEDs.

**An LED clock.** A 25 × 7 panel where every LED is there, unlit ones faintly.
When the minute changes it refreshes column by column.

**Battery as LEDs.** Ten of them. Red at 15 % and not charging — the only
alarm colour. Charging, the next LED breathes.

**The Glyph light.** With notification access, a run of LEDs goes round the
frame when a notification arrives (white), a call or an alarm rings (red,
twice), or the charger goes in (from the bottom). Apps with unread
notifications show as small icons under the clock; tap them for the shade.

**Favourites** at the bottom, where the thumb is.

## Gestures

| | |
|---|---|
| swipe up | drawer |
| swipe down | notifications |
| double tap | lock (optional) |
| hold empty space | wallpaper and settings |
| hold an app | its shortcuts · add, remove, move · hide · info · uninstall |
| home button | back to the home screen |

## The drawer

One field for everything; **enter** runs the first result.

- Accents and case don't matter: `camara` finds *Cámara*. Package names
  count too, so `settings` finds *Ajustes*.
- `>` lists the **actions**: torch, Wi-Fi and sound panels, Bluetooth,
  battery, alarms, lock, the wallpaper studio, settings. They also turn up in
  normal search — `lin` is the torch.
- `=` is the **calculator** (`+ - * / ^ % !`, `sqrt sin cos tan ln log`…,
  `pi`, `e`, `2pi`, `3(4+1)`, comma or point). A sum like `12*7` is
  recognised without `=`. Enter copies the result.
- Nothing found: search the web in your browser.

Hidden apps leave the list but search still finds them.

## Wallpaper studio

A photo becomes an LED matrix at the exact resolution of the screen, with the
desktop's algorithm: luminance per cell, the black point raised so a dark sky
stays off, isolated highlights lit as stars. Fine, normal or coarse dots; home
screen, lock screen or both. All on the phone.

## Permissions

| | |
|---|---|
| `SET_WALLPAPER` | the studio |
| `EXPAND_STATUS_BAR` | swipe down for notifications |
| `REQUEST_DELETE_PACKAGES` | "uninstall" in the app menu |

Optional, off until you turn them on in NIHIL's settings:

- **Notification access** — Glyph light and unread icons. NIHIL reads which
  app posted, the flags and the category; never the text. Silent
  notifications and anything Do Not Disturb holds back stay dark.
- **Accessibility** — only to lock the screen on a double tap (and to open
  the shade if Android ever blocks the normal way). It reads no content.

Installed from an APK file, Android 13+ may grey these out as a *restricted
setting*: App info → ⋮ → *Allow restricted settings*. Installing with
`adb`/Android Studio usually avoids it.

## Install

**From a release.** Download `nihil-launcher-v….apk` from the
[latest release](https://github.com/poligle/Nihil-Launcher/releases/latest),
open it on the phone and allow your browser or file manager to install it.
The `.sha256` next to it lets you check the download.

Then make it the home app: NIHIL's settings → *Home app*, or Settings →
Apps → Default apps → Home app.

## Build

JDK 17 and the Android SDK (platform 35, build-tools 35.0.0).

```sh
git clone https://github.com/poligle/Nihil-Launcher nihil-launcher
cd nihil-launcher
```

**Android Studio** — open the folder, press Run.

**Nix** —

```sh
nix develop
./gradlew installDebug      # phone connected with USB or wireless debugging
./gradlew test              # the pure Kotlin core: search, calculator, LEDs
```

The dev shell brings `adb`. On recent NixOS (systemd 258) USB access works out
of the box; on older releases use `programs.adb.enable` and the `adbusers`
group. Wireless debugging (`adb pair`) works everywhere.

`./gradlew assembleRelease` gives a minified APK, signed with the debug key
unless you pass your own; publishing a release is a tag away
([RELEASING.md](RELEASING.md)). Every push runs the tests and a build on CI.

## Code

```
app/src/main/java/com/nihilshell/launcher/
  LauncherActivity   the home screen: layers, gestures, receivers
  SettingsActivity   settings ("NIHIL" in the app list)
  WallpaperActivity  the studio
  core/   Apps · IconRenderer · Search · Fuzzy · Calculator · Actions
          LedFont · DotMatrix · DotRender · Prefs · Torch
  ui/     FrameView (frame + Glyph) · HomeView · DrawerView · RootLayout
          LedClock · LedGauge · LineIcon · AppMenu · Widgets · Nihil (tokens)
  system/ GlyphListener · Glyph · LockService · Access
```

Icons: *mono* uses the app's themed icon (Android 13) in ink; apps without
one get their foreground layer in greys, inverted when it's dark. *grey* and
*colour* are what they say.

## Licence

MIT. Fonts: Doto, Space Grotesk and Space Mono, SIL Open Font License 1.1
(`app/src/main/assets/fonts/OFL.txt`).
