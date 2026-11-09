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

#define DEVICE_NAME          "ble-lab-esp32"
#define MANUFACTURER_NAME    "Espressif"
#if defined(ARDUINO_M5STACK_CORES3)
#define MODEL_NUMBER         "M5Stack CoreS3"
#else
#define MODEL_NUMBER         "ESP32-WROOM-32"
#endif
#define FIRMWARE_REVISION    "ble-lab 1.1.0"

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
