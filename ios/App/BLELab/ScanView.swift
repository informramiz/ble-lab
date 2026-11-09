import BLELabKit
import SwiftUI

struct ScanView: View {
    @State var model: ScanModel
    let central: any BLECentral
    let repository: any RecordingRepository
    @State private var selection: ScannedDevice?

    var body: some View {
        NavigationStack {
            List {
                if let error = model.error {
                    Section {
                        Label(error.message, systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                            .accessibilityIdentifier("scanError")
                    }
                }
                Section {
                    Toggle(isOn: $model.labOnly) {
                        Label("BLE Lab devices only", systemImage: "line.3.horizontal.decrease.circle")
                    }
                } footer: {
                    Text("Filters by the BLE Lab service UUID so other advertisers stay out of the list.")
                }
                Section {
                    if model.devices.isEmpty {
                        ContentUnavailableView(
                            model.isScanning ? "Looking for devices" : "No devices yet",
                            systemImage: "antenna.radiowaves.left.and.right",
                            description: Text(model.isScanning ? "Keep the board powered and nearby." : "Tap Scan to look for your ESP32.")
                        )
                    }
                    ForEach(model.devices) { device in
                        Button { selection = device } label: { DeviceRow(device: device) }
                            .accessibilityIdentifier("device-\(device.name ?? "unnamed")")
                    }
                } header: {
                    HStack {
                        Text("Nearby")
                        if model.isScanning { ProgressView().controlSize(.small) }
                    }
                }
            }
            .navigationTitle("BLE Lab")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    NavigationLink {
                        HistoryView(model: HistoryModel(repository: repository))
                    } label: { Label("History", systemImage: "clock.arrow.circlepath") }
                        .accessibilityIdentifier("historyButton")
                }
                ToolbarItem(placement: .primaryAction) {
                    Button(model.isScanning ? "Stop" : "Scan") {
                        model.isScanning ? model.stop() : model.start()
                    }
                    .accessibilityIdentifier("scanButton")
                }
            }
            .navigationDestination(item: $selection) { device in
                DeviceView(model: DeviceModel(
                    device: device,
                    manager: ConnectionManager(central: central),
                    recorder: SessionRecorder(repository: repository)
                ))
            }
            .task { model.start() }
        }
    }
}

private struct DeviceRow: View {
    let device: ScannedDevice

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: device.isBleLab ? "cpu.fill" : "dot.radiowaves.left.and.right")
                .font(.title3)
                .foregroundStyle(device.isBleLab ? Color.accentColor : .secondary)
                .frame(width: 40, height: 40)
                .background(.tint.opacity(device.isBleLab ? 0.15 : 0.05), in: .rect(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(device.name ?? "Unnamed device").font(.headline)
                    if device.isSimulated { Badge(text: "DEMO") }
                }
                Text(device.id.uuidString.prefix(8) + "…")
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            }
            Spacer()
            SignalBars(rssi: device.rssi)
        }
        .padding(.vertical, 4)
        .contentShape(.rect)
    }
}

struct Badge: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.caption2.weight(.bold))
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(.tint.opacity(0.18), in: .capsule)
            .foregroundStyle(.tint)
    }
}

private struct SignalBars: View {
    let rssi: Int

    private var level: Int {
        if rssi >= -60 { return 4 }
        if rssi >= -70 { return 3 }
        if rssi >= -80 { return 2 }
        return 1
    }

    var body: some View {
        VStack(alignment: .trailing, spacing: 2) {
            HStack(alignment: .bottom, spacing: 2) {
                ForEach(1...4, id: \.self) { bar in
                    RoundedRectangle(cornerRadius: 1.5)
                        .fill(bar <= level ? Color.accentColor : Color.secondary.opacity(0.25))
                        .frame(width: 4, height: CGFloat(4 + bar * 4))
                }
            }
            Text("\(rssi) dBm").font(.caption2.monospacedDigit()).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Signal \(rssi) dBm")
    }
}

extension ScannedDevice: @retroactive Hashable {
    public func hash(into hasher: inout Hasher) { hasher.combine(id) }
}
