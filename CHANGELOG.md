# Changelog

## Unreleased

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
