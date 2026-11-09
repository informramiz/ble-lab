import BLELabKit
import SwiftUI

@main
struct BLELabApp: App {
    private let central: any BLECentral = Self.makeCentral()

    var body: some Scene {
        WindowGroup {
            ScanView(model: ScanModel(central: central), central: central)
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
