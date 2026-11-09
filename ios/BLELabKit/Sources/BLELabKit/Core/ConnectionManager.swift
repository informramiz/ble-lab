import Foundation
import Observation

/// The connection state machine: connect, discover, read device info, subscribe, stream,
/// and reconnect with exponential backoff when the link drops. Pure Swift over `BLECentral`,
/// so the same code runs against Core Bluetooth, the simulator and the tests.
@MainActor
@Observable
public final class ConnectionManager {
    public private(set) var state: ConnectionState = .idle
    /// Every decoded notification, for one consumer (the device screen's model).
    public let telemetry: AsyncStream<Telemetry>
    /// IMU samples, flattened from the board's batches (firmware 1.1.0+).
    public let motion: AsyncStream<MotionSample>
    /// Touches on the board's screen (firmware 1.1.0+).
    public let touches: AsyncStream<TouchEvent>

    private let central: any BLECentral
    private let backoff: BackoffPolicy
    private let sleeper: any Sleeper
    private let random: @Sendable () -> Double
    private let telemetryContinuation: AsyncStream<Telemetry>.Continuation
    private let motionContinuation: AsyncStream<MotionSample>.Continuation
    private let touchContinuation: AsyncStream<TouchEvent>.Continuation
    private var task: Task<Void, Never>?
    private var link: (any GattLink)?

    public init(
        central: any BLECentral,
        backoff: BackoffPolicy = BackoffPolicy(),
        sleeper: any Sleeper = TaskSleeper(),
        random: @escaping @Sendable () -> Double = { Double.random(in: 0...1) }
    ) {
        self.central = central
        self.backoff = backoff
        self.sleeper = sleeper
        self.random = random
        (telemetry, telemetryContinuation) = AsyncStream.makeStream(bufferingPolicy: .bufferingNewest(64))
        (motion, motionContinuation) = AsyncStream.makeStream(bufferingPolicy: .bufferingNewest(256))
        (touches, touchContinuation) = AsyncStream.makeStream(bufferingPolicy: .bufferingNewest(32))
    }

    /// Starts (or restarts) connecting. Retries transient failures; gives up after `maxAttempts`.
    public func connect(to id: UUID) {
        task?.cancel()
        task = Task { await runWithReconnect(id) }
    }

    /// Stops retries, drops the link and returns to idle.
    public func disconnect() async {
        task?.cancel()
        task = nil
        let current = link
        link = nil
        await current?.disconnect()
        state = .idle
    }

    /// Writes the LED characteristic. On iOS the system pairs on demand when the peripheral
    /// answers "insufficient encryption"; if the user declines, the write fails with `.pairingFailed`.
    public func setLED(_ on: Bool) async throws(BLEError) {
        guard let link, case .ready = state else { throw .notConnected }
        do throws(BLEError) {
            try await link.write(TelemetryCodec.encodeLed(on), to: .custom(GattProfile.led), in: .custom(GattProfile.labService))
        } catch .insufficientEncryption {
            throw .pairingFailed
        }
    }

    /// Shows `text` on the board's screen (encrypted write, like the LED). Blank text fails without
    /// touching the radio; text over 64 bytes is cut at a character boundary.
    public func sendMessage(_ text: String) async throws(BLEError) {
        guard let payload = MessageCodec.encode(text) else { throw .unexpected("Message is blank") }
        guard let link, case .ready = state else { throw .notConnected }
        do throws(BLEError) {
            try await link.write(payload, to: .custom(GattProfile.message), in: .custom(GattProfile.labService))
        } catch .insufficientEncryption {
            throw .pairingFailed
        }
    }

    private func runWithReconnect(_ id: UUID) async {
        var attempt = 0
        while !Task.isCancelled {
            attempt += 1
            state = .connecting(attempt: attempt)
            let error: BLEError
            do throws(BLEError) {
                // A session that reached Ready resets the counter, so a later drop starts the backoff over.
                error = try await runSession(id) { attempt = 0 }
            } catch let thrown {
                error = thrown
            }
            link = nil
            if Task.isCancelled { return }
            guard error.isRetryable, attempt < backoff.maxAttempts else {
                state = .failed(error)
                return
            }
            let next = attempt + 1
            let delay = backoff.delay(forAttempt: next, unit: random())
            state = .reconnecting(attempt: next, delay: delay, cause: error)
            do {
                try await sleeper.sleep(for: delay)
            } catch {
                return  // Cancelled by disconnect() or a new connect().
            }
        }
    }

    /// One connection, from connect to link loss. Returns the reason the link went down.
    private func runSession(_ id: UUID, onReady: () -> Void) async throws(BLEError) -> BLEError {
        let link = try await central.connect(to: id)
        self.link = link
        do throws(BLEError) {
            state = .discovering
            let services = try await link.discoverServices()
            guard services.contains(.custom(GattProfile.labService)) else { throw .serviceNotFound }
            var info = DeviceInfo()
            if services.contains(.sig(GattProfile.deviceInfoService)) {
                info.manufacturer = await readString(link, GattProfile.manufacturerName)
                info.model = await readString(link, GattProfile.modelNumber)
                info.firmware = await readString(link, GattProfile.firmwareRevision)
            }
            let events = link.events
            try await link.setNotify(true, for: .custom(GattProfile.telemetry), in: .custom(GattProfile.labService))
            // Firmware 1.0.0 has neither characteristic; a refusal there must not fail the connection.
            for optional in [GattProfile.motion, GattProfile.touch] {
                do throws(BLEError) {
                    try await link.setNotify(true, for: .custom(optional), in: .custom(GattProfile.labService))
                } catch .serviceNotFound {
                    continue
                } catch .unexpected {
                    continue
                }
            }
            state = .ready(info: info, maxWriteLength: await link.maximumWriteLength)
            onReady()
            for await event in events {
                switch event {
                case .notification(.custom(GattProfile.telemetry), let data):
                    if let reading = try? TelemetryCodec.decode(data) { telemetryContinuation.yield(reading) }
                case .notification(.custom(GattProfile.motion), let data):
                    if let batch = try? MotionCodec.decode(data) { batch.forEach { motionContinuation.yield($0) } }
                case .notification(.custom(GattProfile.touch), let data):
                    if let touch = try? TouchCodec.decode(data) { touchContinuation.yield(touch) }
                case .notification:
                    continue
                case .disconnected(let reason):
                    return reason
                }
            }
            return .linkLost(reason: "event stream ended")
        } catch {
            await link.disconnect()
            throw error
        }
    }

    /// Device Information is optional metadata: a failed read shows as unknown instead of failing the session.
    private func readString(_ link: any GattLink, _ characteristic: UInt16) async -> String? {
        guard let data = try? await link.read(.sig(characteristic), in: .sig(GattProfile.deviceInfoService)) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
