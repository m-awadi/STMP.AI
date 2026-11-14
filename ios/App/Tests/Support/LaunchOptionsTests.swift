import XCTest

@testable import STMP

#if DEBUG
final class LaunchOptionsTests: XCTestCase {
  func testNoOptionsIsAllDefault() {
    XCTAssertEqual(LaunchOptions.parse(arguments: ["STMP"], environment: [:]), .none)
  }

  func testEveryOptionParses() {
    let o = LaunchOptions.parse(
      arguments: ["STMP", "-STMPTestBind", "loopback", "-STMPResetState", "-STMPSkipOnboarding", "-STMPStorageRoot", "/tmp/x"],
      environment: ["STMP_TEST_PORT": "0"])
    XCTAssertTrue(o.testBindLoopback)
    XCTAssertTrue(o.resetState)
    XCTAssertTrue(o.skipOnboarding)
    XCTAssertEqual(o.storageRoot?.path, "/tmp/x")
    XCTAssertEqual(o.testPort, 0)
  }

  func testInvalidPortAndBindAreIgnored() {
    let o = LaunchOptions.parse(arguments: ["STMP", "-STMPTestBind", "lan"], environment: ["STMP_TEST_PORT": "70000"])
    XCTAssertFalse(o.testBindLoopback)
    XCTAssertNil(o.testPort)
  }

  func testSeedPolicyMissingValueIsAVisibleError() {
    let o = LaunchOptions.parse(arguments: ["STMP", "-STMPSeedPolicy"], environment: [:])
    XCTAssertNotNil(o.seedPolicyError)
  }

  func testSeedPolicyMissingFileIsAVisibleError() {
    let o = LaunchOptions.parse(arguments: ["STMP", "-STMPSeedPolicy", "/nonexistent/policy.json"], environment: [:])
    XCTAssertTrue(o.seedPolicyError?.contains("does not exist") ?? false)
  }

  func testSeedPolicyRelativePathAndInvalidJSONAreVisibleErrors() throws {
    XCTAssertNotNil(LaunchOptions.parse(arguments: ["STMP", "-STMPSeedPolicy", "relative.json"], environment: [:]).seedPolicyError)
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("bad-\(UUID().uuidString).json")
    defer { try? FileManager.default.removeItem(at: url) }
    try Data("{not json".utf8).write(to: url)
    XCTAssertTrue(
      LaunchOptions.parse(arguments: ["STMP", "-STMPSeedPolicy", url.path], environment: [:]).seedPolicyError?.contains("not valid JSON")
        ?? false)
  }

  func testSeedPolicyValidFileHasNoError() throws {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("ok-\(UUID().uuidString).json")
    defer { try? FileManager.default.removeItem(at: url) }
    try Data(#"{"global":"WHITELIST"}"#.utf8).write(to: url)
    let o = LaunchOptions.parse(arguments: ["STMP", "-STMPSeedPolicy", url.path], environment: [:])
    XCTAssertNil(o.seedPolicyError)
    XCTAssertEqual(o.seedPolicyPath, url.path)
  }

  func testXCTestHostDetectionExcludesUITestOptions() {
    XCTAssertTrue(LaunchOptions.parse(arguments: ["STMP"], environment: ["XCTestConfigurationFilePath": "x"]).isXCTestHost)
    XCTAssertFalse(
      LaunchOptions.parse(arguments: ["STMP", "-STMPResetState"], environment: ["XCTestConfigurationFilePath": "x"]).isXCTestHost)
  }

  func testResetWipesTheRootAndToleratesKeychainEntitlementErrors() throws {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent("stmp-reset-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    try Data("x".utf8).write(to: root.appendingPathComponent("a"))
    let domain = "com.mido.stmp.test.\(UUID().uuidString)"
    UserDefaults(suiteName: domain)?.set(true, forKey: "k")
    let report = StateReset.perform(root: root, defaultsDomain: domain)
    XCTAssertTrue(report.removedRoot)
    XCTAssertFalse(FileManager.default.fileExists(atPath: root.path))
    XCTAssertNil(UserDefaults(suiteName: domain)?.object(forKey: "k"))
    XCTAssertTrue(report.keychainStatuses.allSatisfy(StateReset.isTolerated), "\(report.keychainStatuses)")
  }
}
#endif

final class AppPreferencesTests: XCTestCase {
  func testDefaultsAndColdLaunchRule() {
    let p = AppPreferences.ephemeral()
    XCTAssertTrue(p.startOnLaunch)
    XCTAssertFalse(p.hasStartedOnce)
    XCTAssertFalse(p.shouldStartOnColdLaunch)
    p.hasStartedOnce = true
    XCTAssertTrue(p.shouldStartOnColdLaunch)
    p.startOnLaunch = false
    XCTAssertFalse(p.shouldStartOnColdLaunch)
  }
}
