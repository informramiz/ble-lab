import BLELabKit
import Foundation
import SwiftData

@Model
final class StoredSession {
    @Attribute(.unique) var id: Int
    var startedAtMillis: Int64
    var endedAtMillis: Int64?
    var deviceName: String?
    var deviceAddress: String
    var telemetryCount: Int
    var motionCount: Int
    @Relationship(deleteRule: .cascade, inverse: \StoredTelemetry.session) var telemetry: [StoredTelemetry] = []
    @Relationship(deleteRule: .cascade, inverse: \StoredMotion.session) var motion: [StoredMotion] = []

    init(id: Int, startedAtMillis: Int64, deviceName: String?, deviceAddress: String) {
        self.id = id
        self.startedAtMillis = startedAtMillis
        self.deviceName = deviceName
        self.deviceAddress = deviceAddress
        self.telemetryCount = 0
        self.motionCount = 0
    }

    var summary: RecordingSession {
        RecordingSession(
            id: id, startedAtMillis: startedAtMillis, endedAtMillis: endedAtMillis,
            deviceName: deviceName, deviceAddress: deviceAddress,
            telemetryCount: telemetryCount, motionCount: motionCount
        )
    }
}

@Model
final class StoredTelemetry {
    var session: StoredSession?
    var tMillis: Int64
    var value: Float
    var ledOn: Bool

    init(_ point: TelemetryPoint) {
        tMillis = point.tMillis
        value = point.value
        ledOn = point.ledOn
    }
}

@Model
final class StoredMotion {
    var session: StoredSession?
    var tMillis: Int64
    var ax: Float
    var ay: Float
    var az: Float
    var gx: Float
    var gy: Float
    var gz: Float

    init(_ point: MotionPoint) {
        tMillis = point.tMillis
        ax = point.ax; ay = point.ay; az = point.az
        gx = point.gx; gy = point.gy; gz = point.gz
    }
}
