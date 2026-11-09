// BLE Lab peripheral for ESP32 (Arduino-ESP32 core 3.3.x, Bluedroid BLE stack).
// API reference: https://github.com/espressif/arduino-esp32/tree/master/libraries/BLE
//
// Services:
//   BLE Lab (custom)   telemetry notify every 200 ms, LED write (encrypted, Just Works bonding),
//                      motion notify (IMU batches, 50 Hz), touch notify, message write (encrypted)
//   Device Information 0x180A: manufacturer, model, firmware revision
//
// Serial test console (115200 baud), for checking the touch path without a finger:
//   tap <x> <y>    inject a tap at screen pixel (x, y); sent with the "injected" flag set
//   hold <x> <y>   inject a long press
//   imu            print one IMU sample
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <BLE2901.h>
#include <BLESecurity.h>
#include <math.h>
#include "gatt_profile.h"
#include "board_display.h"
#include "board_sensors.h"

#if !BOARD_HAS_DISPLAY && !defined(LED_BUILTIN)
#define LED_BUILTIN 2  // Most ESP32 DevKit v1 boards wire the blue LED to GPIO2.
#endif

static BLEServer *server = nullptr;
static BLECharacteristic *telemetryChar = nullptr;
static BLECharacteristic *ledChar = nullptr;
static BLECharacteristic *motionChar = nullptr;
static BLECharacteristic *touchChar = nullptr;
static BLECharacteristic *messageChar = nullptr;
static volatile bool connected = false;
static bool ledOn = false;
static uint32_t sequence = 0;
static uint32_t lastNotifyMs = 0;
static volatile bool screenDirty = true;
static int16_t lastValueX100 = 0;
static uint32_t lastDrawMs = 0;
static uint32_t lastTiltMs = 0;
static bool hasImu = false;
static bool hasTouch = false;

// Motion batching. The board clock of the first sample and a fixed period describe every sample
// in a batch, so a batch is flushed early when a sample comes late (a screen push blocks ~30 ms).
static uint8_t motionBatch[MOTION_HEADER_LENGTH + MOTION_MAX_BATCH * MOTION_SAMPLE_LENGTH];
static uint8_t motionCount = 0;
static uint16_t motionIndex = 0;
static uint32_t nextMotionMs = 0;
static float tiltX = 0, tiltY = 0;  // Low-passed accel for the on-screen level.

static uint16_t touchIndex = 0;
static int16_t rippleX = 0, rippleY = 0;
static uint32_t rippleStartMs = 0;
static bool rippleActive = false;

// Text from the phone. Written on the Bluedroid task, copied out on the loop task.
static portMUX_TYPE messageMux = portMUX_INITIALIZER_UNLOCKED;
static char pendingMessage[MESSAGE_MAX_BYTES + 1];
static volatile bool messagePending = false;
static char message[MESSAGE_MAX_BYTES + 1] = "";

static void setLed(bool on) {
  ledOn = on;
#if !BOARD_HAS_DISPLAY
  digitalWrite(LED_BUILTIN, on ? HIGH : LOW);
#endif
  screenDirty = true;
  uint8_t value = on ? 1 : 0;
  ledChar->setValue(&value, 1);
}

// Reading in hundredths. Chips with an internal sensor (S2/S3/C3/C6...) report it;
// the original ESP32 has none, so it streams a smooth synthetic wave instead.
static int16_t readValueX100() {
#if SOC_TEMP_SENSOR_SUPPORTED
  return (int16_t)lroundf(temperatureRead() * 100.0f);
#else
  float wave = 24.0f + 3.0f * sinf(2.0f * (float)M_PI * (float)sequence / 50.0f);
  return (int16_t)lroundf(wave * 100.0f);
#endif
}

static void encodeTelemetry(uint8_t *out) {
  int16_t value = readValueX100();
  lastValueX100 = value;
  out[0] = sequence & 0xFF;
  out[1] = (sequence >> 8) & 0xFF;
  out[2] = (sequence >> 16) & 0xFF;
  out[3] = (sequence >> 24) & 0xFF;
  out[4] = value & 0xFF;
  out[5] = (value >> 8) & 0xFF;
  uint8_t flags = ledOn ? TELEMETRY_FLAG_LED : 0;
  if (hasImu) flags |= TELEMETRY_FLAG_IMU;
  if (hasTouch) flags |= TELEMETRY_FLAG_TOUCH;
  out[6] = flags;
}

