import STMPCore
import STMPTestSupport
import XCTest

private struct RowsPlugin: Plugin {
  let id = "calendar"
  let displayName = "Calendar"
  func routes() -> [Route] { [] }
  func status() async -> PluginStatus { PluginStatus(summary: "", ready: true) }
}

final class PluginRowsForTests: XCTestCase {
  private let writes: [String: @Sendable (Req) throws -> [ApprovalRow]] = [
    "POST /calendar/events": { _ in [ApprovalRow("TITLE", "x")] },
    "GET /drive/files/{id}/content": { _ in [ApprovalRow("FILE", "f", .mono)] },
  ]

  func testExplicitEntryWinsEvenForAGet() throws {
    let rows = try RowsPlugin().rowsFor(Req(method: "GET", path: "/drive/files/{id}/content"), reads: [], writes: writes)
    XCTAssertEqual(rows, [ApprovalRow("FILE", "f", .mono)])
  }

  func testWriteReturnsItsRows() throws {
    XCTAssertEqual(try RowsPlugin().rowsFor(Req(method: "post", path: "/calendar/events"), reads: [], writes: writes).count, 1)
  }

  func testAGetWithoutAnEntryIsCorrectlyEmpty() throws {
    XCTAssertEqual(try RowsPlugin().rowsFor(Req(method: "GET", path: "/calendar/events"), reads: [], writes: writes), [])
  }

  func testAKnownReadIsEmpty() throws {
    XCTAssertEqual(try RowsPlugin().rowsFor(Req(method: "POST", path: "/q"), reads: ["POST /q"], writes: writes), [])
  }

  func testUnknownWriteThrowsInternalError() {
    XCTAssertThrowsError(try RowsPlugin().rowsFor(Req(method: "POST", path: "/calendar/forgotten"), reads: [], writes: writes)) { e in
      XCTAssertEqual((e as? PluginError)?.code, "internal_error")
    }
  }
}

final class NormalizedIPTests: XCTestCase {
  func testEveryVector() throws {
    let doc = try Fixture.load("core/ip-normalisation.json")
    let vectors = try XCTUnwrap(doc["vectors"]?.arrayValue)
    XCTAssertGreaterThan(vectors.count, 10)
    for v in vectors {
      let input = try XCTUnwrap(v["input"]?.stringValue)
      XCTAssertEqual(NormalizedIP.parse(input)?.rawValue, v["expected"]?.stringValue, "input '\(input)'")
    }
  }

  func testLoopback() {
    XCTAssertTrue(NormalizedIP.parse("::ffff:127.0.0.1")?.isLoopback ?? false)
    XCTAssertFalse(NormalizedIP.parse("100.64.0.1")?.isLoopback ?? true)
  }
}

final class ErrorTableTests: XCTestCase {
  func testUnknownCodeIs500AndKnownCodesMapToStatus() {
    XCTAssertEqual(ErrorTable.status(for: "bad_lines"), 400)
    XCTAssertEqual(ErrorTable.status(for: "secret_locked"), 403)
    XCTAssertEqual(ErrorTable.status(for: "not_supported_on_ios"), 501)
    XCTAssertEqual(ErrorTable.status(for: "approval_timeout"), 408)
    XCTAssertEqual(ErrorTable.status(for: "nothing_like_this"), 500)
  }

  func testOnlyNotSupportedOnIOSIsFlaggedIOS() {
    XCTAssertEqual(ErrorTable.entries.filter { $0.value.platform == "ios" }.map(\.key), ["not_supported_on_ios"])
  }

  // Totality: every `PluginError("code"` literal in the package sources and the app sources is classified.
  func testEveryThrownCodeIsClassified() throws {
    let root = try Fixture.directory().deletingLastPathComponent()
    let walker = try XCTUnwrap(FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil))
    let pattern = try NSRegularExpression(pattern: #"PluginError\(\s*"([a-z_]+)""#)
    var unknown: Set<String> = []
    for case let url as URL in walker where url.pathExtension == "swift" && url.path.contains("/Sources/") {
      let text = try String(contentsOf: url, encoding: .utf8)
      for m in pattern.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
        let code = String(text[Range(m.range(at: 1), in: text)!])
        if !ErrorTable.isClassified(code) { unknown.insert(code) }
      }
    }
    XCTAssertEqual(unknown, [])
  }
}
