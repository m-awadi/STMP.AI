import STMPCore
import XCTest

@testable import STMP

/// D69, D111, D144: the hosted test process launches the app with the inert environment. A host launch writes nothing outside
/// its temporary root, never starts the bridge and never touches the Keychain or the real defaults domain.
@MainActor
final class InertHostTests: XCTestCase {
  private func applicationSupportSTMP() -> URL {
    let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    return base.appendingPathComponent("STMP")
  }

  func testTheHostProcessIsDetectedAsAnXCTestHost() {
    #if DEBUG
    let o = LaunchOptions.parse(arguments: ["STMP"], environment: ["XCTestConfigurationFilePath": "/tmp/x.xctestconfiguration"])
    XCTAssertTrue(o.isXCTestHost)
    #endif
    XCTAssertTrue(LaunchOptions.current.isXCTestHost, "the unit-test host runs with XCTestConfigurationFilePath set")
  }

  func testInertEnvironmentNeverStartsTheBridgeAndWritesNothingOutsideItsRoot() async throws {
    let before = FileManager.default.fileExists(atPath: applicationSupportSTMP().path)
    let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("stmp-inert-test-\(UUID().uuidString)")
    defer { try? FileManager.default.removeItem(at: tmp) }
    let env = AppEnvironment.inert(storageRoot: StorageRoot(url: tmp))
    await env.lifecycle.launch()
    await env.lifecycle.launch()
    XCTAssertFalse(env.bridge.state.current.serverUp)
    XCTAssertThrowsError(try env.policy.current(), "every store read fails closed")
    XCTAssertEqual(env.vault.state.current, .absent)
    XCTAssertFalse(env.gate.canPresent)
    XCTAssertFalse(env.preferences.hasStartedOnce)
    XCTAssertEqual(FileManager.default.fileExists(atPath: applicationSupportSTMP().path), before, "no Application Support/STMP created")
    XCTAssertFalse(FileManager.default.fileExists(atPath: tmp.path), "the inert environment does not even create its own root")
  }

  func testInertEnvironmentUsesAFixedClockAndAUTCCalendar() {
    let env = AppEnvironment.inert(storageRoot: StorageRoot(url: FileManager.default.temporaryDirectory))
    XCTAssertEqual(env.now(), Date(timeIntervalSince1970: 1_767_268_800))
    XCTAssertEqual(env.calendar.timeZone.secondsFromGMT(), 0)
  }
}
