import STMPCalendar
import STMPCore
import STMPDevice
import STMPDrive
import STMPGmail
import STMPGoogle
import STMPPluginsCore
import STMPSSH
import STMPSecrets

/// Registers plugins in Android `BridgeService.registerPlugins` order and exposes the declared route templates.
/// It calls only each module's `<Module>Plugins.make(_:)` and `makeBrokers(_:meta:)` (D78).
public enum PluginCatalog {
  /// notifications, google, gmail, calendar, ssh, drive, debug, logs, audio, contacts, tts, help, probe, secrets.
  public static let registrationOrder = [
    "notifications", "google", "gmail", "calendar", "ssh", "drive", "debug", "logs", "audio", "contacts", "tts", "help", "probe", "secrets",
  ]

  public static func make(_ deps: PluginDeps, vault: VaultMeta = VaultMeta(entries: [])) -> [any Plugin] {
    let all: [any Plugin] =
      DevicePlugins.make(deps) + GooglePlugins.make(deps) + GmailPlugins.make(deps) + CalendarPlugins.make(deps)
      + SSHPlugins.make(deps) + DrivePlugins.make(deps) + PluginsCorePlugins.make(deps) + SecretsPlugins.make(deps)
    let ordered = registrationOrder.compactMap { id in all.first { $0.id == id } }
    return ordered + SecretsPlugins.makeBrokers(deps, meta: vault)
  }

  /// Every route template the app declares, in registration order. The broker is declared once as `POST /secrets/{name}/call`.
  public static var declaredRouteTemplates: [RouteTemplate] {
    let all =
      DevicePlugins.declaredRoutes + GooglePlugins.declaredRoutes + GmailPlugins.declaredRoutes + CalendarPlugins.declaredRoutes
      + SSHPlugins.declaredRoutes + DrivePlugins.declaredRoutes + PluginsCorePlugins.declaredRoutes + SecretsPlugins.declaredRoutes
    return registrationOrder.flatMap { id in all.filter { $0.plugin == id } }
  }
}
