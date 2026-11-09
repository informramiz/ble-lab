import Foundation

/// The BLE Lab GATT profile. Must match firmware/ble_lab/gatt_profile.h and the Android GattProfile.kt.
public enum GattProfile {
    public static let labService = UUID(uuidString: "B1E1AB00-5A1E-4C3A-9F5E-C0DE00B1E1AB")!
    /// Notify + read: 7 bytes LE (u32 sequence, i16 value x100, u8 flags; bit0 = LED).
    public static let telemetry = UUID(uuidString: "B1E1AB01-5A1E-4C3A-9F5E-C0DE00B1E1AB")!
    /// Read + write with response over an encrypted link: 1 byte.
    public static let led = UUID(uuidString: "B1E1AB02-5A1E-4C3A-9F5E-C0DE00B1E1AB")!

    /// Device Information service and its characteristics, as 16-bit SIG-assigned numbers.
    public static let deviceInfoService: UInt16 = 0x180A
    public static let manufacturerName: UInt16 = 0x2A29
    public static let modelNumber: UInt16 = 0x2A24
    public static let firmwareRevision: UInt16 = 0x2A26
}

/// A characteristic or service id: either a 128-bit custom UUID or a 16-bit SIG number.
public enum AttributeID: Hashable, Sendable {
    case custom(UUID)
    case sig(UInt16)
}
