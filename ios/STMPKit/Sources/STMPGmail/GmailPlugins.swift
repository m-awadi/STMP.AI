import STMPCore

/// The module's plugin factory (D78). Placeholder: every handler throws `internal_error` "not built yet".
/// `declaredRoutes` is the FULL route list; the contract tests compare it with `ios/contract/routes/`.
public enum GmailPlugins {
  public static let declaredRoutes: [RouteTemplate] = [
    RouteTemplate(plugin: "gmail", "GET", "/gmail/profile", "Show your Gmail profile", .read),
    RouteTemplate(plugin: "gmail", "GET", "/gmail/messages", "List your Gmail messages", .read),
    RouteTemplate(plugin: "gmail", "GET", "/gmail/messages/{id}", "Read a Gmail message", .read),
    RouteTemplate(plugin: "gmail", "POST", "/gmail/messages/{id}/modify", "Modify Gmail labels", .edit),
    RouteTemplate(plugin: "gmail", "POST", "/gmail/messages/{id}/trash", "TRASH EMAIL", .delete),
    RouteTemplate(plugin: "gmail", "POST", "/gmail/messages/send", "SEND EMAIL", .write),
  ]

  public static func make(_ deps: PluginDeps) -> [any Plugin] {
    [
      NotBuiltPlugin(id: "gmail", displayName: "Gmail", enabledByDefault: true, templates: declaredRoutes.filter { $0.plugin == "gmail" })
    ]
  }
}
