import Observation

/// The toast host's model. `AppRoot` owns one and shows `message` in an overlay; features post through it.
@MainActor @Observable
final class ToastCenter {
  private(set) var message: String?
  func show(_ text: String) { message = text }
  func dismiss() { message = nil }
}
