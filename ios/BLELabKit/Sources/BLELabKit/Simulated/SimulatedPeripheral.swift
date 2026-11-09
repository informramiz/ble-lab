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

    private var continuation: AsyncStream<LinkEvent>.Continuation?
    private var notifying: Set<UUID> = []
    private var sequence: UInt32 = 0
    private var motionIndex = 0
    private var touchCount = 0
    private var rng: SplitMix
    private let notifyInterval: Duration?
    private var tickers: [UUID: Task<Void, Never>] = [:]

    /// `notifyInterval` streams telemetry on its own while notifications are on, like the firmware
    /// (200 ms). `nil` leaves it to `pushTelemetry()`, which keeps tests deterministic.
    public init(
        id: UUID = SimulatedPeripheral.demoID,
        name: String = "BLE Lab (demo)",
        seed: UInt64 = 7,
        notifyInterval: Duration? = nil,
        hasBoardSensors: Bool = true
    ) {
        self.hasBoardSensors = hasBoardSensors
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
        case .sig(GattProfile.firmwareRevision): Data("ble-lab 1.1.0".utf8)
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
        default:
            throw .unexpected("write not permitted")
        }
    }

    private func pairIfRequired() throws(BLEError) {
        guard requireBonding, !bonded else { return }
        // iOS pairs on demand; model the user's answer to the system pairing prompt.
        guard acceptBonding else { throw .insufficientEncryption }
        bonded = true
    }

    func setNotify(_ enabled: Bool, for characteristic: AttributeID) throws(BLEError) {
        guard case .custom(let uuid) = characteristic, Self.notifiable.contains(uuid) else { throw .serviceNotFound }
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

    private static let notifiable: Set<UUID> = [GattProfile.telemetry, GattProfile.motion, GattProfile.touch]

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
