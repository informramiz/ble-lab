import Foundation
import Testing
@testable import BLELabKit

/// A board on the far end of an `OtaLink`: acknowledges like the firmware (START, every 1024 bytes, last byte),
/// or holds acknowledgements back so flow control can be observed.
actor FakeOtaBoard: OtaLink {
    enum Acks { case automatic, manual, none }

    nonisolated let status: AsyncStream<OtaStatus>
    private let continuation: AsyncStream<OtaStatus>.Continuation
    private let acks: Acks
    private let rejectStart: OtaError?
    private let failDataWith: BLEError?
    private let errorAfterBytes: (Int, OtaError)?
    private let chunk: Int

    private(set) var received = Data()
    private(set) var controls: [Data] = []
    private(set) var maxOutstanding = 0
    private var total = 0
    private var acked = 0

    init(chunk: Int = 244, acks: Acks = .automatic, rejectStart: OtaError? = nil, failDataWith: BLEError? = nil, errorAfterBytes: (Int, OtaError)? = nil) {
        (status, continuation) = AsyncStream.makeStream(bufferingPolicy: .unbounded)
        self.chunk = chunk
        self.acks = acks
        self.rejectStart = rejectStart
        self.failDataWith = failDataWith
        self.errorAfterBytes = errorAfterBytes
    }

    var dataChunkSize: Int { chunk }

    func writeControl(_ value: Data) throws(BLEError) {
        controls.append(value)
        switch value.first {
        case OtaProtocol.commandStart:
            total = Int(value[1]) | Int(value[2]) << 8 | Int(value[3]) << 16 | Int(value[4]) << 24
            if let rejectStart {
                continuation.yield(OtaStatus(state: .error, error: rejectStart, offset: 0))
            } else if acks != .none {
                continuation.yield(OtaStatus(state: .receiving, offset: 0))
            }
        case OtaProtocol.commandFinish:
            continuation.yield(OtaStatus(state: .done, offset: received.count))
        default:
            break
        }
    }

    func writeData(_ value: Data) throws(BLEError) {
        if let failDataWith { throw failDataWith }
        received.append(value)
        maxOutstanding = max(maxOutstanding, received.count - acked)
        if let (bytes, error) = errorAfterBytes, received.count >= bytes {
            continuation.yield(OtaStatus(state: .error, error: error, offset: received.count))
        }
        guard acks == .automatic else { return }
        while acked + OtaProtocol.ackInterval <= received.count {
            acked += OtaProtocol.ackInterval
            continuation.yield(OtaStatus(state: .receiving, offset: acked))
        }
        if received.count == total {
            acked = total
            continuation.yield(OtaStatus(state: .receiving, offset: total))
        }
    }

    /// Manual mode: report `offset` bytes as accepted.
    func ack(_ offset: Int) {
        acked = offset
        continuation.yield(OtaStatus(state: .receiving, offset: offset))
    }
}

/// Collects progress from the `@Sendable` callback.
final class ProgressLog: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [OtaProgress] = []
    func add(_ item: OtaProgress) { lock.withLock { items.append(item) } }
    var all: [OtaProgress] { lock.withLock { items } }
}

/// Yields until `condition` holds; for state that another task changes.
func eventually(_ iterations: Int = 20_000, _ condition: () async -> Bool) async -> Bool {
    for _ in 0..<iterations {
        if await condition() { return true }
        await Task.yield()
    }
    return await condition()
}

/// Runs an update and returns how it failed, nil when it finished.
private func outcome(of board: FakeOtaBoard, _ image: FirmwareImage) async -> OtaFailure? {
    do throws(OtaFailure) {
        try await OtaUpdater(link: board, sleeper: ForeverSleeper()).run(image, onProgress: { _ in })
        return nil
    } catch {
        return error
    }
}

@Suite struct OtaUpdaterTests {
    private let raw = testImageBytes(size: 10_000)
    private var image: FirmwareImage { FirmwareImage.parse(OtaTestSigner().pack(raw, versionCode: 10_300))! }

