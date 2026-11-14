import SwiftUI

/// Elevation is one highlight line plus one shade line, built from stacked rounded rectangles with per-layer insets
/// (ui.md 2.4). There are no drop shadows. Corners are circular arcs (`.circular`), matching Android `GradientDrawable`.
nonisolated struct BevelLayer: Hashable, Sendable {
  nonisolated enum Fill: Hashable, Sendable {
    case bevelShade, bevelHighlight, inkHighlight, panel, ink, signal, readout, keyLip, keyLipInk, keyLipSignal
  }
  nonisolated let fill: Fill
  nonisolated let left: CGFloat
  nonisolated let top: CGFloat
  nonisolated let right: CGFloat
  nonisolated let bottom: CGFloat
}

nonisolated enum BevelRecipe: Sendable {
  nonisolated static let module: [BevelLayer] = [
    BevelLayer(fill: .bevelShade, left: 0, top: 0, right: 0, bottom: 0),
    BevelLayer(fill: .bevelHighlight, left: 0, top: 0, right: 0, bottom: 1),
    BevelLayer(fill: .panel, left: 0, top: 1, right: 0, bottom: 1),
  ]

  nonisolated enum KeyKind: Sendable { case panel, ink, signal }

  nonisolated static func key(_ kind: KeyKind, pressed: Bool) -> [BevelLayer] {
    let lip: BevelLayer.Fill = kind == .panel ? .keyLip : kind == .ink ? .keyLipInk : .keyLipSignal
    let highlight: BevelLayer.Fill = kind == .panel ? .bevelHighlight : .inkHighlight
    let fill: BevelLayer.Fill = kind == .panel ? .panel : kind == .ink ? .ink : .signal
    if pressed {
      return [
        BevelLayer(fill: lip, left: 0, top: 2, right: 0, bottom: 0),
        BevelLayer(fill: highlight, left: 0, top: 2, right: 0, bottom: 0),
        BevelLayer(fill: fill, left: 0, top: 3, right: 0, bottom: 0),
      ]
    }
    return [
      BevelLayer(fill: lip, left: 0, top: 0, right: 0, bottom: 0),
      BevelLayer(fill: highlight, left: 0, top: 0, right: 0, bottom: 2),
      BevelLayer(fill: fill, left: 0, top: 1, right: 0, bottom: 2),
    ]
  }

  /// A pressed key's label moves down by this much.
  nonisolated static let pressedLabelOffset: CGFloat = 2
}

extension Palette {
  nonisolated func token(for fill: BevelLayer.Fill) -> ColorToken {
    switch fill {
    case .bevelShade: return bevelShade
    case .bevelHighlight: return bevelHighlight
    case .inkHighlight: return inkHighlight
    case .panel: return panel
    case .ink: return ink
    case .signal: return signal
    case .readout: return readout
    case .keyLip: return keyLip
    case .keyLipInk: return keyLipInk
    case .keyLipSignal: return keyLipSignal
    }
  }
}

/// Draws a bevel recipe. Layers are inset from the full frame, each with the radius reduced by its larger inset.
struct BevelStack: View {
  @Environment(\.palette) private var palette
  let layers: [BevelLayer]
  let radius: CGFloat

  var body: some View {
    ZStack {
      ForEach(Array(layers.enumerated()), id: \.offset) { _, layer in
        RoundedRectangle(cornerRadius: max(0, radius - max(layer.top, layer.bottom, layer.left, layer.right)), style: .circular)
          .fill(palette.token(for: layer.fill).color)
          .padding(EdgeInsets(top: layer.top, leading: layer.left, bottom: layer.bottom, trailing: layer.right))
      }
    }
  }
}
