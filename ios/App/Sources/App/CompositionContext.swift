import Foundation
import STMPCore

/// What every `Composition/<Area>Live.make(_:)` receives. Frozen shape (PLAN 1.9).
struct CompositionContext: Sendable {
  let storageRoot: StorageRoot
  let launchOptions: LaunchOptions
  let preferences: AppPreferences
  let log: AppLog
  let buildInfo: BuildInfo
  let clock: any StmpClock
}
