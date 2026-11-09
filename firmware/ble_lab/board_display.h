// Optional on-board display. On M5Stack CoreS3 (ESP32-S3, 320x240 screen) the "LED" is drawn as a
// lamp on screen next to the connection state and the live reading; other boards are no-ops.
// BLE callbacks run on the Bluedroid task, so they only mark the screen dirty; loop() redraws.
#pragma once
#include <stdint.h>

#if defined(ARDUINO_M5STACK_CORES3)
#define BOARD_HAS_DISPLAY 1
#include <M5Unified.h>

namespace board_display {

static void begin() {
  auto cfg = M5.config();
  cfg.serial_baudrate = 0;  // Serial is opened by the sketch.
  M5.begin(cfg);
  M5.Display.setRotation(1);
  M5.Display.fillScreen(TFT_BLACK);
}

static void draw(bool connected, bool ledOn, int16_t valueX100, uint32_t sequence) {
  auto &d = M5.Display;
  d.startWrite();
  d.fillScreen(TFT_BLACK);

  d.setTextDatum(top_left);
  d.setTextColor(TFT_WHITE, TFT_BLACK);
  d.setFont(&fonts::FreeSansBold12pt7b);
  d.drawString("BLE Lab", 12, 10);

  d.setFont(&fonts::FreeSans9pt7b);
  d.setTextColor(connected ? TFT_GREEN : TFT_ORANGE, TFT_BLACK);
  d.drawString(connected ? "Connected" : "Advertising...", 12, 44);

  d.setTextColor(TFT_LIGHTGREY, TFT_BLACK);
  d.drawString("Die temperature", 12, 84);
  d.setTextColor(TFT_WHITE, TFT_BLACK);
  d.setFont(&fonts::FreeSansBold24pt7b);
  char buf[16];
  snprintf(buf, sizeof(buf), "%.2f C", valueX100 / 100.0f);
  d.drawString(buf, 12, 106);

  d.setFont(&fonts::FreeSans9pt7b);
  d.setTextColor(TFT_LIGHTGREY, TFT_BLACK);
  snprintf(buf, sizeof(buf), "seq %lu", (unsigned long)sequence);
  d.drawString(buf, 12, 206);

  // Lamp: filled yellow when on, outlined grey when off.
  const int cx = 262, cy = 128, r = 34;
  if (ledOn) {
    d.fillCircle(cx, cy, r, TFT_YELLOW);
  } else {
    d.fillCircle(cx, cy, r, TFT_BLACK);
    d.drawCircle(cx, cy, r, TFT_DARKGREY);
  }
  d.setTextDatum(top_center);
  d.setTextColor(TFT_LIGHTGREY, TFT_BLACK);
  d.drawString(ledOn ? "LED on" : "LED off", cx, cy + r + 10);
  d.endWrite();
}

}  // namespace board_display

#else
#define BOARD_HAS_DISPLAY 0
namespace board_display {
static void begin() {}
static void draw(bool, bool, int16_t, uint32_t) {}
}  // namespace board_display
#endif
