import STMPCore

/// The module's plugin factory (D78). Placeholder: every handler throws `internal_error` "not built yet".
/// `declaredRoutes` is the FULL route list; the contract tests compare it with `ios/contract/routes/`.
public enum CalendarPlugins {
  public static let declaredRoutes: [RouteTemplate] = [
    RouteTemplate(plugin: "calendar", "GET", "/calendar/events", "List your calendar events", .read),
    RouteTemplate(plugin: "calendar", "POST", "/calendar/events", "CREATE CALENDAR EVENT", .write),
    RouteTemplate(plugin: "calendar", "POST", "/calendar/events/{id}", "UPDATE CALENDAR EVENT", .edit),
    RouteTemplate(plugin: "calendar", "DELETE", "/calendar/events/{id}", "DELETE CALENDAR EVENT", .delete),
    RouteTemplate(plugin: "calendar", "POST", "/calendar/events/{id}/move", "MOVE CALENDAR EVENT", .edit),
    RouteTemplate(plugin: "calendar", "POST", "/calendar/quickadd", "QUICK-ADD CALENDAR EVENT", .write),
  ]

  public static func make(_ deps: PluginDeps) -> [any Plugin] {
    [
      NotBuiltPlugin(
        id: "calendar", displayName: "Calendar", enabledByDefault: true, templates: declaredRoutes.filter { $0.plugin == "calendar" })
    ]
  }
}
