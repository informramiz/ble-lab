import BLELabKit
import SwiftUI
import UniformTypeIdentifiers

/// Firmware update card: choose a `.signed.bin`, see whether it is signed, send it, watch progress, cancel, and
/// read the board's verdict. All state lives in `DeviceModel`; this view only renders it and reads the picked file.
struct FirmwareCard: View {
    let model: DeviceModel
    @State private var importing = false

    /// Larger than any image the board's 3 MiB slot accepts, so a wrong file is rejected before it is read into memory.
    private static let maxFileBytes = 8 * 1024 * 1024

    var body: some View {
        DeviceCard(title: "Firmware update", symbol: "arrow.down.circle") {
            if model.firmwareSupported {
                content
            } else {
                Text("This firmware has no update service. Flash 1.3.0 or later over USB once.")
                    .font(.footnote).foregroundStyle(.secondary)
                    .accessibilityIdentifier("fwUnsupported")
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("firmwareCard")
        .fileImporter(isPresented: $importing, allowedContentTypes: [.data]) { result in
            model.pickFirmware(Self.read(result))
        }
    }

    @ViewBuilder private var content: some View {
        switch model.firmware {
        case .none:
            hint
            chooseButton("Choose .bin file")
        case .picked(let file):
            picked(file)
            HStack {
                Button("Update") { model.startFirmwareUpdate() }
                    .buttonStyle(.borderedProminent)
                    .disabled(!model.canStartFirmware)
                    .accessibilityIdentifier("fwStart")
                chooseButton("Choose another")
            }
        case .running(let progress):
            running(progress)
        case .finished(let version):
            Label("Updated. The board now runs \(version).", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
                .accessibilityIdentifier("fwFinished")
            Button("Dismiss") { model.dismissFirmware() }
        case .failed(let message):
            Label("Update failed: \(message)", systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.red)
                .accessibilityIdentifier("fwFailed")
            HStack {
                Button("Dismiss") { model.dismissFirmware() }
                chooseButton("Choose another")
            }
        }
    }

    private var hint: some View {
        Text("Pick the .signed.bin made by firmware/tools/sign_firmware.py. The board checks the signature, the version and the SHA-256 before it restarts, and may ask you to pair with the passkey on its screen.")
            .font(.footnote).foregroundStyle(.secondary)
    }

    private func chooseButton(_ title: String) -> some View {
        Button(title) { importing = true }
            .buttonStyle(.bordered)
            .accessibilityIdentifier("fwChoose")
    }

    private func picked(_ file: DeviceModel.PickedFirmware) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            let size = file.sizeBytes.formatted(.byteCount(style: .file))
            Text("\(size) · \(file.version ?? "version not named in the file")")
                .font(.subheadline)
                .accessibilityIdentifier("fwPicked")
            if file.isSigned {
                Label("Signed package. The board checks the signature.", systemImage: "checkmark.seal.fill")
                    .foregroundStyle(.green)
            } else {
                Label("Not signed. The board will refuse it; run sign_firmware.py first.", systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
            }
        }
        .font(.footnote)
        .accessibilityIdentifier("fwSigned")
    }

    @ViewBuilder private func running(_ progress: OtaProgress) -> some View {
        switch progress {
        case .starting:
            ProgressView().accessibilityIdentifier("fwProgress")
            status("Starting, waiting for the board")
        case .sending(let sent, let total):
            let percent = total > 0 ? sent * 100 / total : 0
            ProgressView(value: Double(sent), total: Double(max(total, 1)))
                .accessibilityIdentifier("fwProgress")
            status("Sending \(percent)% (\(sent) of \(total) B)")
        case .verifying:
            ProgressView().accessibilityIdentifier("fwProgress")
            status("Verifying the image")
        case .restarting:
            ProgressView().accessibilityIdentifier("fwProgress")
            status("Board is restarting, reconnecting")
        }
        if progress != .restarting {
            Button("Cancel", role: .cancel) { model.cancelFirmwareUpdate() }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("fwCancel")
        }
    }

    private func status(_ text: String) -> some View {
        Text(text).font(.footnote).foregroundStyle(.secondary).accessibilityIdentifier("fwStatus")
    }

    /// Reads the chosen file under security scope, or nil when it is unreadable or over the cap.
    private static func read(_ result: Result<URL, Error>) -> Data? {
        guard case .success(let url) = result else { return nil }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size <= maxFileBytes else { return nil }
        return try? Data(contentsOf: url)
    }
}
