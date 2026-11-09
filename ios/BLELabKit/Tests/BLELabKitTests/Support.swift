import Foundation
@testable import BLELabKit

/// Returns at once and records every requested delay, so backoff runs in zero wall time.
actor RecordingSleeper: Sleeper {
    private(set) var delays: [Duration] = []
    nonisolated func sleep(for duration: Duration) async throws {
        await record(duration)
        await Task.yield()
        try Task.checkCancellation()
    }
    private func record(_ duration: Duration) { delays.append(duration) }
}

/// Polls `condition` on the main actor, yielding between checks, until it holds or the budget runs out.
@MainActor
func waitUntil(_ iterations: Int = 5_000, _ condition: () -> Bool) async -> Bool {
    for _ in 0..<iterations {
        if condition() { return true }
        await Task.yield()
    }
    return condition()
}

extension ConnectionState {
    var isReady: Bool { if case .ready = self { true } else { false } }
    var isFailed: Bool { if case .failed = self { true } else { false } }
    var isReconnecting: Bool { if case .reconnecting = self { true } else { false } }
}
