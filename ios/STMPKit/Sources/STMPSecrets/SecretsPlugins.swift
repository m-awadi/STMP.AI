import STMPCore

/// The module's plugin factory (D78). Placeholder: every handler throws `internal_error` "not built yet".
/// `declaredRoutes` is the FULL route list; the contract tests compare it with `ios/contract/routes/`.
public enum SecretsPlugins {
  public static let declaredRoutes: [RouteTemplate] = [
    RouteTemplate(plugin: "secrets", "GET", "/secrets", "List every secret's non-secret metadata (no values)", .read),
    RouteTemplate(plugin: "secrets", "GET", "/secrets/{name}", "Read one secret's non-secret metadata (no value)", .read),
    RouteTemplate(
      plugin: "secrets", "POST", "/secrets/{name}/call", "Call an API with the '<name>' secret", nil, fallback: .execute, needsVault: true),
  ]

  public static func make(_ deps: PluginDeps) -> [any Plugin] {
    [
      NotBuiltPlugin(
        id: "secrets", displayName: "Secrets", enabledByDefault: true, templates: declaredRoutes.filter { $0.plugin == "secrets" })
    ]
  }

  /// One broker per vault entry of kind SECRET; declared once as the template `POST /secrets/{name}/call` (D101).
  public static func makeBrokers(_ deps: PluginDeps, meta: VaultMeta) -> [any Plugin] { [] }
}
