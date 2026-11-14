import STMPCore

/// The module's plugin factory (D78). Placeholder: every handler throws `internal_error` "not built yet".
/// `declaredRoutes` is the FULL route list; the contract tests compare it with `ios/contract/routes/`.
public enum SSHPlugins {
  public static let declaredRoutes: [RouteTemplate] = [
    RouteTemplate(plugin: "ssh", "POST", "/ssh/{name}", "RUN COMMAND OVER SSH", .execute, needsVault: true),
    RouteTemplate(plugin: "ssh", "POST", "/ssh/{name}/detach", "START A DETACHED COMMAND OVER SSH", .execute, needsVault: true),
    RouteTemplate(plugin: "ssh", "GET", "/ssh/job/{id}", "READ A DETACHED SSH JOB", .read),
  ]

  public static func make(_ deps: PluginDeps) -> [any Plugin] {
    [
      NotBuiltPlugin(id: "ssh", displayName: "SSH", enabledByDefault: true, templates: declaredRoutes.filter { $0.plugin == "ssh" })
    ]
  }
}
