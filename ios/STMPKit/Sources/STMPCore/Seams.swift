import Foundation

// The frozen service seams (PLAN 1.3). Every protocol is Sendable. Each has a fail-closed `Inert…` implementation in
// Inert.swift and a programmable fake in STMPTestSupport/Core.
//
// D122, the published-snapshot pattern: a synchronous accessor on an actor-backed store (`PolicyProviding.current()`,
// `CallerRegistry.state(of:)`, `PluginToggles.isEnabled(_:)`, `VaultService.isUnlocked`) never reads actor state. The actor
// owns every write and, after each committed write, publishes `Result<Value, StoreReadError>` into a `nonisolated let`
// `Broadcast` (or an `OSAllocatedUnfairLock`); the synchronous accessor reads that and throws on a failure value, so an
// unreadable file is a cached failure. `@unchecked Sendable` is never the answer. Pure rule methods (D118) are
// `nonisolated` and read no actor state.

public struct StoreReadError: Error, Sendable, Hashable {
  public var detail: String
  public init(_ detail: String) { self.detail = detail }
}

// MARK: Policy, callers, toggles

public protocol PolicyProviding: Sendable {
  /// Synchronous published snapshot (D122); throws when the policy file is unreadable.
  func current() throws -> Policy
  func resolve(plugin: String, verb: Verb) throws -> ModeResolution
  /// Pure (D118): Android `ApprovalGate.exceptionOfferFor`. `nil` means the sheet offers no "approve and remember".
  nonisolated func exceptionOffer(global: GlobalMode, plugin: String, verb: Verb, mode: GateMode, needsPassword: Bool) -> ExceptionOffer?
  func writeException(plugin: String, verb: Verb?, mode: GateMode) async throws
  func setGlobal(_ mode: GlobalMode) async throws
  func setFingerprintForModals(_ enabled: Bool) async throws
  func setSoundEffects(_ enabled: Bool) async throws
  func setTts(_ settings: TtsSettings) async throws
  /// Writes atomically and appends one POLICY_CHANGED record (D105).
  func update(_ change: PolicyChange, _ transform: @Sendable (Policy) -> Policy) async throws
}

public enum CallerState: Sendable, Hashable {
  case verified(name: String)
  case denied
}

public struct CallerEntry: Sendable, Hashable {
  public var address: NormalizedIP
  public var state: CallerState
  public init(address: NormalizedIP, state: CallerState) {
    self.address = address
    self.state = state
  }
}

public protocol CallerRegistry: Sendable {
  /// `nil` means nobody has decided about this address yet. Loopback is always verified as "this device".
  func state(of address: NormalizedIP) throws -> CallerState?
  func set(_ address: NormalizedIP, state: CallerState) async throws
  func forget(_ address: NormalizedIP) async throws
  func all() async throws -> [CallerEntry]
}

public protocol PluginToggles: Sendable {
  func isEnabled(_ pluginID: String) throws -> Bool
  func set(_ pluginID: String, enabled: Bool) async throws
}

// MARK: Audit

public struct AuditHeader: Sendable, Hashable {
  public var id: String
  public var ts: Int
  public var outcome: AuditOutcome
  public var label: String
  public var plugin: String
  public var verb: String
  public var summary: String
  public var durationMs: Int
  public var responseStatus: Int
  public var noise: Bool
  public init(
    id: String, ts: Int, outcome: AuditOutcome, label: String, plugin: String, verb: String, summary: String, durationMs: Int,
    responseStatus: Int, noise: Bool
  ) {
    self.id = id
    self.ts = ts
    self.outcome = outcome
    self.label = label
    self.plugin = plugin
    self.verb = verb
    self.summary = summary
    self.durationMs = durationMs
    self.responseStatus = responseStatus
    self.noise = noise
  }
}

public struct AuditFilter: Sendable, Hashable {
  public var includeNoise: Bool
  public var limit: Int
  public init(includeNoise: Bool = false, limit: Int = 200) {
    self.includeNoise = includeNoise
    self.limit = limit
  }
}

public enum AuditHealth: Sendable, Hashable {
  case ok
  case unavailable(String)
}

