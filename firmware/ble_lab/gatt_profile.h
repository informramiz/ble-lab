// BLE Lab GATT profile. Must match android/ble/.../protocol/GattProfile.kt and ios/.../GattProfile.swift.
#pragma once

#define LAB_SERVICE_UUID     "b1e1ab00-5a1e-4c3a-9f5e-c0de00b1e1ab"
// Notify + read. 7 bytes, little-endian: uint32 sequence, int16 value x100, uint8 flags (bit0 = LED on).
#define TELEMETRY_CHAR_UUID  "b1e1ab01-5a1e-4c3a-9f5e-c0de00b1e1ab"
// Read + write with response, encrypted link required. 1 byte: 0x00 off, 0x01 on.
#define LED_CHAR_UUID        "b1e1ab02-5a1e-4c3a-9f5e-c0de00b1e1ab"

#define DEVICE_NAME          "ble-lab-esp32"
#define MANUFACTURER_NAME    "Espressif"
#define MODEL_NUMBER         "ESP32-WROOM-32"
#define FIRMWARE_REVISION    "ble-lab 1.0.0"

#define PREFERRED_MTU        247
#define TELEMETRY_PERIOD_MS  200
#define TELEMETRY_LENGTH     7
