import Foundation

/// The five request classes of Android `core/Labels.kt`.
public enum Verb: String, Sendable, Hashable, Codable, CaseIterable {
  case read = "READ"
  case write = "WRITE"
  case edit = "EDIT"
  case delete = "DELETE"
  case execute = "EXECUTE"
}

/// How one request is gated. Raw values match Android `Mode` (`PARANOID` is the SECURE sheet, `DONT_DISTURB` the NOTIFY card).
public enum GateMode: String, Sendable, Hashable, Codable, CaseIterable {
  case secure = "PARANOID"
  case notify = "DONT_DISTURB"
}

public enum GlobalMode: String, Sendable, Hashable, Codable, CaseIterable {
  case whitelist = "WHITELIST"
  case paranoid = "PARANOID"
  case dontDisturb = "DONT_DISTURB"
}

public enum ModeSource: String, Sendable, Hashable, Codable { case global = "GLOBAL", rule = "RULE", `default` = "DEFAULT" }

public struct Rule: Sendable, Hashable, Codable {
  public static let any = "ANY"
  public var plugin: String
  public var verb: Verb?
  public var mode: GateMode
  public init(plugin: String, verb: Verb?, mode: GateMode) {
    self.plugin = plugin
    self.verb = verb
    self.mode = mode
  }
  public var key: String { "\(plugin)/\(verb?.rawValue ?? Rule.any)" }
  public func covers(plugin: String, verb: Verb) -> Bool { self.plugin == plugin && (self.verb == nil || self.verb == verb) }
}

public struct TtsSettings: Sendable, Hashable, Codable {
  public var maxChars: Int
  public var rate: Double
  public var pitch: Double
  public var queueDepth: Int
  public init(maxChars: Int = 400, rate: Double = 1.0, pitch: Double = 1.0, queueDepth: Int = 3) {
    self.maxChars = maxChars
    self.rate = rate
    self.pitch = pitch
    self.queueDepth = queueDepth
  }
}

/// Android `Policy`. `aiBackend` is tolerated by the parser and read as OFF (G14), so it is not modelled.
public struct Policy: Sendable, Hashable {
  public var global: GlobalMode
  public var rules: [Rule]
  public var fingerprintForModals: Bool
  public var autoStartOnBoot: Bool
  public var soundEffects: Bool
  public var tts: TtsSettings
  public init(
    global: GlobalMode, rules: [Rule] = [], fingerprintForModals: Bool = true, autoStartOnBoot: Bool = true,
    soundEffects: Bool = true, tts: TtsSettings = TtsSettings()
  ) {
    self.global = global
    self.rules = rules
    self.fingerprintForModals = fingerprintForModals
    self.autoStartOnBoot = autoStartOnBoot
    self.soundEffects = soundEffects
    self.tts = tts
  }
}

public struct ModeResolution: Sendable, Hashable {
  public var mode: GateMode
  public var source: ModeSource
  public var rule: Rule?
  public init(mode: GateMode, source: ModeSource, rule: Rule? = nil) {
    self.mode = mode
    self.source = source
    self.rule = rule
  }
}

/// What the SECURE sheet may offer as "approve and remember" (Android `ApprovalGate.exceptionOfferFor`).
public struct ExceptionOffer: Sendable, Hashable {
  public var plugin: String
  public var verb: Verb?
  public var mode: GateMode
  public init(plugin: String, verb: Verb?, mode: GateMode) {
    self.plugin = plugin
    self.verb = verb
    self.mode = mode
  }
}

/// Names the reason of a generic policy write; the audit sink records one POLICY_CHANGED row for it (D105).
public enum PolicyChange: Sendable, Hashable {
  case global, rule(plugin: String, verb: Verb?), fingerprintForModals, soundEffects, tts, autoStartOnBoot, reset
}

// MARK: - Routes and plugins

/// The data half of a route: what `/help`, the contract fixtures and the 501 path read. Closures live on `Route`.
public struct RouteTemplate: Sendable, Hashable {
  public var plugin: String
  public var method: String
  public var path: String
  public var describe: String
  /// `nil` means the verb is computed per request (`Route.verbFor`); `fallbackVerb` is then used for classification.
  public var verb: Verb?
  public var fallbackVerb: Verb
  public var auditRequestBody = true
  public var auditResponseBody = true
  public var auditResponseTransform = false
  public var streamsRequest = false
  public var aiSummary = true
  public var gateExempt = false
  public var holdExempt = false
  public var needsVault = false
  /// A reason text when the route answers 501 `not_supported_on_ios` (Q5).
  public var unsupportedOnIOS: String?

