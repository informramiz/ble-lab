// Over-the-air update receiver: the transfer state machine over the Arduino-ESP32 Update library.
// Transport-agnostic, so the BLE glue in ble_lab.ino stays thin. Called from the Bluedroid task.
// Update API: https://github.com/espressif/arduino-esp32/tree/master/libraries/Update
#pragma once
#include <Update.h>
#include <esp_ota_ops.h>
#include <stdio.h>
#include <string.h>
#include "gatt_profile.h"
#include "ota_verify.h"

class OtaReceiver {
 public:
  uint8_t state() const { return state_; }
  uint8_t error() const { return error_; }
  uint32_t offset() const { return offset_; }
  uint32_t size() const { return size_; }
  bool active() const { return state_ == OTA_STATE_RECEIVING; }
  // True when the offset crossed an ack boundary or reached the end since the last call.
  bool takeAckDue() {
    bool due = ackDue_;
    ackDue_ = false;
    return due;
  }

  // payload: OTA_CMD_START already stripped: size LE, SHA-256, version code LE, signature.
  bool start(const uint8_t *payload, size_t length) {
    if (state_ == OTA_STATE_DONE) return false;
    if (length != OTA_START_LENGTH - 1) return fail(OTA_ERR_BAD_COMMAND);
    if (state_ == OTA_STATE_RECEIVING) Update.abort();
    uint32_t size = payload[0] | (payload[1] << 8) | (payload[2] << 16) | ((uint32_t)payload[3] << 24);
    const esp_partition_t *slot = esp_ota_get_next_update_partition(nullptr);
    if (slot == nullptr) return fail(OTA_ERR_BEGIN);
    if (size == 0 || size > slot->size) return fail(OTA_ERR_TOO_LARGE);
    uint32_t version = payload[36] | (payload[37] << 8) | (payload[38] << 16) | ((uint32_t)payload[39] << 24);
    // Authenticity first, before anything is erased: only an image signed by the built-in key gets in.
    if (!ota_verify::manifestSigned(size, version, payload + 4, payload + 40)) return fail(OTA_ERR_BAD_SIGNATURE);
    if (version < FIRMWARE_VERSION_CODE) return fail(OTA_ERR_DOWNGRADE);
    char hex[65];
    for (int i = 0; i < 32; i++) snprintf(hex + 2 * i, 3, "%02x", payload[4 + i]);
    if (!Update.begin(size, U_FLASH) || !Update.setSHA256(hex)) {
      Update.abort();
      return fail(OTA_ERR_BEGIN);
    }
    size_ = size;
    offset_ = 0;
    error_ = OTA_ERR_NONE;
    state_ = OTA_STATE_RECEIVING;
    ackDue_ = true;  // Tells the phone the board is ready for data.
    return true;
  }

  bool data(const uint8_t *bytes, size_t length) {
    if (state_ != OTA_STATE_RECEIVING) return false;  // Late chunks after an error are dropped.
    if (offset_ + length > size_) return abortWith(OTA_ERR_OVERFLOW);
    if (Update.write(const_cast<uint8_t *>(bytes), length) != length) return abortWith(OTA_ERR_WRITE);
    uint32_t before = offset_;
    offset_ += length;
    if (offset_ == size_ || offset_ / OTA_ACK_INTERVAL != before / OTA_ACK_INTERVAL) ackDue_ = true;
    return true;
  }

  // Verifies size, the SHA-256 the signature covers (inside Update.end, before the slot becomes
  // bootable) and the image header.
  bool finish() {
    if (state_ != OTA_STATE_RECEIVING) return fail(OTA_ERR_BAD_STATE);
    if (offset_ != size_) return abortWith(OTA_ERR_INCOMPLETE);
    if (!Update.end()) {
      uint8_t code = Update.getError();
      return abortWith(code == UPDATE_ERROR_SHA256 ? OTA_ERR_HASH : OTA_ERR_IMAGE);
    }
    state_ = OTA_STATE_DONE;
    return true;
  }

  void abort(uint8_t reason = OTA_ERR_ABORTED) {
    if (state_ == OTA_STATE_RECEIVING) abortWith(reason);
  }

 private:
  bool fail(uint8_t code) {
    error_ = code;
    state_ = OTA_STATE_ERROR;
    ackDue_ = true;
    return false;
  }
  bool abortWith(uint8_t code) {
    Update.abort();
    return fail(code);
  }

  volatile uint8_t state_ = OTA_STATE_IDLE;
  volatile uint8_t error_ = OTA_ERR_NONE;
  volatile uint32_t offset_ = 0;
  uint32_t size_ = 0;
  volatile bool ackDue_ = false;
};
