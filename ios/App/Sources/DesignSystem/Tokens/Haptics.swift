import UIKit

/// The haptic events of ui.md 2.6. `Haptics.perform` goes through an injectable sink, so tests assert on events
/// (the simulator produces no haptics). UIKit respects the system "System Haptics" switch.
nonisolated enum HapticEvent: String, Sendable, CaseIterable {
  case toggle, segment, keyDown, keyUp, holdTick, countdownTick, tapeStep, holdConfirm, holdCancel, reject
}

nonisolated protocol HapticSink: Sendable {
  @MainActor func emit(_ event: HapticEvent)
}

struct SystemHapticSink: HapticSink {
  @MainActor func emit(_ event: HapticEvent) {
    switch event {
    case .toggle, .segment: UISelectionFeedbackGenerator().selectionChanged()
    case .keyDown: UIImpactFeedbackGenerator(style: .light).impactOccurred()
    case .keyUp: UIImpactFeedbackGenerator(style: .soft).impactOccurred(intensity: 0.5)
    case .holdTick, .countdownTick, .tapeStep: UIImpactFeedbackGenerator(style: .rigid).impactOccurred(intensity: 0.6)
    case .holdConfirm: UINotificationFeedbackGenerator().notificationOccurred(.success)
    case .holdCancel: UINotificationFeedbackGenerator().notificationOccurred(.warning)
    case .reject: UINotificationFeedbackGenerator().notificationOccurred(.error)
    }
  }
}

@MainActor
enum Haptics {
  static var sink: any HapticSink = SystemHapticSink()
  static func perform(_ event: HapticEvent) { sink.emit(event) }
}