  public init(
    plugin: String, _ method: String, _ path: String, _ describe: String, _ verb: Verb?, fallback: Verb? = nil,
    auditRequestBody: Bool = true, auditResponseBody: Bool = true, auditResponseTransform: Bool = false,
    streamsRequest: Bool = false, aiSummary: Bool = true, gateExempt: Bool = false, holdExempt: Bool = false,
    needsVault: Bool = false, unsupportedOnIOS: String? = nil
  ) {
    self.plugin = plugin
    self.method = method
    self.path = path
    self.describe = describe
    self.verb = verb
    self.fallbackVerb = verb ?? fallback ?? .execute
    self.auditRequestBody = auditRequestBody
    self.auditResponseBody = auditResponseBody
    self.auditResponseTransform = auditResponseTransform
    self.streamsRequest = streamsRequest
    self.aiSummary = aiSummary
    self.gateExempt = gateExempt
    self.holdExempt = holdExempt
    self.needsVault = needsVault
    self.unsupportedOnIOS = unsupportedOnIOS
  }

  public var key: String { "\(method) \(path)" }
}

public struct Route: Sendable {
  public var template: RouteTemplate
  public var validate: (@Sendable (Req) throws -> Void)?
  public var verbFor: (@Sendable (Req) -> Verb?)?
  public var needsPassword: (@Sendable (Req) -> Bool)?
  public var handler: @Sendable (Req) async throws -> Resp

  public init(
    _ template: RouteTemplate, validate: (@Sendable (Req) throws -> Void)? = nil, verbFor: (@Sendable (Req) -> Verb?)? = nil,
    needsPassword: (@Sendable (Req) -> Bool)? = nil, handler: @escaping @Sendable (Req) async throws -> Resp
  ) {
    self.template = template
    self.validate = validate
    self.verbFor = verbFor
    self.needsPassword = needsPassword
    self.handler = handler
  }

  public var method: String { template.method }
  public var path: String { template.path }
}

public struct PluginPermission: Sendable, Hashable {
  public var name: String
  public var optional: Bool
  public init(name: String, optional: Bool) {
    self.name = name
    self.optional = optional
  }
}

public struct PluginStatus: Sendable, Hashable {
  public var summary: String
  public var ready: Bool
  public init(summary: String, ready: Bool) {
    self.summary = summary
    self.ready = ready
  }
}

public struct ApprovalRow: Sendable, Hashable {
  public enum Style: String, Sendable, Hashable { case text, mono, danger }
  public var label: String
  public var value: String
  public var style: Style
  public init(_ label: String, _ value: String, _ style: Style = .text) {
    self.label = label
    self.value = value
    self.style = style
  }
}

public struct PluginError: Error, Sendable, Hashable {
  public var code: String
  public var detail: String
  public init(_ code: String, _ detail: String) {
    self.code = code
    self.detail = detail
  }
}

public protocol Plugin: Sendable {
  var id: String { get }
  var displayName: String { get }
  var enabledByDefault: Bool { get }
  var scopes: [String] { get }
  var permissions: [PluginPermission] { get }
  func routes() -> [Route]
  func describeRows(_ req: Req) throws -> [ApprovalRow]
  func status() async -> PluginStatus
}

extension Plugin {
  public var enabledByDefault: Bool { true }
  public var scopes: [String] { [] }
  public var permissions: [PluginPermission] { [] }
  public func describeRows(_ req: Req) throws -> [ApprovalRow] { [] }

  /// Port of Android `Plugin.rowsFor` (core/Plugin.kt:402-455), keyed on `METHOD + route template` (`req.path`).
  /// An explicit `writes` entry wins, even for a GET. Otherwise a GET or a key in `reads` is correctly empty.
  /// Anything else is a defect and throws `internal_error` before any modal, never an empty approval.
  public func rowsFor(_ req: Req, reads: Set<String>, writes: [String: @Sendable (Req) throws -> [ApprovalRow]]) throws -> [ApprovalRow] {
    let key = "\(req.method.uppercased()) \(req.path)"
    if let builder = writes[key] { return try builder(req) }
    if req.method.caseInsensitiveCompare("GET") == .orderedSame || reads.contains(key) { return [] }
    throw PluginError(
      "internal_error", "plugin '\(id)' has no approval rows for \(key) (read '\(req.path)'? add it to reads, or add a write entry)")
  }
}