    @Test func sendsStartDataAndFinishInOrder() async throws {
        let board = FakeOtaBoard()
        let log = ProgressLog()
        try await OtaUpdater(link: board, sleeper: ForeverSleeper()).run(image, onProgress: log.add)

        let controls = await board.controls
        #expect(controls.count == 2)
        #expect(controls.first?.count == 105)
        #expect(controls.last == OtaProtocol.finish)
        #expect(await board.received == raw)

        let progress = log.all
        #expect(progress.first == .starting)
        #expect(progress.last == .restarting)
        #expect(progress.contains(.sending(sent: 0, total: 10_000)))
        #expect(progress.contains(.sending(sent: 10_000, total: 10_000)))
        #expect(progress.contains(.verifying))
    }

    @Test func startCarriesSizeHashVersionAndSignatureFromThePackage() async throws {
        let board = FakeOtaBoard()
        let image = image
        try await OtaUpdater(link: board, sleeper: ForeverSleeper()).run(image, onProgress: { _ in })
        let start = try #require(await board.controls.first)
        #expect(start == OtaProtocol.encodeStart(size: 10_000, sha256: image.sha256, versionCode: 10_300, signature: try #require(image.signature)))
    }

    @Test func chunksAreSizedFromTheLink() async throws {
        let board = FakeOtaBoard(chunk: 100)
        try await OtaUpdater(link: board, sleeper: ForeverSleeper()).run(image, onProgress: { _ in })
        #expect(await board.received == raw)
        #expect(await board.maxOutstanding <= OtaProtocol.windowBytes)
    }

    @Test func neverRunsMoreThanTheWindowAheadOfTheBoard() async throws {
        let board = FakeOtaBoard()
        try await OtaUpdater(link: board, sleeper: ForeverSleeper()).run(image, onProgress: { _ in })
        #expect(await board.maxOutstanding <= OtaProtocol.windowBytes)
    }

    @Test func pausesAtTheWindowUntilTheBoardAcknowledges() async throws {
        let board = FakeOtaBoard(acks: .manual)
        let image = image
        let task = Task { await outcome(of: board, image) }
        _ = await eventually { await board.controls.count == 1 }
        await board.ack(0)
        // 16 chunks of 244 fit in the 4096 window; the 17th would exceed it.
        #expect(await eventually { await board.received.count == 3_904 })
        for _ in 0..<200 { await Task.yield() }
        #expect(await board.received.count == 3_904)

        await board.ack(1_024)
        #expect(await eventually { await board.received.count > 3_904 })
        #expect(await board.received.count <= 1_024 + OtaProtocol.windowBytes)

        task.cancel()
        #expect(await task.value == .cancelled)
    }

    @Test func boardRefusingTheSignatureIsTyped() async {
        let board = FakeOtaBoard(rejectStart: .badSignature)
        await #expect(throws: OtaFailure.board(.badSignature)) {
            try await OtaUpdater(link: board, sleeper: ForeverSleeper()).run(image, onProgress: { _ in })
        }
        #expect(await board.received.isEmpty)
        #expect(await board.controls.last == OtaProtocol.abort)
    }

    @Test func boardRefusingADowngradeIsTyped() async {
        let board = FakeOtaBoard(rejectStart: .downgrade)
        await #expect(throws: OtaFailure.board(.downgrade)) {
            try await OtaUpdater(link: board, sleeper: ForeverSleeper()).run(image, onProgress: { _ in })
        }
    }

    @Test func boardErrorMidTransferAbortsTheUpdate() async {
        let board = FakeOtaBoard(errorAfterBytes: (2_000, .write))
        await #expect(throws: OtaFailure.board(.write)) {
            try await OtaUpdater(link: board, sleeper: ForeverSleeper()).run(image, onProgress: { _ in })
        }
        #expect(await board.controls.last == OtaProtocol.abort)
    }

    @Test func silentBoardStallsAndIsAborted() async {
        let board = FakeOtaBoard(acks: .none)
        await #expect(throws: OtaFailure.stalled) {
            try await OtaUpdater(link: board, sleeper: RecordingSleeper()).run(image, onProgress: { _ in })
        }
        #expect(await board.controls.last == OtaProtocol.abort)
    }

    @Test func cancellingAbortsTheTransferOnTheBoard() async {
        let board = FakeOtaBoard(acks: .manual)
        let image = image
        let task = Task { await outcome(of: board, image) }
        _ = await eventually { await board.controls.count == 1 }
        task.cancel()
        #expect(await task.value == .cancelled)
        #expect(await board.controls.last == OtaProtocol.abort)
        #expect(!(await board.controls.contains(OtaProtocol.finish)))
    }

    @Test func linkFailureWhileWritingIsReportedAndAborted() async {
        let board = FakeOtaBoard(failDataWith: .insufficientEncryption)
        await #expect(throws: OtaFailure.link(.insufficientEncryption)) {
            try await OtaUpdater(link: board, sleeper: ForeverSleeper()).run(image, onProgress: { _ in })
        }
        #expect(await board.controls.last == OtaProtocol.abort)
    }

    @Test func failureMessagesNameTheBoardsReason() {
        #expect(OtaFailure.board(.badSignature).message.contains("unsigned"))
        #expect(OtaFailure.board(.downgrade).message.contains("older"))
        #expect(OtaFailure.stalled.message == "The board stopped responding")
        #expect(OtaFailure.link(.pairingFailed).message == BLEError.pairingFailed.message)
    }

    @Test func fractionIsClampedAndZeroOutsideSending() {
        #expect(OtaProgress.sending(sent: 50, total: 200).fraction == 0.25)
        #expect(OtaProgress.sending(sent: 500, total: 200).fraction == 1)
        #expect(OtaProgress.sending(sent: 0, total: 0).fraction == 0)
        #expect(OtaProgress.verifying.fraction == 0)
    }
}

