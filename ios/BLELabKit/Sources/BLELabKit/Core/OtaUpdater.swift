import Foundation

/// The OTA characteristics as the updater needs them.
public protocol OtaLink: Sendable {
    /// Status notifications from the board. Must be hot: the subscription is live when this is read,
    /// and a notification is lost if nobody consumes it.
    var status: AsyncStream<OtaStatus> { get }
    /// Largest payload for one write without response.
    var dataChunkSize: Int { get async }
    /// Write with response to the control characteristic.
    func writeControl(_ value: Data) async throws(BLEError)
    /// Write without response to the data characteristic. Returns once the bytes are handed to the radio.
    func writeData(_ value: Data) async throws(BLEError)
    /// Called when a write was given up on (timeout or cancellation) while the link had not answered it.
    /// The link must drop the connection so the stuck write fails and a late acknowledgement cannot be
    /// mistaken for a later write's.
    func abandonPendingWrites() async
}

extension OtaLink {
    public func abandonPendingWrites() async {}
}

public enum OtaProgress: Equatable, Sendable {
    case starting
    /// `sent` bytes are confirmed by the board, of `total`.
    case sending(sent: Int, total: Int)
    /// Every byte is in; the board is checking the SHA-256 and activating the image.
    case verifying
    /// The board accepted the image and is restarting into it.
    case restarting

    public var fraction: Double {
        guard case .sending(let sent, let total) = self, total > 0 else { return 0 }
        return min(max(Double(sent) / Double(total), 0), 1)
    }
}

/// Why an update did not finish.
public enum OtaFailure: Error, Equatable, Sendable {
    /// The board refused or abandoned the transfer.
    case board(OtaError)
    /// The board stopped answering.
    case stalled
    case link(BLEError)
    case cancelled

    public var message: String {
        switch self {
        case .board(let error): error == .none ? "The board reported an error" : error.message
        case .stalled: "The board stopped responding"
        case .link(let error): error.message
        case .cancelled: "The update was cancelled"
        }
    }
}

/// Pushes a `FirmwareImage` to the board over the OTA service with flow control: it never runs more than
/// `OtaProtocol.windowBytes` ahead of the offset the board last reported, so the board's receive path is
/// not overrun. Cancelling the calling task aborts the transfer on the board.
public struct OtaUpdater: Sendable {
    private let link: any OtaLink
    private let sleeper: any Sleeper
    private let stallTimeout: Duration

    /// The board erases flash while starting, which takes longer than a normal acknowledgement.
    private static let startTimeoutFactor = 2

    public init(link: any OtaLink, sleeper: any Sleeper = TaskSleeper(), stallTimeout: Duration = .seconds(10)) {
        self.link = link
        self.sleeper = sleeper
        self.stallTimeout = stallTimeout
    }

    /// Runs the whole update. `onProgress` is called in order; the call returns after `.restarting`.
    public func run(_ image: FirmwareImage, onProgress: @Sendable (OtaProgress) -> Void) async throws(OtaFailure) {
        onProgress(.starting)
        let inbox = StatusInbox()
        let statuses = link.status  // Subscribe before START so the first notification cannot be missed.
        let pump = Task { for await status in statuses { await inbox.record(status) } }
        do {
            try await transfer(image, inbox: inbox, onProgress: onProgress)
            pump.cancel()
        } catch {
            pump.cancel()
            await abort()
            throw error
        }
        onProgress(.restarting)
    }

    private func transfer(_ image: FirmwareImage, inbox: StatusInbox, onProgress: @Sendable (OtaProgress) -> Void) async throws(OtaFailure) {
        let total = image.size
        guard let start = OtaProtocol.encodeStart(size: total, sha256: image.sha256, versionCode: image.versionCode, signature: image.signatureOrBlank) else {
            throw .board(.badCommand)
        }
        try await control(start)
        _ = try await wait(inbox, timeout: stallTimeout * Self.startTimeoutFactor) { $0.state == .receiving }

        var sent = 0
        var acked = 0
        onProgress(.sending(sent: 0, total: total))
        for chunk in image.chunks(chunkSize: max(await link.dataChunkSize, 1)) {
            while sent + chunk.count - acked > OtaProtocol.windowBytes {
                let confirmed = acked
                let status = try await wait(inbox, timeout: stallTimeout) { $0.offset > confirmed }
                acked = status.offset
                onProgress(.sending(sent: acked, total: total))
            }
            try await data(chunk)
            sent += chunk.count
            if let latest = await inbox.latest { try Self.failIfError(latest) }
        }
        if acked < total {
            acked = try await wait(inbox, timeout: stallTimeout) { $0.offset >= total }.offset
        }
        onProgress(.sending(sent: acked, total: total))
        onProgress(.verifying)
        try await control(OtaProtocol.finish)
        _ = try await wait(inbox, timeout: stallTimeout) { $0.state == .done }
    }