/// A plugin whose handlers all throw `internal_error` "not built yet". T0 placeholders and 501 stubs use it.
public struct NotBuiltPlugin: Plugin {
  public let id: String
  public let displayName: String
  public let enabledByDefault: Bool
  private let templates: [RouteTemplate]
  public init(id: String, displayName: String, enabledByDefault: Bool = true, templates: [RouteTemplate]) {
    self.id = id
    self.displayName = displayName
    self.enabledByDefault = enabledByDefault
    self.templates = templates
  }
  public func routes() -> [Route] {
    templates.map { t in Route(t) { _ in throw PluginError("internal_error", "not built yet") } }
  }
  public func status() async -> PluginStatus { PluginStatus(summary: "not built yet", ready: false) }
}

// MARK: - Request, response

public struct Req: Sendable {
  public var method: String
  /// The matched route template, e.g. `/calendar/events/{id}`.
  public var path: String
  public var rawPath: String
  public var pathParams: [String: String]
  public var query: [String: String]
  public var headers: [String: String]
  public var body: JSONValue
  public var bodyStream: (any RequestBodySource)?
  public var secretInput: SecretBytes?
  public var remote: NormalizedIP?

  public init(
    method: String, path: String, rawPath: String? = nil, pathParams: [String: String] = [:], query: [String: String] = [:],
    headers: [String: String] = [:], body: JSONValue = .object([:]), bodyStream: (any RequestBodySource)? = nil,
    secretInput: SecretBytes? = nil, remote: NormalizedIP? = nil
  ) {
    self.method = method
    self.path = path
    self.rawPath = rawPath ?? path
    self.pathParams = pathParams
    self.query = query
    self.headers = headers
    self.body = body
    self.bodyStream = bodyStream
    self.secretInput = secretInput
    self.remote = remote
  }
}

public enum Resp: Sendable {
  case json(status: Int, body: JSONValue)
  case stream(status: Int, chunks: AsyncThrowingStream<Data, any Error>, contentType: String, length: Int?)

  public var status: Int {
    switch self {
    case .json(let s, _), .stream(let s, _, _, _): return s
    }
  }

  public static func ok(_ body: JSONValue) -> Resp { .json(status: 200, body: body) }

  /// The one error body shape: `{"ok":false,"error":<code>,"detail":<detail>}`.
  public static func error(status: Int, code: String, detail: String) -> Resp {
    .json(status: status, body: .object(["ok": .bool(false), "error": .string(code), "detail": .string(detail)]))
  }
}

// MARK: - Audit (byte-compatible with Android AuditRecord.toJson)

public enum AuditOutcome: String, Sendable, Hashable, Codable, CaseIterable {
  case approved = "APPROVED"
  case denied = "DENIED"
  case timeout = "TIMEOUT"
  case deniedUnattended = "DENIED_UNATTENDED"
  case notifyApproved = "NOTIFY_APPROVED"
  case notifyRejected = "NOTIFY_REJECTED"
  case policyChanged = "POLICY_CHANGED"
  case rejectedInvalid = "REJECTED_INVALID"
  case unrouted = "UNROUTED"
  case handlerFailed = "HANDLER_FAILED"
  case clientGone = "CLIENT_GONE"
}

public struct AuditDecision: Sendable, Hashable {
  public var mode: String
  public var source: ModeSource
  public var globalMode: String?
  public var rule: String?
  public var enqueuedAt: Int?
  public var shownAt: Int?
  public var decidedAt: Int?
  public var queueDepth: Int
  public init(
    mode: String, source: ModeSource, globalMode: String? = nil, rule: String? = nil, enqueuedAt: Int? = nil, shownAt: Int? = nil,
    decidedAt: Int? = nil, queueDepth: Int = 0
  ) {
    self.mode = mode
    self.source = source
    self.globalMode = globalMode
    self.rule = rule
    self.enqueuedAt = enqueuedAt
    self.shownAt = shownAt
    self.decidedAt = decidedAt
    self.queueDepth = queueDepth
  }
}

