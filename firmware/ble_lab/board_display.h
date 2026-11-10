// Optional on-board display. On M5Stack CoreS3 (ESP32-S3, 320x240 screen) the board shows the
// connection state, the live reading, the "LED" as a lamp, a spirit-level tilt widget, the last
// message from the phone and a ripple where the screen was touched. Other boards are no-ops.
// BLE callbacks run on the Bluedroid task, so they only mark the screen dirty; loop() redraws.
#pragma once
#include <stdint.h>

namespace board_display {

struct Frame {
  bool connected;
  bool ledOn;
  int16_t valueX100;
  uint32_t sequence;
  const char *message;  // Last text from the phone, "" if none.
  bool hasImu;
  int16_t rippleX, rippleY;
  int32_t rippleAgeMs;  // < 0: no ripple.
};

}  // namespace board_display

#if defined(ARDUINO_M5STACK_CORES3)
#define BOARD_HAS_DISPLAY 1
#include <M5Unified.h>
#include <math.h>

namespace board_display {

// Off-screen frame buffer: draw the whole frame here, then push it in one go, so redraws
// don't flash. 320x240x16 bit = 150 KB, kept in PSRAM. A full push takes ~30 ms over SPI, so
// it runs at 2 Hz; the tilt widget has its own small sprite that is pushed at 10 Hz.
static M5Canvas canvas(&M5.Display);
static M5Canvas tilt(&M5.Display);

// The layout is landscape. Every x/y below is derived from the live display size, so the
// right-hand column and the footer follow the real edges instead of a hardcoded 320x240.
static int W = 320, H = 240;
static constexpr int TILT_SIZE = 84;
static int tiltX() { return W - TILT_SIZE - 10; }
static int tiltY() { return H - TILT_SIZE - 16; }  // Clear of the footer line.

static constexpr uint16_t BG = 0x0841;      // near-black blue
static constexpr uint16_t CARD = 0x18E5;    // slate card
static constexpr uint16_t ACCENT = 0x5D1F;  // indigo-blue
static constexpr uint16_t MUTED = 0x9CF3;   // grey text
static constexpr uint16_t GOOD = 0x2EA8;    // green
static constexpr uint16_t WARN = 0xFD20;    // orange
static constexpr uint16_t LAMP = 0xFF00;    // warm yellow

static void begin() {
  auto cfg = M5.config();
  cfg.serial_baudrate = 0;  // Serial is opened by the sketch.
  M5.begin(cfg);
  // The CoreS3 panel (ILI9342C/E) is natively 320x240 and M5GFX sets it up with
  // offset_rotation 0 and rotation 0, which is already landscape. setRotation(1) swaps
  // width and height and turns it portrait (240x320), which clipped the old fixed layout.
  // Keep M5's default and only flip by 90 degrees if a panel ever comes up portrait.
  // M5.Touch maps raw points through the same rotation, so touch x/y stay in screen pixels.
  if (M5.Display.width() < M5.Display.height()) {
    M5.Display.setRotation((M5.Display.getRotation() + 1) & 3);
  }
  W = M5.Display.width();
  H = M5.Display.height();
  M5.Display.fillScreen(BG);
  canvas.setPsram(true);
  canvas.setColorDepth(16);
  if (!canvas.createSprite(M5.Display.width(), M5.Display.height())) {
    canvas.setPsram(false);  // PSRAM off in the board options: fall back to internal RAM.
    canvas.createSprite(M5.Display.width(), M5.Display.height());
  }
  tilt.setColorDepth(16);
  tilt.createSprite(TILT_SIZE, TILT_SIZE);
}

// Serial `tap x y` / `hold x y` use the same screen pixels as a finger; keep them on screen.
// Debug screenshot as a PNG (malloc'd, caller free()s). panel=true reads the real panel back
// (needs working readback, includes the tilt widget); panel=false composes the canvas and the
// tilt sprite in RAM and never touches the panel. The composed path draws the tilt widget into
// the canvas, which the next draw() overwrites.
static void *snapshotPng(bool panel, size_t *len) {
  if (panel) return M5.Display.createPng(len, 0, 0, M5.Display.width(), M5.Display.height());
  tilt.pushSprite(&canvas, tiltX(), tiltY());
  return canvas.createPng(len, 0, 0, canvas.width(), canvas.height());
}

static void clampToScreen(int &x, int &y) {
  x = x < 0 ? 0 : (x >= W ? W - 1 : x);
  y = y < 0 ? 0 : (y >= H ? H - 1 : y);
}

static bool isAscii(const char *s) {
  for (; *s; ++s) {
    if ((uint8_t)*s >= 0x80) return false;
  }
  return true;
}

static void drawMessageCard(M5Canvas &d, const char *message) {
  const int x = 8, y = 132, w = tiltX() - 20, h = H - 158;
  d.fillRoundRect(x, y, w, h, 12, CARD);
  d.setTextDatum(top_left);
  d.setFont(&fonts::FreeSans9pt7b);
  d.setTextColor(MUTED);
  d.drawString("Message from phone", x + 10, y + 8);
  d.setClipRect(x + 8, y + 30, w - 16, h - 34);
  d.setTextWrap(true, false);
  if (message[0] == 0) {
    d.setTextColor(MUTED);
    d.setCursor(x + 10, y + 34);
    d.print("Send one from the app");
  } else {
    // Non-ASCII text (accents, CJK, ...) needs the Unicode bitmap font.
    if (isAscii(message)) {
      d.setFont(&fonts::FreeSansBold9pt7b);
    } else {
      d.setFont(&fonts::efontJA_16);
    }
    d.setTextColor(TFT_WHITE);
    d.setCursor(x + 10, y + 34);
    d.print(message);
  }
  d.clearClipRect();
}

static void draw(const Frame &f) {
  auto &d = canvas;
  d.fillScreen(BG);

  // Header: title and a status pill.
  d.setTextDatum(top_left);
  d.setTextColor(TFT_WHITE);
  d.setFont(&fonts::FreeSansBold12pt7b);
  d.drawString("BLE Lab", 12, 12);
  const uint16_t stateColor = f.connected ? GOOD : WARN;
  const char *stateText = f.connected ? "Connected" : "Advertising";
  d.setFont(&fonts::FreeSans9pt7b);
  const int pillW = d.textWidth(stateText) + 30;
  d.fillRoundRect(W - pillW - 10, 10, pillW, 26, 13, CARD);
  d.fillCircle(W - pillW + 4, 23, 5, stateColor);
  d.setTextColor(stateColor);
  d.drawString(stateText, W - pillW + 16, 15);

  // Temperature.
  d.setTextColor(MUTED);
  d.drawString("Chip temperature", 12, 50);
  d.setTextColor(TFT_WHITE);
  d.setFont(&fonts::FreeSansBold24pt7b);
  char buf[24];
  snprintf(buf, sizeof(buf), "%.1f", f.valueX100 / 100.0f);
  d.drawString(buf, 12, 72);
  const int unitX = 16 + d.textWidth(buf);
  d.setFont(&fonts::FreeSans12pt7b);
  d.setTextColor(MUTED);
  d.drawString("C", unitX, 76);

  // Lamp: glowing when on, outlined when off.
  const int cx = W - 48, cy = 84, r = 22;
  if (f.ledOn) {
    d.fillCircle(cx, cy, r + 8, 0x4A40);
    d.fillCircle(cx, cy, r + 4, 0x9C80);
    d.fillCircle(cx, cy, r, LAMP);
  } else {
    d.drawCircle(cx, cy, r, MUTED);
  }
  d.setTextDatum(top_center);
  d.setFont(&fonts::FreeSans9pt7b);
  d.setTextColor(MUTED);
  d.drawString(f.ledOn ? "LED on" : "LED off", cx, cy + r + 10);

  drawMessageCard(d, f.message);

  // Tilt widget label (the bubble lives in its own sprite, pushed separately).
  d.setTextDatum(top_center);
  d.setTextColor(MUTED);
  d.drawString(f.hasImu ? "Tilt" : "No IMU", tiltX() + TILT_SIZE / 2, tiltY() - 18);

  // Footer.
  d.setTextDatum(top_left);
  d.setFont(&fonts::Font0);
  d.setTextColor(MUTED);
  snprintf(buf, sizeof(buf), "seq %lu", (unsigned long)f.sequence);
  d.drawString(buf, 12, H - 14);
  d.setTextDatum(top_right);
  d.drawString("tap the screen to ping the phone", W - 12, H - 14);

  // Touch ripple: expanding rings that fade out over 600 ms.
  if (f.rippleAgeMs >= 0 && f.rippleAgeMs < 600) {
    const int grow = f.rippleAgeMs / 12;
    d.drawCircle(f.rippleX, f.rippleY, 10 + grow, ACCENT);
    d.drawCircle(f.rippleX, f.rippleY, 11 + grow, ACCENT);
    if (grow > 12) d.drawCircle(f.rippleX, f.rippleY, grow - 2, ACCENT);
  }
  d.pushSprite(0, 0);
}

// Spirit level: the bubble drifts away from the low side, like a real one. ax/ay in g.
static void drawTilt(bool hasImu, float ax, float ay) {
  auto &t = tilt;
  const int c = TILT_SIZE / 2, r = c - 2;
  t.fillScreen(BG);
  t.fillCircle(c, c, r, CARD);
  t.drawCircle(c, c, r, MUTED);
  t.drawCircle(c, c, r / 3, MUTED);
  t.drawFastHLine(c - r + 4, c, 2 * r - 8, 0x4208);
  t.drawFastVLine(c, c - r + 4, 2 * r - 8, 0x4208);
  if (hasImu) {
    float bx = -ax, by = ay;
    const float len = sqrtf(bx * bx + by * by);
    if (len > 1.0f) {
      bx /= len;
      by /= len;
    }
    const int px = c + (int)lroundf(bx * (r - 10));
    const int py = c + (int)lroundf(by * (r - 10));
    t.fillCircle(px, py, 9, len < 0.05f ? GOOD : ACCENT);
    t.fillCircle(px - 3, py - 3, 3, TFT_WHITE);
  }
  t.pushSprite(tiltX(), tiltY());
}

// Firmware update screen: percent 0..100, or -1 for "verifying and restarting".
static void drawPasskey(uint32_t passkey) {
  auto &d = canvas;
  d.fillScreen(BG);
  d.setTextDatum(top_left);
  d.setTextColor(TFT_WHITE);
  d.setFont(&fonts::FreeSansBold12pt7b);
  d.drawString("Pairing", 12, 12);
  d.setFont(&fonts::FreeSans9pt7b);
  d.setTextColor(MUTED);
  d.drawString("Type this passkey on the phone", 12, 50);
  char buf[8];
  snprintf(buf, sizeof(buf), "%06u", (unsigned)passkey);
  d.setFont(&fonts::FreeSansBold24pt7b);
  d.setTextColor(ACCENT);
  d.setTextDatum(top_center);
  d.drawString(buf, W / 2, 110);
  d.pushSprite(0, 0);
}

static void drawUpdate(int percent, bool failed) {
  auto &d = canvas;
  d.fillScreen(BG);
  d.setTextDatum(top_left);
  d.setTextColor(TFT_WHITE);
  d.setFont(&fonts::FreeSansBold12pt7b);
  d.drawString(failed ? "Update failed" : "Updating firmware", 12, 12);
  d.setFont(&fonts::FreeSans9pt7b);
  d.setTextColor(MUTED);
  d.drawString(percent < 0 ? "Verifying, restarting..." : "Keep the phone close", 12, 50);
  const int barW = W - 40;
  d.fillRoundRect(20, 110, barW, 22, 11, CARD);
  const int fill = percent < 0 ? barW : (barW * percent) / 100;
  if (fill > 0) d.fillRoundRect(20, 110, fill < 22 ? 22 : fill, 22, 11, failed ? WARN : ACCENT);
  char buf[8];
  snprintf(buf, sizeof(buf), "%d%%", percent < 0 ? 100 : percent);
  d.setFont(&fonts::FreeSansBold24pt7b);
  d.setTextColor(TFT_WHITE);
  d.setTextDatum(top_center);
  d.drawString(buf, W / 2, 150);
  d.pushSprite(0, 0);
}

}  // namespace board_display

#else
#define BOARD_HAS_DISPLAY 0
namespace board_display {
static void begin() {}
static void draw(const Frame &) {}
static void drawTilt(bool, float, float) {}
static void drawUpdate(int, bool) {}
static void drawPasskey(uint32_t) {}
static void *snapshotPng(bool, size_t *len) {
  *len = 0;
  return nullptr;
}
static void clampToScreen(int &, int &) {}
}  // namespace board_display
#endif
