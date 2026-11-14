import SwiftUI

/// The body of every T0 feature placeholder. The owning ticket replaces the feature file, not this view.
struct ScreenPlaceholder: View {
  @Environment(\.palette) private var palette
  let title: String
  var detail: String = "Not built yet."

  var body: some View {
    VStack(alignment: .leading, spacing: Dimens.spaceS) {
      Text(title).typeRole(.title).foregroundStyle(palette.ink.color)
      Text(detail).typeRole(.body).foregroundStyle(palette.etched.color)
      Spacer(minLength: 0)
    }
    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    .padding(.horizontal, Dimens.gutter)
    .padding(.top, Dimens.spaceXL)
    .background(palette.body.color.ignoresSafeArea())
    .accessibilityElement(children: .combine)
  }
}
