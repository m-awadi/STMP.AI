import STMPCore

/// The stores and the gate that the bridge reads (T3, T4, T5 and T12 supply the live objects behind `BridgeLive.make`).
/// T0: fail-closed (the gate denies, the audit sink throws, every store read fails).
struct BridgeServices {
  var policy: any PolicyProviding
  var toggles: any PluginToggles
  var callers: any CallerRegistry
  var auditSink: any AuditSink
  var auditReader: any AuditReader
  var gate: any GateService
  var gatePresenting: any GatePresenting

  static var inert: BridgeServices {
    BridgeServices(
      policy: InertPolicyProviding(), toggles: InertPluginToggles(), callers: InertCallerRegistry(), auditSink: InertAuditSink(),
      auditReader: InertAuditReader(), gate: InertGateService(), gatePresenting: InertGatePresenting())
  }
}

enum BridgeLive {
  static func make(_ ctx: CompositionContext) -> BridgeServices { .inert }
}