@Suite struct WriteWithoutResponsePacerTests {
    private final class Radio {
        var room = true
        var sent: [Data] = []
        var completions: [BLEError?] = []
    }

    private func make(_ radio: Radio) -> WriteWithoutResponsePacer {
        WriteWithoutResponsePacer(canSend: { radio.room }, send: { radio.sent.append($0) })
    }

    @Test func sendsImmediatelyWhenTheRadioHasRoom() {
        let radio = Radio()
        let pacer = make(radio)
        pacer.submit(Data([1])) { radio.completions.append($0.failure) }
        #expect(radio.sent == [Data([1])])
        #expect(radio.completions.count == 1 && radio.completions[0] == nil)
    }

    @Test func holdsWritesWhileFullAndReleasesThemInOrderOnReady() {
        let radio = Radio()
        let pacer = make(radio)
        radio.room = false
        pacer.submit(Data([1])) { radio.completions.append($0.failure) }
        pacer.submit(Data([2])) { radio.completions.append($0.failure) }
        #expect(radio.sent.isEmpty)
        #expect(radio.completions.isEmpty)
        #expect(pacer.queuedCount == 2)

        radio.room = true
        pacer.ready()
        #expect(radio.sent == [Data([1]), Data([2])])
        #expect(radio.completions.count == 2)
    }

    @Test func releasesOnlyAsMuchAsTheRadioTakes() {
        let radio = Radio()
        var budget = 0
        let pacer = WriteWithoutResponsePacer(canSend: { budget > 0 }, send: { radio.sent.append($0); budget -= 1 })
        (1...4).forEach { pacer.submit(Data([UInt8($0)])) { _ in } }
        #expect(radio.sent.isEmpty)
        budget = 2
        pacer.ready()
        #expect(radio.sent == [Data([1]), Data([2])])
        #expect(pacer.queuedCount == 2)
    }

    @Test func aNewWriteDoesNotOvertakeQueuedOnes() {
        let radio = Radio()
        let pacer = make(radio)
        radio.room = false
        pacer.submit(Data([1])) { _ in }
        radio.room = true
        pacer.submit(Data([2])) { _ in }
        #expect(radio.sent == [Data([1]), Data([2])])
    }

    @Test func failingTheLinkFailsEveryQueuedWrite() {
        let radio = Radio()
        let pacer = make(radio)
        radio.room = false
        pacer.submit(Data([1])) { radio.completions.append($0.failure) }
        pacer.submit(Data([2])) { radio.completions.append($0.failure) }
        pacer.fail(.linkLost(reason: "gone"))
        #expect(radio.completions == [.linkLost(reason: "gone"), .linkLost(reason: "gone")])
        radio.room = true
        pacer.ready()
        #expect(radio.sent.isEmpty)
    }
}

private extension Result {
    var failure: Failure? { if case .failure(let error) = self { error } else { nil } }
}
