import CryptoKit
import Foundation

/// An in-process BLE Lab peripheral. It powers the simulator build (there is no Bluetooth in the
/// iOS Simulator), the in-app demo device and the tests. Knobs inject the failures real radios produce.
public actor SimulatedPeripheral {
    public static let demoID = UUID(uuidString: "00000000-0000-4000-8000-00005E0053B1")!

    public nonisolated let id: UUID
    public nonisolated let name: String
    public var connectFailuresRemaining = 0
    public var requireBonding = false
    public var acceptBonding = true
    public private(set) var bonded = false
    public private(set) var ledOn = false
    public private(set) var connectAttempts = 0
    public private(set) var ledWrites: [Data] = []
    /// Every message the central wrote, decoded, oldest first.
    public private(set) var messages: [String] = []
    /// True simulates a CoreS3 (motion and touch); false an IMU-less DevKit, which must degrade gracefully.
    public nonisolated let hasBoardSensors: Bool
    /// False simulates firmware older than 1.2.0, which has no OTA service.
    public nonisolated let supportsOta: Bool
    /// What the board runs now; an accepted update changes it to `otaResultRevision` after the reboot.
    public private(set) var firmwareRevision = "ble-lab 1.1.0"
    /// Images that were accepted and activated.
    public private(set) var otaCompleted = 0
    /// The most image bytes the central had in flight past the last status the board reported.
    public private(set) var otaMaxBytesAhead = 0

    private var continuation: AsyncStream<LinkEvent>.Continuation?
    private var notifying: Set<UUID> = []
    private var sequence: UInt32 = 0
    private var motionIndex = 0
    private var touchCount = 0
    private var rng: SplitMix
    private let notifyInterval: Duration?
    private var tickers: [UUID: Task<Void, Never>] = [:]
    private let otaVerifier: any OtaSignatureVerifier
    private let otaSlotBytes: Int
    private let otaResultRevision: String
    private let rebootDelay: Duration
    private var ota = OtaTransfer()

    /// `notifyInterval` streams telemetry on its own while notifications are on, like the firmware
    /// (200 ms). `nil` leaves it to `pushTelemetry()`, which keeps tests deterministic.
    public init(
        id: UUID = SimulatedPeripheral.demoID,
        name: String = "BLE Lab (demo)",
        seed: UInt64 = 7,
        notifyInterval: Duration? = nil,
        hasBoardSensors: Bool = true,
        supportsOta: Bool = true,
        otaVerifier: any OtaSignatureVerifier = EcdsaOtaVerifier(),
        otaSlotBytes: Int = 3 << 20,
        otaResultRevision: String = "ble-lab 1.3.0",
        rebootDelay: Duration = .seconds(1)
    ) {
        self.hasBoardSensors = hasBoardSensors
        self.supportsOta = supportsOta
        self.otaVerifier = otaVerifier
        self.otaSlotBytes = otaSlotBytes
        self.otaResultRevision = otaResultRevision
        self.rebootDelay = rebootDelay
        self.id = id
        self.name = name
        self.rng = SplitMix(seed: seed)
        self.notifyInterval = notifyInterval
    }

    public func configure(connectFailures: Int = 0, requireBonding: Bool = false, acceptBonding: Bool = true) {
        connectFailuresRemaining = connectFailures
        self.requireBonding = requireBonding
        self.acceptBonding = acceptBonding
    }

    /// Pushes one telemetry notification if a central has subscribed. Returns whether it was sent.
    @discardableResult
    public func pushTelemetry() -> Bool {
        guard notifying.contains(GattProfile.telemetry), let continuation else { return false }
        let wave = 24 + 3 * sin(2 * Double.pi * Double(sequence) / 50)
        let noise = (rng.nextUnit() - 0.5) * 0.4
        let reading = Telemetry(
            sequence: sequence, value: Float(wave + noise), ledOn: ledOn,
            hasMotionSensor: hasBoardSensors, hasTouchScreen: hasBoardSensors
        )
        sequence &+= 1
        continuation.yield(.notification(.custom(GattProfile.telemetry), TelemetryCodec.encode(reading)))
        return true
    }

    /// Pushes one batch of IMU samples (5 x 20 ms) if the central subscribed. Returns whether it was sent.
    @discardableResult
    public func pushMotion() -> Bool {
        guard notifying.contains(GattProfile.motion), let continuation else { return false }
        let batch = (0..<Self.motionBatch).map { motionSample(index: motionIndex + $0) }
        let data = MotionCodec.encode(batch, index: motionIndex, periodMillis: Self.motionPeriodMillis)
        motionIndex += Self.motionBatch
        continuation.yield(.notification(.custom(GattProfile.motion), data))
        return true
    }

    /// Makes the board report a touch, as if someone pressed its screen. Returns whether it was sent.
    @discardableResult
    public func touch(x: Int, y: Int, kind: TouchKind = .tap) -> Bool {
        guard notifying.contains(GattProfile.touch), let continuation else { return false }
        let event = TouchEvent(index: touchCount, kind: kind, injected: false, x: x, y: y)
        touchCount += 1
        continuation.yield(.notification(.custom(GattProfile.touch), TouchCodec.encode(event)))
        return true
    }

    /// Synthetic IMU: the board slowly rocks about both axes, so the tilt visual and charts move.
    func motionSample(index: Int) -> MotionSample {
        let t = Double(index * Self.motionPeriodMillis) / 1000
        let roll = 0.5 * sin(2 * Double.pi * t / 6)
        let pitch = 0.35 * cos(2 * Double.pi * t / 9)
        return MotionSample(
            boardMillis: UInt32(truncatingIfNeeded: index * Self.motionPeriodMillis),
            ax: Float(sin(pitch)), ay: Float(sin(roll)), az: Float(cos(pitch) * cos(roll)),
            gx: Float(-pitch * 20), gy: Float(roll * 20), gz: Float((rng.nextUnit() - 0.5) * 2)
        )
    }

    static let motionBatch = 5
    static let motionPeriodMillis = 20

    /// Simulates the radio dropping the link (supervision timeout, out of range).
    public func dropLink(reason: String = "supervision timeout") {
        if ota.state == .receiving { ota = OtaTransfer() }
        stopNotifying()
        continuation?.yield(.disconnected(.linkLost(reason: reason)))
        continuation?.finish()
        continuation = nil
    }

    // MARK: GATT server behaviour, called by SimulatedLink

    func acceptConnection() throws(BLEError) -> AsyncStream<LinkEvent> {
        connectAttempts += 1
        if connectFailuresRemaining > 0 {
            connectFailuresRemaining -= 1
            throw .connectFailed(reason: "peripheral did not respond")
        }
        let (stream, continuation) = AsyncStream<LinkEvent>.makeStream()
        self.continuation = continuation
        return stream
    }

    func isConnected() -> Bool { continuation != nil }

    func read(_ characteristic: AttributeID) throws(BLEError) -> Data {
        switch characteristic {
        case .sig(GattProfile.manufacturerName): Data("Espressif".utf8)
        case .sig(GattProfile.modelNumber): Data((hasBoardSensors ? "M5Stack CoreS3" : "ESP32-WROOM-32").utf8)
        case .sig(GattProfile.firmwareRevision): Data(firmwareRevision.utf8)
        case .custom(GattProfile.otaStatus) where supportsOta: encodedStatus()
        case .custom(GattProfile.led): TelemetryCodec.encodeLed(ledOn)
        default: throw .unexpected("read not permitted")
        }
    }

    func write(_ value: Data, to characteristic: AttributeID) throws(BLEError) {
        switch characteristic {
        case .custom(GattProfile.led) where value.count == 1:
            try pairIfRequired()
            ledWrites.append(value)
            ledOn = value.first != 0
        case .custom(GattProfile.message) where (1...MessageCodec.maxBytes).contains(value.count):
            try pairIfRequired()
            messages.append(String(decoding: value, as: UTF8.self))
        case .custom(GattProfile.otaControl) where supportsOta:
            try pairIfRequired()
            handleControl(value)
        default:
            throw .unexpected("write not permitted")
        }
    }

    func writeWithoutResponse(_ value: Data, to characteristic: AttributeID) throws(BLEError) {
        guard supportsOta, characteristic == .custom(GattProfile.otaData) else { throw .unexpected("write not permitted") }
        try pairIfRequired()
        handleData(value)
    }

    // MARK: OTA (mirrors firmware/ble_lab ota_service)

    /// The board's one transfer: image bytes seen so far and what START announced.
    private struct OtaTransfer {
        var state = OtaState.idle
        var error = OtaError.none
        var expected = 0
        var versionCode = 0
        var sha256 = Data()
        var received = Data()
        var reported = 0
    }

    private func encodedStatus() -> Data {
        var out = Data([ota.state.rawValue, UInt8(ota.error.rawValue)])
        out.append(littleEndian: UInt32(ota.reported))
        return out
    }

    private func reportStatus() {
        ota.reported = ota.received.count
        guard notifying.contains(GattProfile.otaStatus) else { return }
        continuation?.yield(.notification(.custom(GattProfile.otaStatus), encodedStatus()))
    }

    private func failOta(_ error: OtaError) {
        ota.state = .error
        ota.error = error
        ota.received = Data()
        reportStatus()
    }

    private func handleControl(_ value: Data) {
        switch value.first {
        case OtaProtocol.commandStart: start(value)
        case OtaProtocol.commandAbort:
            ota = OtaTransfer()
            reportStatus()
        case OtaProtocol.commandFinish: finish()
        default: failOta(.badCommand)
        }
    }

    private func start(_ value: Data) {
        guard value.count == OtaProtocol.startLength else { return failOta(.badCommand) }
        guard ota.state != .receiving else { return failOta(.badState) }
        let bytes = [UInt8](value)
        let size = (0..<4).reduce(0) { $0 | Int(bytes[1 + $1]) << (8 * $1) }
        let versionCode = (0..<4).reduce(0) { $0 | Int(bytes[37 + $1]) << (8 * $1) }
        let sha = Data(bytes[5..<37])
        let signature = Data(bytes[41...])
        guard size > 0, size <= otaSlotBytes else { return failOta(.tooLarge) }
        guard otaVerifier.verify(size: size, versionCode: versionCode, sha256: sha, signature: signature) else {
            return failOta(.badSignature)
        }
        guard versionCode >= (OtaSignature.versionCode(revision: firmwareRevision) ?? 0) else { return failOta(.downgrade) }
        ota = OtaTransfer(state: .receiving, expected: size, versionCode: versionCode, sha256: sha)
        otaMaxBytesAhead = 0
        reportStatus()
    }

    private func handleData(_ value: Data) {
        guard ota.state == .receiving else { return failOta(.badState) }
        guard ota.received.count + value.count <= ota.expected else { return failOta(.overflow) }
        ota.received.append(value)
        otaMaxBytesAhead = max(otaMaxBytesAhead, ota.received.count - ota.reported)
        if ota.received.count - ota.reported >= OtaProtocol.ackInterval || ota.received.count == ota.expected {
            reportStatus()
        }
    }

    private func finish() {
        guard ota.state == .receiving else { return failOta(.badState) }
        guard ota.received.count == ota.expected else { return failOta(.incomplete) }
        guard Data(SHA256.hash(data: ota.received)) == ota.sha256 else { return failOta(.hash) }
        guard ota.received.first == 0xE9 else { return failOta(.image) }
        ota.state = .done
        reportStatus()
        otaCompleted += 1
        Task { [rebootDelay] in
            try? await Task.sleep(for: rebootDelay)
            self.reboot()
        }
    }

    private func reboot() {
        firmwareRevision = otaResultRevision
        ota = OtaTransfer()
        dropLink(reason: "board restarted")
    }

    private func pairIfRequired() throws(BLEError) {
        guard requireBonding, !bonded else { return }
        // iOS pairs on demand; model the user's answer to the system pairing prompt.
        guard acceptBonding else { throw .insufficientEncryption }
        bonded = true
    }

    func setNotify(_ enabled: Bool, for characteristic: AttributeID) throws(BLEError) {
        guard case .custom(let uuid) = characteristic, Self.notifiable.contains(uuid) else { throw .serviceNotFound }
        if uuid == GattProfile.otaStatus {
            guard supportsOta else { throw .serviceNotFound }
            try pairIfRequired()
            if enabled { notifying.insert(uuid) } else { notifying.remove(uuid) }
            return
        }
        // An IMU-less board does not have the motion and touch characteristics at all.
        if !hasBoardSensors, uuid != GattProfile.telemetry { throw .serviceNotFound }
        guard enabled else { return stopNotifying(uuid) }
        notifying.insert(uuid)
        guard let notifyInterval, tickers[uuid] == nil else { return }
        let interval = uuid == GattProfile.motion ? Duration.milliseconds(100) : notifyInterval
        guard uuid != GattProfile.touch else { return }
        tickers[uuid] = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: interval)
                guard let self else { return }
                let sent = uuid == GattProfile.motion ? await self.pushMotion() : await self.pushTelemetry()
                if !sent { return }
            }
        }
    }

    private static let notifiable: Set<UUID> = [GattProfile.telemetry, GattProfile.motion, GattProfile.touch, GattProfile.otaStatus]

    private func stopNotifying(_ uuid: UUID) {
        notifying.remove(uuid)
        tickers.removeValue(forKey: uuid)?.cancel()
    }

    private func stopNotifying() {
        notifying = []
        tickers.values.forEach { $0.cancel() }
        tickers = [:]
    }

    func centralDisconnected() {
        if ota.state == .receiving { ota = OtaTransfer() }
        stopNotifying()
        continuation?.finish()
        continuation = nil
    }
}

/// Deterministic noise for reproducible screenshots and tests.
struct SplitMix: Sendable {
    private var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func nextUnit() -> Double {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return Double((z ^ (z >> 31)) >> 11) / Double(1 << 53)
    }
}