public protocol AuditSink: Sendable {
  func append(_ record: AuditRecord) async throws
  /// Pure (D118): Android `AuditClassify.forError` plus the G8 override.
  nonisolated func classify(status: Int, gated: Bool) -> AuditOutcome
}

public protocol AuditReader: Sendable {
  var health: Broadcast<AuditHealth> { get }
  func headers(filter: AuditFilter) async throws -> [AuditHeader]
  func record(id: String) async throws -> AuditRecord?
  func clear() async throws
}

// MARK: Gate and attendance

public enum AttendanceState: String, Sendable, Hashable { case attended, obscured, away, locked }

public struct AttendanceSnapshot: Sendable, Hashable {
  public var state: AttendanceState
  public var epoch: UInt64
  public init(state: AttendanceState, epoch: UInt64) {
    self.state = state
    self.epoch = epoch
  }
}

public struct AttendanceTransition: Sendable, Hashable {
  public var from: AttendanceState
  public var to: AttendanceState
  public var epoch: UInt64
  public init(from: AttendanceState, to: AttendanceState, epoch: UInt64) {
    self.from = from
    self.to = to
    self.epoch = epoch
  }
}

/// A Core mirror of SwiftUI's three scene phases, so Core does not import SwiftUI.
public enum ScenePhaseValue: String, Sendable, Hashable { case active, inactive, background }

/// A system prompt the app expects to appear over itself, with its own ceiling (D92).
public enum SystemPromptKind: Sendable, Hashable {
  case localAuthentication, contactsPermission, webAuthentication, localNetwork

  /// `nil` is "until the body returns", which still has the hard cap `hardCap`.
  public var ceiling: Duration? {
    switch self {
    case .localAuthentication, .contactsPermission, .localNetwork: return .seconds(120)
    case .webAuthentication: return nil
    }
  }
  public static let hardCap: Duration = .seconds(600)
}

public protocol Attendance: Sendable {
  /// Synchronous and lock-free for callers: never hops (D54).
  func snapshot() -> AttendanceSnapshot
  var current: Broadcast<AttendanceSnapshot> { get }
  func expectSystemPrompt<T: Sendable>(_ kind: SystemPromptKind, _ body: @Sendable () async throws -> T) async rethrows -> T
  /// One handler, set once by T12. Called synchronously on main inside each notification handler, after the epoch bump (D121).
  func setTransitionHandler(_ handler: @escaping @MainActor @Sendable (AttendanceTransition) -> Void)
  /// Logs a disagreement over 1 s with the scene phase. Drives nothing else (D121).
  func crossCheck(_ phase: ScenePhaseValue)
}

public struct GateCaller: Sendable, Hashable {
  public var address: NormalizedIP
  public var label: String
  public init(address: NormalizedIP, label: String) {
    self.address = address
    self.label = label
  }
}

/// One request waiting for a human. `rows` come from the plugin; `summary` and `rows` are untrusted request text.
public struct GateRequest: Sendable, Hashable {
  public var id: String
  public var caller: GateCaller
  public var plugin: String
  public var verb: Verb
  public var method: String
  public var path: String
  public var summary: String
  public var rows: [ApprovalRow]
  public var needsPassword: Bool
  public var exceptionOffer: ExceptionOffer?
  public init(
    id: String, caller: GateCaller, plugin: String, verb: Verb, method: String, path: String, summary: String,
    rows: [ApprovalRow] = [], needsPassword: Bool = false, exceptionOffer: ExceptionOffer? = nil
  ) {
    self.id = id
    self.caller = caller
    self.plugin = plugin
    self.verb = verb
    self.method = method
    self.path = path
    self.summary = summary
    self.rows = rows
    self.needsPassword = needsPassword
    self.exceptionOffer = exceptionOffer
  }
}

public enum GateResult: Sendable, Hashable {
  case approved
  case approvedWithException
  case denied(detail: String)
  case timedOut
  /// The prompt cannot be shown (app not active, device locked): the request is refused, never queued.
  case unreachable(detail: String)
}

public enum CallerPromptResult: Sendable, Hashable {
  case verified(name: String)
  case denied
  case unreachable(detail: String)
}

