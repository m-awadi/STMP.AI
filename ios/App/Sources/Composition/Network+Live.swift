import STMPCore

/// Network platform services (T1 fills `NetworkLive.make`; the shape is frozen). T0: fail-closed stubs.
struct NetworkServices {
  var interfaces: any NetworkInterfaces
  var path: any PathMonitor
}

enum NetworkLive {
  static func make(_ ctx: CompositionContext) -> NetworkServices {
    NetworkServices(interfaces: InertNetworkInterfaces(), path: InertPathMonitor())
  }
}