public struct AuditTruncation: Sendable, Hashable {
  public var requestBody: Int
  public var requestBodyAbsent: Bool
  public var responseBody: Int
  public var responseBodyAbsent: Bool
  public init(requestBody: Int = 0, requestBodyAbsent: Bool = false, responseBody: Int = 0, responseBodyAbsent: Bool = false) {
    self.requestBody = requestBody
    self.requestBodyAbsent = requestBodyAbsent
    self.responseBody = responseBody
    self.responseBodyAbsent = responseBodyAbsent
  }
}

public struct AuditRecord: Sendable, Hashable {
  public var id: String
  public var ts: Int
  public var outcome: AuditOutcome
  public var label: String
  public var plugin: String
  public var verb: String
  public var origin: String
  public var remote: String
  public var requester: String
  public var route: String
  public var method: String
  public var path: String
  public var query: [String: String]
  public var summary: String
  public var requestHeaders: [String: String]
  public var requestBody: String
  public var responseStatus: Int
  public var responseBody: String
  public var error: String?
  public var decision: AuditDecision
  public var durationMs: Int
  public var truncated: AuditTruncation
  public var noise: Bool
  public var appVersionName: String
  public var appVersionCode: Int

  public init(
    id: String, ts: Int, outcome: AuditOutcome, label: String, plugin: String, verb: String, origin: String, remote: String,
    requester: String, route: String, method: String, path: String, query: [String: String] = [:], summary: String,
    requestHeaders: [String: String] = [:], requestBody: String = "", responseStatus: Int, responseBody: String = "",
    error: String? = nil, decision: AuditDecision, durationMs: Int, truncated: AuditTruncation = AuditTruncation(), noise: Bool,
    appVersionName: String, appVersionCode: Int
  ) {
    self.id = id
    self.ts = ts
    self.outcome = outcome
    self.label = label
    self.plugin = plugin
    self.verb = verb
    self.origin = origin
    self.remote = remote
    self.requester = requester
    self.route = route
    self.method = method
    self.path = path
    self.query = query
    self.summary = summary
    self.requestHeaders = requestHeaders
    self.requestBody = requestBody
    self.responseStatus = responseStatus
    self.responseBody = responseBody
    self.error = error
    self.decision = decision
    self.durationMs = durationMs
    self.truncated = truncated
    self.noise = noise
    self.appVersionName = appVersionName
    self.appVersionCode = appVersionCode
  }

  /// The same keys and shapes as Android `AuditRecord.toJson()`.
  public func toJSON() -> JSONValue {
    func strMap(_ m: [String: String]) -> JSONValue { .object(m.mapValues { .string($0) }) }
    func optStr(_ s: String?) -> JSONValue { s.map { .string($0) } ?? .null }
    func optInt(_ i: Int?) -> JSONValue { i.map { .int($0) } ?? .null }
    var trunc: [String: JSONValue] = [:]
    if truncated.requestBody > 0 { trunc["requestBody"] = .int(truncated.requestBody) }
    if truncated.requestBodyAbsent { trunc["requestBodyAbsent"] = .bool(true) }
    if truncated.responseBody > 0 { trunc["responseBody"] = .int(truncated.responseBody) }
    if truncated.responseBodyAbsent { trunc["responseBodyAbsent"] = .bool(true) }
    return .object([
      "id": .string(id), "ts": .int(ts), "outcome": .string(outcome.rawValue), "label": .string(label), "plugin": .string(plugin),
      "verb": .string(verb), "origin": .string(origin), "remote": .string(remote), "requester": .string(requester),
      "route": .string(route), "method": .string(method), "path": .string(path), "query": strMap(query), "summary": .string(summary),
      "requestHeaders": strMap(requestHeaders), "requestBody": .string(requestBody), "responseStatus": .int(responseStatus),
      "responseBody": .string(responseBody), "error": optStr(error),
      "decision": .object([
        "mode": .string(decision.mode), "source": .string(decision.source.rawValue), "globalMode": optStr(decision.globalMode),
        "rule": optStr(decision.rule), "enqueuedAt": optInt(decision.enqueuedAt), "shownAt": optInt(decision.shownAt),
        "decidedAt": optInt(decision.decidedAt), "queueDepth": .int(decision.queueDepth),
      ]),
      "durationMs": .int(durationMs), "truncated": .object(trunc), "redacted": .bool(false), "noise": .bool(noise),
      "app": .object(["versionName": .string(appVersionName), "versionCode": .int(appVersionCode)]),
    ])
  }
}
