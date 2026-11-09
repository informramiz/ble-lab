import BLELabKit
import SwiftData
import SwiftUI

@main
struct BLELabApp: App {
    private let central: any BLECentral = Self.makeCentral()
    private let repository: any RecordingRepository

    init() {
        do {
            let container = try ModelContainer(for: StoredSession.self, StoredTelemetry.self, StoredMotion.self)
            let repository = SwiftDataRecordingRepository(modelContainer: container)
            self.repository = repository
            // A recording the OS killed mid-session has no end time; close it before the UI lists it.
            Task { try? await repository.closeOrphans() }
        } catch {
            // The store is unusable; keep the app running with recordings that last until it quits.
            repository = InMemoryRecordingRepository()
        }
    }

    var body: some Scene {
        WindowGroup {
            ScanView(model: ScanModel(central: central), central: central, repository: repository)
        }
    }

    /// The simulator has no Bluetooth, so it runs on the simulated peripheral alone.
    /// On a phone the demo device is listed next to real hardware.
    private static func makeCentral() -> any BLECentral {
        let demo = SimulatedCentral(peripheral: SimulatedPeripheral(notifyInterval: .milliseconds(200)))
        #if targetEnvironment(simulator)
        return demo
        #else
        return CompositeCentral(hardware: CoreBluetoothCentral(), demo: demo)
        #endif
    }
}
