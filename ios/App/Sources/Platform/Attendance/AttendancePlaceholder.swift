import Foundation
import STMPCore
import os

/// The worked example of a platform adapter (D54, D90). T5 replaces it with the real monitor.
///
///  - It conforms to an STMPCore seam, so it is explicitly `nonisolated`; the app target is MainActor by default and
///    `dod.sh` greps for the keyword.
///  - It is a lock-guarded `Sendable` final class (an `actor` would also do). State sits behind `OSAllocatedUnfairLock`.
///  - `snapshot()` reads the lock and never hops.
///  - The state is seeded at registration. The placeholder fails closed: it reports `.locked`.
///  - A blocking entry point is `@concurrent`, so it leaves the caller's actor whatever the default isolation is.
nonisolated final class AttendancePlaceholder: Attendance {
  let current: Broadcast<AttendanceSnapshot>
  private let log: AppLog
  private let handler = OSAllocatedUnfairLock<(@MainActor @Sendable (AttendanceTransition) -> Void)?>(initialState: nil)

  init(log: AppLog) {
    self.log = log
    current = Broadcast(AttendanceSnapshot(state: .locked, epoch: 0), buffering: .unbounded)
  }

  func snapshot() -> AttendanceSnapshot { current.current }

  func expectSystemPrompt<T: Sendable>(_ kind: SystemPromptKind, _ body: @Sendable () async throws -> T) async rethrows -> T {
    try await body()
  }

  func setTransitionHandler(_ handler: @escaping @MainActor @Sendable (AttendanceTransition) -> Void) {
    self.handler.withLock { $0 = handler }
  }

  func crossCheck(_ phase: ScenePhaseValue) {
    log.info("scenePhase \(phase.rawValue) (placeholder attendance: \(snapshot().state.rawValue))")
  }

  /// A stand-in for a blocking call (Keychain, Secure Enclave, `getifaddrs`). `IsolationTests` call it from `@MainActor`.
  @concurrent func blockingProbe() async -> Bool {
    dispatchPrecondition(condition: .notOnQueue(.main))
    return true
  }
}
