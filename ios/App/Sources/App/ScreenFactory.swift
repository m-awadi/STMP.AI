import SwiftUI

/// Maps each `AppRoute` to a fixed entry point (PLAN 1.9). T0 creates every entry point as a placeholder inside its feature
/// directory; the owner replaces the file. This switch is complete and is never extended outside an amendment.
@MainActor
enum ScreenFactory {
  @ViewBuilder
  static func screen(for route: AppRoute, env: AppEnvironment) -> some View {
    switch route {
    case .log: LogScreen(env: env)
    case .logDetail(let id): LogDetailScreen(env: env, id: id)
    case .exceptions: ExceptionsScreen(env: env)
    case .secrets: SecretsScreen(env: env)
    case .ssh: SSHScreen(env: env)
    case .sshConnection(let name): SSHConnectionScreen(env: env, name: name)
    case .plugin(let id): PluginDetailScreen(env: env, id: id)
    case .googleSignIn: GoogleSignInScreen(env: env)
    case .onboarding: OnboardingScreen(env: env)
    case .callers: CallersScreen(env: env)
    case .vault: VaultScreen(env: env)
    case .settingsDebug: SettingsDebugScreen(env: env)
    }
  }
}
