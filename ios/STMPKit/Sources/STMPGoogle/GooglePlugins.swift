import STMPCore

/// The module's plugin factory (D78). Placeholder: every handler throws `internal_error` "not built yet".
/// `declaredRoutes` is the FULL route list; the contract tests compare it with `ios/contract/routes/`.
public enum GooglePlugins {
  public static let declaredRoutes: [RouteTemplate] = []

  public static func make(_ deps: PluginDeps) -> [any Plugin] {
    [
      NotBuiltPlugin(
        id: "google", displayName: "Google", enabledByDefault: true, templates: declaredRoutes.filter { $0.plugin == "google" })
    ]
  }
}