    /// Waits for a status satisfying `wanted`; a board error, a timeout or cancellation ends the update.
    private func wait(_ inbox: StatusInbox, timeout: Duration, _ wanted: @escaping @Sendable (OtaStatus) -> Bool) async throws(OtaFailure) -> OtaStatus {
        let status = await withTaskGroup(of: OtaStatus?.self) { group in
            group.addTask { await inbox.next { $0.state == .error || wanted($0) } }
            group.addTask {
                try? await sleeper.sleep(for: timeout)
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
        if Task.isCancelled { throw .cancelled }
        guard let status else { throw .stalled }
        try Self.failIfError(status)
        return status
    }

    private static func failIfError(_ status: OtaStatus) throws(OtaFailure) {
        if status.state == .error { throw .board(status.error) }
    }

    private func control(_ value: Data) async throws(OtaFailure) {
        let link = link
        try await bounded { () async throws(BLEError) in try await link.writeControl(value) }
    }

    private func data(_ value: Data) async throws(OtaFailure) {
        let link = link
        try await bounded { () async throws(BLEError) in try await link.writeData(value) }
    }

    /// Runs one write, ending the wait on a stall (`stallTimeout`) or when the calling task is cancelled.
    /// A Core Bluetooth write cannot be cancelled, so a write given up on makes the link drop the connection.
    private func bounded(_ write: @escaping @Sendable () async throws(BLEError) -> Void) async throws(OtaFailure) {
        if Task.isCancelled { throw .cancelled }
        let race = TimeoutRace<OtaFailure>()
        let sleeper = sleeper
        let limit = stallTimeout
        let result: Result<Void, OtaFailure> = await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                race.install(continuation)
                if Task.isCancelled { return race.finish(.failure(.cancelled)) }
                Task {
                    do throws(BLEError) {
                        try await write()
                        race.finish(.success(()))
                    } catch {
                        race.finish(.failure(.link(error)))
                    }
                }
                let timer = Task {
                    do { try await sleeper.sleep(for: limit) } catch { return }
                    race.finish(.failure(.stalled))
                }
                race.onFinish { timer.cancel() }
            }
        } onCancel: {
            race.finish(.failure(.cancelled))
        }
        if case .failure(let failure) = result {
            if failure == .stalled || failure == .cancelled { await link.abandonPendingWrites() }
            throw failure
        }
        if Task.isCancelled { throw .cancelled }
    }

    /// Best effort, and runs even when the calling task is cancelled: tells the board to drop the transfer.
    /// Bounded, so a link that no longer answers cannot hang the caller.
    private func abort() async {
        let link = link
        let sleeper = sleeper
        let limit = stallTimeout
        let race = TimeoutRace<OtaFailure>()
        let result: Result<Void, OtaFailure> = await withCheckedContinuation { continuation in
            race.install(continuation)
            Task {
                try? await link.writeControl(OtaProtocol.abort)
                race.finish(.success(()))
            }
            let timer = Task {
                do { try await sleeper.sleep(for: limit) } catch { return }
                race.finish(.failure(.stalled))
            }
            race.onFinish { timer.cancel() }
        }
        if case .failure = result { await link.abandonPendingWrites() }
    }
}

/// Latest status plus whoever is waiting for the next one that matters.
private actor StatusInbox {
    private(set) var latest: OtaStatus?
    private var waiters: [Int: (matches: @Sendable (OtaStatus) -> Bool, continuation: CheckedContinuation<OtaStatus?, Never>)] = [:]
    private var nextID = 0

    func record(_ status: OtaStatus) {
        latest = status
        for (id, waiter) in waiters where waiter.matches(status) {
            waiters[id] = nil
            waiter.continuation.resume(returning: status)
        }
    }

    /// The current status if it matches, else the next one that does; nil when the waiting task is cancelled.
    func next(where matches: @escaping @Sendable (OtaStatus) -> Bool) async -> OtaStatus? {
        if let latest, matches(latest) { return latest }
        let id = nextID
        nextID += 1
        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                if Task.isCancelled { continuation.resume(returning: nil) } else { waiters[id] = (matches, continuation) }
            }
        } onCancel: {
            Task { await self.cancelWaiter(id) }
        }
    }

    private func cancelWaiter(_ id: Int) {
        waiters.removeValue(forKey: id)?.continuation.resume(returning: nil)
    }
}
