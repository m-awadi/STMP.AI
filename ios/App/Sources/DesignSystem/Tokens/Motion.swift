import SwiftUI

/// Durations, easings and springs (ui.md 2.5, Android `Motion.kt`). Durations are in milliseconds.
nonisolated enum Motion {
  nonisolated static let pressMs = 60
  nonisolated static let fastMs = 120
  nonisolated static let tabMs = 180
  nonisolated static let sharedAxisMs = 250
  nonisolated static let ledBreathMs = 1600
  /// Safety, not decoration: it stays under Reduce Motion.
  nonisolated static let holdToApproveMs = 1200
  nonisolated static let holdTickEveryMs = 300
  nonisolated static let punchDropMs = 180
  nonisolated static let punchJoltMs = 220
  nonisolated static let punchSettledMs = 720
  nonisolated static let punchFadeMs = 160
  nonisolated static let tapeStepMs = 60
  nonisolated static let tapeSteps = 4
  /// Safety, not decoration: it stays under Reduce Motion.
  nonisolated static let receiptShowMs = 1500
  nonisolated static let rockerMaxMs = 140

  /// cubic-bezier(0.4, 0, 0.2, 1)
  static func standard(_ ms: Int) -> Animation { .timingCurve(0.4, 0, 0.2, 1, duration: Double(ms) / 1000) }
  /// cubic-bezier(0, 0, 0.2, 1)
  static func decelerate(_ ms: Int) -> Animation { .timingCurve(0, 0, 0.2, 1, duration: Double(ms) / 1000) }

  nonisolated struct Spring: Sendable, Hashable {
    nonisolated let stiffness: Double
    nonisolated let dampingRatio: Double
    /// SwiftUI `response` = 2 pi / omega0 with mass 1.
    nonisolated var response: Double { 2 * .pi / stiffness.squareRoot() }
    var animation: Animation { .spring(response: response, dampingFraction: dampingRatio) }
  }
  nonisolated static let rocker = Spring(stiffness: 3000, dampingRatio: 0.9)
  nonisolated static let holdReturn = Spring(stiffness: 1500, dampingRatio: 1.0)
  nonisolated static let punchSlam = Spring(stiffness: 2500, dampingRatio: 0.5)

  /// Closed form of a unit step response of a spring (mass 1), for the clock-driven punch and hold ring. `t` is in seconds.
  nonisolated static func springAt(_ t: Double, _ spring: Spring) -> Double {
    guard t > 0 else { return 0 }
    let w0 = spring.stiffness.squareRoot()
    let z = spring.dampingRatio
    if z >= 1 { return 1 - exp(-w0 * t) * (1 + w0 * t) }
    let wd = w0 * (1 - z * z).squareRoot()
    return 1 - exp(-z * w0 * t) * (cos(wd * t) + (z * w0 / wd) * sin(wd * t))
  }

  /// Under Reduce Motion every animated component jumps to its end state.
  static func animation(_ animation: Animation, reduceMotion: Bool) -> Animation? { reduceMotion ? nil : animation }
}
