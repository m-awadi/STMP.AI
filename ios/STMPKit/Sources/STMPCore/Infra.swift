import Foundation
import os

// MARK: - Broadcast (D53, D87, D123)

/// A multi-consumer state stream with a synchronous `current`.
///
/// A bare `AsyncStream` delivers each element to one consumer only, so no seam with more than one reader exposes one.
/// It is a final class, not an actor, because the seams promise a synchronous `current`. `OSAllocatedUnfairLock` is
/// iOS 16+ (`Mutex` would need iOS 18). It is `Sendable` without `@unchecked`: its only stored properties are `Sendable`.
///
/// Lock discipline (D123): `send` sets `current` and yields under the lock (a yield never runs `onTermination`, and the
/// lock keeps every consumer's order equal to send order). `updates()` registers and yields `current` under the lock.
/// `onTermination` takes the lock only to remove its own key. `finish()` copies the continuations under the lock and
/// finishes them outside it, because finishing runs `onTermination` synchronously and the lock is not reentrant.
public final class Broadcast<Element: Sendable>: Sendable {
  private struct State: Sendable {
    var current: Element
    var continuations: [UUID: AsyncStream<Element>.Continuation]
    var isFinished = false
  }
  private let state: OSAllocatedUnfairLock<State>
  private let policy: AsyncStream<Element>.Continuation.BufferingPolicy

  public init(_ initial: Element, buffering: AsyncStream<Element>.Continuation.BufferingPolicy = .bufferingNewest(1)) {
    state = OSAllocatedUnfairLock(initialState: State(current: initial, continuations: [:]))
    policy = buffering
  }

  /// Synchronous and lock-protected, callable from any context.
  public var current: Element { state.withLock { $0.current } }

  public func send(_ value: Element) {
    state.withLock { s in
      s.current = value
      for c in s.continuations.values { c.yield(value) }
    }
  }

  /// Registers a new consumer. The first element is always `current`.
  public func updates() -> AsyncStream<Element> {
    AsyncStream(Element.self, bufferingPolicy: policy) { continuation in
      let key = UUID()
      continuation.onTermination = { [weak self] _ in
        self?.state.withLock { _ = $0.continuations.removeValue(forKey: key) }
      }
      let finished = state.withLock { s -> Bool in
        if s.isFinished { return true }
        s.continuations[key] = continuation
        continuation.yield(s.current)
        return false
      }
      if finished {
        continuation.yield(state.withLock { $0.current })
        continuation.finish()
      }
    }
  }

  /// Ends every stream. Later `updates()` calls yield `current` once and finish.
  public func finish() {
    let copies = state.withLock { s -> [AsyncStream<Element>.Continuation] in
      s.isFinished = true
      let all = Array(s.continuations.values)
      s.continuations.removeAll()
      return all
    }
    for c in copies { c.finish() }
  }

  /// Test hook: how many consumers are registered.
  public var consumerCount: Int { state.withLock { $0.continuations.count } }
}

// MARK: - SecretBytes

/// Secret material that is wiped on `wipe()` and on `deinit`. Not `Codable`, description redacted, never logged (not even
/// its length). The one allowed `@unchecked Sendable` of the package.
public final class SecretBytes: @unchecked Sendable, CustomStringConvertible, CustomDebugStringConvertible {
  // SAFETY: every access to `storage` and `count` happens while `lock` is held; the buffer never escapes the lock
  // (`withBytes` hands out a copy-free view only for the duration of the closure, under the lock).
  private let lock = NSLock()
  private var storage: UnsafeMutableRawPointer?
  private var count: Int

  public init(_ bytes: [UInt8]) {
    count = bytes.count
    storage = UnsafeMutableRawPointer.allocate(byteCount: max(bytes.count, 1), alignment: 1)
    bytes.withUnsafeBytes { src in
      if let base = src.baseAddress, !src.isEmpty { storage?.copyMemory(from: base, byteCount: src.count) }
    }
  }

  public convenience init(_ string: String) { self.init(Array(string.utf8)) }

  deinit { wipe() }

  public var isWiped: Bool { lock.withLock { storage == nil } }

