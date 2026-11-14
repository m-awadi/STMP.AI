import SnapshotTesting
import SwiftUI
import UIKit
import XCTest

@testable import STMP

/// The one snapshot entry point (D46, D140). Tickets never call `assertSnapshot` themselves.
///
/// - Matrix: light and dark, each at `.large` and `.accessibility1` Dynamic Type, on the pinned device.
/// - Strategy: `.image(precision: 0.995, perceptualPrecision: 0.98, layout: .device(config: pinned))`.
/// - References land in `__Snapshots__/` beside the calling test, because `file`, `testName` and `line` are forwarded.
/// - Record mode comes from `SNAPSHOT_TESTING_RECORD`: `never` unless `dod.sh --record` passes `missing`. A missing reference
///   therefore fails a plain run.
/// - The clock is fixed at 2026-01-01T12:00:00Z (`StmpSnapshot.fixedNow`); animations and the caret are off.
enum StmpSnapshot {
  static let fixedNow = Date(timeIntervalSince1970: 1_767_268_800)

  /// iPhone 17 portrait: 402 x 874 pt, 3x, Dynamic Island safe area.
  static let device = ViewImageConfig(
    safeArea: UIEdgeInsets(top: 62, left: 0, bottom: 34, right: 0), size: CGSize(width: 402, height: 874),
    traits: UITraitCollection(displayScale: 3))

  struct Cell: Sendable {
    var name: String
    var style: UIUserInterfaceStyle
    var content: UIContentSizeCategory
  }

  static let matrix: [Cell] = [
    Cell(name: "light-large", style: .light, content: .large),
    Cell(name: "light-accessibility1", style: .light, content: .accessibilityMedium),
    Cell(name: "dark-large", style: .dark, content: .large),
    Cell(name: "dark-accessibility1", style: .dark, content: .accessibilityMedium),
  ]

  /// `never` unless the environment says otherwise.
  static func recordMode(_ environment: [String: String] = ProcessInfo.processInfo.environment) -> SnapshotTestingConfiguration.Record {
    switch environment["SNAPSHOT_TESTING_RECORD"] {
    case "missing": return .missing
    case "all": return .all
    case "failed": return .failed
    default: return .never
    }
  }

  /// Verifies every cell of the matrix and returns the failure messages (empty when all match). `assertStmpSnapshot` turns them
  /// into test failures; the helper's own self-tests inspect them.
  @MainActor
  static func check<V: View>(
    of view: V, named name: String? = nil, record: SnapshotTestingConfiguration.Record, file: StaticString, testName: String, line: UInt
  ) -> [String] {
    UIView.setAnimationsEnabled(false)
    UITextField.appearance().tintColor = .clear  // caret blink off
    var failures: [String] = []
    for cell in matrix {
      let traits = UITraitCollection { mutable in
        mutable.userInterfaceStyle = cell.style
        mutable.preferredContentSizeCategory = cell.content
      }
      let strategy = Snapshotting<AnyView, UIImage>.image(
        precision: 0.995, perceptualPrecision: 0.98, layout: .device(config: device), traits: traits)
      let label = [name, cell.name].compactMap { $0 }.joined(separator: ".")
      if let failure = verifySnapshot(
        of: AnyView(StmpSnapshotRoot(content: view)), as: strategy, named: label, record: record, timeout: 10, file: file,
        testName: testName, line: line)
      {
        failures.append(failure)
      }
    }
    return failures
  }
}

/// Resolves the palette from the colour scheme the way the app does, and fills the page.
private struct StmpSnapshotRoot<Content: View>: View {
  let content: Content
  var body: some View {
    PaletteReader {
      PaletteBackground { content }
    }
  }
}

private struct PaletteBackground<Content: View>: View {
  @Environment(\.palette) private var palette
  let content: Content
  init(@ViewBuilder content: () -> Content) { self.content = content() }
  var body: some View {
    content.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading).background(palette.body.color)
  }
}

/// Snapshot assertion over the whole matrix. Forwards the caller's `#filePath`, `#function` and `#line` (D140).
@MainActor
func assertStmpSnapshot<V: View>(
  of view: V, named name: String? = nil, file: StaticString = #filePath, testName: String = #function, line: UInt = #line
) {
  for failure in StmpSnapshot.check(of: view, named: name, record: StmpSnapshot.recordMode(), file: file, testName: testName, line: line) {
    XCTFail(failure, file: file, line: line)
  }
}
