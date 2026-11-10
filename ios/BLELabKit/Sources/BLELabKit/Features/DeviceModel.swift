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
    public private(set) var firmware: FirmwareUpdate = .none

    /// The firmware card's state. `picked` and `failed` carry what the card shows; `running` carries the live progress.
    public enum FirmwareUpdate: Equatable, Sendable {
        case none
        case picked(PickedFirmware)
        case running(OtaProgress)
        case finished(version: String)
        case failed(String)
    }

    public struct PickedFirmware: Equatable, Sendable {
        public let sizeBytes: Int
        /// The revision named inside the file, nil when it does not contain one.
        public let version: String?
        public let isSigned: Bool
    }

    public enum MessageStatus: Equatable, Sendable {
        case idle
        case shown(String)
        case failed(String)
    }

    private let sleeper: any Sleeper
    private var firmwareImage: FirmwareImage?
    private var firmwareTask: Task<Void, Never>?
    private var telemetryTask: Task<Void, Never>?
    private var motionTask: Task<Void, Never>?
    private var touchTask: Task<Void, Never>?
    private var lastLoggedState: ConnectionState?

    public init(device: ScannedDevice, manager: ConnectionManager, recorder: SessionRecorder? = nil, sleeper: any Sleeper = TaskSleeper()) {
        self.device = device
        self.manager = manager
        self.recorder = recorder
        self.sleeper = sleeper
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
        firmwareTask?.cancel()
        firmwareTask = nil
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
        // A new attempt starts clean; a stale "Not delivered" must not outlive the retry.
        messageStatus = .idle
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

    // MARK: Firmware update

    public static let rebootTimeoutPolls = 240
    public static let rebootPollInterval = Duration.milliseconds(250)

    /// The connected board has the OTA service.
    public var firmwareSupported: Bool { manager.otaSupported }

    public var canStartFirmware: Bool {
        guard case .picked = firmware, case .ready = manager.state else { return false }
        return manager.otaSupported
    }

    /// True while a transfer or the reboot wait is under way.
    public var firmwareBusy: Bool { if case .running = firmware { true } else { false } }

    /// `data` is the picked file's bytes, nil when it could not be read.
    public func pickFirmware(_ data: Data?) {
        guard !firmwareBusy else { return }
        guard let data else {
            firmwareImage = nil
            firmware = .failed("The file could not be read")
            return
        }
        guard let image = FirmwareImage.parse(data) else {
            firmwareImage = nil
            firmware = .failed("This is not an ESP32 firmware image (a .bin built for the board)")
            return
        }
        firmwareImage = image
        firmware = .picked(PickedFirmware(sizeBytes: image.size, version: image.version, isSigned: image.isSigned))
    }

    public func startFirmwareUpdate() {
        guard canStartFirmware, let image = firmwareImage else { return }
        firmware = .running(.starting)
        append("Firmware update started (\(image.size) bytes)")
        firmwareTask = Task { [weak self] in await self?.runFirmwareUpdate(image) }
    }

    /// Aborts the transfer and goes back to the picked file. Ignored once the board is restarting.
    public func cancelFirmwareUpdate() {
        guard case .running(let progress) = firmware, progress != .restarting else { return }
        firmwareTask?.cancel()
    }

    public func dismissFirmware() {
        guard !firmwareBusy else { return }
        firmware = .none
        firmwareImage = nil
    }

    private func runFirmwareUpdate(_ image: FirmwareImage) async {
        let before: String? = if case .ready(let info, _) = manager.state { info.firmware } else { nil }
        let (progress, sink) = AsyncStream<OtaProgress>.makeStream()
        let relay = Task { [weak self] in
            for await step in progress { self?.firmware = .running(step) }
        }
        defer { firmwareTask = nil }
        do throws(OtaFailure) {
            try await manager.updateFirmware(image) { sink.yield($0) }
        } catch {
            sink.finish()
            await relay.value
            if error == .cancelled {
                firmware = .picked(PickedFirmware(sizeBytes: image.size, version: image.version, isSigned: image.isSigned))
                append("Firmware update cancelled")
            } else {
                firmware = .failed(error.message)
                append("Firmware update failed: \(error.message)")
            }
            return
        }
        sink.finish()
        await relay.value
        firmware = .running(.restarting)
        firmware = await awaitReboot(target: image.version, before: before)
        if case .finished(let version) = firmware { append("Firmware updated, board runs \(version)") }
    }

    /// The board restarts a second after it accepts an image: the session drops and reconnects by itself.
    /// Waits up to a minute for it to come back and checks it runs what was sent.
    private func awaitReboot(target: String?, before: String?) async -> FirmwareUpdate {
        var sawDown = false
        for _ in 0..<Self.rebootTimeoutPolls {
            if case .ready(let info, _) = manager.state {
                if sawDown || info.firmware != before { return Self.verdict(reported: info.firmware, target: target) }
            } else {
                sawDown = true
            }
            do { try await sleeper.sleep(for: Self.rebootPollInterval) } catch { return .failed("The update was interrupted") }
        }
        return .failed("The board did not come back after restarting")
    }

    private static func verdict(reported: String?, target: String?) -> FirmwareUpdate {
        guard let target else { return .finished(version: reported ?? "the new firmware") }
        let matches = switch (OtaSignature.versionCode(revision: reported), OtaSignature.versionCode(revision: target)) {
        case (let a?, let b?): a == b
        default: reported == target
        }
        return matches
            ? .finished(version: target)
            : .failed("The board came back on \(reported ?? "an unknown version"), not \(target); it rolled back")
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
