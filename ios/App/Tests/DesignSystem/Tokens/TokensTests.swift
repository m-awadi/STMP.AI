import SwiftUI
import UIKit
import XCTest

@testable import STMP

final class TokensPaletteTests: XCTestCase {
  func testEveryRoleExistsInBothThemes() {
    XCTAssertEqual(Palette.roleNames.count, 44)
    XCTAssertEqual(Set(Palette.roleNames).count, 44)
    for name in Palette.roleNames {
      XCTAssertNotNil(Palette.light.token(name), name)
      XCTAssertNotNil(Palette.dark.token(name), name)
    }
    XCTAssertFalse(Palette.light.isDark)
    XCTAssertTrue(Palette.dark.isDark)
  }

  func testKnownValues() {
    XCTAssertEqual(Palette.light.body.argb, 0xFFE4_E2DD)
    XCTAssertEqual(Palette.dark.body.argb, 0xFF14_1416)
    XCTAssertEqual(Palette.light.signal.argb, 0xFFFF_4F1A)
    XCTAssertEqual(Palette.dark.bevelHighlight.argb, 0x0AFF_FFFF, "alpha roles keep their alpha")
    XCTAssertEqual(Palette.light.scrim.argb, 0x7300_0000)
  }

  func testCurrentForSchemeAndContrast() {
    XCTAssertEqual(Palette.current(for: .light), Palette.light)
    XCTAssertEqual(Palette.current(for: .dark), Palette.dark)
    XCTAssertEqual(Palette.current(for: .light, increasedContrast: true).etched, Palette.light.ink)
  }
}

final class TokensTypeTests: XCTestCase {
  func testSeventeenRolesAndTheSizeFloor() {
    XCTAssertEqual(TypeRole.allCases.count, 17)
    for role in TypeRole.allCases {
      XCTAssertGreaterThanOrEqual(role.spec.size, TypeRole.sizeFloor, role.rawValue)
      XCTAssertGreaterThanOrEqual(role.spec.line, role.spec.size, role.rawValue)
    }
  }

  func testEveryFaceIsRegisteredFromTheBundle() {
    for role in TypeRole.allCases { XCTAssertNotNil(UIFont(name: role.spec.face, size: role.spec.size), role.spec.face) }
    XCTAssertNotNil(UIFont(name: TypeRole.tabSelectedFace, size: 12))
  }

  func testSpecimenValuesFromTheTable() {
    XCTAssertEqual(TypeRole.display.spec.size, 36)
    XCTAssertEqual(TypeRole.display.spec.trackingEm, -0.04)
    XCTAssertTrue(TypeRole.key.spec.caps)
    XCTAssertEqual(TypeRole.label.spec.trackingEm, 0.14)
    XCTAssertEqual(TypeRole.wordmark.spec.trackingEm, 0.2)
  }
}

final class TokensMotionAndHapticsTests: XCTestCase {
  func testSpringResponseMatchesTheTable() {
    XCTAssertEqual(Motion.rocker.response, 0.115, accuracy: 0.001)
    XCTAssertEqual(Motion.holdReturn.response, 0.162, accuracy: 0.001)
    XCTAssertEqual(Motion.punchSlam.response, 0.126, accuracy: 0.001)
  }

  func testSpringStepResponseSettlesAtOne() {
    XCTAssertEqual(Motion.springAt(0, Motion.rocker), 0)
    XCTAssertEqual(Motion.springAt(1, Motion.rocker), 1, accuracy: 0.001)
    XCTAssertEqual(Motion.springAt(1, Motion.holdReturn), 1, accuracy: 0.001)
    XCTAssertGreaterThan(Motion.springAt(0.05, Motion.punchSlam), 0.5)
  }

  func testSafetyDurationsAreFixed() {
    XCTAssertEqual(Motion.holdToApproveMs, 1200)
    XCTAssertEqual(Motion.receiptShowMs, 1500)
  }

  @MainActor
  func testHapticsGoThroughTheInjectedSink() {
    final class Recorder: HapticSink, @unchecked Sendable {
      // SAFETY: only touched on the main actor by this test.
      nonisolated(unsafe) var events: [HapticEvent] = []
      func emit(_ event: HapticEvent) { events.append(event) }
    }
    let original = Haptics.sink
    defer { Haptics.sink = original }
    let recorder = Recorder()
    Haptics.sink = recorder
    Haptics.perform(.keyDown)
    Haptics.perform(.holdConfirm)
    XCTAssertEqual(recorder.events, [.keyDown, .holdConfirm])
  }

  func testDimensAnchors() {
    XCTAssertEqual(Dimens.minTarget, 48)
    XCTAssertEqual(Dimens.gutter, 20)
    XCTAssertEqual(Dimens.holdKey, 92)
    XCTAssertEqual(Dimens.disabledOpacity, 0.38)
  }

  func testBevelRecipesMatchTheTable() {
    XCTAssertEqual(BevelRecipe.module.count, 3)
    XCTAssertEqual(BevelRecipe.key(.panel, pressed: false)[2], BevelLayer(fill: .panel, left: 0, top: 1, right: 0, bottom: 2))
    XCTAssertEqual(BevelRecipe.key(.signal, pressed: true)[0].fill, .keyLipSignal)
    XCTAssertEqual(BevelRecipe.key(.signal, pressed: true)[0].top, 2)
  }
}
