import STMPCore

/// The module's plugin factory (D78). Placeholder: every handler throws `internal_error` "not built yet".
/// `declaredRoutes` is the FULL route list; the contract tests compare it with `ios/contract/routes/`.
public enum DevicePlugins {
  public static let declaredRoutes: [RouteTemplate] = [
    RouteTemplate(
      plugin: "audio", "POST", "/audio/{cue}", "PLAY AN ATTENTION CUE", .execute, auditRequestBody: false, aiSummary: false,
      gateExempt: true),
    RouteTemplate(plugin: "tts", "POST", "/tts", "SAY TEXT ALOUD", .execute, aiSummary: false, gateExempt: true),
    RouteTemplate(plugin: "contacts", "GET", "/contacts", "Search your contacts", .read, auditResponseTransform: true),
    RouteTemplate(
      plugin: "debug", "GET", "/debug", "Read the phone's diagnostic report", .read, auditResponseBody: false, aiSummary: false),
    RouteTemplate(plugin: "debug", "DELETE", "/audit", "DELETE THE REQUEST LOG", .delete, aiSummary: false),
    RouteTemplate(
      plugin: "logs", "GET", "/logs", "Read this app's own logcat", .read, auditRequestBody: false, auditResponseBody: false,
      aiSummary: false),
    RouteTemplate(
      plugin: "notifications", "GET", "/notifications", "List your notifications", .read,
      unsupportedOnIOS: "Android notifications cannot be read or acted on from an iPhone app; iOS offers no equivalent API."),
    RouteTemplate(
      plugin: "notifications", "POST", "/notifications/{id}/action", "Fire a notification action", .execute,
      unsupportedOnIOS: "Android notifications cannot be read or acted on from an iPhone app; iOS offers no equivalent API."),
    RouteTemplate(
      plugin: "notifications", "POST", "/notifications/{id}/dismiss", "Dismiss a notification", .execute, holdExempt: true,
      unsupportedOnIOS: "Android notifications cannot be read or acted on from an iPhone app; iOS offers no equivalent API."),
  ]

  public static func make(_ deps: PluginDeps) -> [any Plugin] {
    [
      NotBuiltPlugin(id: "audio", displayName: "Audio", enabledByDefault: true, templates: declaredRoutes.filter { $0.plugin == "audio" }),
      NotBuiltPlugin(
        id: "tts", displayName: "Text to speech", enabledByDefault: true, templates: declaredRoutes.filter { $0.plugin == "tts" }),
      NotBuiltPlugin(
        id: "contacts", displayName: "Contacts", enabledByDefault: true, templates: declaredRoutes.filter { $0.plugin == "contacts" }),
      NotBuiltPlugin(id: "debug", displayName: "Debug", enabledByDefault: true, templates: declaredRoutes.filter { $0.plugin == "debug" }),
      NotBuiltPlugin(id: "logs", displayName: "Logs", enabledByDefault: true, templates: declaredRoutes.filter { $0.plugin == "logs" }),
      NotBuiltPlugin(
        id: "notifications", displayName: "Notifications", enabledByDefault: true,
        templates: declaredRoutes.filter { $0.plugin == "notifications" }),
    ]
  }
}
