import SwiftUI

/// The 17 Instrument type roles (ui.md 2.1). Sizes are the base sizes at the `.large` Dynamic Type setting.
nonisolated enum TypeRole: String, CaseIterable, Sendable {
  case display, numeral, punch, title, command, rowLabel, body, bodyStrong, detail, readout, monoBody, key, label, chip, tab, caption,
    wordmark

  nonisolated struct Spec: Sendable, Hashable {
    nonisolated let face: String
    nonisolated let size: CGFloat
    nonisolated let line: CGFloat
    nonisolated let trackingEm: CGFloat
    nonisolated let caps: Bool
    nonisolated let textStyle: Font.TextStyle
  }

  nonisolated var spec: Spec {
    switch self {
    case .display: return Spec(face: "GeistMono-Medium", size: 36, line: 40, trackingEm: -0.04, caps: false, textStyle: .largeTitle)
    case .numeral: return Spec(face: "GeistMono-Medium", size: 24, line: 28, trackingEm: -0.04, caps: false, textStyle: .title)
    case .punch: return Spec(face: "Geist-Bold", size: 28, line: 32, trackingEm: 0.02, caps: true, textStyle: .title)
    case .title: return Spec(face: "Geist-SemiBold", size: 20, line: 26, trackingEm: -0.02, caps: false, textStyle: .title3)
    case .command: return Spec(face: "GeistMono-Medium", size: 16, line: 24, trackingEm: 0, caps: false, textStyle: .body)
    case .rowLabel: return Spec(face: "Geist-Medium", size: 16, line: 22, trackingEm: 0, caps: false, textStyle: .body)
    case .body: return Spec(face: "Geist-Regular", size: 14, line: 20, trackingEm: 0, caps: false, textStyle: .subheadline)
    case .bodyStrong: return Spec(face: "Geist-Medium", size: 14, line: 20, trackingEm: 0, caps: false, textStyle: .subheadline)
    case .detail: return Spec(face: "Geist-Regular", size: 13, line: 18, trackingEm: 0, caps: false, textStyle: .footnote)
    case .readout: return Spec(face: "GeistMono-Medium", size: 13, line: 18, trackingEm: 0, caps: false, textStyle: .footnote)
    case .monoBody: return Spec(face: "GeistMono-Regular", size: 13, line: 18, trackingEm: 0, caps: false, textStyle: .footnote)
    case .key: return Spec(face: "GeistMono-SemiBold", size: 13, line: 16, trackingEm: 0.12, caps: true, textStyle: .footnote)
    case .label: return Spec(face: "GeistMono-Medium", size: 12, line: 16, trackingEm: 0.14, caps: true, textStyle: .caption)
    case .chip: return Spec(face: "GeistMono-SemiBold", size: 12, line: 16, trackingEm: 0.06, caps: true, textStyle: .caption)
    case .tab: return Spec(face: "Geist-Medium", size: 12, line: 16, trackingEm: 0.02, caps: false, textStyle: .caption)
    case .caption: return Spec(face: "Geist-Regular", size: 12, line: 16, trackingEm: 0, caps: false, textStyle: .caption)
    case .wordmark: return Spec(face: "Geist-Bold", size: 13, line: 16, trackingEm: 0.2, caps: false, textStyle: .footnote)
    }
  }

  /// The tab label face when selected (Sans 600).
  nonisolated static let tabSelectedFace = "Geist-SemiBold"

  /// Android's floor is 12 sp; iOS never goes below `.caption` at `.large`, which is 12 pt.
  nonisolated static let sizeFloor: CGFloat = 12
}

extension TypeRole {
  /// The font scaled with Dynamic Type from the role's base size. No synthetic bold: the weight is the face.
  func font() -> Font { Font.custom(spec.face, size: spec.size, relativeTo: spec.textStyle) }
}

private struct TypeRoleModifier: ViewModifier {
  let role: TypeRole
  @ScaledMetric private var scale: CGFloat

  init(role: TypeRole) {
    self.role = role
    _scale = ScaledMetric(wrappedValue: 1, relativeTo: role.spec.textStyle)
  }

  func body(content: Content) -> some View {
    let spec = role.spec
    let extra = max(0, (spec.line - spec.size * 1.2) * scale)
    content
      .font(role.font())
      .tracking(spec.trackingEm * spec.size * scale)
      .lineSpacing(extra)
      .textCase(spec.caps ? .uppercase : nil)
  }
}

extension View {
  /// Applies a type role: face, Dynamic Type curve, tracking (em, scaled), line spacing and caps.
  func typeRole(_ role: TypeRole) -> some View { modifier(TypeRoleModifier(role: role)) }
}
