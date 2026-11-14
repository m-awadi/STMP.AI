import STMPCore
import XCTest

@testable import STMP

/// D90, D126: a platform adapter's blocking entry point leaves the main actor even though the app target is MainActor by default.
/// The assertion lives inside the adapter body (`dispatchPrecondition(condition: .notOnQueue(.main))`); `Thread.isMainThread` is
/// unavailable from async code in Swift 6.
@MainActor
final class IsolationTests: XCTestCase {
  func testBlockingEntryRunsOffTheMainQueue() async {
    let adapter = AttendancePlaceholder(log: AppLog())
    let ok = await adapter.blockingProbe()  // called from @MainActor; a main-queue run would trap in dispatchPrecondition
    XCTAssertTrue(ok)
  }

  func testSnapshotIsSynchronousAndFailsClosed() {
    let adapter = AttendancePlaceholder(log: AppLog())
    XCTAssertEqual(adapter.snapshot().state, .locked)
  }

  func testAdapterIsUsableFromANonisolatedContext() async {
    let adapter = AttendancePlaceholder(log: AppLog())
    let state = await Task.detached { adapter.snapshot().state }.value
    XCTAssertEqual(state, .locked)
  }
}
