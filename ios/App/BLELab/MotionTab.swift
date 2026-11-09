import BLELabKit
import SwiftUI

struct MotionTab: View {
    let model: DeviceModel

    var body: some View {
        if model.hasMotionSensor {
            VStack(spacing: 16) {
                TiltCard(tilt: model.tilt)
                DeviceCard(title: "Acceleration (g)", symbol: "arrow.up.and.down.and.arrow.left.and.right") {
                    AxisChart(series: Series.acceleration(model.motion), xs: xs, unit: "g", minExtent: 1.2)
                        .accessibilityIdentifier("accelChart")
                }
                DeviceCard(title: "Rotation (°/s)", symbol: "gyroscope") {
                    AxisChart(series: Series.rotation(model.motion), xs: xs, unit: "°/s", minExtent: 30)
                        .accessibilityIdentifier("gyroChart")
                }
            }
        } else {
            ContentUnavailableView(
                "No motion sensor",
                systemImage: "gyroscope",
                description: Text("This board has no accelerometer or gyroscope. Connect a CoreS3 to see live motion.")
            )
            .accessibilityIdentifier("noMotion")
        }
    }

    private var xs: [Double] { model.motion.indices.map(Double.init) }
}

private struct TiltCard: View {
    let tilt: Tilt?

    var body: some View {
        DeviceCard(title: "Tilt", symbol: "level") {
            HStack(spacing: 20) {
                ZStack {
                    Circle().stroke(.secondary.opacity(0.25), lineWidth: 2)
                    Capsule().fill(.tint).frame(width: 74, height: 6)
                        .rotationEffect(.degrees(Double(-(tilt?.rollDegrees ?? 0))))
                        .animation(.easeOut(duration: 0.15), value: tilt?.rollDegrees)
                }
                .frame(width: 92, height: 92)
                VStack(alignment: .leading, spacing: 6) {
                    Text(String(format: "Roll %.0f°", tilt?.rollDegrees ?? 0))
                    Text(String(format: "Pitch %.0f°", tilt?.pitchDegrees ?? 0))
                }
                .font(.title3.monospacedDigit().weight(.medium))
                Spacer()
            }
        }
        .accessibilityIdentifier("tiltCard")
    }
}
