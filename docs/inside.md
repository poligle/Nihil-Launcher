# Inside NIHIL for Android

## Structure

```
app/src/main/java/com/nihilshell/launcher/
├── LauncherActivity.kt    the home screen: layers, insets, gestures, receivers
├── SettingsActivity.kt    settings, also "NIHIL" in the app list
├── WallpaperActivity.kt   the studio
├── DeckActivity.kt        the deck: music, volume, caffeine, night light, lock, apps and webs, trackpad
├── DeckScanActivity.kt    pairing: the camera and the QR
├── DeckShareActivity.kt   "Open on the computer" in the share sheet
├── WebShareActivity.kt    "Add to NIHIL" in the share sheet
├── TasksActivity.kt       the desktop's kanban, and Vector above it
├── VectorShareActivity.kt "Vector" in the share sheet
├── deck/
│   ├── DeckClient.kt      HTTPS pinned to the computer, pairing, mDNS
│   ├── DeckStore.kt       the computer, its token in the Keystore, the webs
│   ├── DeckCache.kt       the icons the computer gives, kept a week
│   ├── TrackpadLink.kt    the trackpad's stream to the computer
│   ├── Vector.kt          what comes from the computer and goes to it, its listening and notification
│   ├── VectorProvider.kt  the last thing that came, for other apps to read
│   ├── VectorAction.kt    the notification's Copy and Save, and the share sheet's answer
│   └── TasksStore.kt      the kanban on the phone, its clock and its sync
├── core/
│   ├── Apps.kt            every launchable app, in every profile; icons off the main thread
│   ├── Webs.kt            the webs kept on the phone, opened in Chrome
│   ├── IconRenderer.kt    mono, grey and colour icons
│   ├── Search.kt          what the drawer shows for a query
│   ├── Fuzzy.kt           scoring: exact, prefix, word, inside, letters in order
│   ├── Calculator.kt      the calculator: sums, percentages, units, bases
│   ├── Symbolic.kt        its calculus: derivatives, integrals, equations, series, limits
│   ├── QrDecoder.kt       the deck's QR reader
│   ├── TaskBoard.kt       the kanban and how two boards merge
│   ├── Actions.kt         the "> actions"
│   ├── LedFont.kt         5 × 7 LED digits
│   ├── DotMatrix.kt       a photo as LEDs (pure Kotlin)
│   ├── DotRender.kt       decoding and drawing for the studio
│   ├── Prefs.kt           everything NIHIL remembers
│   └── Torch.kt
├── ui/
│   ├── FrameView.kt       the frame
│   ├── Halo.kt            the Glyph light, as the desktop's
│   ├── HomeView.kt        the clock's card, favourites
│   ├── DrawerView.kt      the field and the results
│   ├── RootLayout.kt      swipes, double tap, long press
│   ├── Workspaces.kt      the deck's pages, side by side
│   ├── ReorderGrid.kt     cells put in order by holding one
│   ├── ReorderList.kt     rows put in order the same way: the tasks
│   ├── Trackpad.kt        the deck's trackpad, its LEDs under the fingers
│   ├── VectorBox.kt       Vector's box, its LEDs running round when something goes or comes
│   ├── LedGauge.kt, LineIcon.kt, AppMenu.kt, WebDialog.kt, EntryIcon.kt, Widgets.kt
│   └── Nihil.kt           tokens, fonts, motion
└── system/
    ├── GlyphListener.kt   notification access (optional)
    ├── LockService.kt     accessibility, to lock and light (optional)
    ├── HaloOverlay.kt     the Glyph light over other apps
    ├── Glyph.kt           the bus between the two
    └── Access.kt          home role, accesses, the shade
```

## Technical details

- **No libraries.** Views are built in code on the framework: `ListView`, `PopupWindow`,
  `LauncherApps`, `RoleManager`, `WindowInsets`. The only dependencies are the Kotlin
  standard library and, for the tests, JUnit.
- **The frame** is one even-odd path: the screen minus a rounded card whose corner comes
  from the display's own `RoundedCorner`. Opening the drawer only moves the card's bottom
  edge, so each frame of the animation is a single path redraw.
- **Nothing runs in the background.** The clock follows `TIME_TICK` and the battery its
  broadcast, only while the home screen is visible. The notification listener and the
  accessibility service exist only if you turn them on.
- **The Glyph light** is the desktop's (`GlyphLight.qml`): a segment that runs once round,
  clockwise from the top-right corner, and two lights that climb from the bottom for the
  charger. Round the card on the home screen; round the screen's own corners, from the
  accessibility service, over other apps.
- **Icons**: Android 13's monochrome layer tinted ink. Without one, the foreground layer is
  clipped to the icon's circle, turned to greys and inverted when it's mostly dark; a
  foreground that fills the icon falls back to grey. Rendered on two threads, cached.
- **The trackpad** sends fingers, not gestures: each frame says which fingers touch and
  where, in twentieths of a millimetre, with the screen's own time for every sample. On the
  computer they become a uinput touchpad as large as the phone's screen, so libinput's taps,
  scrolling and pinches and Hyprland's gestures behave as on the laptop. The frames keep the
  phone's spacing there, 25 ms late: libinput reads speed from their times.
- **Webs** open with an app id each (`Browser.EXTRA_APPLICATION_ID`), so Chrome goes back to
  the tab it opened for that web instead of opening another.
- **Search** strips accents (NFD), scores exact, prefix, start of a word, inside and
  letters in order, and gives actions a small handicap so apps win ties.
- **Swipe down** uses `StatusBarManager.expandNotificationsPanel` through reflection, as
  many launchers do; if Android ever blocks it, the accessibility service opens the shade.
- **The QR reader** takes a camera frame's luminance through the steps of any reader: a
  local threshold, the finder patterns measured along the code's own axes, the alignment
  pattern, a perspective transform, format and version, Reed-Solomon, the segments.
  Versions 1 to 15, square modules or the desktop's dots.
- **The calculator's calculus** works on expression trees: derivatives by the rules,
  simplified enough to read; antiderivatives where the rules reach; definite integrals,
  equations, sums and limits numerically (adaptive Simpson, bisection).
- **Tests** cover the pure core on the JVM: search, the calculator and its calculus, the QR
  reader (codes drawn square and in dots, turned, small, noisy and damaged, and the
  desktop's QR as it shows on a screen), the LED font, the dot matrix and icon statistics.
