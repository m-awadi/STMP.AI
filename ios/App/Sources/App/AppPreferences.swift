import Foundation

/// Every UserDefaults key lives here and nowhere else (D57). Tickets never write raw keys.
///
/// Persisted: `startOnLaunch` (default ON; Settings) and `hasStartedOnce` (set after the first explicit Home Start). The bridge
/// switch itself is in-memory: a cold launch sets it to `startOnLaunch && hasStartedOnce`.
nonisolated struct AppPreferences: Sendable {
  enum Key {
    static let startOnLaunch = "com.mido.stmp.startOnLaunch"
    static let hasStartedOnce = "com.mido.stmp.hasStartedOnce"
  }

  /// The defaults domain the app uses. `-STMPResetState` removes it.
  static let domainName = "com.mido.stmp"

  // UserDefaults is documented thread safe, but not annotated Sendable.
  private nonisolated(unsafe) let defaults: UserDefaults

  init(defaults: UserDefaults) { self.defaults = defaults }

  static func standard() -> AppPreferences { AppPreferences(defaults: UserDefaults(suiteName: domainName) ?? .standard) }

  /// A private, empty domain for the inert host and previews, so nothing touches the real one.
  static func ephemeral() -> AppPreferences {
    let name = "com.mido.stmp.inert.\(UUID().uuidString)"
    return AppPreferences(defaults: UserDefaults(suiteName: name) ?? .standard)
  }

  var startOnLaunch: Bool {
    get { defaults.object(forKey: Key.startOnLaunch) as? Bool ?? true }
    nonmutating set { defaults.set(newValue, forKey: Key.startOnLaunch) }
  }

  var hasStartedOnce: Bool {
    get { defaults.bool(forKey: Key.hasStartedOnce) }
    nonmutating set { defaults.set(newValue, forKey: Key.hasStartedOnce) }
  }

  /// The initial position of the in-memory bridge switch on a cold launch.
  var shouldStartOnColdLaunch: Bool { startOnLaunch && hasStartedOnce }
}