// VaultNeed (Android `VaultNeed.decide(needsVault, unlocked, state, mode, canPresent)`, VaultNeed.kt:42).
public enum VaultStateKind: String, Sendable, Hashable { case absent, locked, unlocked }

public struct VaultNeedInput: Sendable, Hashable {
  public var needsVault: Bool
  public var unlocked: Bool
  public var state: VaultStateKind
  public var mode: GateMode
  public var canPresent: Bool
  public init(needsVault: Bool, unlocked: Bool, state: VaultStateKind, mode: GateMode, canPresent: Bool) {
    self.needsVault = needsVault
    self.unlocked = unlocked
    self.state = state
    self.mode = mode
    self.canPresent = canPresent
  }
}

public enum VaultNeedAction: Sendable, Hashable {
  /// The route does not need the vault, or it is already open.
  case proceed
  /// Ask the human to unlock, then continue.
  case promptUnlock
  /// Refuse with this error code and detail.
  case refuse(code: String, detail: String)
}

public struct AdmitInput: Sendable, Hashable {
  public var attendance: AttendanceSnapshot
  /// False for `gateExempt` routes.
  public var gated: Bool
  public var mode: GateMode
  public init(attendance: AttendanceSnapshot, gated: Bool, mode: GateMode) {
    self.attendance = attendance
    self.gated = gated
    self.mode = mode
  }
}

public enum AdmitVerdict: Sendable, Hashable {
  case proceed
  case deny(detail: String)
}

public protocol GateService: Sendable {
  func awaitSecure(_ request: GateRequest) async -> GateResult
  func awaitNotify(_ request: GateRequest) async -> GateResult
  func awaitCaller(_ address: NormalizedIP) async -> CallerPromptResult
  func awaitVaultUnlock(reason: String) async -> GateResult
  var canPresent: Bool { get }
  /// Generation-scoped (D89): a stale `denyAll` cannot close the gate of a newer generation.
  func denyAll(reason: String, generation: UInt64) async
  func reopen(generation: UInt64) async
  /// Pure (D118).
  nonisolated func vaultNeed(_ input: VaultNeedInput) -> VaultNeedAction
  /// Pure (D118, D135): the attendance arrival verdict.
  nonisolated func admit(_ input: AdmitInput) -> AdmitVerdict
}

/// The gesture proofs the UI may submit (D95). The UI never proves a gesture by itself: `.approveHeld` is accepted only if
/// the coordinator saw `beginHold` for the same id and epoch at least 1200 ms earlier on its own clock with no `cancelHold`
/// since; `.approveWithBiometric` makes the coordinator run `Authenticator.evaluate` itself.
public enum GateDecision: Sendable {
  case deny
  case approveTap
  case approveHeld
  case approveWithBiometric
  case approveWithPassword(SecretBytes, held: Bool)
  case approveAddException
  case notifyApprove
  case notifyReject
}

public enum GatePresentationKind: Sendable, Hashable { case secure, notify, caller, vaultUnlock }

public struct GatePresentation: Sendable, Hashable {
  public var id: String
  public var epoch: UInt64
  public var kind: GatePresentationKind
  public var request: GateRequest?
  public init(id: String, epoch: UInt64, kind: GatePresentationKind, request: GateRequest? = nil) {
    self.id = id
    self.epoch = epoch
    self.kind = kind
    self.request = request
  }
}

/// Single consumer by design (GateHost only, D53): a bare `AsyncStream`, not a `Broadcast`.
public protocol GatePresenting: Sendable {
  var presentations: AsyncStream<GatePresentation> { get }
  func didPresent(_ id: String, epoch: UInt64) async
  func reportFullyVisible(_ id: String, _ visible: Bool) async
  func beginHold(_ id: String, epoch: UInt64) async
  func cancelHold(_ id: String) async
  func resolve(_ id: String, decision: GateDecision, armedEpoch: UInt64) async
}

// MARK: Vault, keychain, authentication, SSH key

public enum AuthPolicy: Sendable, Hashable { case biometricsOnly, biometricsOrPasscode }
public enum AuthOutcome: Sendable, Hashable { case success, cancelled, failed(String), unavailable(String) }

