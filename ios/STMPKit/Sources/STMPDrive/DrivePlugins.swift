import STMPCore

/// The module's plugin factory (D78). Placeholder: every handler throws `internal_error` "not built yet".
/// `declaredRoutes` is the FULL route list; the contract tests compare it with `ios/contract/routes/`.
public enum DrivePlugins {
  public static let declaredRoutes: [RouteTemplate] = [
    RouteTemplate(plugin: "drive", "GET", "/drive/files", "List or search your Drive files", .read),
    RouteTemplate(plugin: "drive", "GET", "/drive/files/{id}", "Show a Drive file's details", .read),
    RouteTemplate(plugin: "drive", "GET", "/drive/files/{id}/content", "DOWNLOAD a Drive file", .read, auditResponseBody: false),
    RouteTemplate(plugin: "drive", "POST", "/drive/files", "CREATE DRIVE FOLDER", .write),
    RouteTemplate(plugin: "drive", "POST", "/drive/files/{id}/rename", "RENAME DRIVE FILE", .edit),
    RouteTemplate(plugin: "drive", "POST", "/drive/files/{id}/move", "MOVE DRIVE FILE", .edit),
    RouteTemplate(plugin: "drive", "POST", "/drive/files/{id}/trash", "TRASH DRIVE FILE", .delete),
    RouteTemplate(
      plugin: "drive", "POST", "/drive/files/upload", "UPLOAD FILE TO DRIVE", .write, auditRequestBody: false, streamsRequest: true),
  ]

  public static func make(_ deps: PluginDeps) -> [any Plugin] {
    [
      NotBuiltPlugin(id: "drive", displayName: "Drive", enabledByDefault: true, templates: declaredRoutes.filter { $0.plugin == "drive" })
    ]
  }
}
