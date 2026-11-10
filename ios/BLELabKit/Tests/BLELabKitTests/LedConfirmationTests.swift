import Foundation
import Testing
@testable import BLELabKit

/// A board stand-in whose LED write the test controls: it can be held, failed or never answered.
final class StubBoard: BLECentral, @unchecked Sendable {
    enum Write: Sendable { case succeed, hold, hang, fail(BLEError) }

    private let lock = NSLock()
    private var behavior: Write = .succeed
    private var held: CheckedContinuation<Void, Never>?
    private var writes = 0
    var writeBehavior: Write {
        get { lock.withLock { behavior } }
        set { lock.withLock { behavior = newValue } }
    }
    var ledWrites: Int { lock.withLock { writes } }

    func scan(serviceFilter: UUID?) -> AsyncThrowingStream<ScannedDevice, any Error> { AsyncThrowingStream { $0.finish() } }
    func connect(to id: UUID) async throws(BLEError) -> any GattLink { StubLink(board: self) }

    func release() { lock.withLock { let h = held; held = nil; return h }?.resume() }

    func performWrite() async throws(BLEError) {
        let mode = lock.withLock { () -> Write in writes += 1; return behavior }
        switch mode {
        case .succeed: return
        case .fail(let error): throw error
        case .hang: try? await Task.sleep(for: .seconds(3600))
        case .hold: await withCheckedContinuation { c in lock.withLock { held = c } }
        }
    }
}

struct StubLink: GattLink {
    let board: StubBoard
    var events: AsyncStream<LinkEvent> { AsyncStream { _ in } }
    var maximumWriteLength: Int { get async { 244 } }
    var maximumWriteWithoutResponseLength: Int { get async { 244 } }
    func discoverServices() async throws(BLEError) -> Set<AttributeID> { [.custom(GattProfile.labService)] }
    func read(_ characteristic: AttributeID, in service: AttributeID) async throws(BLEError) -> Data { throw .serviceNotFound }
    func write(_ value: Data, to characteristic: AttributeID, in service: AttributeID) async throws(BLEError) { try await board.performWrite() }
    func writeWithoutResponse(_ value: Data, to characteristic: AttributeID, in service: AttributeID) async throws(BLEError) {}
    func setNotify(_ enabled: Bool, for characteristic: AttributeID, in service: AttributeID) async throws(BLEError) {}
    func disconnect() async {}
}

/// Fires at once, so the write timeout elapses in zero wall time.
struct ImmediateSleeper: Sleeper {
    func sleep(for duration: Duration) async throws {}
}

@MainActor
@Suite struct LedConfirmationTests {
    let board = StubBoard()

    func makeModel(timeoutSleeper: any Sleeper = ForeverSleeper()) async -> DeviceModel {
        let manager = ConnectionManager(central: board, sleeper: RecordingSleeper(), timeoutSleeper: timeoutSleeper)
        let model = DeviceModel(device: ScannedDevice(id: UUID(), name: "ble-lab-esp32", rssi: -50, isBleLab: true), manager: manager)
        model.start()
        #expect(await waitUntil { model.state.isReady })
        return model
    }

    @Test func switchStaysOffUntilTheBoardAcknowledges() async {
        board.writeBehavior = .hold
        let model = await makeModel()
        let write = Task { await model.setLED(true) }
        #expect(await waitUntil { model.ledPending && board.ledWrites == 1 })
        #expect(!model.ledOn)  // Not confirmed yet.
        #expect(model.requestedLED == true)
        board.release()
        await write.value
        #expect(model.ledOn)
        #expect(!model.ledPending)
        #expect(model.requestedLED == nil)
    }

    @Test(.timeLimit(.minutes(1))) func unansweredWriteTimesOutAndLeavesTheSwitchOff() async {
        board.writeBehavior = .hang
        let model = await makeModel(timeoutSleeper: ImmediateSleeper())
        await model.setLED(true)
        #expect(!model.ledOn)
        #expect(!model.ledPending)
        #expect(model.writeError == .timeout)
        #expect(model.log.last?.hasPrefix("LED write failed") == true)
    }

    @Test func failedWriteLeavesTheSwitchOffAndExplainsWhatToDo() async {
        board.writeBehavior = .fail(.insufficientEncryption)
        let model = await makeModel()
        await model.setLED(true)
        #expect(!model.ledOn)
        #expect(model.writeError == .pairingFailed)
        #expect(model.writeError?.userMessage.contains("6-digit code shown on the board") == true)
    }

    @Test func pairingFailureMessageToTheBoardAlsoCarriesGuidance() async {
        board.writeBehavior = .fail(.insufficientEncryption)
        let model = await makeModel()
        await model.sendMessage("hi")
        guard case .failed(let text) = model.messageStatus else { Issue.record("expected failure"); return }
        #expect(text.contains("6-digit code shown on the board"))
    }
}
