import STMPCore
import XCTest

final class StorageRootTests: XCTestCase {
  func testPrepareCreatesTheRootExcludedFromBackupWithItsProtectionClass() throws {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("stmp-root-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: url) }
    let root = StorageRoot(url: url)
    try root.prepare()
    XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
    XCTAssertEqual(try url.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
    // Read back from disk. The simulator may not report a class; when it does it must be complete-until-first-unlock (D94).
    if let cls = root.protectionClass() { XCTAssertEqual(cls, .completeUntilFirstUserAuthentication) }
  }

  func testAtomicWriteInsideTheRoot() throws {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("stmp-root-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: url) }
    let root = StorageRoot(url: url)
    try root.prepare()
    try AtomicFile.write(Data("x".utf8), to: root.appendingPathComponent("policy.json"), protection: .completeUntilFirstUserAuthentication)
    XCTAssertEqual(try String(contentsOf: root.appendingPathComponent("policy.json"), encoding: .utf8), "x")
  }
}
