import Foundation
import Observation

/// Device screen state on top of `ConnectionManager`: a rolling chart window, the LED switch
/// with optimistic update and rollback, and a short event log.
@MainActor
@Observable
public final class DeviceModel {
    public static let window = 90
    public static let motionWindow = 150
    public static let touchHistory = 20

    public let device: ScannedDevice
    public let manager: ConnectionManager
    public private(set) var samples: [Telemetry] = []
    public private(set) var ledOn = false
    public private(set) var ledPending = false
    public private(set) var writeError: BLEError?
    public private(set) var log: [String] = []
    public private(set) var motion: [MotionSample] = []
    public private(set) var touches: [TouchEvent] = []
    public private(set) var messageStatus: MessageStatus = .idle
    public private(set) var messageSending = false
    public let recorder: SessionRecorder?

    public enum MessageStatus: Equatable, Sendable {
        case idle
        case shown(String)
        case failed(String)
    }

    private var telemetryTask: Task<Void, Never>?
    private var motionTask: Task<Void, Never>?
    private var touchTask: Task<Void, Never>?
    private var lastLoggedState: ConnectionState?

    public init(device: ScannedDevice, manager: ConnectionManager, recorder: SessionRecorder? = nil) {
        self.device = device
        self.manager = manager
        self.recorder = recorder
    }

    /// Flags from the newest telemetry; false until the first reading (firmware before 1.1.0 never sets them).
    public var hasMotionSensor: Bool { latest?.hasMotionSensor ?? false }
    public var hasTouchScreen: Bool { latest?.hasTouchScreen ?? false }

    public var tilt: Tilt? { motion.last.map(Tilt.init(from:)) }

    public var isRecording: Bool { recorder?.isRecording ?? false }

    public var canSendMessage: Bool {
        if case .ready = manager.state { !messageSending } else { false }
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
            for await reading in stream { await self?.receive(reading) }
        }
        let motionStream = manager.motion
        motionTask = Task { [weak self] in
            for await sample in motionStream { await self?.receive(sample) }
        }
        let touchStream = manager.touches
        touchTask = Task { [weak self] in
            for await event in touchStream { self?.receive(event) }
        }
    }

    public func retry() { start() }

    public func stop() async {
        telemetryTask?.cancel()
        motionTask?.cancel()
        touchTask?.cancel()
        telemetryTask = nil
        motionTask = nil
        touchTask = nil
        await recorder?.stop()  // Leaving the screen saves the session instead of leaving an orphan.
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

    public func toggleRecording() async {
        guard let recorder else { return }
        if recorder.isRecording {
            if let id = await recorder.stop() { append("Saved recording #\(id)") }
        } else {
            await recorder.start(deviceAddress: device.id.uuidString, deviceName: device.name)
            append("Recording started")
        }
    }

    public func sendMessage(_ text: String) async {
        guard canSendMessage else { return }
        messageSending = true
        defer { messageSending = false }
        do throws(BLEError) {
            try await manager.sendMessage(text)
            messageStatus = .shown(text.trimmingCharacters(in: .whitespacesAndNewlines))
            append("Message sent")
        } catch {
            messageStatus = .failed(error.message)
            append("Message failed: \(error.message)")
        }
    }

    private func receive(_ sample: MotionSample) async {
        motion.append(sample)
        if motion.count > Self.motionWindow { motion.removeFirst(motion.count - Self.motionWindow) }
        await recorder?.onMotion(sample)
    }

    private func receive(_ event: TouchEvent) {
        touches.append(event)
        if touches.count > Self.touchHistory { touches.removeFirst(touches.count - Self.touchHistory) }
    }

    private func receive(_ reading: Telemetry) async {
        await recorder?.onTelemetry(reading)
        samples.append(reading)
        if samples.count > Self.window { samples.removeFirst(samples.count - Self.window) }
        if !ledPending { ledOn = reading.ledOn }  // The board is the source of truth once a write settles.
    }

    private func append(_ line: String) {
        log.append(line)
        if log.count > 50 { log.removeFirst() }
    }
}
