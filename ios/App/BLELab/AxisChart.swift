import BLELabKit
import Charts
import SwiftUI

/// Three-axis line chart with a legend and labelled y axis, shared by the live and session screens.
struct AxisChart: View {
    struct Point: Identifiable {
        let id: Int
        let x: Double
        let axis: String
        let y: Double
    }

    let series: [[Float]]
    /// X position of each sample, same length as every series.
    let xs: [Double]
    let unit: String
    let minExtent: Float
    var height: CGFloat = 160

    private static let axes = ["X", "Y", "Z"]
    private static let colors: [Color] = [.red, .green, .blue]

    private var points: [Point] {
        var out: [Point] = []
        for (axisIndex, values) in series.enumerated() {
            for (i, value) in values.enumerated() where i < xs.count {
                out.append(Point(id: axisIndex * xs.count + i, x: xs[i], axis: Self.axes[axisIndex], y: Double(value)))
            }
        }
        return out
    }

    var body: some View {
        let extent = Double(ChartScale.symmetricExtent(series, minExtent: minExtent) * 1.1)
        Chart(points) { point in
            LineMark(x: .value("Time", point.x), y: .value(unit, point.y))
                .foregroundStyle(by: .value("Axis", point.axis))
                .interpolationMethod(.monotone)
                .lineStyle(StrokeStyle(lineWidth: 1.6, lineJoin: .round))
        }
        .chartForegroundStyleScale(domain: Self.axes, range: Self.colors)
        .chartYScale(domain: -extent...extent)
        .chartXScale(domain: (xs.first ?? 0)...max(xs.last ?? 1, (xs.first ?? 0) + 0.001))
        .chartXAxis(.hidden)
        .chartYAxis {
            AxisMarks(position: .leading) { value in
                AxisGridLine()
                AxisValueLabel {
                    if let v = value.as(Double.self) { Text(v, format: .number.precision(.fractionLength(v.magnitude < 10 ? 1 : 0))) }
                }
            }
        }
        .chartLegend(position: .top, alignment: .trailing)
        .frame(height: height)
    }
}
