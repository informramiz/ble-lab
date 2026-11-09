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

    private var continuation: AsyncStream<LinkEvent>.Continuation?
    private var notifying = false
    private var sequence: UInt32 = 0
    private var rng: SplitMix

    public init(id: UUID = SimulatedPeripheral.demoID, name: String = "BLE Lab (demo)", seed: UInt64 = 7) {
        self.id = id
        self.name = name
        self.rng = SplitMix(seed: seed)
    }

    public func configure(connectFailures: Int = 0, requireBonding: Bool = false, acceptBonding: Bool = true) {
        connectFailuresRemaining = connectFailures
        self.requireBonding = requireBonding
        self.acceptBonding = acceptBonding
    }

    /// Pushes one telemetry notification if a central has subscribed. Returns whether it was sent.
    @discardableResult
    public func pushTelemetry() -> Bool {
        guard notifying, let continuation else { return false }
        let wave = 24 + 3 * sin(2 * Double.pi * Double(sequence) / 50)
        let noise = (rng.nextUnit() - 0.5) * 0.4
        let reading = Telemetry(sequence: sequence, value: Float(wave + noise), ledOn: ledOn)
        sequence &+= 1
        continuation.yield(.notification(.custom(GattProfile.telemetry), TelemetryCodec.encode(reading)))
        return true
    }

    /// Simulates the radio dropping the link (supervision timeout, out of range).
    public func dropLink(reason: String = "supervision timeout") {
        notifying = false
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
        case .sig(GattProfile.modelNumber): Data("ESP32-WROOM-32".utf8)
        case .sig(GattProfile.firmwareRevision): Data("ble-lab 1.0.0".utf8)
        case .custom(GattProfile.led): TelemetryCodec.encodeLed(ledOn)
        default: throw .unexpected("read not permitted")
        }
    }

    func write(_ value: Data, to characteristic: AttributeID) throws(BLEError) {
        guard characteristic == .custom(GattProfile.led), value.count == 1 else { throw .unexpected("write not permitted") }
        if requireBonding, !bonded {
            // iOS pairs on demand; model the user's answer to the system pairing prompt.
            guard acceptBonding else { throw .insufficientEncryption }
            bonded = true
        }
        ledWrites.append(value)
        ledOn = value.first != 0
    }

    func setNotify(_ enabled: Bool) { notifying = enabled }

    func centralDisconnected() {
        notifying = false
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
