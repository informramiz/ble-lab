import Testing
@testable import BLELabKit

@Suite struct BackoffPolicyTests {
    let policy = BackoffPolicy(initial: .milliseconds(500), maximum: .seconds(4), factor: 2, jitterRatio: 0.2, maxAttempts: 6)

    @Test func doublesEachAttemptWithoutJitterAtMidpoint() {
        #expect(policy.delay(forAttempt: 1, unit: 0.5) == .milliseconds(500))
        #expect(policy.delay(forAttempt: 2, unit: 0.5) == .seconds(1))
        #expect(policy.delay(forAttempt: 3, unit: 0.5) == .seconds(2))
    }

    @Test func capsAtMaximum() {
        #expect(policy.delay(forAttempt: 10, unit: 0.5) == .seconds(4))
    }

    @Test func jitterStaysWithinRatio() {
        #expect(policy.delay(forAttempt: 2, unit: 0) == .milliseconds(800))
        #expect(policy.delay(forAttempt: 2, unit: 1) == .milliseconds(1_200))
    }
}
