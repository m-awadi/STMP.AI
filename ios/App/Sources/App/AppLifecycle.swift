import Foundation
import STMPCore

/// `env.lifecycle`. T0 stub: `launch()` runs `start(origin: .launch)` once. T12 replaces the body with the
/// `ServingController`, which also registers as the Attendance transition handler (D121). The signature is frozen.
@MainActor
final class AppLifecycle {
  private let bridge: any BridgeControlling
  private var launched = false

  init(bridge: any BridgeControlling) { self.bridge = bridge }

  /// Called once from `AppRoot`'s `.task`. A second call does nothing.
  func launch() async {
    guard !launched else { return }
    launched = true
    await bridge.start(origin: .launch)
  }
}
