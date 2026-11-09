import BLELabKit
import Foundation
import SwiftData

/// SwiftData adapter for `RecordingRepository`. A `ModelActor` keeps every fetch and save off the
/// main thread, so a 50 Hz IMU never janks the charts.
@ModelActor
actor SwiftDataRecordingRepository: RecordingRepository {
    func start(deviceAddress: String, deviceName: String?, startedAtMillis: Int64) throws -> Int {
        let id = try nextId()
        modelContext.insert(StoredSession(id: id, startedAtMillis: startedAtMillis, deviceName: deviceName, deviceAddress: deviceAddress))
        try modelContext.save()
        return id
    }

    func append(sessionId: Int, telemetry: [TelemetryPoint], motion: [MotionPoint]) throws {
        guard let session = try stored(sessionId) else { return }
        for point in telemetry { session.telemetry.append(StoredTelemetry(point)) }
        for point in motion { session.motion.append(StoredMotion(point)) }
        session.telemetryCount += telemetry.count
        session.motionCount += motion.count
        try modelContext.save()
    }

    func finish(sessionId: Int, endedAtMillis: Int64) throws {
        guard let session = try stored(sessionId) else { return }
        session.endedAtMillis = endedAtMillis
        try modelContext.save()
    }

    func closeOrphans() throws {
        let open = try modelContext.fetch(FetchDescriptor<StoredSession>(predicate: #Predicate { $0.endedAtMillis == nil }))
        for session in open {
            let lastTelemetry = session.telemetry.map(\.tMillis).max() ?? 0
            let lastMotion = session.motion.map(\.tMillis).max() ?? 0
            session.endedAtMillis = session.startedAtMillis + max(lastTelemetry, lastMotion)
        }
        try modelContext.save()
    }

    func sessions() throws -> [RecordingSession] {
        let all = try modelContext.fetch(FetchDescriptor<StoredSession>(sortBy: [SortDescriptor(\.startedAtMillis, order: .reverse)]))
        return all.map(\.summary)
    }

    func load(sessionId: Int) throws -> SessionData? {
        guard let session = try stored(sessionId) else { return nil }
        return SessionData(
            session: session.summary,
            telemetry: session.telemetry.sorted { $0.tMillis < $1.tMillis }.map { TelemetryPoint(tMillis: $0.tMillis, value: $0.value, ledOn: $0.ledOn) },
            motion: session.motion.sorted { $0.tMillis < $1.tMillis }.map {
                MotionPoint(tMillis: $0.tMillis, ax: $0.ax, ay: $0.ay, az: $0.az, gx: $0.gx, gy: $0.gy, gz: $0.gz)
            }
        )
    }

    func delete(sessionId: Int) throws {
        guard let session = try stored(sessionId) else { return }
        modelContext.delete(session)
        try modelContext.save()
    }

    private func stored(_ id: Int) throws -> StoredSession? {
        var descriptor = FetchDescriptor<StoredSession>(predicate: #Predicate { $0.id == id })
        descriptor.fetchLimit = 1
        return try modelContext.fetch(descriptor).first
    }

    private func nextId() throws -> Int {
        var descriptor = FetchDescriptor<StoredSession>(sortBy: [SortDescriptor(\.id, order: .reverse)])
        descriptor.fetchLimit = 1
        return (try modelContext.fetch(descriptor).first?.id ?? 0) + 1
    }
}
