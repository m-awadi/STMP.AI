import Foundation
import STMPCore
import os

// Shared test infrastructure (D77). This module never imports XCTest and is never linked into the app (D111).

public enum FixtureError: Error, CustomStringConvertible {
  case contractDirNotFound(from: String)
  case notJSON(String)
  public var description: String {
    switch self {
    case .contractDirNotFound(let f): return "Fixture: no ios/contract directory above \(f). Set STMP_CONTRACT_DIR."
    case .notJSON(let p): return "Fixture: \(p) is not valid JSON."
    }
  }
}

/// Loads shared contract fixtures. The directory is found by walking up from the calling test's `#filePath` to `ios/contract/`,
/// or taken from `STMP_CONTRACT_DIR` when set.
public enum Fixture {
  public static func directory(from file: StaticString = #filePath) throws -> URL {
    if let override = ProcessInfo.processInfo.environment["STMP_CONTRACT_DIR"], !override.isEmpty { return URL(fileURLWithPath: override) }
    var dir = URL(fileURLWithPath: "\(file)").deletingLastPathComponent()
    while dir.path != "/" {
      let candidate = dir.appendingPathComponent("ios/contract")
      var isDir: ObjCBool = false
      if FileManager.default.fileExists(atPath: candidate.path, isDirectory: &isDir), isDir.boolValue { return candidate }
      let sibling = dir.appendingPathComponent("contract")  // when `dir` is already `ios/`
      if dir.lastPathComponent == "ios", FileManager.default.fileExists(atPath: sibling.path) { return sibling }
      dir = dir.deletingLastPathComponent()
    }
    throw FixtureError.contractDirNotFound(from: "\(file)")
  }

  public static func load(_ relativePath: String, file: StaticString = #filePath) throws -> JSONValue {
    let url = try directory(from: file).appendingPathComponent(relativePath)
    do { return try JSONValue.parse(Data(contentsOf: url)) } catch is DecodingError { throw FixtureError.notJSON(relativePath) }
  }

  /// Every `*.json` below the contract directory, relative paths sorted.
  public static func allJSON(file: StaticString = #filePath) throws -> [String] {
    let root = try directory(from: file)
    guard let walker = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil) else { return [] }
    let base = root.standardizedFileURL.path + "/"
    return walker.compactMap { ($0 as? URL)?.standardizedFileURL.path }.filter { $0.hasSuffix(".json") }.map {
      String($0.dropFirst(base.count))
    }.sorted()
  }
}

/// A clock that moves only when told to.
public final class TestClock: StmpClock {
  private struct Sleeper {
    var deadline: Date
    var continuation: CheckedContinuation<Void, any Error>
  }
  private struct State {
    var now: Date
    var sleepers: [UUID: Sleeper] = [:]
  }
  private let state: OSAllocatedUnfairLock<State>

  public init(start: Date = Date(timeIntervalSince1970: 1_767_268_800)) {  // 2026-01-01T12:00:00Z
    state = OSAllocatedUnfairLock(initialState: State(now: start))
  }

  public func now() -> Date { state.withLock { $0.now } }

  public func sleep(for duration: Duration) async throws {
    let id = UUID()
    try await withTaskCancellationHandler {
      try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, any Error>) in
        let due = state.withLock { s -> Bool in
          let deadline = s.now.addingTimeInterval(duration.timeInterval)
          if deadline <= s.now { return true }
          s.sleepers[id] = Sleeper(deadline: deadline, continuation: c)
          return false
        }
        if due { c.resume() }
      }
    } onCancel: {
      let s = state.withLock { $0.sleepers.removeValue(forKey: id) }
      s?.continuation.resume(throwing: CancellationError())
    }
  }

  /// Moves time forward and resumes every sleeper whose deadline has passed.
  public func advance(by duration: Duration) {
    let due = state.withLock { s -> [Sleeper] in
      s.now = s.now.addingTimeInterval(duration.timeInterval)
      let ids = s.sleepers.filter { $0.value.deadline <= s.now }.map(\.key)
      return ids.compactMap { s.sleepers.removeValue(forKey: $0) }
    }
    for s in due { s.continuation.resume() }
  }

  public var sleeperCount: Int { state.withLock { $0.sleepers.count } }
}

extension Duration {
  fileprivate var timeInterval: TimeInterval { Double(components.seconds) + Double(components.attoseconds) / 1e18 }
}

/// A programmable answer for one seam method: fail-closed by default, a FIFO of scripted results, an optional handler,
/// and a record of every input.
public final class Scripted<Input: Sendable, Output: Sendable>: Sendable {
  private struct State {
    var queue: [Output] = []
    var calls: [Input] = []
    var handler: (@Sendable (Input) -> Output)?
  }
  private let state = OSAllocatedUnfairLock(initialState: State())
  private let failClosed: @Sendable (Input) -> Output

  public init(failClosed: @escaping @Sendable (Input) -> Output) { self.failClosed = failClosed }

  public func call(_ input: Input) -> Output {
    let (queued, handler) = state.withLock { s -> (Output?, (@Sendable (Input) -> Output)?) in
      s.calls.append(input)
      return (s.queue.isEmpty ? nil : s.queue.removeFirst(), s.handler)
    }
    if let queued { return queued }
    if let handler { return handler(input) }
    return failClosed(input)
  }

  public func enqueue(_ output: Output) { state.withLock { $0.queue.append(output) } }
  public func setHandler(_ handler: @escaping @Sendable (Input) -> Output) { state.withLock { $0.handler = handler } }
  public var calls: [Input] { state.withLock { $0.calls } }
  public var callCount: Int { state.withLock { $0.calls.count } }
}

public enum FakeError: Error, Sendable, Hashable, CustomStringConvertible {
  case unscripted(String)
  public var description: String {
    switch self {
    case .unscripted(let n): return "fake '\(n)' has no scripted answer (fail closed)"
    }
  }
}

/// A recorded list of events.
public final class CallLog<Event: Sendable>: Sendable {
  private let events = OSAllocatedUnfairLock<[Event]>(initialState: [])
  public init() {}
  public func record(_ e: Event) { events.withLock { $0.append(e) } }
  public var all: [Event] { events.withLock { $0 } }
}

/// A minimal HTTP client for the bridge's real socket (host tests and UI tests).
public struct HTTPTestClient: Sendable {
  public struct Response: Sendable {
    public var status: Int
    public var headers: [String: String]
    public var body: Data
    public var json: JSONValue? { try? JSONValue.parse(body) }
  }
  public let baseURL: URL
  private let session: URLSession
  public init(baseURL: URL) {
    self.baseURL = baseURL
    session = URLSession(configuration: .ephemeral)
  }

  public func request(_ method: String, _ path: String, headers: [String: String] = [:], body: Data? = nil) async throws -> Response {
    var req = URLRequest(url: URL(string: path, relativeTo: baseURL)!)
    req.httpMethod = method
    req.httpBody = body
    for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
    let (data, resp) = try await session.data(for: req)
    let http = resp as! HTTPURLResponse
    var h: [String: String] = [:]
    for (k, v) in http.allHeaderFields { h["\(k)".lowercased()] = "\(v)" }
    return Response(status: http.statusCode, headers: h, body: data)
  }
  public func get(_ path: String) async throws -> Response { try await request("GET", path) }
  public func post(_ path: String, json: JSONValue) async throws -> Response {
    try await request("POST", path, headers: ["Content-Type": "application/json"], body: try json.serialized())
  }
}
