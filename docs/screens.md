# Ludolog on any screen

How Ludolog fits different screens, and what a theme needs to look right on all of them. Ludolog
always runs in landscape: it flips 180° if you turn the device over, but never goes portrait.

## Screen shapes

Ludolog arranges itself by its window's aspect ratio. There is nothing to configure.

| shape | aspect ratio | layout |
|---|---|---|
| wide (16:9, 20:9) | 1.7 or more | the list on the left, the console or picture on the right |
| medium (4:3, 3:2, 5:3) | 1.25 to 1.7 | the same, with the list at its narrowest and the console and its text filling the rest |
| nearly square | under 1.25 | Mainframe and Gallery put the picture on top and the list below; Parlour stays as on medium screens |

The list takes about a third of the width, never less than on a 1920×1080 screen and never more
than half, so game names stay readable. The themes were drawn for 16:9 at 1080p.

## Dual screens

On a device with two screens, the list goes on the bottom screen and the top one keeps the scene:
the room, the spinning console, the box art and the video. The gamepad drives the list, which also
responds to touch, while shoulder buttons and triggers stay with the top screen (L+R opens apps,
L2+R2 the Companion). If the second screen turns off, the list moves back to the top. This can be
turned off in **Settings → Interface → Second screen**, shown only on dual-screen devices.

## What a theme needs to know

**A room (like Parlour)** is a small pixel-art image that Ludolog scales by a whole number to fill
the screen's height, so pixels stay square. Narrower screens crop it on the left, behind the list;
wider ones fill the gap on the left with the theme's background colour. So:

- keep what matters in the right half, and nothing important at the top and bottom edges;
- in the room's `tvs.toml`, `corners` (the TV screen) is in fractions of the room image, and
  `console` and `caption` (the spinning console and its text) are in fractions of a 16:9 screen.
  On medium and nearly square screens Ludolog places the console and caption itself.

With these rules one 16:9 room works on every screen.

**A flat backdrop (like Mainframe and Gallery)**, `backdrop.jpg`, `.png` or `.mp4`, is scaled to
fill the screen and cropped at the edges without stretching: keep what matters in the centre.
**Console spins** are fitted inside their box on any screen, with nothing to do.

## Testing

The Android emulator is enough to try other shapes: change its resolution with
`adb shell wm size` and `adb shell wm density`, and add a second screen with
`adb shell settings put global overlay_display_devices 1240x1080/320`. The gamepad, performance
and the physical screen still need checking on a real device.
