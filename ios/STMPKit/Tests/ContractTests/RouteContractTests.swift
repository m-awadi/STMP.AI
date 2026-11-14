import STMPBridge
import STMPCore
import STMPTestSupport
import XCTest

/// The declared routes, the error table and the fixtures agree (D133, D134, D106).
final class RouteContractTests: XCTestCase {
  private func fixtureRoutes() throws -> [[String: JSONValue]] {
    try XCTUnwrap(Fixture.load("routes/routes.json")["routes"]?.arrayValue).compactMap(\.objectValue)
  }

  func testDeclaredTemplatesEqualRoutesJSON() throws {
    let declared = Set(PluginCatalog.declaredRouteTemplates.map(\.key))
    let fixture = Set(try fixtureRoutes().map { "\($0["method"]?.stringValue ?? "") \($0["path"]?.stringValue ?? "")" })
    XCTAssertEqual(fixture.count, 37)
    XCTAssertEqual(declared.count, 37)
    XCTAssertEqual(declared, fixture)
    XCTAssertEqual(PluginCatalog.declaredRouteTemplates.count, 37, "a template is declared twice")
  }

  func testExemptSetIsExactlyAudioAndTts() throws {
    let fixture = try XCTUnwrap(Fixture.load("routes/routes.json")["exempt"]?.arrayValue).compactMap(\.stringValue)
    XCTAssertEqual(Set(fixture), ["POST /audio/{cue}", "POST /tts"])
    XCTAssertEqual(Set(PluginCatalog.declaredRouteTemplates.filter(\.gateExempt).map(\.key)), ["POST /audio/{cue}", "POST /tts"])
  }

  func testEveryDeclaredRouteMatchesItsFlagsFixture() throws {
    let flags = try XCTUnwrap(Fixture.load("routes/route-flags.json")["routes"]?.arrayValue).compactMap(\.objectValue)
    XCTAssertEqual(flags.count, 37)
    let byKey = Dictionary(uniqueKeysWithValues: flags.map { ("\($0["method"]?.stringValue ?? "") \($0["path"]?.stringValue ?? "")", $0) })
    for t in PluginCatalog.declaredRouteTemplates {
      let f = try XCTUnwrap(byKey[t.key], "no route-flags entry for \(t.key)")
      XCTAssertEqual(f["plugin"]?.stringValue, t.plugin, t.key)
      XCTAssertEqual(f["describe"]?.stringValue, t.describe, t.key)
      if f["verb"]?.stringValue == "verbFor" {
        XCTAssertNil(t.verb, t.key)
        XCTAssertEqual(f["fallback"]?.stringValue, t.fallbackVerb.rawValue, t.key)
      } else {
        XCTAssertEqual(f["verb"]?.stringValue, t.verb?.rawValue, t.key)
      }
      XCTAssertEqual(f["auditRequestBody"]?.boolValue, t.auditRequestBody, t.key)
      XCTAssertEqual(f["auditResponseBody"]?.boolValue, t.auditResponseBody, t.key)
      XCTAssertEqual(f["auditResponseTransform"]?.boolValue, t.auditResponseTransform, t.key)
      XCTAssertEqual(f["streamsRequest"]?.boolValue, t.streamsRequest, t.key)
      XCTAssertEqual(f["aiSummary"]?.boolValue, t.aiSummary, t.key)
      XCTAssertEqual(f["gateExempt"]?.boolValue, t.gateExempt, t.key)
      XCTAssertEqual(f["holdExempt"]?.boolValue, t.holdExempt, t.key)
      XCTAssertEqual(f["needsVault"]?.boolValue, t.needsVault, t.key)
    }
  }

  func testNotificationsAreTheOnly501Routes() throws {
    let overlay = try XCTUnwrap(Fixture.load("routes/routes-ios.json")["routes"]?.arrayValue).compactMap(\.objectValue)
    let keys = Set(overlay.compactMap { $0["key"]?.stringValue })
    XCTAssertEqual(Set(PluginCatalog.declaredRouteTemplates.filter { $0.unsupportedOnIOS != nil }.map(\.key)), keys)
    XCTAssertEqual(keys.count, 3)
    XCTAssertTrue(keys.allSatisfy { $0.contains("/notifications") })
  }

  func testRegistrationOrderIsAndroidsAndEveryPluginIsRegisteredOnce() {
    let plugins = PluginCatalog.make(PluginDeps.inert())
    XCTAssertEqual(plugins.map(\.id), PluginCatalog.registrationOrder)
    XCTAssertEqual(plugins.first { $0.id == "notifications" }?.enabledByDefault, true, "D100")
  }

  func testEveryPlaceholderRouteHandlerThrowsNotBuiltYet() async throws {
    let registry = PluginRegistry(plugins: PluginCatalog.make(PluginDeps.inert()))
    XCTAssertEqual(registry.templates.count, 37)
    for t in registry.templates {
      let route = try XCTUnwrap(
        registry.match(method: t.method, path: t.path.replacingOccurrences(of: "{", with: "").replacingOccurrences(of: "}", with: "")))
      do {
        _ = try await route.route.handler(Req(method: t.method, path: t.path))
        XCTFail("\(t.key) must throw")
      } catch let e as PluginError { XCTAssertEqual(e.code, "internal_error") }
    }
  }
}

final class ErrorTableContractTests: XCTestCase {
  func testErrorTableEqualsTheFixture() throws {
    let rows = try XCTUnwrap(Fixture.load("errors/error-codes.json")["codes"]?.arrayValue).compactMap(\.objectValue)
    XCTAssertEqual(Set(rows.compactMap { $0["code"]?.stringValue }), ErrorTable.codes)
    for r in rows {
      let code = try XCTUnwrap(r["code"]?.stringValue)
      let entry = try XCTUnwrap(ErrorTable.entries[code])
      XCTAssertEqual(r["status"]?.intValue, entry.status, code)
      XCTAssertEqual(r["listed"]?.boolValue, entry.listedOnAndroid, code)
      XCTAssertEqual(r["platform"]?.stringValue, entry.platform, code)
    }
  }

  func testTheFourUnlistedCodesAreFlagged() {
    for code in ["approval_timeout", "caller_registry_unavailable", "audit_unavailable", "logcat_failed"] {
      XCTAssertEqual(ErrorTable.entries[code]?.listedOnAndroid, false, code)
    }
  }
}

final class FixtureSourceContractTests: XCTestCase {
  private static let grammar = try! NSRegularExpression(pattern: #"^(android:[^:]+:\d+@[0-9a-f]{7,40}|ios:[DQ]\d+)$"#)

  private func sources(in node: JSONValue) -> [String] {
    switch node {
    case .object(let o): return o.flatMap { k, v in k == "_source" ? [v.stringValue ?? "<not a string>"] : sources(in: v) }
    case .array(let a): return a.flatMap(sources)
    default: return []
    }
  }

  func testEveryFixtureHasValidSources() throws {
    let files = try Fixture.allJSON()
    XCTAssertGreaterThanOrEqual(files.count, 5)
    for rel in files where !rel.contains("/vendored/") {
      let doc = try Fixture.load(rel)
      let all = sources(in: doc)
      XCTAssertFalse(all.isEmpty, "\(rel): no _source")
      for s in all {
        XCTAssertNotNil(Self.grammar.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)), "\(rel): bad _source '\(s)'")
      }
    }
  }
}
