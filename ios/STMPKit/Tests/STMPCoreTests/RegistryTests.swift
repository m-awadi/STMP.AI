import STMPCore
import STMPTestSupport
import XCTest

final class RegistryTests: XCTestCase {
  private func plugin(_ id: String, _ routes: [(String, String)]) -> FakePlugin {
    FakePlugin(id: id, templates: routes.map { RouteTemplate(plugin: id, $0.0, $0.1, "d", .read) })
  }

  func testMatchesLiteralAndCapturesSegments() throws {
    let r = PluginRegistry(plugins: [plugin("c", [("GET", "/calendar/events/{id}/move")])])
    let m = try XCTUnwrap(r.match(method: "GET", path: "/calendar/events/abc/move"))
    XCTAssertEqual(m.pathParams, ["id": "abc"])
  }

  func testSegmentCountMustMatch() {
    let r = PluginRegistry(plugins: [plugin("c", [("GET", "/a/{id}")])])
    XCTAssertNil(r.match(method: "GET", path: "/a"))
    XCTAssertNil(r.match(method: "GET", path: "/a/b/c"))
  }

  func testMethodIsCaseInsensitiveAndTrailingSlashIgnored() {
    let r = PluginRegistry(plugins: [plugin("c", [("POST", "/tts")])])
    XCTAssertNotNil(r.match(method: "post", path: "/tts/"))
    XCTAssertNil(r.match(method: "GET", path: "/tts"))
  }

  func testFirstMatchWinsSoALiteralRegisteredFirstShadowsACapture() throws {
    let literalFirst = PluginRegistry(plugins: [plugin("g", [("POST", "/gmail/messages/send"), ("POST", "/gmail/messages/{id}")])])
    XCTAssertEqual(try XCTUnwrap(literalFirst.match(method: "POST", path: "/gmail/messages/send")).route.path, "/gmail/messages/send")
    let captureFirst = PluginRegistry(plugins: [plugin("g", [("POST", "/gmail/messages/{id}"), ("POST", "/gmail/messages/send")])])
    XCTAssertEqual(try XCTUnwrap(captureFirst.match(method: "POST", path: "/gmail/messages/send")).route.path, "/gmail/messages/{id}")
  }

  func testUnknownPathIsUnrouted() {
    XCTAssertNil(PluginRegistry(plugins: []).match(method: "GET", path: "/nope"))
  }

  func testTemplatesKeepRegistrationOrder() {
    let r = PluginRegistry(plugins: [plugin("a", [("GET", "/x")]), plugin("b", [("GET", "/y")])])
    XCTAssertEqual(r.templates.map(\.path), ["/x", "/y"])
  }
}
