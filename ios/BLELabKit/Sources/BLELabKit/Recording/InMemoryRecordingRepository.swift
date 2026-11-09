import Foundation

/// A `RecordingRepository` that keeps everything in memory: for tests, previews and the simulator demo.
public actor InMemoryRecordingRepository: RecordingRepository {
    private struct Stored {
        var session: RecordingSession
        var telemetry: [TelemetryPoint] = []
        var motion: [MotionPoint] = []

        var summary: RecordingSession {
            var copy = session
            copy.telemetryCount = telemetry.count
            copy.motionCount = motion.count
            return copy
        }
    }

    private var store: [Int: Stored] = [:]
    private var nextId = 1
    /// Size of every `append`, as (telemetry, motion), so tests can assert batching.
    public private(set) var appendCalls: [(telemetry: Int, motion: Int)] = []

    public init() {}

    public func start(deviceAddress: String, deviceName: String?, startedAtMillis: Int64) -> Int {
        let id = nextId
        nextId += 1
        store[id] = Stored(session: RecordingSession(
            id: id, startedAtMillis: startedAtMillis, endedAtMillis: nil,
            deviceName: deviceName, deviceAddress: deviceAddress, telemetryCount: 0, motionCount: 0
        ))
        return id
    }

    public func append(sessionId: Int, telemetry: [TelemetryPoint], motion: [MotionPoint]) {
        appendCalls.append((telemetry.count, motion.count))
        store[sessionId]?.telemetry += telemetry
        store[sessionId]?.motion += motion
    }

    public func finish(sessionId: Int, endedAtMillis: Int64) {
        store[sessionId]?.session.endedAtMillis = endedAtMillis
    }

    public func closeOrphans() {
        for (id, stored) in store where stored.session.endedAtMillis == nil {
            let last = max(stored.telemetry.map(\.tMillis).max() ?? 0, stored.motion.map(\.tMillis).max() ?? 0)
            store[id]?.session.endedAtMillis = stored.session.startedAtMillis + last
        }
    }

    public func sessions() -> [RecordingSession] {
        store.values.map(\.summary).sorted { $0.startedAtMillis > $1.startedAtMillis }
    }

    public func load(sessionId: Int) -> SessionData? {
        store[sessionId].map { SessionData(session: $0.summary, telemetry: $0.telemetry, motion: $0.motion) }
    }

    public func delete(sessionId: Int) {
        store[sessionId] = nil
    }
}