static void putU16(uint8_t *out, uint16_t v) {
  out[0] = v & 0xFF;
  out[1] = (v >> 8) & 0xFF;
}

static int16_t clampI16(float v) {
  if (v > 32767.0f) return 32767;
  if (v < -32768.0f) return -32768;
  return (int16_t)lroundf(v);
}

// Samples that fit one notification at the negotiated MTU (ATT payload = MTU - 3).
static uint8_t motionBatchCapacity() {
  uint16_t mtu = server->getPeerMTU(server->getConnId());
  int room = (int)mtu - 3 - MOTION_HEADER_LENGTH;
  int n = room / MOTION_SAMPLE_LENGTH;
  if (n < 1) return 1;
  return n > MOTION_MAX_BATCH ? MOTION_MAX_BATCH : (uint8_t)n;
}

static void flushMotion() {
  if (motionCount == 0) return;
  uint16_t first = motionIndex - motionCount;
  putU16(motionBatch + 4, first);
  motionBatch[6] = motionCount;
  motionBatch[7] = MOTION_PERIOD_MS;
  motionChar->setValue(motionBatch, MOTION_HEADER_LENGTH + motionCount * MOTION_SAMPLE_LENGTH);
  motionChar->notify();
  motionCount = 0;
}

static void appendMotion(const board_sensors::ImuSample &s, uint32_t now) {
  if (motionCount == 0) {
    motionBatch[0] = now & 0xFF;
    motionBatch[1] = (now >> 8) & 0xFF;
    motionBatch[2] = (now >> 16) & 0xFF;
    motionBatch[3] = (now >> 24) & 0xFF;
  }
  uint8_t *p = motionBatch + MOTION_HEADER_LENGTH + motionCount * MOTION_SAMPLE_LENGTH;
  putU16(p + 0, (uint16_t)clampI16(s.ax * 1000.0f));
  putU16(p + 2, (uint16_t)clampI16(s.ay * 1000.0f));
  putU16(p + 4, (uint16_t)clampI16(s.az * 1000.0f));
  putU16(p + 6, (uint16_t)clampI16(s.gx * 10.0f));
  putU16(p + 8, (uint16_t)clampI16(s.gy * 10.0f));
  putU16(p + 10, (uint16_t)clampI16(s.gz * 10.0f));
  motionCount++;
  motionIndex++;
  if (motionCount >= motionBatchCapacity()) flushMotion();
}

static void sampleMotion(uint32_t now) {
  if (!hasImu || (int32_t)(now - nextMotionMs) < 0) return;
  bool late = (int32_t)(now - nextMotionMs) > MOTION_PERIOD_MS / 2;
  if (late) flushMotion();  // Keep "first sample time + i * period" true inside a batch.
  nextMotionMs = late ? now + MOTION_PERIOD_MS : nextMotionMs + MOTION_PERIOD_MS;
  board_sensors::ImuSample s;
  if (!board_sensors::readImu(s)) return;
  tiltX += 0.2f * (s.ax - tiltX);
  tiltY += 0.2f * (s.ay - tiltY);
  if (connected) appendMotion(s, now);
}

static void sendTouch(uint8_t kind, int16_t x, int16_t y, bool injected) {
  rippleX = x;
  rippleY = y;
  rippleStartMs = millis();
  rippleActive = true;
  screenDirty = true;
  Serial.printf("touch %s at %d,%d%s\n", kind == TOUCH_KIND_HOLD ? "hold" : "tap", x, y, injected ? " (injected)" : "");
  uint8_t out[TOUCH_LENGTH];
  putU16(out, touchIndex++);
  out[2] = kind;
  out[3] = injected ? TOUCH_FLAG_INJECTED : 0;
  putU16(out + 4, (uint16_t)x);
  putU16(out + 6, (uint16_t)y);
  touchChar->setValue(out, TOUCH_LENGTH);
  if (connected) touchChar->notify();
}