public protocol Authenticator: Sendable {
  func evaluate(policy: AuthPolicy, reason: String) async -> AuthOutcome
  func invalidate()
}

public struct KeychainAccess: Sendable, Hashable {
  public var service: String
  public var account: String
  public var requiresUserPresence: Bool
  public init(service: String, account: String, requiresUserPresence: Bool) {
    self.service = service
    self.account = account
    self.requiresUserPresence = requiresUserPresence
  }
}

public protocol KeychainStore: Sendable {
  func get(_ access: KeychainAccess) async throws -> SecretBytes?
  func set(_ value: SecretBytes, for access: KeychainAccess) async throws
  func delete(_ access: KeychainAccess) async throws
}

public enum VaultState: Sendable, Hashable {
  case absent
  case locked
  case unlocked
  case unavailable(String)
}

public struct VaultEntryMeta: Sendable, Hashable {
  public enum Kind: String, Sendable, Hashable { case secret, sshKey }
  public var name: String
  public var kind: Kind
  public var allowedHosts: [String]
  public init(name: String, kind: Kind, allowedHosts: [String] = []) {
    self.name = name
    self.kind = kind
    self.allowedHosts = allowedHosts
  }
}

public protocol VaultService: Sendable {
  var state: Broadcast<VaultState> { get }
  /// Synchronous published snapshot (D122).
  func isUnlocked() throws -> Bool
  func unlock(reason: String) async throws
  func lock() async
  func index() async throws -> [VaultEntryMeta]
  func withSecret<T: Sendable>(name: String, _ body: @Sendable (SecretBytes) async throws -> T) async throws -> T
}

/// Key material for the SSH plugin (D88). There is no abstract `sign(Data)`: swift-nio-ssh authenticates only with a
/// concrete `NIOSSHPrivateKey`, which `STMPSSH` builds from this value.
public enum SSHKeyMaterial: Sendable {
  case secureEnclaveP256(dataRepresentation: Data)
  case softwareP256(rawRepresentation: SecretBytes)
}

public protocol SSHKeyStore: Sendable {
  func createKey(name: String) async throws -> SSHKeyMaterial
  func publicKeyOpenSSH(_ material: SSHKeyMaterial) throws -> String
}

// MARK: Google, device, network

public protocol GoogleAuth: Sendable { func accessToken() async throws -> String }
public protocol GoogleHTTP: Sendable { func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) }
public protocol WebAuthSession: Sendable { func run(url: URL, callbackScheme: String) async throws -> URL }

public protocol SpeechOutput: Sendable { func speak(_ text: String, rate: Double, pitch: Double) async throws }
public protocol CuePlayer: Sendable { func play(cue: String) async throws }

public struct ContactRow: Sendable, Hashable {
  public var name: String
  public var phones: [String]
  public var emails: [String]
  public init(name: String, phones: [String] = [], emails: [String] = []) {
    self.name = name
    self.phones = phones
    self.emails = emails
  }
}

public protocol ContactsSource: Sendable { func search(_ query: String, limit: Int) async throws -> [ContactRow] }
public protocol LogSource: Sendable { func read(lines: Int, filter: String?) async throws -> [String] }
public protocol DiagnosticsSource: Sendable { func report() async throws -> JSONValue }
public protocol CallObserver: Sendable { var isInCall: Bool { get } }

public struct InterfaceAddress: Sendable, Hashable {
  public var interface: String
  public var address: String
  public init(interface: String, address: String) {
    self.interface = interface
    self.address = address
  }
}

public protocol NetworkInterfaces: Sendable { func list() throws -> [InterfaceAddress] }

public enum NetworkPath: Sendable, Hashable { case satisfied, unsatisfied }
public protocol PathMonitor: Sendable { var path: Broadcast<NetworkPath> { get } }

// MARK: Bridge

public enum StartOrigin: Sendable, Hashable { case user, launch, foregroundResume }
public enum StopReason: Sendable, Hashable { case user, failClosed(String) }

