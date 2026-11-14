import CoreGraphics

/// Spacing, radii and sizes (ui.md 2.3, Android `Dimens.kt`). All in points.
nonisolated enum Dimens {
  // Spacing
  nonisolated static let space2XS: CGFloat = 2
  nonisolated static let spaceXS: CGFloat = 4
  nonisolated static let spaceS: CGFloat = 8
  nonisolated static let moduleGap: CGFloat = 9
  nonisolated static let spaceM: CGFloat = 12
  nonisolated static let modulePad: CGFloat = 13
  nonisolated static let spaceL: CGFloat = 16
  /// Screen side padding.
  nonisolated static let gutter: CGFloat = 20
  nonisolated static let spaceXL: CGFloat = 24
  nonisolated static let space2XL: CGFloat = 32
  nonisolated static let space3XL: CGFloat = 40

  // Radii
  nonisolated static let radiusModule: CGFloat = 16
  nonisolated static let radiusKey: CGFloat = 12
  nonisolated static let radiusReadout: CGFloat = 10
  nonisolated static let radiusKnobThumb: CGFloat = 8
  nonisolated static let radiusRockerTrack: CGFloat = 9
  nonisolated static let radiusRockerCap: CGFloat = 7
  nonisolated static let radiusVerb: CGFloat = 4

  // Sizes
  nonisolated static let minTarget: CGFloat = 48
  nonisolated static let targetGap: CGFloat = 8
  nonisolated static let destructiveGap: CGFloat = 16
  nonisolated static let keyHeight: CGFloat = 48
  nonisolated static let keyHeightLarge: CGFloat = 60
  nonisolated static let keySmallVisual: CGFloat = 36
  nonisolated static let holdKey: CGFloat = 92
  nonisolated static let holdDisc: CGFloat = 70
  nonisolated static let holdRingStroke: CGFloat = 4
  nonisolated static let rockerWidth: CGFloat = 58
  nonisolated static let rockerHeight: CGFloat = 32
  nonisolated static let rockerCapWidth: CGFloat = 30
  nonisolated static let rockerCapHeight: CGFloat = 26
  nonisolated static let knobHeight: CGFloat = 40
  nonisolated static let led: CGFloat = 8
  nonisolated static let ledBox: CGFloat = 16
  nonisolated static let topBarHeight: CGFloat = 56
  nonisolated static let tabBarHeight: CGFloat = 64
  nonisolated static let ticksHeight: CGFloat = 6
  nonisolated static let tickGap: CGFloat = 2.4
  nonisolated static let icon: CGFloat = 24
  nonisolated static let focusRing: CGFloat = 2
  nonisolated static let outlineStroke: CGFloat = 1.5
  /// Whole visual of a disabled control.
  nonisolated static let disabledOpacity: Double = 0.38
}
