# BLE Lab demo: shot list

Target: 60–90 s. Two sources, cut side by side: a camera on the board (phone held in frame is fine) and a screen recording on the phone, no audio.

Before you start (either phone)
- Board: CoreS3 on USB power, firmware **1.3.9** or later (the Device information card in the app shows it). From 1.3.9 the board keeps up to three bonded phones; older firmware unpaired one phone when another one paired.
- Phone: Do Not Disturb on, brightness up.
- Have a signed update file on the phone if you want the OTA shot (`firmware/tools/sign_firmware.py` makes it; I'll put one in Downloads / Files).
- Optional, on the PC: `board.py --port COM4 bonds` lists the phones the board has bonded.

## Android

- App: BLE Lab debug build. Board **not** paired yet: Settings → Connected devices → forget `ble-lab-esp32` if listed.
- Screen recording: Quick Settings → Screen record, no audio.

Shots
1. **Scan (5 s)**: open the app; `ble-lab-esp32` appears next to the demo device.
2. **Connect (5 s)**: tap it; status goes Connecting → Ready; the Live chart starts moving.
3. **Motion (10 s)**: Motion tab; tilt and shake the board, the charts and tilt dot follow.
4. **Pair + lamp (10 s)**: Board tab, flip the lamp switch. The board shows a 6-digit code; type it on the phone. The lamp on the board screen turns on. Flip again: no prompt now.
5. **Touch (8 s)**: tap and long-press the board screen; events appear in the Touch card.
6. **Message (8 s)**: type "Hello from my phone", Send; the text appears on the board screen.
7. **Firmware update (15 s, optional)**: Firmware card, pick the signed file, progress bar, board reboots, app reconnects showing the new version.
8. **Record (10 s)**: start recording, wait a few seconds, stop; open History, open the session, Share → CSV.
9. **End card (3 s)**: `github.com/informramiz/ble-lab`.

## iPhone

Install
- Open `ios/BLELab.xcodeproj` in Xcode, set your team under Signing & Capabilities, pick the iPhone as the run destination and press Run (iOS 17+, bundle id `com.codebyramiz.blelab`). If iOS refuses to open it the first time, trust the developer under Settings → General → VPN & Device Management.
- Allow Bluetooth when the app asks.

Before each take
- Settings → Bluetooth → ⓘ next to `ble-lab-esp32` → **Forget This Device**, if it is listed. Otherwise iOS reuses the old bond and the pairing prompt never appears.
- Screen recording: Control Center → Screen Recording (add it in Control Center settings if it is missing). Long-press it to make sure the microphone is off. Recording starts after a 3-second countdown; stop it from the red indicator at the top. The video goes to Photos.

What iOS shows when you pair
- The app has no pairing button. iOS asks to pair the first time the app writes the encrypted LED switch (or subscribes to OTA status when you tap Update).
- The system alert is titled **Bluetooth Pairing Request** and reads roughly *"ble-lab-esp32" would like to pair with your iPhone. Enter the code shown on "ble-lab-esp32".*, with a code field, Cancel and Pair. Type the 6 digits from the board screen and tap Pair.
- While it waits, the LED card says "Waiting for the board. If iOS asks to pair, type the code shown on the board." An unanswered prompt times out after 45 s with an error under the switch; Cancel shows a pairing error there too.

Shots (what differs from Android)
1. **Scan (5 s)**: the list is titled BLE Lab, section Nearby; `ble-lab-esp32` appears next to "BLE Lab (demo)" (DEMO badge). Tap **Scan** top right if it is not scanning.
2. **Connect (5 s)**: tap it; the status card goes Connecting → Discovering services → Connected and the Live telemetry chart starts moving.
3. **Motion (10 s)**: segmented control at the top → **Motion**: Tilt card, Acceleration and Rotation charts.
4. **Pair + LED (10 s)**: on iOS the switch is on the **Live** tab (Onboard LED card, below the chart), not on Board. Flip it; the board shows the code; the alert above appears; type the code, Pair. The switch shows On only once the board confirms. Flip again: no prompt.
5. **Touch (8 s)**: **Board** tab, Touch events card; tap and long-press the board screen.
6. **Message (8 s)**: Board tab, Message to the board; type "Hello from my phone", Send; the card shows "Shown on the board: …".
7. **Firmware update (15 s, optional)**: Live tab, Firmware update card → Choose .bin file (opens Files) → the `.signed.bin` → Update. Status: Sending N% → Verifying the image → Board is restarting, reconnecting → "Updated. The board now runs …". Not yet tested from iOS on the real board; skip it if it fails.
8. **Record (10 s)**: Live tab, Recording card → Record, wait, Stop and save; back on the scan list tap History (top left) → the session → Export CSV.
9. **End card (3 s)**: `github.com/informramiz/ble-lab`.

Send me both raw files; I cut them side by side, add short captions per shot, and export MP4 + a short GIF for the README.
