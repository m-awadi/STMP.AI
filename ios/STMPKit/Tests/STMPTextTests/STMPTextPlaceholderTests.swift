import STMPText
import XCTest

final class UntrustedTextTests: XCTestCase {
  func testPlaceholderEscapesEveryNonASCIIScalar() {
    XCTAssertEqual(UntrustedText.sanitise("a\u{202E}b", escapeWrapGlyphs: false), "a\\u{202E}b")
    XCTAssertEqual(UntrustedText.sanitise("café", escapeWrapGlyphs: true), "caf\\u{E9}")
  }

  func testSystemSurfaceEscapesControlsToo() {
    XCTAssertEqual(UntrustedText.systemSurface("a\nb\tc"), "a\\u{A}b\\u{9}c")
    XCTAssertEqual(UntrustedText.sanitise("a\nb", escapeWrapGlyphs: false), "a\nb")
  }
}