  /// Runs `body` over the bytes. Throws nothing; a wiped value yields an empty buffer.
  public func withBytes<R>(_ body: (UnsafeRawBufferPointer) throws -> R) rethrows -> R {
    try lock.withLock {
      guard let s = storage else { return try body(UnsafeRawBufferPointer(start: nil, count: 0)) }
      return try body(UnsafeRawBufferPointer(start: s, count: count))
    }
  }

  /// Overwrites the buffer with zeros (`memset_s`, not elidable) and releases it.
  public func wipe() {
    lock.withLock {
      guard let s = storage else { return }
      _ = memset_s(s, max(count, 1), 0, max(count, 1))
      s.deallocate()
      storage = nil
      count = 0
    }
  }

  public var description: String { "SecretBytes(<redacted>)" }
  public var debugDescription: String { description }
}

// MARK: - Clock

public protocol StmpClock: Sendable {
  func now() -> Date
  /// Suspends for `duration` on this clock. Tests inject a clock that only advances when told to.
  func sleep(for duration: Duration) async throws
}

public struct SystemStmpClock: StmpClock {
  public init() {}
  public func now() -> Date { Date() }
  public func sleep(for duration: Duration) async throws { try await ContinuousClock().sleep(for: duration) }
}

// MARK: - Storage

public enum AtomicFile {
  /// Writes `data` next to `url` and renames it over `url`, so a reader sees the old or the new file, never half of one.
  /// `protection` is applied on iOS; it is ignored on the host.
  public static func write(_ data: Data, to url: URL, protection: FileProtectionType? = nil) throws {
    let dir = url.deletingLastPathComponent()
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    let tmp = dir.appendingPathComponent(".\(url.lastPathComponent).\(UUID().uuidString).tmp")
    do {
      try data.write(to: tmp, options: [.atomic])
      #if os(iOS)
      if let protection { try FileManager.default.setAttributes([.protectionKey: protection], ofItemAtPath: tmp.path) }
      #endif
      _ = try FileManager.default.replaceItemAt(url, withItemAt: tmp)
    } catch {
      try? FileManager.default.removeItem(at: tmp)
      throw error
    }
  }
}

/// The directory everything persistent lives in (D94): complete-until-first-unlock, excluded from backup.
public struct StorageRoot: Sendable, Hashable {
  public let url: URL
  public init(url: URL) { self.url = url }

  public func prepare() throws {
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    #if os(iOS)
    try FileManager.default.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: url.path)
    #endif
    var values = URLResourceValues()
    values.isExcludedFromBackup = true
    var u = url
    try u.setResourceValues(values)
  }

  public func appendingPathComponent(_ name: String) -> URL { url.appendingPathComponent(name) }

  /// The protection class read back from disk (`nil` on the host).
  public func protectionClass() -> FileProtectionType? {
    (try? FileManager.default.attributesOfItem(atPath: url.path))?[.protectionKey] as? FileProtectionType
  }
}

// MARK: - Build info and logging

public struct BuildInfo: Sendable, Hashable {
  public var version: String
  public var build: Int
  public init(version: String, build: Int) {
    self.version = version
    self.build = build
  }
  public static func from(_ bundle: Bundle) -> BuildInfo {
    BuildInfo(
      version: bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.0",
      build: Int(bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "") ?? 0)
  }
}

/// `os.Logger` plus an in-memory ring of the last lines. Request text and secrets never go here.
public final class AppLog: Sendable {
  public static let ringCapacity = 500
  private let logger: Logger
  private let ring = OSAllocatedUnfairLock<[String]>(initialState: [])
  public init(subsystem: String = "com.mido.stmp", category: String = "app") { logger = Logger(subsystem: subsystem, category: category) }

  public func info(_ message: String) {
    logger.info("\(message, privacy: .public)")
    record("I \(message)")
  }
  public func error(_ message: String) {
    logger.error("\(message, privacy: .public)")
    record("E \(message)")
  }
  private func record(_ line: String) {
    ring.withLock { r in
      r.append(line)
      if r.count > Self.ringCapacity { r.removeFirst(r.count - Self.ringCapacity) }
    }
  }
  public func lines() -> [String] { ring.withLock { $0 } }
}

/// The vault's non-secret index, handed to `SecretsPlugins.makeBrokers` (one broker per SECRET entry, none for `ssh-*`).
public struct VaultMeta: Sendable, Hashable {
  public var entries: [VaultEntryMeta]
  public init(entries: [VaultEntryMeta]) { self.entries = entries }
}
