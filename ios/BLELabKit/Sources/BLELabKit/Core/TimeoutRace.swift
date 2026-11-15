import Foundation

/// First result wins; later ones are ignored. Lets a caller stop waiting for an operation that cannot be
/// cancelled (a Core Bluetooth write), without the operation's late result ever reaching it.
final class TimeoutRace<Failure: Error & Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Result<Void, Failure>, Never>?
    private var cleanup: (@Sendable () -> Void)?
    private var done = false

    func install(_ continuation: CheckedContinuation<Result<Void, Failure>, Never>) { lock.withLock { self.continuation = continuation } }

    func onFinish(_ cleanup: @escaping @Sendable () -> Void) {
        let runNow = lock.withLock { () -> Bool in
            if done { return true }
            self.cleanup = cleanup
            return false
        }
        if runNow { cleanup() }
    }

    func finish(_ result: Result<Void, Failure>) {
        let (continuation, cleanup) = lock.withLock { () -> (CheckedContinuation<Result<Void, Failure>, Never>?, (@Sendable () -> Void)?) in
            guard !done else { return (nil, nil) }
            done = true
            return (self.continuation, self.cleanup)
        }
        continuation?.resume(returning: result)
        cleanup?()
    }
}
