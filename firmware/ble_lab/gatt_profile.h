// BLE Lab GATT profile. Must match android/ble/.../protocol/GattProfile.kt and ios/.../GattProfile.swift.
#pragma once

#define LAB_SERVICE_UUID     "b1e1ab00-5a1e-4c3a-9f5e-c0de00b1e1ab"
// Notify + read. 7 bytes, little-endian: uint32 sequence, int16 value x100, uint8 flags.
// Flags: bit0 = LED on, bit1 = board has a motion sensor (IMU), bit2 = board has a touchscreen.
// bit1/bit2 were added in 1.1.0; older apps ignore them.
#define TELEMETRY_CHAR_UUID  "b1e1ab01-5a1e-4c3a-9f5e-c0de00b1e1ab"
// Read + write with response, encrypted link required. 1 byte: 0x00 off, 0x01 on.
#define LED_CHAR_UUID        "b1e1ab02-5a1e-4c3a-9f5e-c0de00b1e1ab"
// Notify (1.1.0). Batches of IMU samples, little-endian:
//   header: uint32 t_ms (board clock at the first sample), uint16 first sample index (wraps),
//           uint8 count, uint8 period_ms
//   count x 12 bytes: int16 ax, ay, az in milli-g, int16 gx, gy, gz in 0.1 deg/s
// Batch size follows the negotiated MTU: 1 sample at the default MTU 23, up to 5 at 50 Hz.
// Present on every board; only boards with an IMU ever notify.
#define MOTION_CHAR_UUID     "b1e1ab03-5a1e-4c3a-9f5e-c0de00b1e1ab"
// Notify (1.1.0). One touch event, 8 bytes, little-endian:
//   uint16 event index (wraps), uint8 kind (1 = tap, 2 = long press), uint8 flags (bit0 = injected
//   from the serial test console, not a finger), uint16 x, uint16 y (screen pixels, 320x240)
#define TOUCH_CHAR_UUID      "b1e1ab04-5a1e-4c3a-9f5e-c0de00b1e1ab"
// Write with response (1.1.0), encrypted link required. UTF-8 text, 1..64 bytes, shown on screen.
#define MESSAGE_CHAR_UUID    "b1e1ab05-5a1e-4c3a-9f5e-c0de00b1e1ab"

// Over-the-air update service (1.2.0; signed images and MITM pairing since 1.3.0). Writes need an authenticated link. One image per session:
//   1. central writes START to control, waits for a status notify with state RECEIVING, offset 0
//   2. central streams the .bin as consecutive write-without-response chunks on data, never more
//      than OTA_WINDOW_BYTES ahead of the last offset the board reported
//   3. board notifies status (state RECEIVING, offset = bytes flashed so far) every
//      OTA_ACK_INTERVAL bytes and once when the last byte is in
//   4. central writes FINISH; board checks size, SHA-256 and the image header, then notifies
//      state DONE and reboots about a second later. ABORT (or a disconnect) discards the transfer.
#define OTA_SERVICE_UUID     "b1e1ab10-5a1e-4c3a-9f5e-c0de00b1e1ab"
// Write with response, authenticated (MITM-protected) link required. Commands:
//   0x01 START + uint32 size LE + 32-byte SHA-256 of the whole image + uint32 version code LE +
//        64-byte ECDSA P-256 signature r||s (105 bytes). The board checks the signature over
//        "BLFW1" | size | version | sha256 against its built-in public key, and the version against
//        the running firmware, before it erases or writes anything. See firmware/tools/sign_firmware.py.
//   0x02 ABORT (1 byte), 0x03 FINISH (1 byte).
#define OTA_CONTROL_CHAR_UUID "b1e1ab11-5a1e-4c3a-9f5e-c0de00b1e1ab"
// Write without response. Raw image bytes in order, 1..(MTU - 3) bytes per write.
#define OTA_DATA_CHAR_UUID   "b1e1ab12-5a1e-4c3a-9f5e-c0de00b1e1ab"
// Read + notify, 6 bytes, little-endian: uint8 state, uint8 error, uint32 offset.
// Reading it returns the current state (IDLE after boot, so a phone can tell the board restarted).
#define OTA_STATUS_CHAR_UUID "b1e1ab13-5a1e-4c3a-9f5e-c0de00b1e1ab"

