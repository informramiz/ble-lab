import BLELabKit
import Charts
import SwiftUI

struct DeviceView: View {
    @State var model: DeviceModel

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                StatusCard(state: model.state, onRetry: model.retry)
                TelemetryCard(model: model)
                LedCard(model: model)
                InfoCard(model: model)
                LogCard(lines: model.log)
            }
            .padding()
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle(model.device.name ?? "Device")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: model.state, initial: true) { _, state in model.observe(state) }
        .task { model.start() }
        .onDisappear { Task { await model.stop() } }
    }
}

private struct Card<Content: View>: View {
    let title: String
    let symbol: String
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(title, systemImage: symbol).font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(Color(.secondarySystemGroupedBackground), in: .rect(cornerRadius: 20))
    }
}

private struct StatusCard: View {
    let state: ConnectionState
    let onRetry: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Circle().fill(color).frame(width: 12, height: 12)
                .shadow(color: color.opacity(0.6), radius: 6)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                if let detail { Text(detail).font(.footnote).foregroundStyle(.secondary) }
            }
            Spacer()
            if case .failed = state {
                Button("Retry", systemImage: "arrow.clockwise", action: onRetry)
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("retryButton")
            } else if isBusy {
                ProgressView()
            }
        }
        .padding()
        .background(color.opacity(0.12), in: .rect(cornerRadius: 20))
        .accessibilityIdentifier("statusCard")
    }

    private var isBusy: Bool {
        switch state {
        case .connecting, .discovering, .reconnecting: true
        default: false
        }
    }

    private var title: String {
        switch state {
        case .idle: "Idle"
        case .connecting(let attempt): attempt > 1 ? "Connecting (attempt \(attempt))" : "Connecting"
        case .discovering: "Discovering services"
        case .ready: "Connected"
        case .reconnecting(let attempt, _, _): "Reconnecting (attempt \(attempt))"
        case .failed: "Connection failed"
        }
    }

    private var detail: String? {
        switch state {
        case .reconnecting(_, let delay, let cause): "\(cause.message). Next try in \(delay.formatted(.units(allowed: [.seconds], width: .narrow, fractionalPart: .show(length: 1))))"
        case .failed(let error): error.message
        case .ready(_, let length): "Notifications on · \(length)-byte writes"
        default: nil
        }
    }

    private var color: Color {
        switch state {
        case .ready: .green
        case .failed: .red
        case .reconnecting: .orange
        default: .accentColor
        }
    }
}

private struct TelemetryCard: View {
    let model: DeviceModel

    private var xDomain: ClosedRange<UInt32> {
        let first = model.samples.first?.sequence ?? 0
        return first...max(model.samples.last?.sequence ?? 0, first + 1)
    }

    var body: some View {
        Card(title: "Live telemetry", symbol: "waveform.path.ecg") {
            HStack(alignment: .firstTextBaseline) {
                Text(model.latest.map { String(format: "%.2f", $0.value) } ?? "--")
                    .font(.system(size: 44, weight: .semibold, design: .rounded).monospacedDigit())
                    .contentTransition(.numericText())
                Text("°C").font(.title3).foregroundStyle(.secondary)
                Spacer()
                if let stats = model.stats {
                    VStack(alignment: .trailing) {
                        Text(String(format: "min %.1f  max %.1f", stats.min, stats.max))
                        Text(String(format: "mean %.2f · #%d", stats.mean, model.latest?.sequence ?? 0))
                    }
                    .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                }
            }
            Chart(model.samples, id: \.sequence) { sample in
                AreaMark(x: .value("Sample", sample.sequence), y: .value("Value", sample.value))
                    .foregroundStyle(.linearGradient(colors: [.accentColor.opacity(0.35), .clear], startPoint: .top, endPoint: .bottom))
                    .interpolationMethod(.catmullRom)
                LineMark(x: .value("Sample", sample.sequence), y: .value("Value", sample.value))
                    .foregroundStyle(Color.accentColor)
                    .interpolationMethod(.catmullRom)
            }
            .chartYScale(domain: .automatic(includesZero: false))
            .chartXScale(domain: xDomain)  // Automatic rounds the start down and leaves a gap on the left.
            .chartXAxis(.hidden)
            .frame(height: 170)
            .accessibilityIdentifier("telemetryChart")
        }
    }
}

private struct LedCard: View {
    let model: DeviceModel

    var body: some View {
        Card(title: "Onboard LED", symbol: "lightbulb") {
            HStack {
                Image(systemName: model.ledOn ? "lightbulb.max.fill" : "lightbulb")
                    .font(.largeTitle)
                    .foregroundStyle(model.ledOn ? .yellow : .secondary)
                    .shadow(color: .yellow.opacity(model.ledOn ? 0.7 : 0), radius: 14)
                    .animation(.easeInOut, value: model.ledOn)
                VStack(alignment: .leading) {
                    Text(model.ledOn ? "On" : "Off").font(.headline)
                    Text("Encrypted write; iOS asks to pair the first time.").font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Toggle("LED", isOn: Binding(get: { model.ledOn }, set: { on in Task { await model.setLED(on) } }))
                    .labelsHidden()
                    .disabled(!model.canWrite)
                    .accessibilityIdentifier("ledSwitch")
            }
            if let error = model.writeError {
                Label(error.message, systemImage: "lock.trianglebadge.exclamationmark").font(.footnote).foregroundStyle(.red)
            }
        }
    }
}

private struct InfoCard: View {
    let model: DeviceModel

    var body: some View {
        Card(title: "Device information", symbol: "info.circle") {
            Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 8) {
                row("Manufacturer", info?.manufacturer)
                row("Model", info?.model)
                row("Firmware", info?.firmware)
                row("Identifier", String(model.device.id.uuidString.prefix(18)))
            }
            .font(.callout)
        }
    }

    private var info: DeviceInfo? {
        if case .ready(let info, _) = model.state { info } else { nil }
    }

    private func row(_ label: String, _ value: String?) -> some View {
        GridRow {
            Text(label).foregroundStyle(.secondary)
            Text(value ?? "—").font(.callout.monospaced())
        }
    }
}

private struct LogCard: View {
    let lines: [String]

    var body: some View {
        Card(title: "Event log", symbol: "list.bullet.rectangle") {
            ForEach(Array(lines.suffix(6).reversed().enumerated()), id: \.offset) { _, line in
                Text(line).font(.caption.monospaced()).foregroundStyle(.secondary)
            }
        }
    }
}