// Line-based serial console; see the header comment.
static void pollConsole() {
  static char line[48];
  static size_t len = 0;
  while (Serial.available() > 0) {
    char c = (char)Serial.read();
    if (c != '\n' && c != '\r') {
      if (len < sizeof(line) - 1) line[len++] = c;
      continue;
    }
    line[len] = 0;
    len = 0;
    int x = 0, y = 0;
    if (sscanf(line, "tap %d %d", &x, &y) == 2) {
      sendTouch(TOUCH_KIND_TAP, x, y, true);
    } else if (sscanf(line, "hold %d %d", &x, &y) == 2) {
      sendTouch(TOUCH_KIND_HOLD, x, y, true);
    } else if (strcmp(line, "imu") == 0) {
      board_sensors::ImuSample s;
      if (board_sensors::readImu(s)) {
        Serial.printf("imu a=%.3f,%.3f,%.3f g  g=%.1f,%.1f,%.1f dps\n", s.ax, s.ay, s.az, s.gx, s.gy, s.gz);
      } else {
        Serial.println("imu none");
      }
    }
  }
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override {
    connected = true;
    screenDirty = true;
    Serial.println("central connected");
  }
  void onDisconnect(BLEServer *s) override {
    connected = false;
    screenDirty = true;
    Serial.println("central disconnected; advertising again");
    // advertiseOnDisconnect(true) restarts advertising, so the phone's backoff loop can reconnect.
  }
};

class MessageCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String v = c->getValue();
    if (v.length() == 0 || v.length() > MESSAGE_MAX_BYTES) return;  // Out of contract: ignore.
    portENTER_CRITICAL(&messageMux);
    memcpy(pendingMessage, v.c_str(), v.length());
    pendingMessage[v.length()] = 0;
    messagePending = true;
    portEXIT_CRITICAL(&messageMux);
  }
};

class LedCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String v = c->getValue();
    if (v.length() != 1) return;  // Ignore malformed writes; the stored value stays as it was.
    setLed(v[0] != 0);
    Serial.printf("LED %s\n", ledOn ? "on" : "off");
  }
};

static void addDeviceInformationService() {
  BLEService *dis = server->createService(BLEUUID((uint16_t)0x180A));
  dis->createCharacteristic(BLEUUID((uint16_t)0x2A29), BLECharacteristic::PROPERTY_READ)->setValue(MANUFACTURER_NAME);
  dis->createCharacteristic(BLEUUID((uint16_t)0x2A24), BLECharacteristic::PROPERTY_READ)->setValue(MODEL_NUMBER);
  dis->createCharacteristic(BLEUUID((uint16_t)0x2A26), BLECharacteristic::PROPERTY_READ)->setValue(FIRMWARE_REVISION);
  dis->start();
}

