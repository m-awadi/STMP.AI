import Foundation

// MARK: - NormalizedIP (D119)

/// A remote address in the one canonical spelling: IPv4 dotted, IPv4-mapped IPv6 collapsed to dotted IPv4, the zone id
/// dropped, IPv6 lower-cased and compressed (RFC 5952). T1's server is the only caller of `parse` for a remote;
/// `CallerRegistry` and the dispatcher consume `NormalizedIP` and never re-normalise.
public struct NormalizedIP: Sendable, Hashable, CustomStringConvertible {
  public let rawValue: String
  private init(rawValue: String) { self.rawValue = rawValue }
  public var description: String { rawValue }

  /// Loopback is the phone itself (Android `CallerRegistry.isLoopback`).
  public var isLoopback: Bool { rawValue == "127.0.0.1" || rawValue == "::1" }

  /// Test and adapter hook for an already-canonical spelling; returns nil unless `parse` would give the same text back.
  public static func canonical(_ text: String) -> NormalizedIP? {
    guard let p = parse(text), p.rawValue == text else { return nil }
    return p
  }

  public static func parse(_ raw: String) -> NormalizedIP? {
    var text = raw.trimmingCharacters(in: .whitespaces)
    if let pct = text.firstIndex(of: "%") { text = String(text[..<pct]) }
    if text.isEmpty { return nil }
    var v4 = in_addr()
    if inet_pton(AF_INET, text, &v4) == 1 { return NormalizedIP(rawValue: dotted(v4.s_addr)) }
    var v6 = in6_addr()
    guard inet_pton(AF_INET6, text, &v6) == 1 else { return nil }
    let bytes = withUnsafeBytes(of: &v6) { Array($0) }
    if bytes[0..<10].allSatisfy({ $0 == 0 }) && bytes[10] == 0xFF && bytes[11] == 0xFF {
      return NormalizedIP(rawValue: "\(bytes[12]).\(bytes[13]).\(bytes[14]).\(bytes[15])")
    }
    let groups = stride(from: 0, to: 16, by: 2).map { (Int(bytes[$0]) << 8) | Int(bytes[$0 + 1]) }
    return NormalizedIP(rawValue: compressed(groups))
  }

  private static func dotted(_ addr: UInt32) -> String {
    let b = withUnsafeBytes(of: addr) { Array($0) }
    return "\(b[0]).\(b[1]).\(b[2]).\(b[3])"
  }

  /// RFC 5952: the longest run of at least two zero groups becomes `::` (the first one on a tie).
  private static func compressed(_ g: [Int]) -> String {
    var bestStart = -1
    var bestLen = 0
    var i = 0
    while i < 8 {
      if g[i] == 0 {
        var j = i
        while j < 8 && g[j] == 0 { j += 1 }
        if j - i > bestLen {
          bestStart = i
          bestLen = j - i
        }
        i = j
      } else {
        i += 1
      }
    }
    func hex(_ s: ArraySlice<Int>) -> String { s.map { String($0, radix: 16) }.joined(separator: ":") }
    guard bestLen >= 2 else { return hex(g[0..<8]) }
    return hex(g[0..<bestStart]) + "::" + hex(g[(bestStart + bestLen)..<8])
  }
}

// MARK: - The request hand-off (D119)

/// What the HTTP server hands to dispatch after the one normalisation pass: method upper-cased; path with the query
/// stripped and the trailing `/` trimmed; `query` decoded with the first repeated key winning.
public struct RequestHead: Sendable, Hashable {
  public var method: String
  public var path: String
  public var query: [String: String]
  public var headers: [String: String]
  public var contentLength: Int?
  public var charset: String?
  public init(
    method: String, path: String, query: [String: String] = [:], headers: [String: String] = [:], contentLength: Int? = nil,
    charset: String? = nil
  ) {
    self.method = method
    self.path = path
    self.query = query
    self.headers = headers
    self.contentLength = contentLength
    self.charset = charset
  }
}

public enum BodyReadError: Error, Sendable, Hashable {
  case tooLarge(bytes: Int)
  case stalled
  case lengthRequired
}

/// A lazy reader over the request body. The 8 MiB cap and the deadlines start when dispatch first reads (T1 implements it).
public protocol RequestBodySource: Sendable {
  func readAll(limit: Int) async throws -> Data
  func stream() -> AsyncThrowingStream<Data, any Error>
}

public struct RequestContext: Sendable, Hashable {
  public var generation: UInt64
  public var acceptedAt: Date
  public init(generation: UInt64, acceptedAt: Date) {
    self.generation = generation
    self.acceptedAt = acceptedAt
  }
}

/// T2's `Dispatcher` implements it; T1's server calls it.
public protocol RequestHandling: Sendable {
  func handle(_ head: RequestHead, remote: NormalizedIP, body: any RequestBodySource, context: RequestContext) async -> Resp
}

/// T2's `InFlightRegistry` implements it; T12 calls it. `interruptAll` cancels that generation's running handlers and
/// finalises each as HANDLER_FAILED with `detail`, returning after their records are appended.
public protocol InFlightControl: Sendable {
  func interruptAll(generation: UInt64, detail: String) async -> Int
  func count(generation: UInt64) -> Int
}

// MARK: - Registry

public struct RouteMatch: Sendable {
  public var plugin: any Plugin
  public var route: Route
  public var pathParams: [String: String]
}

/// Segment matcher. First registered match wins; `{x}` captures one segment; the method compares case-insensitively and a
/// trailing slash is ignored. Literal routes registered before a `{id}` route therefore shadow it (`/messages/send`).
public struct PluginRegistry: Sendable {
  public let plugins: [any Plugin]
  private let entries: [(plugin: any Plugin, route: Route, segments: [String])]

  public init(plugins: [any Plugin]) {
    self.plugins = plugins
    entries = plugins.flatMap { p in p.routes().map { (p, $0, Self.segments($0.path)) } }
  }

  public var templates: [RouteTemplate] { entries.map { $0.route.template } }

  public func plugin(id: String) -> (any Plugin)? { plugins.first { $0.id == id } }

  public func match(method: String, path: String) -> RouteMatch? {
    let want = Self.segments(path)
    for e in entries where e.route.method.caseInsensitiveCompare(method) == .orderedSame && e.segments.count == want.count {
      var params: [String: String] = [:]
      var ok = true
      for (tpl, actual) in zip(e.segments, want) {
        if tpl.hasPrefix("{") && tpl.hasSuffix("}") {
          params[String(tpl.dropFirst().dropLast())] = actual
        } else if tpl != actual {
          ok = false
          break
        }
      }
      if ok { return RouteMatch(plugin: e.plugin, route: e.route, pathParams: params) }
    }
    return nil
  }

  private static func segments(_ path: String) -> [String] { path.split(separator: "/", omittingEmptySubsequences: true).map(String.init) }
}
