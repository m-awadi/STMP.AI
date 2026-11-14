import Foundation
import Security

/// Test-only launch options (D56, D82). Parsing exists in DEBUG builds only: a Release binary contains none of the option
/// names (`dod.sh --merge` runs `strings` over it), and `parse` returns the all-default value there.
///
///   -STMPTestBind loopback      bind 127.0.0.1 instead of the tailnet
///   STMP_TEST_PORT=<n>          the port (0 picks one), read through `testPort`
///   -STMPStorageRoot <dir>      the storage root
///   -STMPResetState             wipe that root, the app's Keychain items and its defaults domain before starting
///   -STMPSkipOnboarding         skip onboarding
///   -STMPSeedPolicy <abs path>  seed the policy from a fixture file; a missing or invalid file is a visible launch error
nonisolated struct LaunchOptions: Equatable, Sendable {
  var testBindLoopback = false
  var testPort: Int?
  var storageRoot: URL?
  var resetState = false
  var skipOnboarding = false
  var seedPolicyPath: String?
  /// Set when `-STMPSeedPolicy` is missing its value, names a missing file or names invalid JSON. Never falls back to defaults.
  var seedPolicyError: String?
  /// True in a unit-test host process (`XCTestConfigurationFilePath` in the environment) without the UI-test options (D69).
  var isXCTestHost = false

  /// Release builds ignore every option.
  static let none = LaunchOptions()

  static var current: LaunchOptions {
    #if DEBUG
    parse(arguments: CommandLine.arguments, environment: ProcessInfo.processInfo.environment)
    #else
    .none
    #endif
  }

  #if DEBUG
  static func parse(arguments: [String], environment: [String: String]) -> LaunchOptions {
    var o = LaunchOptions()
    func value(after flag: String) -> String? {
      guard let i = arguments.firstIndex(of: flag) else { return nil }
      let next = i + 1
      guard next < arguments.count, !arguments[next].hasPrefix("-") else { return nil }
      return arguments[next]
    }
    o.testBindLoopback = value(after: "-STMPTestBind") == "loopback"
    o.testPort = environment["STMP_TEST_PORT"].flatMap { Int($0) }.flatMap { (0...65535).contains($0) ? $0 : nil }
    o.storageRoot = value(after: "-STMPStorageRoot").map { URL(fileURLWithPath: $0, isDirectory: true) }
    o.resetState = arguments.contains("-STMPResetState")
    o.skipOnboarding = arguments.contains("-STMPSkipOnboarding")
    if arguments.contains("-STMPSeedPolicy") {
      if let path = value(after: "-STMPSeedPolicy") {
        o.seedPolicyPath = path
        o.seedPolicyError = validateSeed(path)
      } else {
        o.seedPolicyError = "-STMPSeedPolicy needs an absolute path to a policy fixture."
      }
    }
    let usesUITestOptions = o.testBindLoopback || o.resetState || o.storageRoot != nil
    o.isXCTestHost = environment["XCTestConfigurationFilePath"] != nil && !usesUITestOptions
    return o
  }

  private static func validateSeed(_ path: String) -> String? {
    guard path.hasPrefix("/") else { return "The seed policy path must be absolute: \(path)" }
    guard let data = FileManager.default.contents(atPath: path) else { return "The seed policy file does not exist: \(path)" }
    guard (try? JSONSerialization.jsonObject(with: data)) != nil else { return "The seed policy file is not valid JSON: \(path)" }
    return nil
  }
  #endif
}

/// What a reset did. A Keychain status of `errSecMissingEntitlement` (-34018, an unsigned simulator build) is tolerated.
nonisolated struct ResetReport: Equatable, Sendable {
  var removedRoot = false
  var keychainStatuses: [OSStatus] = []
}

nonisolated enum StateReset {
  /// Wipes `root`, the app's own Keychain items and the defaults domain. Keychain items are scoped to the app's own access
  /// group, so a class-wide delete reaches only `com.mido.stmp` items. -34018 and "not found" are not failures.
  static func perform(root: URL, defaultsDomain: String) -> ResetReport {
    var report = ResetReport()
    if FileManager.default.fileExists(atPath: root.path) {
      try? FileManager.default.removeItem(at: root)
      report.removedRoot = true
    }
    for cls in [kSecClassGenericPassword, kSecClassKey, kSecClassInternetPassword] {
      report.keychainStatuses.append(SecItemDelete([kSecClass: cls] as CFDictionary))
    }
    UserDefaults(suiteName: defaultsDomain)?.removePersistentDomain(forName: defaultsDomain)
    return report
  }

  static func isTolerated(_ status: OSStatus) -> Bool {
    status == errSecSuccess || status == errSecItemNotFound || status == errSecMissingEntitlement
  }
}
