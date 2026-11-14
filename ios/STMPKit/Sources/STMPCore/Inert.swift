import Foundation

// One fail-closed implementation per seam (D111). The T0 placeholders and the inert unit-test host (D69) use these:
// the gate denies, the vault is ABSENT, the audit sink throws, the bridge never starts, every store read fails.

private let inertDetail = "inert seam: not wired"

public struct InertPolicyProviding: PolicyProviding {
  public init() {}
  public func current() throws -> Policy { throw StoreReadError(inertDetail) }
  public func resolve(plugin: String, verb: Verb) throws -> ModeResolution { throw StoreReadError(inertDetail) }
  public func exceptionOffer(global: GlobalMode, plugin: String, verb: Verb, mode: GateMode, needsPassword: Bool) -> ExceptionOffer? { nil }
  public func writeException(plugin: String, verb: Verb?, mode: GateMode) async throws { throw StoreReadError(inertDetail) }
  public func setGlobal(_ mode: GlobalMode) async throws { throw StoreReadError(inertDetail) }
  public func setFingerprintForModals(_ enabled: Bool) async throws { throw StoreReadError(inertDetail) }
  public func setSoundEffects(_ enabled: Bool) async throws { throw StoreReadError(inertDetail) }
  public func setTts(_ settings: TtsSettings) async throws { throw StoreReadError(inertDetail) }
  public func update(_ change: PolicyChange, _ transform: @Sendable (Policy) -> Policy) async throws { throw StoreReadError(inertDetail) }
}

public struct InertCallerRegistry: CallerRegistry {
  public init() {}
  public func state(of address: NormalizedIP) throws -> CallerState? { throw StoreReadError(inertDetail) }
  public func set(_ address: NormalizedIP, state: CallerState) async throws { throw StoreReadError(inertDetail) }
  public func forget(_ address: NormalizedIP) async throws { throw StoreReadError(inertDetail) }
  public func all() async throws -> [CallerEntry] { throw StoreReadError(inertDetail) }
}

public struct InertPluginToggles: PluginToggles {
  public init() {}
  public func isEnabled(_ pluginID: String) throws -> Bool { throw StoreReadError(inertDetail) }
  public func set(_ pluginID: String, enabled: Bool) async throws { throw StoreReadError(inertDetail) }
}

public struct InertAuditSink: AuditSink {
  public init() {}
  public func append(_ record: AuditRecord) async throws { throw StoreReadError(inertDetail) }
  public func classify(status: Int, gated: Bool) -> AuditOutcome { .handlerFailed }
}

public struct InertAuditReader: AuditReader {
  public let health = Broadcast<AuditHealth>(.unavailable(inertDetail))
  public init() {}
  public func headers(filter: AuditFilter) async throws -> [AuditHeader] { throw StoreReadError(inertDetail) }
  public func record(id: String) async throws -> AuditRecord? { throw StoreReadError(inertDetail) }
  public func clear() async throws { throw StoreReadError(inertDetail) }
}

public struct InertGateService: GateService {
  public init() {}
  public func awaitSecure(_ request: GateRequest) async -> GateResult { .denied(detail: inertDetail) }
  public func awaitNotify(_ request: GateRequest) async -> GateResult { .denied(detail: inertDetail) }
  public func awaitCaller(_ address: NormalizedIP) async -> CallerPromptResult { .denied }
  public func awaitVaultUnlock(reason: String) async -> GateResult { .denied(detail: inertDetail) }
  public var canPresent: Bool { false }
  public func denyAll(reason: String, generation: UInt64) async {}
  public func reopen(generation: UInt64) async {}
  public func vaultNeed(_ input: VaultNeedInput) -> VaultNeedAction { .refuse(code: "secret_locked", detail: inertDetail) }
  public func admit(_ input: AdmitInput) -> AdmitVerdict { .deny(detail: inertDetail) }
}

public struct InertGatePresenting: GatePresenting {
  public let presentations: AsyncStream<GatePresentation>
  public init() { presentations = AsyncStream { $0.finish() } }
  public func didPresent(_ id: String, epoch: UInt64) async {}
  public func reportFullyVisible(_ id: String, _ visible: Bool) async {}
  public func beginHold(_ id: String, epoch: UInt64) async {}
  public func cancelHold(_ id: String) async {}
  public func resolve(_ id: String, decision: GateDecision, armedEpoch: UInt64) async {}
}

public struct InertAttendance: Attendance {
  public let current = Broadcast<AttendanceSnapshot>(AttendanceSnapshot(state: .locked, epoch: 0))
  public init() {}
  public func snapshot() -> AttendanceSnapshot { current.current }
  public func expectSystemPrompt<T: Sendable>(_ kind: SystemPromptKind, _ body: @Sendable () async throws -> T) async rethrows -> T {
    try await body()
  }
  public func setTransitionHandler(_ handler: @escaping @MainActor @Sendable (AttendanceTransition) -> Void) {}
  public func crossCheck(_ phase: ScenePhaseValue) {}
}

public struct InertAuthenticator: Authenticator {
  public init() {}
  public func evaluate(policy: AuthPolicy, reason: String) async -> AuthOutcome { .unavailable(inertDetail) }
  public func invalidate() {}
}

