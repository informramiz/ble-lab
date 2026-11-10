# BLE Lab demo: shot list

Target: 60–90 s. Two sources, cut side by side: a camera on the board (phone held in frame is fine) and a screen recording on the phone (Android: Quick Settings → Screen record, no audio).

Before you start
- Board: CoreS3 on USB power, firmware 1.3.4. Phone: BLE Lab debug app, board **not** paired yet (Settings → Connected devices → forget `ble-lab-esp32` if listed).
- Phone: Do Not Disturb on, brightness up, screen record running.
- Have a signed update file on the phone if you want the OTA shot (I'll put one in Downloads).

Shots
1. **Scan (5 s)**: open the app; `ble-lab-esp32` appears next to the demo device.
2. **Connect (5 s)**: tap it; status goes Connecting → Ready; the Live chart starts moving.
3. **Motion (10 s)**: Motion tab; tilt and shake the board, the charts and tilt dot follow.
4. **Pair + lamp (10 s)**: Board tab, flip the lamp switch. The board shows a 6-digit code; type it on the phone. The lamp on the board screen turns on. Flip again: no prompt now.
5. **Touch (8 s)**: tap and long-press the board screen; events appear in the Touch card. (Never tested on hardware; if it fails, skip it and tell me.)
6. **Message (8 s)**: type "Hello from my phone", Send; the text appears on the board screen.
7. **Firmware update (15 s, optional)**: Firmware card, pick the signed file, progress bar, board reboots, app reconnects showing the new version.
8. **Record (10 s)**: start recording, wait a few seconds, stop; open History, open the session, Share → CSV.
9. **End card (3 s)**: `github.com/informramiz/ble-lab`.

Send me both raw files; I cut them side by side, add short captions per shot, and export MP4 + a short GIF for the README.
