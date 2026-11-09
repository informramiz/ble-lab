// Optional on-board sensors. On M5Stack CoreS3 the BMI270 IMU and the capacitive touchscreen are
// read through M5Unified (M5.Imu, M5.Touch); other boards report no sensors and the motion/touch
// characteristics simply never notify. All reads happen on the loop() task: the IMU and the touch
// controller share one I2C bus and M5Unified does not lock it, so they must not run concurrently.
#pragma once
#include <stdint.h>

namespace board_sensors {

struct ImuSample {
  float ax, ay, az;  // g
  float gx, gy, gz;  // deg/s
};

struct TouchEvent {
  uint8_t kind;  // TOUCH_KIND_TAP or TOUCH_KIND_HOLD
  int16_t x, y;
};

}  // namespace board_sensors

#if defined(ARDUINO_M5STACK_CORES3)
#include <M5Unified.h>

namespace board_sensors {

static bool hasImu() { return M5.Imu.isEnabled(); }
static bool hasTouch() { return M5.Touch.isEnabled(); }

// Reads the latest accel + gyro registers. M5Unified's BMI270 driver reads the data registers
// directly (no FIFO), so the caller paces the reads.
static bool readImu(ImuSample &out) {
  if (!M5.Imu.isEnabled()) return false;
  M5.Imu.update();
  const auto &d = M5.Imu.getImuData();
  out = {d.accel.x, d.accel.y, d.accel.z, d.gyro.x, d.gyro.y, d.gyro.z};
  return true;
}

// Call once per loop(). Reports a finished tap, or the moment a press turns into a long press.
static bool pollTouch(TouchEvent &out) {
  M5.update();
  if (!M5.Touch.isEnabled()) return false;
  const auto &t = M5.Touch.getDetail();
  if (t.wasClicked()) {
    out = {1, t.x, t.y};
    return true;
  }
  if (t.wasHold()) {
    out = {2, t.x, t.y};
    return true;
  }
  return false;
}

}  // namespace board_sensors

#else
namespace board_sensors {
static bool hasImu() { return false; }
static bool hasTouch() { return false; }
static bool readImu(ImuSample &) { return false; }
static bool pollTouch(TouchEvent &) { return false; }
}  // namespace board_sensors
#endif
