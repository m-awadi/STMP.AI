import STMPCore

public struct BridgeConfig: Sendable, Hashable {
  /// Tailnet-only (Q8). Tests pass `loopback`.
  public enum Bind: Sendable, Hashable { case tailnet, loopback }
  public var bind: Bind
  public var port: Int
  public init(bind: Bind = .tailnet, port: Int = 8787) {
    self.bind = bind
    self.port = port
  }
}

/// What the bridge needs from the platform, built by `Composition/`.
public struct BridgePlatform: Sendable {
  public var deps: PluginDeps
  public var interfaces: any NetworkInterfaces
  public var path: any PathMonitor
  public init(deps: PluginDeps, interfaces: any NetworkInterfaces, path: any PathMonitor) {
    self.deps = deps
    self.interfaces = interfaces
    self.path = path
  }
}

/// Frozen signature (D78). T12 replaces the body; T0's bridge never starts.
public enum BridgeFactory {
  public static func make(config: BridgeConfig, platform: BridgePlatform) -> any BridgeControlling { InertBridgeControlling() }
}
