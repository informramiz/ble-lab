import Foundation
import Observation

public enum RecordingState: Equatable, Sendable {
    case idle
    case recording(sessionId: Int, startedAtMillis: Int64, telemetryCount: Int, motionCount: Int)
}

/// Turns the live telemetry and motion streams into stored rows. Rows are buffered and written in
/// batches so a 50 Hz IMU does not mean 50 inserts a second.
@MainActor
@Observable
public final class SessionRecorder {
    public static let defaultBatchSize = 50

    public private(set) var state: RecordingState = .idle
    /// Set when the store rejected a write; recording continues in memory-less mode (samples are dropped).
    public private(set) var failure: String?

    @ObservationIgnored private let repository: any RecordingRepository
    @ObservationIgnored private let clock: any WallClock
    @ObservationIgnored private let batchSize: Int
    @ObservationIgnored private var telemetryBuffer: [TelemetryPoint] = []
    @ObservationIgnored private var motionBuffer: [MotionPoint] = []
    @ObservationIgnored private var boardOrigin: UInt32?
    @ObservationIgnored private var phoneOriginForBoard: Int64 = 0

    public init(repository: any RecordingRepository, clock: any WallClock = SystemWallClock(), batchSize: Int = SessionRecorder.defaultBatchSize) {
        self.repository = repository
        self.clock = clock
        self.batchSize = batchSize
    }

    public var isRecording: Bool {
        if case .recording = state { true } else { false }
    }

    public func start(deviceAddress: String, deviceName: String?) async {
        guard !isRecording else { return }
        let now = clock.nowMillis()
        do {
            let id = try await repository.start(deviceAddress: deviceAddress, deviceName: deviceName, startedAtMillis: now)
            boardOrigin = nil
            failure = nil
            state = .recording(sessionId: id, startedAtMillis: now, telemetryCount: 0, motionCount: 0)
        } catch {
            failure = error.localizedDescription
        }
    }

    public func onTelemetry(_ reading: Telemetry) async {
        guard case .recording(let id, let started, let telemetry, let motion) = state else { return }
        telemetryBuffer.append(TelemetryPoint(tMillis: clock.nowMillis() - started, value: reading.value, ledOn: reading.ledOn))
        state = .recording(sessionId: id, startedAtMillis: started, telemetryCount: telemetry + 1, motionCount: motion)
        await flushIfFull(id)
    }

    /// Motion arrives in batches that share one phone arrival time, so spacing comes from the board clock.
    public func onMotion(_ sample: MotionSample) async {
        guard case .recording(let id, let started, let telemetry, let motion) = state else { return }
        if boardOrigin == nil {
            boardOrigin = sample.boardMillis
            phoneOriginForBoard = clock.nowMillis() - started
        }
        let elapsed = Int64(sample.boardMillis &- (boardOrigin ?? sample.boardMillis))  // wrap-safe
        motionBuffer.append(MotionPoint(
            tMillis: phoneOriginForBoard + elapsed,
            ax: sample.ax, ay: sample.ay, az: sample.az, gx: sample.gx, gy: sample.gy, gz: sample.gz
        ))
        state = .recording(sessionId: id, startedAtMillis: started, telemetryCount: telemetry, motionCount: motion + 1)
        await flushIfFull(id)
    }

    /// Writes what is buffered and closes the session. Returns its id, or nil when nothing was recording.
    @discardableResult
    public func stop() async -> Int? {
        guard case .recording(let id, _, _, _) = state else { return nil }
        await flush(id)
        do {
            try await repository.finish(sessionId: id, endedAtMillis: clock.nowMillis())
        } catch {
            failure = error.localizedDescription
        }
        state = .idle
        return id
    }

    private func flushIfFull(_ id: Int) async {
        if telemetryBuffer.count + motionBuffer.count >= batchSize { await flush(id) }
    }

    private func flush(_ id: Int) async {
        guard !telemetryBuffer.isEmpty || !motionBuffer.isEmpty else { return }
        let telemetry = telemetryBuffer
        let motion = motionBuffer
        telemetryBuffer = []
        motionBuffer = []
        do {
            try await repository.append(sessionId: id, telemetry: telemetry, motion: motion)
        } catch {
            failure = error.localizedDescription
        }
    }
}
