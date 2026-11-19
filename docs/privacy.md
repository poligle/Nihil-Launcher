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
| Notification access, optional  | The light on the frame and the unread icons                       |
| Accessibility, optional        | To lock the phone on a double tap, and the Glyph light over other apps |

`INTERNET` reaches only the paired computer, over HTTPS pinned to its key; the webs'
icons come through it too. The camera's frames never leave the phone: NIHIL reads the QR
itself. Bluetooth is for the same computer, when its addresses don't answer: the phone looks
only for the UUID its pin makes, never for where it is (`neverForLocation`), and on the channel
goes the same HTTPS, pinned to its key. Of a notification, NIHIL looks at which app posted it,
its flags and its category, never its text; silent notifications and anything Do Not
Disturb holds back stay dark. The accessibility service reads no content and only
listens to NIHIL's own windows; the light it draws is a window of its own that takes no
touch, and is there only while the light runs.
