import STMPCore

/// The module's plugin factory (D78). Placeholder: every handler throws `internal_error` "not built yet".
/// `declaredRoutes` is the FULL route list; the contract tests compare it with `ios/contract/routes/`.
public enum PluginsCorePlugins {
  public static let declaredRoutes: [RouteTemplate] = [
    RouteTemplate(
      plugin: "probe", "GET", "/probe", "Check that the approval gate reaches you", .read, auditResponseBody: false, aiSummary: false),
    RouteTemplate(
      plugin: "help", "GET", "/help", "Describe this bridge and everything it can do", .read, auditResponseBody: false, aiSummary: false),
  ]

  public static func make(_ deps: PluginDeps) -> [any Plugin] {
    [
      NotBuiltPlugin(id: "probe", displayName: "Probe", enabledByDefault: true, templates: declaredRoutes.filter { $0.plugin == "probe" }),
      NotBuiltPlugin(id: "help", displayName: "Help", enabledByDefault: true, templates: declaredRoutes.filter { $0.plugin == "help" }),
    ]
  }
}
