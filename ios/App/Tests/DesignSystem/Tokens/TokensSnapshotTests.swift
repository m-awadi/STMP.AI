import SwiftUI
import XCTest

@testable import STMP

/// Token snapshots (D46): light and dark, `.large` and `.accessibility1`, through the T0 helper.
@MainActor
final class TokensSnapshotTests: XCTestCase {
  func testPaletteSwatches() {
    assertStmpSnapshot(of: PaletteSwatches())
  }

  func testTypeSpecimens() {
    assertStmpSnapshot(of: TypeSpecimens())
  }

  func testBevelRecipes() {
    assertStmpSnapshot(of: BevelSpecimens())
  }
}

private struct PaletteSwatches: View {
  @Environment(\.palette) private var palette
  private let roles = ["body", "panel", "sunken", "readout", "ink", "signal", "okSurface", "cautionSurface", "signalSurface", "tapePaper"]
  var body: some View {
    VStack(alignment: .leading, spacing: Dimens.spaceS) {
      ForEach(roles, id: \.self) { role in
        HStack(spacing: Dimens.spaceM) {
          RoundedRectangle(cornerRadius: Dimens.radiusVerb, style: .circular)
            .fill((palette.token(role) ?? palette.ink).color)
            .overlay(RoundedRectangle(cornerRadius: Dimens.radiusVerb, style: .circular).stroke(palette.outline.color, lineWidth: 1))
            .frame(width: 56, height: 28)
          Text(role).typeRole(.readout).foregroundStyle(palette.ink.color)
        }
      }
    }
    .padding(Dimens.gutter)
  }
}

private struct TypeSpecimens: View {
  @Environment(\.palette) private var palette
  var body: some View {
    VStack(alignment: .leading, spacing: Dimens.spaceXS) {
      ForEach(TypeRole.allCases, id: \.self) { role in
        Text(role == .display ? ":8787" : role.rawValue).typeRole(role).foregroundStyle(palette.ink.color)
      }
    }
    .padding(Dimens.gutter)
  }
}

private struct BevelSpecimens: View {
  @Environment(\.palette) private var palette
  var body: some View {
    VStack(spacing: Dimens.spaceL) {
      BevelStack(layers: BevelRecipe.module, radius: Dimens.radiusModule).frame(height: 64)
      BevelStack(layers: BevelRecipe.key(.panel, pressed: false), radius: Dimens.radiusKey).frame(height: Dimens.keyHeight)
      BevelStack(layers: BevelRecipe.key(.ink, pressed: true), radius: Dimens.radiusKey).frame(height: Dimens.keyHeight)
      BevelStack(layers: BevelRecipe.key(.signal, pressed: false), radius: Dimens.radiusKey).frame(height: Dimens.keyHeight)
    }
    .padding(Dimens.gutter)
  }
}
