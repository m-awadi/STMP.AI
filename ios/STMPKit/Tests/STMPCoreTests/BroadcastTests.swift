import STMPCore
import XCTest

final class BroadcastTests: XCTestCase {
  func testTwoConsumersBothSeeEveryElement() async {
    let b = Broadcast<Int>(0, buffering: .unbounded)
    let a = b.updates()
    let c = b.updates()
    b.send(1)
    b.send(2)
    b.finish()
    var seenA: [Int] = []
    var seenC: [Int] = []
    for await v in a { seenA.append(v) }
    for await v in c { seenC.append(v) }
    XCTAssertEqual(seenA, [0, 1, 2])
    XCTAssertEqual(seenC, [0, 1, 2])
  }

  func testCurrentIsSynchronousAndUpdatesYieldsItFirst() async {
    let b = Broadcast<String>("a")
    b.send("b")
    XCTAssertEqual(b.current, "b")
    var it = b.updates().makeAsyncIterator()
    let first = await it.next()
    XCTAssertEqual(first, "b")
  }

  func testTerminatedConsumerIsRemoved() async {
    let b = Broadcast<Int>(0)
    do {
      var it = b.updates().makeAsyncIterator()
      _ = await it.next()
      XCTAssertEqual(b.consumerCount, 1)
    }
    XCTAssertEqual(b.consumerCount, 0)
  }

  // D123: finish() with two live consumers must not deadlock (onTermination runs inside continuation.finish()).
  func testFinishWithTwoLiveConsumers() async {
    let b = Broadcast<Int>(0)
    let s1 = b.updates()
    let s2 = b.updates()
    b.finish()
    var n = 0
    for await _ in s1 { n += 1 }
    for await _ in s2 { n += 1 }
    XCTAssertEqual(n, 2)
    XCTAssertEqual(b.consumerCount, 0)
    var late = 0
    for await _ in b.updates() { late += 1 }
    XCTAssertEqual(late, 1)
  }

  // D123: a consumer cancelled while send runs must not deadlock or lose the other consumer's elements.
  func testConsumerCancelledWhileSending() async {
    let b = Broadcast<Int>(0, buffering: .unbounded)
    let stream = b.updates()
    let victim = Task { for await _ in b.updates() {} }
    let survivor = Task { () -> Int in
      var n = 0
      for await v in stream {
        n += 1
        if v == 200 { break }
      }
      return n
    }
    let sender = Task { for i in 1...200 { b.send(i) } }
    victim.cancel()
    await sender.value
    let count = await survivor.value
    XCTAssertEqual(count, 201)
  }

  // D123: a consumer that breaks out of its own loop is removed.
  func testConsumerBreakingOutOfItsLoop() async {
    let b = Broadcast<Int>(0, buffering: .unbounded)
    for await v in b.updates() {
      b.send(v + 1)
      if v >= 2 { break }
    }
    XCTAssertEqual(b.consumerCount, 0)
    b.send(99)
    XCTAssertEqual(b.current, 99)
  }
}

final class BroadcastSyncReadTests: XCTestCase {
  func testCurrentReadFromANonisolatedSynchronousFunction() {
    let b = Broadcast<Int>(7)
    b.send(8)
    XCTAssertEqual(Self.readSynchronously(b), 8)
  }

  private nonisolated static func readSynchronously(_ b: Broadcast<Int>) -> Int { b.current }
}
