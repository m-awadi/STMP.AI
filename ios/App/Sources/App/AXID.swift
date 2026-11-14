import Foundation

/// Shared accessibility identifiers (D39). Constants only, Foundation only: this file also compiles into STMPUITests.
/// Each feature extends it in `Features/<Feature>/AXID+<Feature>.swift`.
enum AXID {
  nonisolated static let bridgeRocker = "bridge.rocker"
  nonisolated static let bridgePort = "bridge.port"
  nonisolated static let tabHome = "tab.home"
  nonisolated static let tabPlugins = "tab.plugins"
  nonisolated static let tabSettings = "tab.settings"
  nonisolated static let launchError = "launch.error"
}
