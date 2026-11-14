import XCTest

/// The Info.plist is written by T0 so no later ticket edits it (PLAN 1.2). These assertions keep it that way (D55, D81, Q7).
final class InfoPlistGuardTests: XCTestCase {
  private var info: [String: Any] { Bundle.main.infoDictionary ?? [:] }

  func testPortraitOnlyAndIPhoneOnly() {
    XCTAssertEqual(info["UISupportedInterfaceOrientations"] as? [String], ["UIInterfaceOrientationPortrait"])
    XCTAssertNil(info["UISupportedInterfaceOrientations~ipad"])
    XCTAssertEqual(info["UIDeviceFamily"] as? [Int], [1])
  }

  func testOneSceneOnly() throws {
    let manifest = try XCTUnwrap(info["UIApplicationSceneManifest"] as? [String: Any])
    XCTAssertEqual(manifest["UIApplicationSupportsMultipleScenes"] as? Bool, false)
  }

  func testNoBackgroundModes() {
    XCTAssertNil(info["UIBackgroundModes"], "Q7: no background tricks")
  }

  func testNoAppTransportSecurityException() {
    XCTAssertNil(info["NSAppTransportSecurity"], "D81: only the UI-test runner relaxes ATS for loopback")
  }

  func testUsageStringsAndPrivacyKeys() {
    for key in ["NSFaceIDUsageDescription", "NSContactsUsageDescription", "NSLocalNetworkUsageDescription"] {
      XCTAssertFalse((info[key] as? String ?? "").isEmpty, key)
    }
    XCTAssertEqual(info["UIFileSharingEnabled"] as? Bool, false)
    XCTAssertEqual(info["ITSAppUsesNonExemptEncryption"] as? Bool, false)
  }

  func testFontsAndGoogleKeys() {
    XCTAssertEqual((info["UIAppFonts"] as? [String])?.count, 7)
    XCTAssertNotNil(info["STMPGoogleClientID"], "present but empty without Secrets.xcconfig")
    let urlTypes = info["CFBundleURLTypes"] as? [[String: Any]]
    XCTAssertEqual((urlTypes?.first?["CFBundleURLSchemes"] as? [String])?.count, 1)
  }

  func testBundleIdentity() {
    XCTAssertEqual(Bundle.main.bundleIdentifier, "com.mido.stmp")
    XCTAssertEqual(info["CFBundleDisplayName"] as? String, "STMP")
  }
}
