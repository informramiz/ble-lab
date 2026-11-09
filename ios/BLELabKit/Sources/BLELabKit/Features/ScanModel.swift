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

    public init(central: any BLECentral, duration: Duration = .seconds(10), sleeper: any Sleeper = TaskSleeper()) {
        self.central = central
        self.duration = duration
        self.sleeper = sleeper
    }

    public func start() {
        task?.cancel()
        devices = []
        error = nil
        isScanning = true
        let stream = central.scan(serviceFilter: labOnly ? GattProfile.labService : nil)
        task = Task { [weak self] in
            do {
                for try await device in stream { self?.upsert(device) }
            } catch {
                self?.error = (error as? BLEError) ?? .unexpected(error.localizedDescription)
                self?.isScanning = false
            }
        }
        timer?.cancel()
        timer = Task { [weak self, sleeper, duration] in
            do { try await sleeper.sleep(for: duration) } catch { return }
            self?.stop()
        }
    }

    public func stop() {
        task?.cancel()
        task = nil
        timer?.cancel()
        timer = nil
        isScanning = false
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
