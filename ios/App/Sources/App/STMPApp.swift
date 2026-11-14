import STMPCore
import SwiftUI

@main
struct STMPApp: App {
  @State private var env = STMPApp.makeEnvironment()

  var body: some Scene {
    WindowGroup { AppRoot(env: env) }
  }

  /// The one place the environment is chosen: the inert host for a unit-test process (D69), otherwise the live composition.
  static func makeEnvironment() -> AppEnvironment {
    let options = LaunchOptions.current
    if options.isXCTestHost {
      let root = StorageRoot(url: FileManager.default.temporaryDirectory.appendingPathComponent("stmp-inert-\(UUID().uuidString)"))
      return .inert(storageRoot: root)
    }
    let root = options.storageRoot.map { StorageRoot(url: $0) } ?? defaultStorageRoot()
    if options.resetState { _ = StateReset.perform(root: root.url, defaultsDomain: AppPreferences.domainName) }
    try? root.prepare()
    return .live(storageRoot: root, launchOptions: options, preferences: .standard())
  }

  private static func defaultStorageRoot() -> StorageRoot {
    let base =
      FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
    return StorageRoot(url: base.appendingPathComponent("STMP", isDirectory: true))
  }
}
