import Foundation

/// A recorded run against one device. `endedAtMillis` is nil while the recording is still open.
public struct RecordingSession: Identifiable, Equatable, Sendable {
    public let id: Int
    public let startedAtMillis: Int64
    public var endedAtMillis: Int64?
    public let deviceName: String?
    public let deviceAddress: String
    public var telemetryCount: Int
    public var motionCount: Int

    public init(id: Int, startedAtMillis: Int64, endedAtMillis: Int64?, deviceName: String?, deviceAddress: String, telemetryCount: Int, motionCount: Int) {
        self.id = id
        self.startedAtMillis = startedAtMillis
        self.endedAtMillis = endedAtMillis
        self.deviceName = deviceName
        self.deviceAddress = deviceAddress
        self.telemetryCount = telemetryCount
        self.motionCount = motionCount
    }

    public var durationMillis: Int64? { endedAtMillis.map { $0 - startedAtMillis } }
    public var startedAt: Date { Date(timeIntervalSince1970: Double(startedAtMillis) / 1000) }
}

/// Temperature-style reading, `tMillis` counted from the start of the session.
public struct TelemetryPoint: Equatable, Sendable {
    public let tMillis: Int64
    public let value: Float
    public let ledOn: Bool

    public init(tMillis: Int64, value: Float, ledOn: Bool) {
        self.tMillis = tMillis
        self.value = value
        self.ledOn = ledOn
    }
}

/// One IMU sample, `tMillis` counted from the start of the session. Acceleration in g, rate in deg/s.
public struct MotionPoint: Equatable, Sendable {
    public let tMillis: Int64
    public let ax: Float, ay: Float, az: Float
    public let gx: Float, gy: Float, gz: Float

    public init(tMillis: Int64, ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float) {
        self.tMillis = tMillis
        self.ax = ax; self.ay = ay; self.az = az
        self.gx = gx; self.gy = gy; self.gz = gz
    }
}

/// Everything stored for one session, ordered by time.
public struct SessionData: Equatable, Sendable {
    public let session: RecordingSession
    public let telemetry: [TelemetryPoint]
    public let motion: [MotionPoint]

    public init(session: RecordingSession, telemetry: [TelemetryPoint], motion: [MotionPoint]) {
        self.session = session
        self.telemetry = telemetry
        self.motion = motion
    }
}

/// Wall-clock source, injected so recording timestamps are deterministic in tests.
public protocol WallClock: Sendable {
    func nowMillis() -> Int64
}

public struct SystemWallClock: WallClock {
    public init() {}
    public func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}

/// Local store for recorded sessions; the app depends on this port, SwiftData is one adapter.
public protocol RecordingRepository: Sendable {
    func start(deviceAddress: String, deviceName: String?, startedAtMillis: Int64) async throws -> Int
    func append(sessionId: Int, telemetry: [TelemetryPoint], motion: [MotionPoint]) async throws
    func finish(sessionId: Int, endedAtMillis: Int64) async throws
    /// Ends every session that was never finished (the app was killed mid-recording) at its last
    /// sample. Call once at process start, before any new recording can begin.
    func closeOrphans() async throws
    /// Newest first.
    func sessions() async throws -> [RecordingSession]
    func load(sessionId: Int) async throws -> SessionData?
    func delete(sessionId: Int) async throws
}