public struct BridgeState: Sendable, Hashable {
  public var serverUp: Bool
  public var alive: Bool
  public var bind: String?
  public var port: Int?
  public var lastError: String?
  public var pausedInBackground: Bool
  public var generation: UInt64
  /// Incremented on every accepted request and every gate activity event (D125); consumers only need "changed".
  public var activitySeq: UInt64
  public var gateBusy: Bool
  public init(
    serverUp: Bool = false, alive: Bool = false, bind: String? = nil, port: Int? = nil, lastError: String? = nil,
    pausedInBackground: Bool = false, generation: UInt64 = 0, activitySeq: UInt64 = 0, gateBusy: Bool = false
  ) {
    self.serverUp = serverUp
    self.alive = alive
    self.bind = bind
    self.port = port
    self.lastError = lastError
    self.pausedInBackground = pausedInBackground
    self.generation = generation
    self.activitySeq = activitySeq
    self.gateBusy = gateBusy
  }
}

public protocol BridgeControlling: Sendable {
  /// A no-op while the current generation is serving (D121), so the cold-launch double trigger is harmless.
  func start(origin: StartOrigin) async
  func stop(reason: StopReason) async
  var state: Broadcast<BridgeState> { get }
}

/// v1 has no AI (G14); the seam exists so a later ticket does not reshape the dispatcher.
public protocol AISummarizer: Sendable { func summarise(_ request: GateRequest) async -> String? }

/// The plugin dependency bundle (D78). A plugin uses any field without a signature change.
public struct PluginDeps: Sendable {
  public var policy: any PolicyProviding
  public var toggles: any PluginToggles
  public var callers: any CallerRegistry
  public var auditSink: any AuditSink
  public var auditReader: any AuditReader
  public var gate: any GateService
  public var attendance: any Attendance
  public var vault: any VaultService
  public var keychain: any KeychainStore
  public var clock: any StmpClock
  public var log: AppLog
  public var buildInfo: BuildInfo
  public var googleAuth: any GoogleAuth
  public var googleHTTP: any GoogleHTTP
  public var speech: any SpeechOutput
  public var cues: any CuePlayer
  public var contacts: any ContactsSource
  public var logs: any LogSource
  public var diagnostics: any DiagnosticsSource
  public var calls: any CallObserver
  public var sshKeys: any SSHKeyStore
  public var urlSession: @Sendable () -> URLSession
  /// Filled lazily by T12 for `/debug` and `/probe`.
  public var bridgeStatus: @Sendable () -> BridgeState
  /// A read-only view of the registered route templates, for `/help`.
  public var routeTemplates: @Sendable () -> [RouteTemplate]

  public init(
    policy: any PolicyProviding, toggles: any PluginToggles, callers: any CallerRegistry, auditSink: any AuditSink,
    auditReader: any AuditReader, gate: any GateService, attendance: any Attendance, vault: any VaultService,
    keychain: any KeychainStore, clock: any StmpClock, log: AppLog, buildInfo: BuildInfo, googleAuth: any GoogleAuth,
    googleHTTP: any GoogleHTTP, speech: any SpeechOutput, cues: any CuePlayer, contacts: any ContactsSource, logs: any LogSource,
    diagnostics: any DiagnosticsSource, calls: any CallObserver, sshKeys: any SSHKeyStore,
    urlSession: @escaping @Sendable () -> URLSession = { URLSession(configuration: .ephemeral) },
    bridgeStatus: @escaping @Sendable () -> BridgeState = { BridgeState() },
    routeTemplates: @escaping @Sendable () -> [RouteTemplate] = { [] }
  ) {
    self.policy = policy
    self.toggles = toggles
    self.callers = callers
    self.auditSink = auditSink
    self.auditReader = auditReader
    self.gate = gate
    self.attendance = attendance
    self.vault = vault
    self.keychain = keychain
    self.clock = clock
    self.log = log
    self.buildInfo = buildInfo
    self.googleAuth = googleAuth
    self.googleHTTP = googleHTTP
    self.speech = speech
    self.cues = cues
    self.contacts = contacts
    self.logs = logs
    self.diagnostics = diagnostics
    self.calls = calls
    self.sshKeys = sshKeys
    self.urlSession = urlSession
    self.bridgeStatus = bridgeStatus
    self.routeTemplates = routeTemplates
  }
}
