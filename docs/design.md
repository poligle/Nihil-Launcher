# Design

The same three rules as the desktop:

1. **Hierarchy is made with greys.** Red only interrupts: low battery, a call, an alarm,
   "Uninstall".
2. **Dots are for display.** The clock and the battery are LEDs, Doto sets the big
   numbers; text is Space Grotesk and metadata uppercase Space Mono.
3. **The frame is hardware.** Opaque, flat, no shadows. The drawer is part of it, not a
   sheet on top.

The tokens are the desktop's ([`theme/nihil.nix`](https://github.com/poligle/Nihil-Shell/blob/nihil/theme/nihil.nix)),
mirrored in [`ui/Nihil.kt`](../app/src/main/java/com/nihilshell/launcher/ui/Nihil.kt):

| Token     | Hex       | Use                               |
|-----------|-----------|-----------------------------------|
| `void`    | `#000000` | Frame, drawer, menus              |
| `raised`  | `#1A1A1A` | The selected result, the sums     |
| `line`    | `#2A2A2A` | 1 px lines, outlines              |
| `muted`   | `#7A7A7A` | Metadata, hints                   |
| `dim`     | `#A3A3A3` | Secondary text, icons             |
| `ink`     | `#F2F2F2` | Text, lit LEDs                    |
| `signal`  | `#D71921` | Interrupts only                   |

Motion uses one curve, `cubic-bezier(0.2, 0, 0, 1)`, at 140, 240, 320 or 480 ms.
