import Foundation
import Testing
@testable import BLELabKit

final class FakeClock: WallClock, @unchecked Sendable {
    var now: Int64
    init(_ now: Int64 = 1_000) { self.now = now }
    func nowMillis() -> Int64 { now }
}

private func motion(_ board: UInt32, ax: Float = 0, ay: Float = 0, az: Float = 1) -> MotionSample {
    MotionSample(boardMillis: board, ax: ax, ay: ay, az: az, gx: 0, gy: 0, gz: 0)
}

@MainActor
struct SessionRecorderTests {
    private func make(batch: Int = 4) -> (SessionRecorder, InMemoryRecordingRepository, FakeClock) {
        let repo = InMemoryRecordingRepository()
        let clock = FakeClock()
        return (SessionRecorder(repository: repo, clock: clock, batchSize: batch), repo, clock)
    }

    @Test func ignoresSamplesWhileIdle() async {
        let (recorder, repo, _) = make()
        await recorder.onTelemetry(Telemetry(sequence: 1, value: 1, ledOn: false))
        await recorder.onMotion(motion(0))
        #expect(recorder.state == .idle)
        #expect(await repo.sessions().isEmpty)
    }

    @Test func telemetryTimeIsRelativeToStart() async {
        let (recorder, repo, clock) = make()
        await recorder.start(deviceAddress: "A", deviceName: "Board")
        clock.now = 1_500
        await recorder.onTelemetry(Telemetry(sequence: 1, value: 21.5, ledOn: true))
        let id = await recorder.stop()
        let data = await repo.load(sessionId: id ?? -1)
        #expect(data?.telemetry == [TelemetryPoint(tMillis: 500, value: 21.5, ledOn: true)])
    }

    @Test func motionSpacingComesFromBoardClock() async {
        let (recorder, repo, clock) = make()
        await recorder.start(deviceAddress: "A", deviceName: nil)
        clock.now = 1_200
        for i in 0..<3 { await recorder.onMotion(motion(UInt32(5_000 + i * 20))) }
        let id = await recorder.stop()
        let times = await repo.load(sessionId: id ?? -1)?.motion.map(\.tMillis)
        #expect(times == [200, 220, 240])
    }

    @Test func batchesThenFlushesRemainder() async {
        let (recorder, repo, _) = make(batch: 4)
        await recorder.start(deviceAddress: "A", deviceName: nil)
        for i in 0..<10 { await recorder.onMotion(motion(UInt32(i))) }
        #expect(await repo.appendCalls.count == 2)
        let id = await recorder.stop()
        #expect(await repo.appendCalls.count == 3)
        #expect(await repo.load(sessionId: id ?? -1)?.motion.count == 10)
        #expect(await repo.sessions().first?.endedAtMillis != nil)
    }

    @Test func startTwiceKeepsFirstSession() async {
        let (recorder, repo, _) = make()
        await recorder.start(deviceAddress: "A", deviceName: nil)
        await recorder.start(deviceAddress: "B", deviceName: nil)
        #expect(await repo.sessions().count == 1)
        #expect(await recorder.stop() != nil)
        #expect(await recorder.stop() == nil)
    }

    @Test func boardOriginRestartsOnEachStart() async {
        let (recorder, repo, _) = make()
        await recorder.start(deviceAddress: "A", deviceName: nil)
        await recorder.onMotion(motion(9_000))
        await recorder.stop()
        await recorder.start(deviceAddress: "A", deviceName: nil)
        await recorder.onMotion(motion(9_500))
        let id = await recorder.stop()
        #expect(await repo.load(sessionId: id ?? -1)?.motion.first?.tMillis == 0)
    }

    @Test func closesOrphansAtLastSample() async throws {
        let repo = InMemoryRecordingRepository()
        let id = await repo.start(deviceAddress: "A", deviceName: nil, startedAtMillis: 1_000)
        await repo.append(sessionId: id, telemetry: [TelemetryPoint(tMillis: 300, value: 1, ledOn: false)], motion: [])
        await repo.closeOrphans()
        #expect(await repo.sessions().first?.endedAtMillis == 1_300)
    }
}

struct SessionCsvTests {
    private let session = RecordingSession(id: 7, startedAtMillis: 0, endedAtMillis: 10, deviceName: nil, deviceAddress: "A", telemetryCount: 1, motionCount: 1)

    @Test func mergesRowsInTimeOrder() {
        let data = SessionData(
            session: session,
            telemetry: [TelemetryPoint(tMillis: 100, value: 21.5, ledOn: true)],
            motion: [MotionPoint(tMillis: 50, ax: 0.1, ay: 0.2, az: 1, gx: 1, gy: 2, gz: 3)]
        )
        let lines = SessionCsv.format(data).split(separator: "\n").map(String.init)
        #expect(lines[0] == SessionCsv.header)
        #expect(lines[1] == "motion,50,,,0.100,0.200,1.000,1.000,2.000,3.000")
        #expect(lines[2] == "telemetry,100,21.500,1,,,,,,")
        #expect(lines.allSatisfy { $0.split(separator: ",", omittingEmptySubsequences: false).count == 10 })
    }

    @Test func emptySessionIsHeaderOnly() {
        #expect(SessionCsv.format(SessionData(session: session, telemetry: [], motion: [])) == SessionCsv.header + "\n")
    }

    @Test func fileName() { #expect(SessionCsv.fileName(session) == "ble-lab-session-7.csv") }
}

struct TiltAndChartTests {
    @Test func flatBoardIsLevel() {
        let tilt = Tilt(from: motion(0))
        #expect(abs(tilt.rollDegrees) < 0.01 && abs(tilt.pitchDegrees) < 0.01)
    }

    @Test func gravityOnYIsRoll90() {
        #expect(abs(Tilt(from: motion(0, ay: 1, az: 0)).rollDegrees - 90) < 0.01)
    }

    @Test func pitchFromX() {
        #expect(abs(Tilt(from: motion(0, ax: -0.5, az: 0.866)).pitchDegrees - 30) < 0.1)
    }

    @Test func fitKeepsMinSpanAndCentres() {
        let r = ChartScale.fit([20, 20.5])
        #expect(r.span >= ChartScale.defaultMinSpan)
        #expect(abs((r.min + r.max) / 2 - 20.25) < 0.001)
        #expect(ChartScale.fit([]) == YRange(min: 0, max: 4))
    }

    @Test func symmetricExtent() {
        #expect(ChartScale.symmetricExtent([[1, -3], [2]], minExtent: 2) == 3)
        #expect(ChartScale.symmetricExtent([[]], minExtent: 2) == 2)
    }

    @Test func downsampleKeepsEnds() {
        let out = Series.downsample(Array(0..<1000), max: 300)
        #expect(out.count == 300 && out.first == 0 && out.last == 999)
        #expect(Series.downsample([1, 2, 3], max: 300) == [1, 2, 3])
    }
}
