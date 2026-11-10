import CryptoKit
import Foundation
import Testing
@testable import BLELabKit

/// Waits at once for short backoff delays but never for the updater's stall timeouts (10 s and up),
/// so a healthy transfer finishes in zero wall time and a stalled one would hang instead of passing.
struct ShortDelaySleeper: Sleeper {
    func sleep(for duration: Duration) async throws {
        if duration >= .seconds(10) { try await Task.sleep(for: .seconds(3600)) }
        await Task.yield()
        try Task.checkCancellation()
    }
}

/// The whole OTA path: `ConnectionManager` -> `OtaUpdater` -> `SimulatedLink` -> `SimulatedPeripheral`.
@MainActor
@Suite struct OtaSimulatedTests {
    let signer = OtaTestSigner()

    func makePeripheral(supportsOta: Bool = true, slot: Int = 3 << 20, requireBonding: Bool = false) async -> SimulatedPeripheral {
        let peripheral = SimulatedPeripheral(
            supportsOta: supportsOta, otaVerifier: signer.verifier, otaSlotBytes: slot, rebootDelay: .milliseconds(5)
        )
        await peripheral.configure(requireBonding: requireBonding)
        return peripheral
    }

    func connect(_ peripheral: SimulatedPeripheral) async -> ConnectionManager {
        let manager = ConnectionManager(
            central: SimulatedCentral(peripheral: peripheral, latency: .zero, sleeper: RecordingSleeper()),
            backoff: BackoffPolicy(initial: .milliseconds(100), maximum: .seconds(1)),
            sleeper: ShortDelaySleeper(),
            random: { 0.5 }
        )
        manager.connect(to: SimulatedPeripheral.demoID)
        #expect(await waitUntil { manager.state.isReady })
        return manager
    }

    func signedImage(size: Int = 20_000, revision: String = "ble-lab 1.3.0") -> FirmwareImage {
        let bytes = testImageBytes(size: size, revision: revision)
        let code = OtaSignature.versionCode(revision: revision) ?? 0
        return FirmwareImage.parse(signer.pack(bytes, versionCode: code))!
    }

    func failure(_ manager: ConnectionManager, _ image: FirmwareImage) async -> OtaFailure? {
        do throws(OtaFailure) {
            try await manager.updateFirmware(image) { _ in }
            return nil
        } catch {
            return error
        }
    }

    @Test func discoversTheOtaServiceOnlyWhenTheBoardHasIt() async {
        let with = await connect(await makePeripheral())
        #expect(with.otaSupported)
        let without = await connect(await makePeripheral(supportsOta: false))
        #expect(!without.otaSupported)
    }

    @Test func updatesTheBoardAndItComesBackOnTheNewVersion() async throws {
        let peripheral = await makePeripheral()
        let manager = await connect(peripheral)
        let image = signedImage()
        #expect(await failure(manager, image) == nil)
        #expect(await peripheral.otaCompleted == 1)
        // The board restarts, the session drops and reconnects by itself.
        #expect(await waitUntil { if case .ready(let info, _) = manager.state { info.firmware == "ble-lab 1.3.0" } else { false } })
    }

    @Test func staysInsideTheFlowControlWindow() async {
        let peripheral = await makePeripheral()
        let manager = await connect(peripheral)
        #expect(await failure(manager, signedImage(size: 60_000)) == nil)
        let ahead = await peripheral.otaMaxBytesAhead
        #expect(ahead > 0)
        #expect(ahead <= OtaProtocol.windowBytes + 244, "never more than a window past the last reported offset")
    }

    @Test func reportsProgressUpToTheEnd() async {
        let manager = await connect(await makePeripheral())
        let log = ProgressLog()
        do throws(OtaFailure) { try await manager.updateFirmware(signedImage(size: 5_000)) { log.add($0) } } catch { Issue.record("\(error)") }
        let seen = log.all
        #expect(seen.first == .starting)
        #expect(seen.contains(.sending(sent: 5_000, total: 5_000)))
        #expect(seen.last == .restarting)
    }

    @Test func rejectsAnImageSignedByAnotherKeyWithBadSignature() async {
        let peripheral = await makePeripheral()
        let manager = await connect(peripheral)
        let foreign = OtaTestSigner()
        let bytes = testImageBytes(size: 2_000)
        let image = FirmwareImage.parse(foreign.pack(bytes, versionCode: 10_300))!
        #expect(await failure(manager, image) == .board(.badSignature))
        #expect(await peripheral.otaCompleted == 0)
    }

    @Test func rejectsAnUnsignedImageWithBadSignature() async {
        let manager = await connect(await makePeripheral())
        let unsigned = FirmwareImage.parse(testImageBytes(size: 2_000))!
        #expect(!unsigned.isSigned)
        #expect(await failure(manager, unsigned) == .board(.badSignature))
    }

    @Test func rejectsADowngrade() async {
        let peripheral = await makePeripheral()
        let manager = await connect(peripheral)
        // The board runs 1.1.0 (10100); 1.0.0 is correctly signed but older.
        #expect(await failure(manager, signedImage(revision: "ble-lab 1.0.0")) == .board(.downgrade))
        #expect(await peripheral.otaCompleted == 0)
    }

    @Test func rejectsAnImageThatDoesNotFitTheSlot() async {
        let manager = await connect(await makePeripheral(slot: 10_000))
        #expect(await failure(manager, signedImage(size: 20_000)) == .board(.tooLarge))
    }

    @Test func refusesWithoutAnOtaService() async {
        let manager = await connect(await makePeripheral(supportsOta: false))
        #expect(await failure(manager, signedImage()) == .link(.serviceNotFound))
    }

    @Test func refusesWhenNotConnected() async {
        let manager = ConnectionManager(central: SimulatedCentral(peripheral: SimulatedPeripheral(), latency: .zero))
        #expect(await failure(manager, signedImage()) == .link(.notConnected))
    }

    @Test func declinedPairingFailsTheUpdateWithoutTouchingTheBoard() async {
        let peripheral = await makePeripheral(requireBonding: true)
        await peripheral.configure(requireBonding: true, acceptBonding: false)
        let manager = await connect(peripheral)
        #expect(await failure(manager, signedImage()) == .link(.pairingFailed))
        #expect(await peripheral.otaCompleted == 0)
    }

    @Test func cancelAbortsAndTheBoardAcceptsANewTransfer() async {
        let peripheral = await makePeripheral()
        let manager = await connect(peripheral)
        let image = signedImage(size: 200_000)
        let task = Task { await failure(manager, image) }
        await Task.yield()
        task.cancel()
        #expect(await task.value == .cancelled)
        #expect(await failure(manager, signedImage(size: 3_000)) == nil)
        #expect(await peripheral.otaCompleted == 1)
    }
}
