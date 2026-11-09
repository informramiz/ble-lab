import Foundation

/// Visible value range of a chart's y axis.
public struct YRange: Equatable, Sendable {
    public let min: Float
    public let max: Float
    public var span: Float { max - min }
    public var closed: ClosedRange<Float> { min...max }

    public init(min: Float, max: Float) {
        self.min = min
        self.max = max
    }
}

/// Picks y ranges that stay honest: a flat signal must not be stretched to fill the chart.
public enum ChartScale {
    public static let defaultMinSpan: Float = 4
    private static let paddingFraction: Float = 0.1

    /// Range around `samples`, at least `minSpan` tall and centred on the data, with headroom.
    public static func fit(_ samples: [Float], minSpan: Float = defaultMinSpan) -> YRange {
        guard let low = samples.min(), let high = samples.max() else { return YRange(min: 0, max: minSpan) }
        let span = max(high - low, minSpan)
        let centre = (low + high) / 2
        let pad = span * paddingFraction
        return YRange(min: centre - span / 2 - pad, max: centre + span / 2 + pad)
    }

    /// Largest absolute value over all `series`, at least `minExtent`; the axis runs from -extent to +extent.
    public static func symmetricExtent(_ series: [[Float]], minExtent: Float) -> Float {
        max(series.compactMap { $0.map(abs).max() }.max() ?? 0, minExtent)
    }
}

/// Chart-ready series derived from stored rows.
public enum Series {
    public static let maxPoints = 300

    /// Keeps at most `max` points, evenly spread, always including the first and last.
    public static func downsample<T>(_ items: [T], max: Int = maxPoints) -> [T] {
        guard items.count > max, max >= 2 else { return items }
        return (0..<max).map { items[$0 * (items.count - 1) / (max - 1)] }
    }

    /// X, Y and Z acceleration (g) as three parallel series.
    public static func acceleration(_ motion: [MotionPoint]) -> [[Float]] { [motion.map(\.ax), motion.map(\.ay), motion.map(\.az)] }

    /// X, Y and Z rotation rate (deg/s) as three parallel series.
    public static func rotation(_ motion: [MotionPoint]) -> [[Float]] { [motion.map(\.gx), motion.map(\.gy), motion.map(\.gz)] }
}

extension Series {
    /// Live-window variants, for samples that have not been recorded.
    public static func acceleration(_ samples: [MotionSample]) -> [[Float]] { [samples.map(\.ax), samples.map(\.ay), samples.map(\.az)] }
    public static func rotation(_ samples: [MotionSample]) -> [[Float]] { [samples.map(\.gx), samples.map(\.gy), samples.map(\.gz)] }
}
