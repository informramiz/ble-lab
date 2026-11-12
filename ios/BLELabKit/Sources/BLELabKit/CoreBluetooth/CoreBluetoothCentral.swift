@preconcurrency import CoreBluetooth
import Foundation
import os

/// `BLECentral` over Core Bluetooth. Delegate callbacks arrive on one private serial queue;
/// each pending operation is a continuation keyed by characteristic, resumed from the callback.
///
/// State restoration: the manager is created with `CBCentralManagerOptionRestoreIdentifierKey`.
/// If iOS relaunches the app in the background for a Bluetooth event (requires the
/// `bluetooth-central` background mode), `willRestoreState` hands back the peripherals that were
/// connected or pending; `restoredPeripheralIDs` exposes them so the app can resume the session.
///
/// Runs only on hardware: the simulator has no Bluetooth. Covered by the README's on-device checklist.
public final class CoreBluetoothCentral: NSObject, BLECentral, @unchecked Sendable {
    public static let restoreIdentifier = "com.codebyramiz.blelab.central"

    private let queue = DispatchQueue(label: "com.codebyramiz.blelab.ble")
    private let log = Logger(subsystem: "com.codebyramiz.blelab", category: "ble")
    private var manager: CBCentralManager!
    // All mutable state below is touched only on `queue`.
    private var poweredOn: [CheckedContinuation<Void, any Error>] = []
    private var scanContinuation: AsyncThrowingStream<ScannedDevice, any Error>.Continuation?
    private var seen: [UUID: CBPeripheral] = [:]
    private var connecting: [UUID: CheckedContinuation<Void, any Error>] = [:]
    private var links: [UUID: CoreBluetoothLink] = [:]
    private var availabilityObservers: [UUID: AsyncStream<Bool>.Continuation] = [:]
    public private(set) var restoredPeripheralIDs: [UUID] = []

    /// Follows `centralManagerDidUpdateState`: `true` on every move to `.poweredOn`, `false` on off, denied or unsupported.
    public var radioAvailability: AsyncStream<Bool> {
        AsyncStream { continuation in
            let key = UUID()
            queue.async {
                self.availabilityObservers[key] = continuation
                // A subscriber arriving after the state settled still learns it.
                if self.manager.state == .poweredOn { continuation.yield(true) }
            }
            continuation.onTermination = { _ in self.queue.async { self.availabilityObservers[key] = nil } }
        }
    }

    public override init() {
        super.init()
        manager = CBCentralManager(delegate: self, queue: queue, options: [
            CBCentralManagerOptionShowPowerAlertKey: true,
            CBCentralManagerOptionRestoreIdentifierKey: Self.restoreIdentifier,
        ])
    }

