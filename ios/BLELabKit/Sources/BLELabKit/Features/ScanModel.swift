import Foundation
import Observation

/// Scan screen state: a de-duplicated, RSSI-sorted device list from a time-boxed scan.
@MainActor
@Observable
public final class ScanModel {
    public private(set) var devices: [ScannedDevice] = []
    public private(set) var isScanning = false
    public private(set) var error: BLEError?
    /// Filter to the BLE Lab service UUID (on by default; off shows every advertiser).
    public var labOnly = true {
        didSet { if isScanning { start() } }
    }

    private let central: any BLECentral
    private let duration: Duration
    private let sleeper: any Sleeper
    private var task: Task<Void, Never>?
    private var timer: Task<Void, Never>?
    private var availabilityTask: Task<Void, Never>?
    /// True when the last scan ended because Bluetooth was off or not allowed; the next "radio is usable" restarts it.
    private var resumeWhenRadioReturns = false

    public init(central: any BLECentral, duration: Duration = .seconds(10), sleeper: any Sleeper = TaskSleeper()) {
        self.central = central
        self.duration = duration
        self.sleeper = sleeper
    }

    public func start() {
        observeRadio()
        task?.cancel()
        devices = []
        error = nil
        isScanning = true
        let stream = central.scan(serviceFilter: labOnly ? GattProfile.labService : nil)
        task = Task { [weak self] in
            do {
                for try await device in stream { self?.upsert(device) }
            } catch {
                let mapped = (error as? BLEError) ?? .unexpected(error.localizedDescription)
                self?.error = mapped
                self?.isScanning = false
                switch mapped {
                case .bluetoothUnavailable, .unauthorized: self?.resumeWhenRadioReturns = true
                default: break
                }
            }
        }
        timer?.cancel()
        timer = Task { [weak self, sleeper, duration] in
            do { try await sleeper.sleep(for: duration) } catch { return }
            self?.stop()
        }
    }

    public func stop() {
        resumeWhenRadioReturns = false
        task?.cancel()
        task = nil
        timer?.cancel()
        timer = nil
        isScanning = false
    }

    /// Bluetooth switched back on or the permission prompt answered with Allow: scan again without a relaunch.
    private func observeRadio() {
        guard availabilityTask == nil else { return }
        let updates = central.radioAvailability
        availabilityTask = Task { [weak self] in
            for await available in updates where available {
                guard let self, self.resumeWhenRadioReturns else { continue }
                self.start()
            }
        }
    }

    private func upsert(_ device: ScannedDevice) {
        if let index = devices.firstIndex(where: { $0.id == device.id }) {
            var merged = device
            merged.name = device.name ?? devices[index].name  // Names often arrive only in the scan response.
            devices[index] = merged
        } else {
            devices.append(device)
        }
        devices.sort { ($0.isBleLab ? 1 : 0, $0.rssi) > ($1.isBleLab ? 1 : 0, $1.rssi) }
    }
}