static void addLabService() {
  BLEService *lab = server->createService(LAB_SERVICE_UUID);

  telemetryChar = lab->createCharacteristic(
      TELEMETRY_CHAR_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  telemetryChar->addDescriptor(new BLE2902());  // CCCD: the central writes 0x0001 to subscribe.
  BLE2901 *telemetryName = new BLE2901();
  telemetryName->setDescription("Telemetry: u32 seq, i16 value x100, u8 flags");
  telemetryChar->addDescriptor(telemetryName);

  ledChar = lab->createCharacteristic(
      LED_CHAR_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE);
  // Writes need an encrypted link: the first write from an unbonded phone fails with
  // "insufficient authentication/encryption", which triggers pairing on Android and iOS.
  ledChar->setAccessPermissions(ESP_GATT_PERM_READ | ESP_GATT_PERM_WRITE_ENCRYPTED);
  ledChar->setCallbacks(new LedCallbacks());
  BLE2901 *ledName = new BLE2901();
  ledName->setDescription("LED: 0x00 off, 0x01 on");
  ledChar->addDescriptor(ledName);

  motionChar = lab->createCharacteristic(MOTION_CHAR_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  motionChar->addDescriptor(new BLE2902());
  BLE2901 *motionName = new BLE2901();
  motionName->setDescription("Motion: u32 t_ms, u16 index, u8 n, u8 period; n x 6 i16 (mg, 0.1 dps)");
  motionChar->addDescriptor(motionName);

  touchChar = lab->createCharacteristic(TOUCH_CHAR_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  touchChar->addDescriptor(new BLE2902());
  BLE2901 *touchName = new BLE2901();
  touchName->setDescription("Touch: u16 index, u8 kind, u8 flags, u16 x, u16 y");
  touchChar->addDescriptor(touchName);

  messageChar = lab->createCharacteristic(MESSAGE_CHAR_UUID, BLECharacteristic::PROPERTY_WRITE);
  messageChar->setAccessPermissions(ESP_GATT_PERM_WRITE_ENCRYPTED);
  messageChar->setCallbacks(new MessageCallbacks());
  BLE2901 *messageName = new BLE2901();
  messageName->setDescription("Message: UTF-8 text, up to 64 bytes");
  messageChar->addDescriptor(messageName);

  lab->start();
}

void setup() {
  Serial.begin(115200);
  board_display::begin();
#if !BOARD_HAS_DISPLAY
  pinMode(LED_BUILTIN, OUTPUT);
#endif
  hasImu = board_sensors::hasImu();
  hasTouch = board_sensors::hasTouch();

  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setMTU(PREFERRED_MTU);

  // Just Works bonding with Secure Connections: no display or keyboard on the board.
  BLESecurity *security = new BLESecurity();
  security->setAuthenticationMode(true, false, true);
  security->setCapability(ESP_IO_CAP_NONE);

  server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  server->advertiseOnDisconnect(true);

  addDeviceInformationService();
  addLabService();
  setLed(false);

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(LAB_SERVICE_UUID);  // Lets the phones scan with a service filter.
  advertising->setScanResponse(true);             // Name goes in the scan response; the 128-bit UUID fills the advert.
  advertising->setMinPreferred(0x06);             // Connection interval hints that help iOS connect quickly.
  advertising->setMaxPreferred(0x12);
  BLEDevice::startAdvertising();
  Serial.printf("advertising as %s (imu %s, touch %s)\n", DEVICE_NAME, hasImu ? "yes" : "no", hasTouch ? "yes" : "no");
}

void loop() {
  uint32_t now = millis();
  sampleMotion(now);
  if (connected && now - lastNotifyMs >= TELEMETRY_PERIOD_MS) {
    lastNotifyMs = now;
    uint8_t payload[TELEMETRY_LENGTH];
    encodeTelemetry(payload);
    telemetryChar->setValue(payload, TELEMETRY_LENGTH);
    telemetryChar->notify();  // No-op for a central that has not enabled the CCCD.
    sequence++;
  }
  if (!connected) motionCount = 0;  // Drop a half-built batch from the previous link.

  board_sensors::TouchEvent touch;
  if (board_sensors::pollTouch(touch)) sendTouch(touch.kind, touch.x, touch.y, false);
  pollConsole();

  if (messagePending) {
    portENTER_CRITICAL(&messageMux);
    memcpy(message, pendingMessage, sizeof(message));
    messagePending = false;
    portEXIT_CRITICAL(&messageMux);
    screenDirty = true;
    Serial.printf("message: %s\n", message);
  }

  // Redraw on state changes, twice a second for the live reading, and fast while a ripple plays.
  // Never from BLE callbacks.
  int32_t rippleAge = rippleActive ? (int32_t)(now - rippleStartMs) : -1;
  if (rippleAge >= 600) rippleActive = false;
  uint32_t interval = rippleActive ? 60 : 500;
  if (BOARD_HAS_DISPLAY && (screenDirty || now - lastDrawMs >= interval)) {
    if (!connected) lastValueX100 = readValueX100();
    screenDirty = false;
    lastDrawMs = now;
    board_display::draw({connected, ledOn, lastValueX100, sequence, message, hasImu, rippleX, rippleY,
                         rippleActive ? rippleAge : -1});
    board_display::drawTilt(hasImu, tiltX, tiltY);
    lastTiltMs = now;
  } else if (BOARD_HAS_DISPLAY && now - lastTiltMs >= 100) {
    lastTiltMs = now;
    board_display::drawTilt(hasImu, tiltX, tiltY);
  }
  delay(1);
}
