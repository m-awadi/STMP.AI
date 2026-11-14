import STMPCore
import STMPTestSupport
import XCTest

final class JSONValueTests: XCTestCase {
  func testRoundTripAndOrderInsensitiveEquality() throws {
    let a = try JSONValue.parse(Data(#"{"a":1,"b":[true,null,"x"],"c":{"d":2.5}}"#.utf8))
    let b = try JSONValue.parse(Data(#"{"c":{"d":2.5},"b":[true,null,"x"],"a":1}"#.utf8))
    XCTAssertEqual(a, b)
    XCTAssertEqual(try JSONValue.parse(a.serialized()), a)
  }

  func testIntAndDoubleCompareByValue() {
    XCTAssertEqual(JSONValue.int(1), .double(1))
    XCTAssertNotEqual(JSONValue.int(1), .double(1.5))
  }

  func testSerializedKeysAreSorted() throws {
    let text = String(decoding: try JSONValue.object(["b": .int(1), "a": .int(2)]).serialized(), as: UTF8.self)
    XCTAssertEqual(text, #"{"a":2,"b":1}"#)
  }
}

final class AuditRecordTests: XCTestCase {
  func testToJSONHasAndroidKeys() throws {
    let r = AuditRecord(
      id: "1", ts: 5, outcome: .approved, label: "l", plugin: "p", verb: "READ", origin: "o", remote: "r", requester: "q", route: "GET /x",
      method: "GET", path: "/x", summary: "s", responseStatus: 200, decision: AuditDecision(mode: "PARANOID", source: .rule), durationMs: 3,
      truncated: AuditTruncation(requestBody: 9), noise: false, appVersionName: "0.1.0", appVersionCode: 1)
    let keys = try XCTUnwrap(r.toJSON().objectValue).keys.sorted()
    XCTAssertEqual(
      keys,
      [
        "app", "decision", "durationMs", "error", "id", "label", "method", "noise", "origin", "outcome", "path", "plugin", "query",
        "redacted",
        "remote", "requestBody", "requestHeaders", "requester", "responseBody", "responseStatus", "route", "summary", "truncated", "ts",
        "verb",
      ])
    XCTAssertEqual(r.toJSON()["error"], .null)
    XCTAssertEqual(r.toJSON()["truncated"], .object(["requestBody": .int(9)]))
    XCTAssertEqual(r.toJSON()["decision"]?["globalMode"], .null)
  }
}

final class AtomicFileTests: XCTestCase {
  func testWriteReplacesAndLeavesNoTempFile() throws {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("stmp-atomic-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: dir) }
    let url = dir.appendingPathComponent("a.json")
    try AtomicFile.write(Data("one".utf8), to: url)
    try AtomicFile.write(Data("two".utf8), to: url, protection: .completeUntilFirstUserAuthentication)
    XCTAssertEqual(try String(contentsOf: url, encoding: .utf8), "two")
    XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: dir.path), ["a.json"])
  }

  func testStorageRootPrepareCreatesAndExcludesFromBackup() throws {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("stmp-root-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: dir) }
    try StorageRoot(url: dir).prepare()
    XCTAssertEqual(try dir.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
  }
}

final class SecretBytesTests: XCTestCase {
  func testWipeZeroesAndRedacts() {
    let s = SecretBytes("hunter2")
    var copy: [UInt8] = []
    s.withBytes { copy = Array($0) }
    XCTAssertEqual(copy, Array("hunter2".utf8))
    XCTAssertFalse("\(s)".contains("hunter2"))
    XCTAssertFalse("\(String(reflecting: s))".contains("hunter2"))
    s.wipe()
    XCTAssertTrue(s.isWiped)
    var after: [UInt8] = [1]
    s.withBytes { after = Array($0) }
    XCTAssertTrue(after.isEmpty)
  }
}

final class FixtureTests: XCTestCase {
  func testLoadWalksUpToTheContractDirectory() throws {
    let v = try Fixture.load("core/ip-normalisation.json")
    XCTAssertNotNil(v["vectors"]?.arrayValue)
  }

  func testMissingFixtureThrows() {
    XCTAssertThrowsError(try Fixture.load("nope/missing.json"))
  }

  func testEveryFixtureHasASource() throws {
    for rel in try Fixture.allJSON() where !rel.contains("/vendored/") {
      let doc = try Fixture.load(rel)
      if doc["_source"]?.stringValue != nil { continue }
      let rows = doc.objectValue?.values.compactMap(\.arrayValue).first ?? doc.arrayValue ?? []
      XCTAssertFalse(rows.isEmpty, "\(rel): no _source and no entries")
      for (i, row) in rows.enumerated() { XCTAssertNotNil(row["_source"]?.stringValue, "\(rel)[\(i)]: missing _source") }
    }
  }
}
