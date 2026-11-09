import BLELabKit
import SwiftUI

/// Touch feed and message history. The compose bar lives in `BoardComposeBar` so the keyboard
/// pushes it up instead of covering it.
struct BoardTab: View {
    let model: DeviceModel

    var body: some View {
        VStack(spacing: 16) {
            DeviceCard(title: "Touch events", symbol: "hand.tap") {
                if !model.hasTouchScreen {
                    Text("This board has no touch screen.").font(.callout).foregroundStyle(.secondary)
                } else if model.touches.isEmpty {
                    Text("Tap or hold the board's screen and the events appear here.").font(.callout).foregroundStyle(.secondary)
                } else {
                    ForEach(model.touches.reversed(), id: \.index) { event in
                        HStack {
                            Image(systemName: event.kind == .tap ? "hand.tap.fill" : "hand.point.up.left.fill").foregroundStyle(.tint)
                            Text(event.kind == .tap ? "Tap" : "Long press").font(.callout.weight(.medium))
                            if event.injected { Badge(text: "TEST") }
                            Spacer()
                            Text("(\(event.x), \(event.y))").font(.callout.monospacedDigit()).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .accessibilityIdentifier("touchCard")
            DeviceCard(title: "Message to the board", symbol: "text.bubble") {
                switch model.messageStatus {
                case .idle:
                    Text("Type below. The text appears on the board's screen.").font(.callout).foregroundStyle(.secondary)
                case .shown(let text):
                    Label("Shown on the board: \(text)", systemImage: "checkmark.circle.fill").font(.callout).foregroundStyle(.green)
                case .failed(let reason):
                    Label("Not delivered: \(reason)", systemImage: "exclamationmark.triangle.fill").font(.callout).foregroundStyle(.red)
                }
            }
            .accessibilityIdentifier("messageCard")
        }
    }
}

struct BoardComposeBar: View {
    let model: DeviceModel
    @State private var text = ""
    @FocusState private var focused: Bool

    private var bytes: Int { text.utf8.count }
    private var over: Bool { bytes > MessageCodec.maxBytes }

    var body: some View {
        VStack(alignment: .trailing, spacing: 6) {
            HStack(spacing: 10) {
                TextField("Text for the screen", text: $text)
                    .focused($focused)
                    .submitLabel(.send)
                    .onSubmit(send)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(Color(.secondarySystemGroupedBackground), in: .capsule)
                    .accessibilityIdentifier("messageField")
                Button(action: send) {
                    Image(systemName: "arrow.up.circle.fill").font(.system(size: 34))
                }
                .disabled(!model.canSendMessage || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .accessibilityIdentifier("sendButton")
            }
            Text("\(bytes) / \(MessageCodec.maxBytes) B")
                .font(.caption.monospacedDigit())
                .foregroundStyle(over ? .red : .secondary)
                .accessibilityIdentifier("messageCounter")
        }
        .padding(.horizontal).padding(.vertical, 8)
        .background(.bar)
    }

    private func send() {
        let toSend = text
        guard model.canSendMessage else { return }
        Task {
            await model.sendMessage(toSend)
            if case .shown = model.messageStatus { text = "" }
        }
    }
}
