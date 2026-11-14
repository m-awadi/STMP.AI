import SwiftUI

/// Placeholder (T0). T13 installs the gate window hosts and the privacy cover here, once per scene (D38). Frozen:
/// `GateHost.install(in:env:)`. The gate surfaces are not in AppRoot's view tree.
@MainActor
enum GateHost {
  static func install(in scene: UIWindowScene, env: AppEnvironment) {}
}