    public func scan(serviceFilter: UUID?) -> AsyncThrowingStream<ScannedDevice, any Error> {
        AsyncThrowingStream { continuation in
            Task {
                do {
                    try await self.waitForPoweredOn()
                } catch {
                    continuation.finish(throwing: error)
                    return
                }
                self.queue.async {
                    self.scanContinuation = continuation
                    let services = serviceFilter.map { [CBUUID(nsuuid: $0)] }
                    // Duplicates on so RSSI stays live in the list; iOS coalesces them in the background anyway.
                    self.manager.scanForPeripherals(withServices: services, options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
                }
            }
            continuation.onTermination = { _ in
                self.queue.async {
                    self.manager.stopScan()
                    self.scanContinuation = nil
                }
            }
        }
    }

    public func connect(to id: UUID) async throws(BLEError) -> any GattLink {
        do {
            try await waitForPoweredOn()
            let peripheral: CBPeripheral = try await withCheckedThrowingContinuation { continuation in
                queue.async {
                    guard let peripheral = self.seen[id] ?? self.manager.retrievePeripherals(withIdentifiers: [id]).first else {
                        continuation.resume(throwing: BLEError.connectFailed(reason: "unknown peripheral"))
                        return
                    }
                    self.seen[id] = peripheral
                    continuation.resume(returning: peripheral)
                }
            }
            let link = CoreBluetoothLink(peripheral: peripheral, queue: queue) { [weak self] in
                self?.manager.cancelPeripheralConnection(peripheral)
            }
            try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
                    queue.async {
                        self.links[id] = link
                        self.connecting[id] = continuation
                        // Core Bluetooth connect requests never time out; the timeout lives in the caller.
                        self.manager.connect(peripheral, options: nil)
                    }
                }
            } onCancel: {
                self.queue.async { self.manager.cancelPeripheralConnection(peripheral) }
            }
            return link
        } catch let error as BLEError {
            throw error
        } catch {
            throw Self.map(error)
        }
    }

    private func waitForPoweredOn() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, any Error>) in
            queue.async {
                switch self.manager.state {
                case .poweredOn: continuation.resume()
                case .unauthorized: continuation.resume(throwing: BLEError.unauthorized)
                case .unsupported: continuation.resume(throwing: BLEError.bluetoothUnavailable(reason: "not supported on this device"))
                case .poweredOff: continuation.resume(throwing: BLEError.bluetoothUnavailable(reason: "Bluetooth is off"))
                default: self.poweredOn.append(continuation)  // .unknown / .resetting: wait for the update.
                }
            }
        }
    }

    static func map(_ error: any Error) -> BLEError {
        if let error = error as? BLEError { return error }
        if let att = error as? CBATTError {
            switch att.code {
            case .insufficientEncryption, .insufficientAuthentication: return .insufficientEncryption
            default: return .unexpected("ATT error \(att.code.rawValue)")
            }
        }
        if let cb = error as? CBError {
            switch cb.code {
            case .connectionTimeout, .peripheralDisconnected, .connectionFailed: return .linkLost(reason: cb.localizedDescription)
            case .peerRemovedPairingInformation: return .pairingRemoved
            case .encryptionTimedOut: return .pairingFailed
            default: return .connectFailed(reason: cb.localizedDescription)
            }
        }
        return .unexpected(error.localizedDescription)
    }
}

extension CoreBluetoothCentral: CBCentralManagerDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let waiters = poweredOn
        switch central.state {
        case .poweredOn:
            availabilityObservers.values.forEach { $0.yield(true) }
            poweredOn = []
            waiters.forEach { $0.resume() }
        case .unauthorized, .unsupported, .poweredOff:
            availabilityObservers.values.forEach { $0.yield(false) }
            poweredOn = []
            let error = central.state == .unauthorized ? BLEError.unauthorized : .bluetoothUnavailable(reason: "Bluetooth is off")
            waiters.forEach { $0.resume(throwing: error) }
            scanContinuation?.finish(throwing: error)
            scanContinuation = nil
            links.values.forEach { $0.didDisconnect(error: .bluetoothUnavailable(reason: "Bluetooth turned off")) }
            links = [:]
        default:
            break
        }
    }

    public func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        let peripherals = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] ?? []
        peripherals.forEach { seen[$0.identifier] = $0 }
        restoredPeripheralIDs = peripherals.map(\.identifier)
        log.info("Restored \(peripherals.count) peripheral(s) after relaunch")
    }

    public func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        seen[peripheral.identifier] = peripheral
        let services = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
        let name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name
        scanContinuation?.yield(ScannedDevice(
            id: peripheral.identifier,
            name: name,
            rssi: RSSI.intValue,
            isBleLab: services.contains(CBUUID(nsuuid: GattProfile.labService)),
            isSimulated: false
        ))
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        connecting.removeValue(forKey: peripheral.identifier)?.resume()
    }

    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: (any Error)?) {
        links[peripheral.identifier] = nil
        let mapped: BLEError = error.map(Self.map) ?? .connectFailed(reason: "unknown")
        connecting.removeValue(forKey: peripheral.identifier)?.resume(throwing: BLEError.connectFailed(reason: mapped.message))
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: (any Error)?) {
        connecting.removeValue(forKey: peripheral.identifier)?.resume(throwing: BLEError.connectFailed(reason: "disconnected while connecting"))
        // error == nil means we asked for it (cancelPeripheralConnection).
        let reason: BLEError = error.map(Self.map) ?? .linkLost(reason: "disconnected")
        links.removeValue(forKey: peripheral.identifier)?.didDisconnect(error: reason)
    }
}

