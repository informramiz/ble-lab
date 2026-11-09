// BLE Lab peripheral for ESP32 (Arduino-ESP32 core 3.3.x, Bluedroid BLE stack).
// API reference: https://github.com/espressif/arduino-esp32/tree/master/libraries/BLE
//
// Services:
//   BLE Lab (custom)   telemetry notify every 200 ms, LED write (encrypted, Just Works bonding)
//   Device Information 0x180A: manufacturer, model, firmware revision
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <BLE2901.h>
#include <BLESecurity.h>
#include <math.h>
#include "gatt_profile.h"

#ifndef LED_BUILTIN
#define LED_BUILTIN 2  // Most ESP32 DevKit v1 boards wire the blue LED to GPIO2.
#endif

static BLEServer *server = nullptr;
static BLECharacteristic *telemetryChar = nullptr;
static BLECharacteristic *ledChar = nullptr;
static volatile bool connected = false;
static bool ledOn = false;
static uint32_t sequence = 0;
static uint32_t lastNotifyMs = 0;

static void setLed(bool on) {
  ledOn = on;
  digitalWrite(LED_BUILTIN, on ? HIGH : LOW);
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
  out[0] = sequence & 0xFF;
  out[1] = (sequence >> 8) & 0xFF;
  out[2] = (sequence >> 16) & 0xFF;
  out[3] = (sequence >> 24) & 0xFF;
  out[4] = value & 0xFF;
  out[5] = (value >> 8) & 0xFF;
  out[6] = ledOn ? 0x01 : 0x00;
}

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *s) override {
    connected = true;
    Serial.println("central connected");
  }
  void onDisconnect(BLEServer *s) override {
    connected = false;
    Serial.println("central disconnected; advertising again");
    // advertiseOnDisconnect(true) restarts advertising, so the phone's backoff loop can reconnect.
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

  lab->start();
}

void setup() {
  Serial.begin(115200);
  pinMode(LED_BUILTIN, OUTPUT);

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
  Serial.println("advertising as " DEVICE_NAME);
}

void loop() {
  uint32_t now = millis();
  if (connected && now - lastNotifyMs >= TELEMETRY_PERIOD_MS) {
    lastNotifyMs = now;
    uint8_t payload[TELEMETRY_LENGTH];
    encodeTelemetry(payload);
    telemetryChar->setValue(payload, TELEMETRY_LENGTH);
    telemetryChar->notify();  // No-op for a central that has not enabled the CCCD.
    sequence++;
  }
  delay(5);
}
