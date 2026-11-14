import STMPCore
import STMPTestSupport
import XCTest

@testable import STMP

/// Port of Android `ContrastTest` (parity 0.52.1 R2: dark-mode token contrast). The pairs are the fixture
/// `contract/tokens/contrast-pairs.json`, transcribed from the Android test. WCAG: 4.5 for text, 3.0 for UI parts and glyphs.
final class TokensContrastTests: XCTestCase {
  private func luminance(_ c: ColorToken) -> Double {
    func ch(_ v: Double) -> Double { v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4) }
    return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
  }

  private func ratio(_ fg: ColorToken, _ bg: ColorToken) -> Double {
    let a = luminance(fg)
    let b = luminance(bg)
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
  }

  private func failures(for palette: Palette, theme: String) throws -> [String] {
    let pairs = try XCTUnwrap(Fixture.load("tokens/contrast-pairs.json")["pairs"]?.arrayValue)
    XCTAssertGreaterThanOrEqual(pairs.count, 50)
    var out: [String] = []
    for p in pairs {
      if p["darkOnly"]?.boolValue == true && !palette.isDark { continue }
      let name = try XCTUnwrap(p["name"]?.stringValue)
      let fg = try XCTUnwrap(palette.token(try XCTUnwrap(p["fg"]?.stringValue)), name)
      var bg = try XCTUnwrap(palette.token(try XCTUnwrap(p["bg"]?.stringValue)), name)
      if let over = p["bgOver"] {
        let top = try XCTUnwrap(palette.token(try XCTUnwrap(over["fg"]?.stringValue)))
        let under = try XCTUnwrap(palette.token(try XCTUnwrap(over["bg"]?.stringValue)))
        bg = Palette.over(top, under, alpha: try XCTUnwrap(over["alpha"]?.intValue))
      }
      let minimum = try XCTUnwrap(
        p["min"].flatMap { v -> Double? in
          v.intValue.map(Double.init)
            ?? {
              if case .double(let d) = v { return d }
              return nil
            }()
        })
      let r = ratio(fg, bg)
      if r + 1e-9 < minimum { out.append(String(format: "%@ (%@): %.2f < %.1f", name, theme, r, minimum)) }
    }
    return out
  }

  func testLightPairsMeetTheirMinimum() throws { XCTAssertEqual(try failures(for: .light, theme: "light"), []) }
  func testDarkPairsMeetTheirMinimum() throws { XCTAssertEqual(try failures(for: .dark, theme: "dark"), []) }

  func testIncreasedContrastNeverLowersASecondaryTextPair() {
    for base in [Palette.light, Palette.dark] {
      let boosted = base.increasedContrast()
      XCTAssertGreaterThanOrEqual(ratio(boosted.etched, boosted.body), ratio(base.etched, base.body))
      XCTAssertGreaterThanOrEqual(ratio(boosted.outline, boosted.panel), ratio(base.outline, base.panel))
    }
  }

  func testOverBlendRoundsLikeAndroid() {
    let blended = Palette.over(Palette.light.ink, Palette.light.body, alpha: 0x38)
    XCTAssertEqual(blended.alpha, 1)
    XCTAssertGreaterThan(blended.red, Palette.light.ink.red)
    XCTAssertLessThan(blended.red, Palette.light.body.red)
  }
}
