import Foundation
import Testing
@testable import BLELabKit

/// A link whose control writes are never answered (a dead radio); it records that it was told to drop the connection.
private final class DeadControlLink: OtaLink, @unchecked Sendable {
    let status = AsyncStream<OtaStatus> { _ in }
    private let lock = NSLock()
    private var started = 0
    private var abandoned = 0

    var dataChunkSize: Int { 244 }
    var controlWrites: Int { lock.withLock { started } }
    var abandonCount: Int { lock.withLock { abandoned } }

    func writeControl(_ value: Data) async throws(BLEError) {
        lock.withLock { started += 1 }
        try? await Task.sleep(for: .seconds(3600))
    }

    func writeData(_ value: Data) async throws(BLEError) {}
    func abandonPendingWrites() async { lock.withLock { abandoned += 1 } }
}

/// The first sleep never ends on its own (the write's stall timer); later ones, such as the abort's, return at once.
private final class FirstSleepForever: Sleeper, @unchecked Sendable {
    private let lock = NSLock()
    private var calls = 0
    func sleep(for duration: Duration) async throws {
        let first = lock.withLock { () -> Bool in calls += 1; return calls == 1 }
        if first { try await Task.sleep(for: .seconds(3600)) }
    }
}

@Suite struct OtaWriteBoundTests {
    private var image: FirmwareImage { FirmwareImage.parse(OtaTestSigner().pack(testImageBytes(size: 2_000), versionCode: 10_300))! }

    @Test(.timeLimit(.minutes(1))) func unansweredControlWriteStallsAndDropsTheLink() async {
        let link = DeadControlLink()
        let image = image
        do throws(OtaFailure) {
            try await OtaUpdater(link: link, sleeper: ImmediateSleeper()).run(image, onProgress: { _ in })
            Issue.record("expected the update to fail")
        } catch {
            #expect(error == .stalled)
        }
        #expect(link.abandonCount >= 1)
    }

    @Test(.timeLimit(.minutes(1))) func cancellingDuringAHeldControlWriteEndsTheUpdateAndDropsTheLink() async {
        let link = DeadControlLink()
        let image = image
        let task = Task { () -> OtaFailure? in
            do throws(OtaFailure) {
                try await OtaUpdater(link: link, sleeper: FirstSleepForever()).run(image, onProgress: { _ in })
                return nil
            } catch { return error }
        }
        #expect(await eventually { link.controlWrites > 0 })
        task.cancel()
        #expect(await task.value == .cancelled)
        #expect(link.abandonCount >= 1)
    }
}
