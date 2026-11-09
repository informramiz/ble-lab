import BLELabKit
import Charts
import SwiftUI

@MainActor
@Observable
final class HistoryModel {
    private let repository: any RecordingRepository
    private(set) var sessions: [RecordingSession] = []
    private(set) var error: String?

    init(repository: any RecordingRepository) { self.repository = repository }

    func refresh() async {
        do { sessions = try await repository.sessions() } catch { self.error = error.localizedDescription }
    }

    func delete(_ ids: [Int]) async {
        for id in ids {
            do { try await repository.delete(sessionId: id) } catch { self.error = error.localizedDescription }
        }
        await refresh()
    }

    func load(_ id: Int) async -> SessionData? { try? await repository.load(sessionId: id) }
}

enum SessionFormat {
    static func summary(_ session: RecordingSession) -> String {
        let date = session.startedAt.formatted(date: .abbreviated, time: .shortened)
        guard let ms = session.durationMillis else { return "\(date) · Not closed" }
        return "\(date) · \(ms / 1000) s"
    }
}

struct HistoryView: View {
    @State var model: HistoryModel

    var body: some View {
        List {
            if model.sessions.isEmpty {
                ContentUnavailableView(
                    "No recordings yet",
                    systemImage: "waveform.path",
                    description: Text("Open a device and tap Record session.")
                )
                .accessibilityIdentifier("emptyHistory")
            }
            ForEach(model.sessions) { session in
                NavigationLink {
                    SessionView(model: model, sessionId: session.id)
                } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(session.deviceName ?? "Unnamed device").font(.headline)
                        Text(SessionFormat.summary(session)).font(.subheadline).foregroundStyle(.secondary)
                        Text("\(session.telemetryCount) readings · \(session.motionCount) motion samples")
                            .font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                    }
                }
            }
            .onDelete { offsets in
                let ids = offsets.map { model.sessions[$0].id }
                Task { await model.delete(ids) }
            }
        }
        .navigationTitle("Recorded sessions")
        .task { await model.refresh() }
    }
}

struct SessionView: View {
    let model: HistoryModel
    let sessionId: Int
    @State private var data: SessionData?
    @State private var loaded = false

    var body: some View {
        ScrollView {
            if let data {
                VStack(spacing: 16) {
                    DeviceCard(title: "Session", symbol: "info.circle") {
                        Text(SessionFormat.summary(data.session)).font(.callout)
                    }
                    DeviceCard(title: "Telemetry", symbol: "waveform.path.ecg") { telemetryChart(data) }
                    if data.motion.isEmpty {
                        DeviceCard(title: "Motion", symbol: "gyroscope") {
                            Text("No motion data in this session.").font(.callout).foregroundStyle(.secondary)
                        }
                    } else {
                        let motion = Series.downsample(data.motion)
                        let xs = motion.map { Double($0.tMillis) / 1000 }
                        DeviceCard(title: "Acceleration (g)", symbol: "arrow.up.and.down.and.arrow.left.and.right") {
                            AxisChart(series: Series.acceleration(motion), xs: xs, unit: "g", minExtent: 1.2)
                        }
                        DeviceCard(title: "Rotation (°/s)", symbol: "gyroscope") {
                            AxisChart(series: Series.rotation(motion), xs: xs, unit: "°/s", minExtent: 30)
                        }
                    }
                }
                .padding()
            } else if loaded {
                ContentUnavailableView("This recording no longer exists.", systemImage: "trash")
            } else {
                ProgressView().padding(.top, 80)
            }
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("Session")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let data {
                ToolbarItem(placement: .primaryAction) {
                    ShareLink(
                        item: CsvDocument(data: data),
                        preview: SharePreview(SessionCsv.fileName(data.session))
                    ) { Label("Export CSV", systemImage: "square.and.arrow.up") }
                        .accessibilityIdentifier("exportCsv")
                }
            }
        }
        .task {
            data = await model.load(sessionId)
            loaded = true
        }
    }

    private func telemetryChart(_ data: SessionData) -> some View {
        let points = Series.downsample(data.telemetry)
        let range = ChartScale.fit(points.map(\.value))
        return Chart(points, id: \.tMillis) { point in
            LineMark(x: .value("Time (s)", Double(point.tMillis) / 1000), y: .value("Value", point.value))
                .interpolationMethod(.monotone)
        }
        .chartYScale(domain: range.closed)
        .frame(height: 150)
    }
}

/// Lets `ShareLink` hand the CSV over as a real `.csv` file.
struct CsvDocument: Transferable {
    let data: SessionData

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .commaSeparatedText) { document in
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(SessionCsv.fileName(document.data.session))
            try SessionCsv.format(document.data).write(to: url, atomically: true, encoding: .utf8)
            return SentTransferredFile(url)
        }
    }
}