/// One connected `CBPeripheral`. Operations are serialised by the caller (the connection manager
/// awaits each one), so one continuation slot per kind is enough.
final class CoreBluetoothLink: NSObject, GattLink, CBPeripheralDelegate, @unchecked Sendable {
    let events: AsyncStream<LinkEvent>
    private let eventContinuation: AsyncStream<LinkEvent>.Continuation
    private let peripheral: CBPeripheral
    private let queue: DispatchQueue
    private let cancel: @Sendable () -> Void
    private var discovery: CheckedContinuation<Set<AttributeID>, any Error>?
    private var pendingServices = 0
    private var reads: [CBUUID: CheckedContinuation<Data, any Error>] = [:]
    private var writes: [CBUUID: CheckedContinuation<Void, any Error>] = [:]
    private var notifies: [CBUUID: CheckedContinuation<Void, any Error>] = [:]
    /// Queue-confined; holds writes without response until `canSendWriteWithoutResponse` is true.
    private lazy var pacer = WriteWithoutResponsePacer(
        canSend: { [peripheral] in peripheral.canSendWriteWithoutResponse },
        send: { [peripheral, weak self] data in
            guard let target = self?.pacedTarget else { return }
            peripheral.writeValue(data, for: target, type: .withoutResponse)
        }
    )
    private var pacedTarget: CBCharacteristic?

    init(peripheral: CBPeripheral, queue: DispatchQueue, cancel: @escaping @Sendable () -> Void) {
        self.peripheral = peripheral
        self.queue = queue
        self.cancel = cancel
        (events, eventContinuation) = AsyncStream.makeStream(bufferingPolicy: .bufferingNewest(128))
        super.init()
        peripheral.delegate = self
    }

    var maximumWriteLength: Int {
        get async { await onQueue { self.peripheral.maximumWriteValueLength(for: .withResponse) } }
    }

    var maximumWriteWithoutResponseLength: Int {
        get async { await onQueue { self.peripheral.maximumWriteValueLength(for: .withoutResponse) } }
    }

    func discoverServices() async throws(BLEError) -> Set<AttributeID> {
        try await run { (c: CheckedContinuation<Set<AttributeID>, any Error>) in
            self.discovery = c
            self.peripheral.discoverServices([
                CBUUID(nsuuid: GattProfile.labService), Self.uuid(.sig(GattProfile.deviceInfoService)),
                CBUUID(nsuuid: GattProfile.otaService),
            ])
        }
    }

    func read(_ characteristic: AttributeID, in service: AttributeID) async throws(BLEError) -> Data {
        try await run { (c: CheckedContinuation<Data, any Error>) in
            guard let target = self.find(characteristic, in: service) else { return c.resume(throwing: BLEError.serviceNotFound) }
            self.reads[target.uuid] = c
            self.peripheral.readValue(for: target)
        }
    }

    func write(_ value: Data, to characteristic: AttributeID, in service: AttributeID) async throws(BLEError) {
        try await run { (c: CheckedContinuation<Void, any Error>) in
            guard let target = self.find(characteristic, in: service) else { return c.resume(throwing: BLEError.serviceNotFound) }
            self.writes[target.uuid] = c
            self.peripheral.writeValue(value, for: target, type: .withResponse)
        }
    }

    /// Apple: `writeValue(_:for:type: .withoutResponse)` drops data when the queue is full, so every write
    /// goes through the pacer, which checks `canSendWriteWithoutResponse` and waits for
    /// `peripheralIsReady(toSendWriteWithoutResponse:)`.
    func writeWithoutResponse(_ value: Data, to characteristic: AttributeID, in service: AttributeID) async throws(BLEError) {
        try await run { (c: CheckedContinuation<Void, any Error>) in
            guard let target = self.find(characteristic, in: service) else { return c.resume(throwing: BLEError.serviceNotFound) }
            self.pacedTarget = target
            self.pacer.submit(value) { result in
                switch result {
                case .success: c.resume()
                case .failure(let error): c.resume(throwing: error)
                }
            }
        }
    }

