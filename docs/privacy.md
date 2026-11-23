# Permissions

| Permission                     | What for                                                          |
|--------------------------------|-------------------------------------------------------------------|
| `SET_WALLPAPER`                | The studio, and NIHIL's wallpaper the first time it is home       |
| `EXPAND_STATUS_BAR`            | Swipe down for the notifications                                  |
| `REQUEST_DELETE_PACKAGES`      | "Uninstall" in the app menu                                       |
| `INTERNET`                     | The deck, only ever to the computer it was paired with            |
| `CAMERA`                       | The deck, to read the computer's pairing QR                       |
| `BLUETOOTH_SCAN`               | The deck, to find the paired computer when no network reaches it  |
| `BLUETOOTH_CONNECT`            | The deck, to open a channel to that computer                      |
| `WRITE_EXTERNAL_STORAGE` (Android 9 only) | Vector, to save what the computer sends in `Download/Vector` |
| Notification access, optional  | The light on the frame and the unread icons                       |
| Accessibility, optional        | To lock the phone on a double tap, and the Glyph light over other apps |

`INTERNET` reaches only the paired computer, over HTTPS pinned to its key; the webs'
icons come through it too. The camera's frames never leave the phone: NIHIL reads the QR
itself. Bluetooth is for the same computer, when its addresses don't answer: the phone looks
only for the UUID its pin makes, never for where it is (`neverForLocation`), and on the channel
goes the same HTTPS, pinned to its key. It is Android 12 and later's: before that, the deck
goes over the network alone. Saving what Vector brings is the one thing
`WRITE_EXTERNAL_STORAGE` is for, and only on Android 9, where the Downloads folder is still a
file to write. Of a notification, NIHIL looks at which app posted it, its flags and its
category, never its text; silent notifications and anything Do Not Disturb holds back stay dark.
The accessibility service reads no content and only listens to NIHIL's own windows; the light
it draws is a window of its own that takes no touch, and is there only while the light runs.
