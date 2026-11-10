import Foundation

/// Board orientation from gravity, in degrees. Roll is about the X axis, pitch about Y.
public struct Tilt: Equatable, Sendable {
    public let rollDegrees: Float
    public let pitchDegrees: Float

    public static let level = Tilt(rollDegrees: 0, pitchDegrees: 0)

    public init(rollDegrees: Float, pitchDegrees: Float) {
        self.rollDegrees = rollDegrees
        self.pitchDegrees = pitchDegrees
    }

    public init(from sample: MotionSample) {
        let roll = atan2(sample.ay, sample.az)
        let pitch = atan2(-sample.ax, (sample.ay * sample.ay + sample.az * sample.az).squareRoot())
        self.init(rollDegrees: roll * 180 / .pi, pitchDegrees: pitch * 180 / .pi)
    }
}
