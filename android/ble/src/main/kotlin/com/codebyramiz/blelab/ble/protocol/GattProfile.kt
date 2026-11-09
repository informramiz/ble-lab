package com.codebyramiz.blelab.ble.protocol

import java.util.UUID

/**
 * The GATT profile shared with the ESP32 firmware (`firmware/ble_lab/gatt_profile.h`)
 * and the iOS client. Change all three together.
 */
object GattProfile {
    /** Custom "BLE Lab" service. */
    val LAB_SERVICE: UUID = UUID.fromString("b1e1ab00-5a1e-4c3a-9f5e-c0de00b1e1ab")

    /** Notify + read: [TelemetryCodec] payload, streamed by the peripheral. */
    val TELEMETRY: UUID = UUID.fromString("b1e1ab01-5a1e-4c3a-9f5e-c0de00b1e1ab")

    /** Read + write (with response, encrypted link): 1 byte, 0x00 = LED off, 0x01 = LED on. */
    val LED: UUID = UUID.fromString("b1e1ab02-5a1e-4c3a-9f5e-c0de00b1e1ab")

    /** Notify (firmware 1.1.0+): batches of IMU samples, see [MotionCodec]. */
    val MOTION: UUID = UUID.fromString("b1e1ab03-5a1e-4c3a-9f5e-c0de00b1e1ab")

    /** Notify (firmware 1.1.0+): one touchscreen event per notification, see [TouchCodec]. */
    val TOUCH: UUID = UUID.fromString("b1e1ab04-5a1e-4c3a-9f5e-c0de00b1e1ab")

    /** Write with response, encrypted link (firmware 1.1.0+): UTF-8 text shown on the board's screen, see [MessageCodec]. */
    val MESSAGE: UUID = UUID.fromString("b1e1ab05-5a1e-4c3a-9f5e-c0de00b1e1ab")

    /** Bluetooth SIG Device Information service and the characteristics we read from it. */
    val DEVICE_INFO_SERVICE: UUID = sig(0x180A)
    val MANUFACTURER_NAME: UUID = sig(0x2A29)
    val MODEL_NUMBER: UUID = sig(0x2A24)
    val FIRMWARE_REVISION: UUID = sig(0x2A26)

    /** Client Characteristic Configuration Descriptor, written to enable notifications. */
    val CCCD: UUID = sig(0x2902)

    /** ATT MTU we ask for: 247 fits one LE Data Length Extension packet (251 - 4 byte L2CAP header). */
    const val PREFERRED_MTU = 247

    /** Default ATT MTU before negotiation (Bluetooth Core spec). */
    const val DEFAULT_MTU = 23

    private fun sig(shortUuid: Int): UUID = UUID.fromString(String.format(java.util.Locale.ROOT, "%08x-0000-1000-8000-00805f9b34fb", shortUuid))
}