public struct InertKeychainStore: KeychainStore {
  public init() {}
  public func get(_ access: KeychainAccess) async throws -> SecretBytes? { throw StoreReadError(inertDetail) }
  public func set(_ value: SecretBytes, for access: KeychainAccess) async throws { throw StoreReadError(inertDetail) }
  public func delete(_ access: KeychainAccess) async throws { throw StoreReadError(inertDetail) }
}

public struct InertVaultService: VaultService {
  public let state = Broadcast<VaultState>(.absent)
  public init() {}
  public func isUnlocked() throws -> Bool { false }
  public func unlock(reason: String) async throws { throw PluginError("vault_missing", inertDetail) }
  public func lock() async {}
  public func index() async throws -> [VaultEntryMeta] { throw PluginError("vault_missing", inertDetail) }
  public func withSecret<T: Sendable>(name: String, _ body: @Sendable (SecretBytes) async throws -> T) async throws -> T {
    throw PluginError("secret_locked", inertDetail)
  }
}

public struct InertSSHKeyStore: SSHKeyStore {
  public init() {}
  public func createKey(name: String) async throws -> SSHKeyMaterial { throw PluginError("ssh_keygen_failed", inertDetail) }
  public func publicKeyOpenSSH(_ material: SSHKeyMaterial) throws -> String { throw PluginError("ssh_bad_key", inertDetail) }
}

public struct InertGoogleAuth: GoogleAuth {
  public init() {}
  public func accessToken() async throws -> String { throw PluginError("auth_not_configured", inertDetail) }
}

public struct InertGoogleHTTP: GoogleHTTP {
  public init() {}
  public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) { throw PluginError("auth_not_configured", inertDetail) }
}

public struct InertWebAuthSession: WebAuthSession {
  public init() {}
  public func run(url: URL, callbackScheme: String) async throws -> URL { throw PluginError("auth_not_configured", inertDetail) }
}

public struct InertSpeechOutput: SpeechOutput {
  public init() {}
  public func speak(_ text: String, rate: Double, pitch: Double) async throws { throw PluginError("tts_unavailable", inertDetail) }
}

public struct InertCuePlayer: CuePlayer {
  public init() {}
  public func play(cue: String) async throws { throw PluginError("audio_unavailable", inertDetail) }
}

public struct InertContactsSource: ContactsSource {
  public init() {}
  public func search(_ query: String, limit: Int) async throws -> [ContactRow] { throw PluginError("contacts_unavailable", inertDetail) }
}

public struct InertLogSource: LogSource {
  public init() {}
  public func read(lines: Int, filter: String?) async throws -> [String] { throw PluginError("logcat_failed", inertDetail) }
}

public struct InertDiagnosticsSource: DiagnosticsSource {
  public init() {}
  public func report() async throws -> JSONValue { throw PluginError("internal_error", inertDetail) }
}

public struct InertCallObserver: CallObserver {
  public init() {}
  /// Fail closed: with no observer, assume a call is in progress so nothing speaks.
  public var isInCall: Bool { true }
}

public struct InertNetworkInterfaces: NetworkInterfaces {
  public init() {}
  public func list() throws -> [InterfaceAddress] { [] }
}

public struct InertPathMonitor: PathMonitor {
  public let path = Broadcast<NetworkPath>(.unsatisfied)
  public init() {}
}

public struct InertBridgeControlling: BridgeControlling {
  public let state = Broadcast<BridgeState>(BridgeState(lastError: "inert seam: the bridge never starts"))
  public init() {}
  public func start(origin: StartOrigin) async {}
  public func stop(reason: StopReason) async {}
}

public struct InertRequestHandling: RequestHandling {
  public init() {}
  public func handle(_ head: RequestHead, remote: NormalizedIP, body: any RequestBodySource, context: RequestContext) async -> Resp {
    .error(status: 503, code: "internal_error", detail: inertDetail)
  }
}

public struct InertInFlightControl: InFlightControl {
  public init() {}
  public func interruptAll(generation: UInt64, detail: String) async -> Int { 0 }
  public func count(generation: UInt64) -> Int { 0 }
}

public struct InertAISummarizer: AISummarizer {
  public init() {}
  public func summarise(_ request: GateRequest) async -> String? { nil }
}

extension PluginDeps {
  /// Every dependency fail-closed. Placeholders and the inert host use it.
  public static func inert(clock: any StmpClock = SystemStmpClock(), buildInfo: BuildInfo = BuildInfo(version: "0.0.0", build: 0))
    -> PluginDeps
  {
    PluginDeps(
      policy: InertPolicyProviding(), toggles: InertPluginToggles(), callers: InertCallerRegistry(), auditSink: InertAuditSink(),
      auditReader: InertAuditReader(), gate: InertGateService(), attendance: InertAttendance(), vault: InertVaultService(),
      keychain: InertKeychainStore(), clock: clock, log: AppLog(), buildInfo: buildInfo, googleAuth: InertGoogleAuth(),
      googleHTTP: InertGoogleHTTP(), speech: InertSpeechOutput(), cues: InertCuePlayer(), contacts: InertContactsSource(),
      logs: InertLogSource(), diagnostics: InertDiagnosticsSource(), calls: InertCallObserver(), sshKeys: InertSSHKeyStore())
  }
}
