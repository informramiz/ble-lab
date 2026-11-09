import Foundation

/// Exponential backoff with jitter: initial x factor^(attempt-1), capped, then +/- jitterRatio.
public struct BackoffPolicy: Sendable {
    public let initial: Duration
    public let maximum: Duration
    public let factor: Double
    public let jitterRatio: Double
    public let maxAttempts: Int

    public init(
        initial: Duration = .milliseconds(500),
        maximum: Duration = .seconds(16),
        factor: Double = 2,
        jitterRatio: Double = 0.2,
        maxAttempts: Int = 6
    ) {
        precondition(initial > .zero && maximum >= initial && factor >= 1 && (0...1).contains(jitterRatio) && maxAttempts >= 1)
        self.initial = initial
        self.maximum = maximum
        self.factor = factor
        self.jitterRatio = jitterRatio
        self.maxAttempts = maxAttempts
    }

    /// `unit` is a random number in 0...1 (injected for determinism).
    public func delay(forAttempt attempt: Int, unit: Double = Double.random(in: 0...1)) -> Duration {
        precondition(attempt >= 1)
        let base = min(initial * pow(factor, Double(attempt - 1)), maximum)
        let jitter = 1 + jitterRatio * (unit * 2 - 1)
        return base * jitter
    }
}
