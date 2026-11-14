import SnapshotTesting
import SwiftUI
import XCTest

@testable import STMP

/// D140: the reference lands beside the calling test, and a missing reference fails a plain run.
@MainActor
final class SnapshotHelperSelfTests: XCTestCase {
  private struct Dummy: View {
    var body: some View { Text("snapshot helper").typeRole(.title).padding(Dimens.gutter) }
  }

  /// `__Snapshots__/<this file's name>/` next to this source file.
  private var snapshotDirectory: URL {
    URL(fileURLWithPath: "\(#filePath)").deletingLastPathComponent().appendingPathComponent("__Snapshots__/SnapshotHelperSelfTests")
  }

  private func references(named testName: String) -> [String] {
    ((try? FileManager.default.contentsOfDirectory(atPath: snapshotDirectory.path)) ?? []).filter { $0.hasPrefix(testName) }.sorted()
  }

  private func removeReferences(named testName: String) {
    for f in references(named: testName) { try? FileManager.default.removeItem(at: snapshotDirectory.appendingPathComponent(f)) }
  }

  func testReferenceLandsBesideTheCallingTest() {
    let testName = "selfTestRecordsBesideTheCaller"
    removeReferences(named: testName)
    defer { removeReferences(named: testName) }
    // Record mode `missing` is what `dod.sh --record` passes through SNAPSHOT_TESTING_RECORD.
    _ = StmpSnapshot.check(of: Dummy(), named: "dummy", record: .missing, file: #filePath, testName: testName, line: #line)
    XCTAssertEqual(
      references(named: testName),
      [
        "\(testName).dummy.dark-accessibility1.png", "\(testName).dummy.dark-large.png", "\(testName).dummy.light-accessibility1.png",
        "\(testName).dummy.light-large.png",
      ])
    // With the references on disk a plain run (never) passes.
    XCTAssertEqual(StmpSnapshot.check(of: Dummy(), named: "dummy", record: .never, file: #filePath, testName: testName, line: #line), [])
  }

  func testAMissingReferenceFailsAPlainRun() {
    let testName = "selfTestNoReference"
    removeReferences(named: testName)
    let failures = StmpSnapshot.check(of: Dummy(), named: "absent", record: .never, file: #filePath, testName: testName, line: #line)
    XCTAssertEqual(failures.count, StmpSnapshot.matrix.count, "every cell must fail without a reference")
    XCTAssertTrue(failures.allSatisfy { $0.localizedCaseInsensitiveContains("No reference") }, "\(failures)")
    XCTAssertEqual(references(named: testName), [], "a plain run never writes a reference")
  }

  func testRecordModeComesFromTheEnvironmentAndDefaultsToNever() {
    XCTAssertEqual(StmpSnapshot.recordMode([:]), .never)
    XCTAssertEqual(StmpSnapshot.recordMode(["SNAPSHOT_TESTING_RECORD": "missing"]), .missing)
    XCTAssertEqual(StmpSnapshot.recordMode(["SNAPSHOT_TESTING_RECORD": "bogus"]), .never)
  }

  func testTheMatrixIsLightAndDarkAtLargeAndAccessibility1() {
    XCTAssertEqual(StmpSnapshot.matrix.map(\.name), ["light-large", "light-accessibility1", "dark-large", "dark-accessibility1"])
  }
}
