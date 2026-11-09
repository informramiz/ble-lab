import Foundation
import Testing
@testable import BLELabKit

/// Poll waits are real but tiny, so the reboot wait sees the simulated board restart; stall timeouts never fire.
struct PollSleeper: Sleeper {
    func sleep(for duration: Duration) async throws {
        try await Task.sleep(for: duration >= .seconds(10) ? .seconds(3600) : .milliseconds(2))
    }
}

@MainActor
@Suite struct DeviceModelFirmwareTests {
    let signer = OtaTestSigner()

    func makeModel(
        supportsOta: Bool = true, resultRevision: String = "ble-lab 1.3.0", rebootDelay: Duration = .milliseconds(20)
    ) async -> (DeviceModel, SimulatedPeripheral) {
        let peripheral = SimulatedPeripheral(
            supportsOta: supportsOta, otaVerifier: signer.verifier, otaResultRevision: resultRevision, rebootDelay: rebootDelay
        )
        let manager = ConnectionManager(
            central: SimulatedCentral(peripheral: peripheral, latency: .zero, sleeper: RecordingSleeper()),
            backoff: BackoffPolicy(initial: .milliseconds(5), maximum: .milliseconds(20)),
            sleeper: PollSleeper(),
            random: { 0.5 }
        )
        let device = ScannedDevice(id: peripheral.id, name: peripheral.name, rssi: -50, isBleLab: true, isSimulated: true)
        let model = DeviceModel(device: device, manager: manager, sleeper: PollSleeper())
        model.start()
        #expect(await waitUntil { model.state.isReady })
        return (model, peripheral)
    }

    func signedFile(revision: String = "ble-lab 1.3.0", size: Int = 8_000) -> Data {
        signer.pack(testImageBytes(size: size, revision: revision), versionCode: OtaSignature.versionCode(revision: revision) ?? 0)
    }

    func settled(_ model: DeviceModel) async -> Bool {
        await waitUntil(200_000) { !model.firmwareBusy }
    }

    @Test func pickingASignedFileShowsItsDetails() async {
        let (model, _) = await makeModel()
        model.pickFirmware(signedFile())
        #expect(model.firmware == .picked(.init(sizeBytes: 8_000, version: "ble-lab 1.3.0", isSigned: true)))
        #expect(model.canStartFirmware)
    }

    @Test func pickingAnUnsignedFileIsFlagged() async {
        let (model, _) = await makeModel()
        model.pickFirmware(testImageBytes(size: 4_000))
        #expect(model.firmware == .picked(.init(sizeBytes: 4_000, version: "ble-lab 1.3.0", isSigned: false)))
    }

    @Test func rejectsUnreadableAndNonFirmwareFiles() async {
        let (model, _) = await makeModel()
        model.pickFirmware(nil)
        #expect(model.firmware == .failed("The file could not be read"))
        model.pickFirmware(Data("hello".utf8))
        #expect(model.firmware == .failed("This is not an ESP32 firmware image (a .bin built for the board)"))
        #expect(!model.canStartFirmware)
        model.dismissFirmware()
        #expect(model.firmware == .none)
    }

    @Test func updatesRestartsAndConfirmsTheNewVersion() async {
        let (model, peripheral) = await makeModel()
        model.pickFirmware(signedFile())
        model.startFirmwareUpdate()
        #expect(model.firmwareBusy)
        #expect(await settled(model))
        #expect(model.firmware == .finished(version: "ble-lab 1.3.0"))
        #expect(await peripheral.otaCompleted == 1)
        #expect(model.log.last == "Firmware updated, board runs ble-lab 1.3.0")
    }

    @Test func reportsARollbackWhenTheBoardComesBackOnAnotherVersion() async {
        let (model, _) = await makeModel(resultRevision: "ble-lab 1.1.0")
        model.pickFirmware(signedFile())
        model.startFirmwareUpdate()
        #expect(await settled(model))
        #expect(model.firmware == .failed("The board came back on ble-lab 1.1.0, not ble-lab 1.3.0; it rolled back"))
    }

    @Test func showsTheBoardsReasonForRejectingAnUnsignedFile() async {
        let (model, peripheral) = await makeModel()
        model.pickFirmware(testImageBytes(size: 4_000))
        model.startFirmwareUpdate()
        #expect(await settled(model))
        #expect(model.firmware == .failed(OtaError.badSignature.message))
        #expect(await peripheral.otaCompleted == 0)
    }

    @Test func showsTheDowngradeReason() async {
        let (model, _) = await makeModel()
        model.pickFirmware(signedFile(revision: "ble-lab 1.0.0"))
        model.startFirmwareUpdate()
        #expect(await settled(model))
        #expect(model.firmware == .failed(OtaError.downgrade.message))
    }

    @Test func cancelReturnsToThePickedFile() async {
        let (model, _) = await makeModel()
        model.pickFirmware(signedFile(size: 400_000))
        model.startFirmwareUpdate()
        await Task.yield()
        model.cancelFirmwareUpdate()
        #expect(await settled(model))
        #expect(model.firmware == .picked(.init(sizeBytes: 400_000, version: "ble-lab 1.3.0", isSigned: true)))
        #expect(model.canStartFirmware)
    }

    @Test func cannotStartOnABoardWithoutTheService() async {
        let (model, _) = await makeModel(supportsOta: false)
        #expect(!model.firmwareSupported)
        model.pickFirmware(signedFile())
        #expect(!model.canStartFirmware)
        model.startFirmwareUpdate()
        #expect(!model.firmwareBusy)
    }

    @Test func pickingIsIgnoredWhileRunning() async {
        let (model, _) = await makeModel()
        model.pickFirmware(signedFile(size: 400_000))
        model.startFirmwareUpdate()
        model.pickFirmware(nil)
        #expect(model.firmwareBusy)
        model.cancelFirmwareUpdate()
        #expect(await settled(model))
    }
}
