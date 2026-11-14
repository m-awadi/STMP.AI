import SwiftUI

/// Placeholder shell (T0): three tabs, each with its own `NavigationStack` over `AppRoute`. T14 replaces it with the custom
/// tab bar, the pop-to-root on re-tap and the swipe-back shim (D62). Frozen: `RootShell(env:)`.
struct RootShell: View {
  enum Tab: String, CaseIterable { case home = "Home", plugins = "Plugins", settings = "Settings" }

  let env: AppEnvironment
  @Environment(\.palette) private var palette
  @State private var selection: Tab = .home
  @State private var paths: [Tab: [AppRoute]] = [:]

  var body: some View {
    VStack(spacing: 0) {
      NavigationStack(path: binding(for: selection)) {
        tabContent
          .navigationDestination(for: AppRoute.self) { ScreenFactory.screen(for: $0, env: env) }
          .toolbar(.hidden, for: .navigationBar)
      }
      tabBar
    }
    .background(palette.body.color.ignoresSafeArea())
  }

  @ViewBuilder private var tabContent: some View {
    switch selection {
    case .home: HomeScreen(env: env)
    case .plugins: PluginsScreen(env: env)
    case .settings: SettingsScreen(env: env)
    }
  }

  private func binding(for tab: Tab) -> Binding<[AppRoute]> {
    Binding(get: { paths[tab] ?? [] }, set: { paths[tab] = $0 })
  }

  private var tabBar: some View {
    HStack(spacing: 0) {
      ForEach(Tab.allCases, id: \.self) { tab in
        Button {
          if tab == selection { paths[tab] = [] }
          selection = tab
        } label: {
          Text(tab.rawValue)
            .typeRole(.tab)
            .foregroundStyle(tab == selection ? palette.ink.color : palette.etched.color)
            .frame(maxWidth: .infinity, minHeight: Dimens.minTarget)
            .contentShape(Rectangle())
        }
        .accessibilityIdentifier(Self.identifier(for: tab))
        .accessibilityAddTraits(tab == selection ? .isSelected : [])
      }
    }
    .frame(minHeight: Dimens.tabBarHeight)
    .background(palette.body.color.ignoresSafeArea(edges: .bottom))
    .overlay(alignment: .top) { Rectangle().fill(palette.hairline.color).frame(height: 1) }
  }

  private static func identifier(for tab: Tab) -> String {
    switch tab {
    case .home: return AXID.tabHome
    case .plugins: return AXID.tabPlugins
    case .settings: return AXID.tabSettings
    }
  }
}
