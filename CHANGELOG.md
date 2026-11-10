# Changelog

## Unreleased

- The deck forgets the computer with a cross instead of a bin, and its dots have no names
  beside them.
- The home screen's favourites fade where they meet the clock, instead of going over it.
- Webs on the phone too: Add a web, holding the home screen, or Add to NIHIL from a
  browser's share sheet. They are in the drawer among the apps, go on the home screen as
  apps do, and open in Chrome (or the phone's browser without it), each in its own tab.
  Their icons come from the paired computer, as the deck's webs' do; without it, their
  letter.
- Tasks: held and moved, a card goes to another place in its column, and the desktop has
  the same order (each card has a rank there, as the desktop's: `core/TaskBoard.kt`). A
  pencil on each card writes it again. A side from before doesn't undo an order.
- The deck's third workspace is the computer's trackpad: the fingers go to a touchpad
  there, and its own driver makes of them what the laptop's touchpad does (a tap clicks,
  two fingers right-click and scroll, three middle-click, a pinch zooms, and the desktop's
  gestures run). LEDs light up under the fingers. It needs NIHIL on the computer from this
  day on.

## 0.3.2 — 2026-11-09

- The deck's top has the computer's caffeine, night light and lock, with the desktop's own
  icons, lit while on (NIHIL on the computer from this day on). The lock is no longer under
  the volume.
- The phone's screen staying on in the deck is a setting now: Settings, Screen on in the deck.
- The cover of what plays is in greys, as everything else.
- No NIHIL over the deck and the tasks; in Settings, and on the deck before pairing, NIHIL is
  the logo's own dots, with its one-column I.

## 0.3.1 — 2026-11-09

- The README and nihilshell.com/android wear the launcher's own logo, the Android robot in
  dots before NIHIL, instead of the old ring and the desktop's snowflake; the website has
  the icon as its favicon and its own social image (`docs/brand/social-preview.png`).
- CI: a dependency that fails to download is tried 8 times instead of 3, and a new version
  no longer starts the build without its cache.

## 0.3.0 — 2026-11-08

- Tasks: the desktop's kanban on the phone, synced through the deck. Each side goes on alone
  and the next sync keeps what both did: the text written last and the column chosen last
  win for each card, a deletion wins over anything older (`core/TaskBoard.kt`).
- The deck: the cover of what plays; caffeine, the cup at the top, keeps the screen on; apps
  and webs put in order by holding one and moving it; "Connected: address".
- The home screen's favourites scroll when there are more than fit under the clock, and move
  when held, as the deck's tiles.
- The studio applies a wallpaper with one key, and then asks where: home screen, lock screen
  or both.
- Holding the home screen no longer offers the system's wallpaper.
- Everything is written in sentence case, and nothing is set apart with "·" any more.
- The icon is only the robot's lit dots, on black; the README and the website show it, and the
  website uses it as its favicon.
- More room above "Try the light".

## 0.2.0 — 2026-11-08

- The Glyph light for notifications runs on the home screen even when a heads-up
  notification takes the focus (One UI did, and the light never ran). Settings says whether
  Android has connected NIHIL to the notifications, and **Try the light** runs it.
- The battery charging is the desktop's: a light scans from the level to the end, an LED
  every 160 ms, each one fading in and out.
- Settings' headings are written as the desktop's: Look, Behaviour, Apps, About.
- The version is one line (`nihilVersion`); Android's versionCode comes from it, so every
  release and pre-release goes up on its own.
- The deck is landscape and fits one screen, in workspaces as the desktop's: the music,
  the volume and the lock in one; the apps and the webs, each scrolling on its own, in the
  other. Swipe between them or tap their dots. Forgetting the computer is the bin at the top.
- "Open on the computer" from the share sheet works over whatever is on screen, without
  turning the phone.
- The calculator is nearer the desktop's (qalc): percentages as it reads them
  (200 + 15% = 230), `mod`, degrees (sin(30°)), 0x 0b 0o numbers and conversions into hex,
  bin and oct, more functions (ceil, trunc, hyperbolics, min, max, hypot, gcd, lcm, root,
  log with a base) and unit conversions: `10 km to mi`, `100 °F a °C`, `1 gb to mb`, also
  without `=`. `%` is now a percentage, as on the desktop; the remainder is `mod`.
- Settings, Language (Android 13 and later): NIHIL can speak English or Spanish whatever
  the phone's language.
- About: no more "no internet permission"; the link goes to nihilshell.com/android.
- NIHIL's icon is the Android robot in dots over a round matrix, as the desktop wears the
  NixOS snowflake in dots; with its monochrome layer for themed icons, and its attribution
  (Creative Commons 3.0 BY) in About and the README.
- Calculus in the calculator, as the desktop's qalc does it: `diff(x^2)` = 2x and its value
  at a point, `integrate(x^2)` = x^3/3 + C where the rules reach and definite integrals to
  `inf`, `solve(x^2 = 4)`, `sum`, `product` and `limit`; with Spanish names too.
- README: the new look, the deck, the calculator and the icon.

- The deck: the phone as a remote for NIHIL on the computer (Nihil-Shell's docs/deck.md).
  Pairing with the QR in the computer's Preferences, Phone, read by NIHIL's own QR reader
  (core/QrDecoder.kt: no libraries, the camera's frames never leave the phone); HTTPS that
  trusts only the computer's pinned key; the token encrypted with the Android Keystore and
  kept out of backups. Its music, volume and lock; its apps with their icons and the webs
  kept here with theirs, which the computer finds; sharing a web opens it there. From the
  drawer's laptop key, `> computer` and Settings. Two permissions, for it alone: INTERNET,
  only ever to the paired computer, and CAMERA, only to read its QR.

- It looks like the desktop now, piece by piece from home/shell:
  - The drawer is the desktop's launcher: the apps in a grid, monochrome, the first
    result raised and in colour; the field at the bottom, on the surface with a hairline
    and how many apps it holds; under it the keys `>` and `=`, which start each mode.
    Actions, the web search and the calculator stay as rows across.
  - The home screen's clock is a card like the desktop's clock widget: the time in Doto,
    the day and the date, and under a hairline the battery, the next alarm and the unread
    apps.
  - Settings are the desktop's Preferences: a heading for each section saying what it is
    for, settings with their name on the left and the control on the right, hairlines
    between them; the desktop's toggle, choice and key buttons.
  - Labels are written as they are, in Space Mono, without capitals; notices are cards like
    the desktop's notifications; the app menu is the desktop's black card.

- The Glyph light is the desktop's: a segment that runs round clockwise from the top-right
  corner (red and twice for a call or an alarm), and the charger's two lights rising, as
  strokes with their glow instead of LEDs.
- Over other apps too: the accessibility service, when it is on, draws the light round the
  screen's own corners in a window that takes no touch and goes when the light does.
- The notification listener is bound again when Android dropped it after an update.
- NIHIL's own wallpaper, the night in LEDs, the first time it is the home app on a fresh
  install. An update never changes it.
- A paused work profile's apps show half lit, and suspended apps update at once.
- TalkBack hears the switches as switches, on or off, and which option is chosen.
- Each icon is painted once however many rows ask for it; fewer allocations as the home
  screen and the drawer draw.
- CI: Android lint as a report; a release's versionCode must be above the last one's.

- Settings: when the Glyph light or the double tap is on but Android hasn't granted its
  access yet, tapping the row opens the access page instead of switching it off.
- README: a proper one, with the design language, settings, the drawer's grammar and the
  structure of the code.

## 0.1.0 — 2026-10-29

The first version.

- The frame: the wallpaper as a rounded card set into a black screen; the
  drawer is the frame growing over it.
- A 25 × 7 LED clock, the battery as ten LEDs, the next alarm, unread apps.
- The Glyph light around the frame for notifications, calls, alarms and the
  charger (optional, needs notification access).
- One search field for apps and actions, accents and case ignored; `>` for
  actions, `=` for the calculator.
- App menu with shortcuts, favourites, hide, info and uninstall.
- Wallpaper studio: a photo as an LED matrix at the screen's resolution.
- Settings: icon style (mono, grey, colour), dimmed wallpaper, keyboard on
  open, Glyph light, double tap to lock (optional, accessibility).
- English and Spanish. No internet permission.