#define OTA_STATE_IDLE       0
#define OTA_STATE_RECEIVING  1
#define OTA_STATE_DONE       2   // image verified, rebooting
#define OTA_STATE_ERROR      3   // transfer discarded; "error" says why

#define OTA_ERR_NONE         0
#define OTA_ERR_BAD_COMMAND  1   // unknown or malformed command
#define OTA_ERR_BAD_STATE    2   // command or data in the wrong state
#define OTA_ERR_TOO_LARGE    3   // image does not fit the OTA slot
#define OTA_ERR_BEGIN        4   // Update.begin failed (no OTA partition)
#define OTA_ERR_WRITE        5   // flash write failed
#define OTA_ERR_OVERFLOW     6   // more bytes than START announced
#define OTA_ERR_INCOMPLETE   7   // FINISH before every byte arrived
#define OTA_ERR_HASH         8   // SHA-256 mismatch
#define OTA_ERR_IMAGE        9   // not a valid ESP32 app image, or activation failed
#define OTA_ERR_ABORTED      10  // ABORT command or disconnect
#define OTA_ERR_BAD_SIGNATURE 11 // unsigned image or signed by another key
#define OTA_ERR_DOWNGRADE    12  // image version is older than the running firmware

#define OTA_CMD_START        0x01
#define OTA_CMD_ABORT        0x02
#define OTA_CMD_FINISH       0x03
#define OTA_START_LENGTH     105
#define OTA_STATUS_LENGTH    6
#define OTA_ACK_INTERVAL     1024
#define OTA_WINDOW_BYTES     4096
#define OTA_REBOOT_DELAY_MS  1000
#define OTA_VALIDATE_AFTER_MS 5000

#define DEVICE_NAME          "ble-lab-esp32"
#define DEVICE_SHORT_NAME    "ble-lab"  // Shortened name (AD type 0x08) in the advert itself.
#define MANUFACTURER_NAME    "Espressif"
#if defined(ARDUINO_M5STACK_CORES3)
#define MODEL_NUMBER         "M5Stack CoreS3"
#else
#define MODEL_NUMBER         "ESP32-WROOM-32"
#endif
// One source for the version: the revision string and the anti-rollback code derive from it.
#define FIRMWARE_VERSION_MAJOR 1
#define FIRMWARE_VERSION_MINOR 3
#define FIRMWARE_VERSION_PATCH 9
#define BLE_LAB_STR_(x) #x
#define BLE_LAB_STR(x) BLE_LAB_STR_(x)
#define FIRMWARE_REVISION                                                                      \
  "ble-lab " BLE_LAB_STR(FIRMWARE_VERSION_MAJOR) "." BLE_LAB_STR(FIRMWARE_VERSION_MINOR) "." \
      BLE_LAB_STR(FIRMWARE_VERSION_PATCH)
// major*10000 + minor*100 + patch; an OTA image with a lower code is refused (anti-rollback).
#define FIRMWARE_VERSION_CODE \
  (FIRMWARE_VERSION_MAJOR * 10000 + FIRMWARE_VERSION_MINOR * 100 + FIRMWARE_VERSION_PATCH)
static_assert(FIRMWARE_VERSION_MINOR < 100 && FIRMWARE_VERSION_PATCH < 100, "version code needs minor and patch below 100");

#define PREFERRED_MTU        247
#define TELEMETRY_PERIOD_MS  200
#define TELEMETRY_LENGTH     7
#define TELEMETRY_FLAG_LED   0x01
#define TELEMETRY_FLAG_IMU   0x02
#define TELEMETRY_FLAG_TOUCH 0x04

#define MOTION_PERIOD_MS     20   // 50 Hz
#define MOTION_HEADER_LENGTH 8
#define MOTION_SAMPLE_LENGTH 12
#define MOTION_MAX_BATCH     5    // 10 notifications a second at 50 Hz

#define TOUCH_LENGTH         8
#define TOUCH_KIND_TAP       1
#define TOUCH_KIND_HOLD      2
#define TOUCH_FLAG_INJECTED  0x01

#define MESSAGE_MAX_BYTES    64
