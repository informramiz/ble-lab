import Foundation

/// Holds writes without response until Core Bluetooth can take them.
///
/// `CBPeripheral.writeValue(_:for:type: .withoutResponse)` silently drops data when the transmit queue
/// is full. Apple's rule: check `canSendWriteWithoutResponse` before every write, and when it is false
/// wait for `peripheralIsReady(toSendWriteWithoutResponse:)`. This type holds that rule apart from the
/// Core Bluetooth types so it can be unit tested: call `submit` for each write, `ready()` from the
/// delegate callback and `fail(_:)` when the link drops. Not thread safe; confine it to one queue.
final class WriteWithoutResponsePacer {
    typealias Completion = (Result<Void, BLEError>) -> Void

    private let canSend: () -> Bool
    private let send: (Data) -> Void
    private var pending: [(data: Data, completion: Completion)] = []

    init(canSend: @escaping () -> Bool, send: @escaping (Data) -> Void) {
        self.canSend = canSend
        self.send = send
    }

    var queuedCount: Int { pending.count }

    /// Sends `data` now if the radio has room and nothing is ahead of it, otherwise queues it. `completion`
    /// runs once the data has been handed to Core Bluetooth, or with an error if the link fails first.
    func submit(_ data: Data, completion: @escaping Completion) {
        pending.append((data, completion))
        drain()
    }

    /// The radio has room again.
    func ready() { drain() }

    /// The link is gone: nothing queued will ever be sent.
    func fail(_ error: BLEError) {
        let queued = pending
        pending = []
        queued.forEach { $0.completion(.failure(error)) }
    }

    private func drain() {
        while let next = pending.first, canSend() {
            pending.removeFirst()
            send(next.data)
            next.completion(.success(()))
        }
    }
}
