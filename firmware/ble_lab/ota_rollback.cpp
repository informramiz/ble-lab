// Overrides the core's weak verifyRollbackLater() hook (esp32-hal-misc.c). A freshly flashed image
// boots as "pending verify"; by default the core confirms it during startup, before setup() runs.
// Returning true defers that: ble_lab.ino confirms it with esp_ota_mark_app_valid_cancel_rollback()
// after BLE is up and loop() has run for a few seconds. If the image crashes or resets before that,
// the bootloader rolls back to the previous slot on the next reset.
extern "C" bool verifyRollbackLater() {
  return true;
}
