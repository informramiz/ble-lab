import Foundation

/// CSV export of a session: one row per sample, telemetry and motion share the time column.
public enum SessionCsv {
    public static let header = "kind,t_ms,value,led,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps"

    public static func format(_ data: SessionData) -> String {
        let rows = data.telemetry.map { ($0.tMillis, telemetryRow($0)) } + data.motion.map { ($0.tMillis, motionRow($0)) }
        // Stable sort: equal timestamps keep telemetry before motion.
        let ordered = rows.enumerated().sorted { ($0.element.0, $0.offset) < ($1.element.0, $1.offset) }.map(\.element.1)
        return ([header] + ordered).joined(separator: "\n") + "\n"
    }

    public static func fileName(_ session: RecordingSession) -> String { "ble-lab-session-\(session.id).csv" }

    private static func telemetryRow(_ p: TelemetryPoint) -> String {
        "telemetry,\(p.tMillis),\(num(p.value)),\(p.ledOn ? 1 : 0),,,,,,"
    }

    private static func motionRow(_ p: MotionPoint) -> String {
        "motion,\(p.tMillis),,,\(num(p.ax)),\(num(p.ay)),\(num(p.az)),\(num(p.gx)),\(num(p.gy)),\(num(p.gz))"
    }

    // A fixed POSIX locale so a comma-decimal region cannot corrupt the CSV.
    private static func num(_ value: Float) -> String {
        String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), value)
    }
}
