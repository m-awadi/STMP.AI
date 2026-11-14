import STMPCore
import SwiftUI

/// The scene's root view. It builds nothing itself: the shell is T14's, the gate surfaces are T13's.
///  - installs the window hosts once per scene (`GateHost.install(in:env:)`, D38);
///  - forwards `scenePhase` only to `env.attendance.crossCheck(_:)`, which logs and drives nothing else (D121);
///  - calls `env.lifecycle.launch()` once from `.task`.
struct AppRoot: View {
  let env: AppEnvironment
  @Environment(\.scenePhase) private var scenePhase
  @State private var toasts = ToastCenter()
  @State private var installedHosts = false

  var body: some View {
    PaletteReader {
      content
        .overlay(alignment: .bottom) { ToastHost(center: toasts) }
        .environment(\.soundEffectsEnabled, env.soundEffectsEnabled)
        .background(SceneReader { scene in installHosts(in: scene) })
        .task { await env.lifecycle.launch() }
        .onChange(of: scenePhase) { _, phase in env.attendance.crossCheck(Self.value(of: phase)) }
    }
  }

  @ViewBuilder private var content: some View {
    if env.launchOptions.isXCTestHost {
      Color.clear  // The inert unit-test host shows nothing (D69).
    } else if let error = env.launchOptions.seedPolicyError {
      LaunchErrorView(message: error)
    } else {
      RootShell(env: env)
    }
  }

  private func installHosts(in scene: UIWindowScene) {
    guard !installedHosts else { return }
    installedHosts = true
    GateHost.install(in: scene, env: env)
  }

  static func value(of phase: ScenePhase) -> ScenePhaseValue {
    switch phase {
    case .active: return .active
    case .inactive: return .inactive
    case .background: return .background
    @unknown default: return .inactive
    }
  }
}

/// A launch error is visible, never a fallback (D82).
struct LaunchErrorView: View {
  @Environment(\.palette) private var palette
  let message: String
  var body: some View {
    ScreenPlaceholder(title: "Launch error", detail: message)
      .accessibilityIdentifier(AXID.launchError)
      .foregroundStyle(palette.signalText.color)
  }
}

struct ToastHost: View {
  @Environment(\.palette) private var palette
  let center: ToastCenter
  var body: some View {
    if let message = center.message {
      Text(message)
        .typeRole(.bodyStrong)
        .foregroundStyle(palette.onInk.color)
        .padding(Dimens.spaceM)
        .background(palette.ink.color, in: RoundedRectangle(cornerRadius: Dimens.radiusKey, style: .circular))
        .padding(Dimens.gutter)
        .onTapGesture { center.dismiss() }
    }
  }
}

/// Reports the hosting `UIWindowScene` once the view is in a window.
private struct SceneReader: UIViewRepresentable {
  let onScene: (UIWindowScene) -> Void
  func makeUIView(context: Context) -> SceneProbeView {
    let v = SceneProbeView()
    v.onScene = onScene
    v.isUserInteractionEnabled = false
    return v
  }
  func updateUIView(_ uiView: SceneProbeView, context: Context) {}
}

private final class SceneProbeView: UIView {
  var onScene: ((UIWindowScene) -> Void)?
  override func didMoveToWindow() {
    super.didMoveToWindow()
    if let scene = window?.windowScene { onScene?(scene) }
  }
}
