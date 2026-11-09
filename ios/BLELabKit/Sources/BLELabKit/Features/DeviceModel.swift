import Foundation
import Observation

/// Device screen state on top of `ConnectionManager`: a rolling chart window, the LED switch
/// with optimistic update and rollback, and a short event log.
@MainActor
@Observable
public final class DeviceModel {
    public static let window = 90

    public let device: ScannedDevice
    public let manager: ConnectionManager
    public private(set) var samples: [Telemetry] = []
    public private(set) var ledOn = false
    public private(set) var ledPending = false
    public private(set) var writeError: BLEError?
    public private(set) var log: [String] = []

    private var telemetryTask: Task<Void, Never>?
    private var lastLoggedState: ConnectionState?

    public init(device: ScannedDevice, manager: ConnectionManager) {
        self.device = device
        self.manager = manager
    }

    public var state: ConnectionState { manager.state }

    public var latest: Telemetry? { samples.last }

    public var stats: (min: Float, max: Float, mean: Float)? {
        guard !samples.isEmpty else { return nil }
        let values = samples.map(\.value)
        return (values.min()!, values.max()!, values.reduce(0, +) / Float(values.count))
    }

    public var canWrite: Bool {
        if case .ready = manager.state { !ledPending } else { false }
    }

    public func start() {
        append("Connecting to \(device.name ?? "unnamed device")")
        manager.connect(to: device.id)
        guard telemetryTask == nil else { return }
        let stream = manager.telemetry
        telemetryTask = Task { [weak self] in
            for await reading in stream { self?.receive(reading) }
        }
    }

    public func retry() { start() }

    public func stop() async {
        telemetryTask?.cancel()
        telemetryTask = nil
        await manager.disconnect()
    }

    /// Call when `state` changes so transitions land in the log.
    public func observe(_ state: ConnectionState) {
        guard state != lastLoggedState else { return }
        lastLoggedState = state
        switch state {
        case .idle: break
        case .connecting(let attempt): if attempt > 1 { append("Connect attempt \(attempt)") }
        case .discovering: append("Connected, discovering services")
        case .ready(_, let length): append("Ready: \(length)-byte writes, notifications on")
        case .reconnecting(let attempt, let delay, let cause): append("\(cause.message). Retry \(attempt) in \(delay.formatted(.units(allowed: [.seconds, .milliseconds], width: .narrow)))")
        case .failed(let error): append("Failed: \(error.message)")
        }
    }

    public func setLED(_ on: Bool) async {
        guard canWrite else { return }
        let previous = ledOn
        ledOn = on
        ledPending = true
        writeError = nil
        defer { ledPending = false }
        do throws(BLEError) {
            try await manager.setLED(on)
            append("LED \(on ? "on" : "off")")
        } catch {
            ledOn = previous
            writeError = error
            append("LED write failed: \(error.message)")
        }
    }

    private func receive(_ reading: Telemetry) {
        samples.append(reading)
        if samples.count > Self.window { samples.removeFirst(samples.count - Self.window) }
        if !ledPending { ledOn = reading.ledOn }  // The board is the source of truth once a write settles.
    }

    private func append(_ line: String) {
        log.append(line)
        if log.count > 50 { log.removeFirst() }
    }
}