    func setNotify(_ enabled: Bool, for characteristic: AttributeID, in service: AttributeID) async throws(BLEError) {
        try await run { (c: CheckedContinuation<Void, any Error>) in
            guard let target = self.find(characteristic, in: service) else { return c.resume(throwing: BLEError.serviceNotFound) }
            self.notifies[target.uuid] = c
            self.peripheral.setNotifyValue(enabled, for: target)
        }
    }

    func disconnect() async { queue.async { self.cancel() } }

    /// Called on `queue` by the central when the link goes down: fail everything pending.
    func didDisconnect(error: BLEError) {
        let pending: [any Error] = [error]
        discovery?.resume(throwing: pending[0]); discovery = nil
        reads.values.forEach { $0.resume(throwing: error) }; reads = [:]
        writes.values.forEach { $0.resume(throwing: error) }; writes = [:]
        notifies.values.forEach { $0.resume(throwing: error) }; notifies = [:]
        pacer.fail(error)
        eventContinuation.yield(.disconnected(error))
        eventContinuation.finish()
    }

    // MARK: CBPeripheralDelegate (on `queue`)

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: (any Error)?) {
        if let error { discovery?.resume(throwing: CoreBluetoothCentral.map(error)); discovery = nil; return }
        let services = peripheral.services ?? []
        pendingServices = services.count
        if services.isEmpty { finishDiscovery() }
        services.forEach { peripheral.discoverCharacteristics(nil, for: $0) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: (any Error)?) {
        pendingServices -= 1
        if pendingServices == 0 { finishDiscovery() }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: (any Error)?) {
        if let continuation = reads.removeValue(forKey: characteristic.uuid) {
            if let error { continuation.resume(throwing: CoreBluetoothCentral.map(error)) } else { continuation.resume(returning: characteristic.value ?? Data()) }
        } else if error == nil, let value = characteristic.value {
            eventContinuation.yield(.notification(Self.attribute(characteristic.uuid), value))
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: (any Error)?) {
        guard let continuation = writes.removeValue(forKey: characteristic.uuid) else { return }
        if let error { continuation.resume(throwing: CoreBluetoothCentral.map(error)) } else { continuation.resume() }
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        pacer.ready()
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: (any Error)?) {
        guard let continuation = notifies.removeValue(forKey: characteristic.uuid) else { return }
        if let error { continuation.resume(throwing: CoreBluetoothCentral.map(error)) } else { continuation.resume() }
    }

    // MARK: Helpers

    private func finishDiscovery() {
        let ids = Set((peripheral.services ?? []).map { Self.attribute($0.uuid) })
        discovery?.resume(returning: ids)
        discovery = nil
    }

    private func find(_ characteristic: AttributeID, in service: AttributeID) -> CBCharacteristic? {
        peripheral.services?.first { $0.uuid == Self.uuid(service) }?.characteristics?.first { $0.uuid == Self.uuid(characteristic) }
    }

    private func run<T: Sendable>(_ start: @escaping @Sendable (CheckedContinuation<T, any Error>) -> Void) async throws(BLEError) -> T {
        do {
            return try await withCheckedThrowingContinuation { continuation in queue.async { start(continuation) } }
        } catch {
            throw CoreBluetoothCentral.map(error)
        }
    }

    private func onQueue<T: Sendable>(_ body: @escaping @Sendable () -> T) async -> T {
        await withCheckedContinuation { continuation in queue.async { continuation.resume(returning: body()) } }
    }

    static func uuid(_ id: AttributeID) -> CBUUID {
        switch id {
        case .custom(let uuid): CBUUID(nsuuid: uuid)
        case .sig(let short): CBUUID(string: String(format: "%04X", short))
        }
    }

    static func attribute(_ uuid: CBUUID) -> AttributeID {
        if uuid.data.count == 2 { return .sig(uuid.data.withUnsafeBytes { UInt16(bigEndian: $0.loadUnaligned(as: UInt16.self)) }) }
        return .custom(UUID(uuidString: uuid.uuidString) ?? UUID())
    }
}
