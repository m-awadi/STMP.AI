import Foundation
import STMPCore
import os

// One programmable fake per seam (D77): fail-closed by default (deny, throw, ABSENT, never start), scripted answers through
// `Scripted` (FIFO queue or handler), recorded calls, and `Broadcast`-backed `current` + `updates()` where D53 applies.
// Synchronous seams read a published snapshot (D122) so they can be called from a nonisolated synchronous function.

public typealias FakeMutation = Scripted<String, Result<Void, any Error>>
private func failing(_ name: String) -> @Sendable (String) -> Result<Void, any Error> { { _ in .failure(FakeError.unscripted(name)) } }

public struct ExceptionOfferInput: Sendable, Hashable {
  public var global: GlobalMode
  public var plugin: String
  public var verb: Verb
  public var mode: GateMode
  public var needsPassword: Bool
}
public struct ResolveInput: Sendable, Hashable {
  public var plugin: String
  public var verb: Verb
}
public struct ClassifyInput: Sendable, Hashable {
  public var status: Int
  public var gated: Bool
}

public final class FakePolicyProviding: PolicyProviding {
  public let snapshot = Broadcast<Result<Policy, StoreReadError>>(.failure(StoreReadError("fake policy: nothing published")))
  public let resolution = Scripted<ResolveInput, Result<ModeResolution, StoreReadError>> { _ in .failure(StoreReadError("fake: unscripted"))
  }
  public let offers = Scripted<ExceptionOfferInput, ExceptionOffer?> { _ in nil }
  public let mutations = FakeMutation(failClosed: failing("policy write"))
  public var writes: [String] { mutations.calls }
  public init() {}
  public func publish(_ policy: Policy) { snapshot.send(.success(policy)) }
  public func current() throws -> Policy { try snapshot.current.get() }
  public func resolve(plugin: String, verb: Verb) throws -> ModeResolution {
    try resolution.call(ResolveInput(plugin: plugin, verb: verb)).get()
  }
  public func exceptionOffer(global: GlobalMode, plugin: String, verb: Verb, mode: GateMode, needsPassword: Bool) -> ExceptionOffer? {
    offers.call(ExceptionOfferInput(global: global, plugin: plugin, verb: verb, mode: mode, needsPassword: needsPassword))
  }
  public func writeException(plugin: String, verb: Verb?, mode: GateMode) async throws {
    try mutations.call("writeException(\(plugin),\(verb?.rawValue ?? "ANY"),\(mode.rawValue))").get()
  }
  public func setGlobal(_ mode: GlobalMode) async throws { try mutations.call("setGlobal(\(mode.rawValue))").get() }
  public func setFingerprintForModals(_ enabled: Bool) async throws { try mutations.call("setFingerprintForModals(\(enabled))").get() }
  public func setSoundEffects(_ enabled: Bool) async throws { try mutations.call("setSoundEffects(\(enabled))").get() }
  public func setTts(_ settings: TtsSettings) async throws { try mutations.call("setTts").get() }
  public func update(_ change: PolicyChange, _ transform: @Sendable (Policy) -> Policy) async throws {
    try mutations.call("update(\(change))").get()
    if case .success(let p) = snapshot.current { snapshot.send(.success(transform(p))) }
  }
}

public final class FakeCallerRegistry: CallerRegistry {
  public let snapshot = Broadcast<Result<[NormalizedIP: CallerState], StoreReadError>>(
    .failure(StoreReadError("fake callers: nothing published")))
  public let mutations = FakeMutation(failClosed: failing("caller write"))
  public var writes: [String] { mutations.calls }
  public init() {}
  public func publish(_ entries: [NormalizedIP: CallerState]) { snapshot.send(.success(entries)) }
  public func state(of address: NormalizedIP) throws -> CallerState? {
    let entries = try snapshot.current.get()
    return address.isLoopback ? .verified(name: "this device") : entries[address]
  }
  public func set(_ address: NormalizedIP, state: CallerState) async throws { try mutations.call("set(\(address))").get() }
  public func forget(_ address: NormalizedIP) async throws { try mutations.call("forget(\(address))").get() }
  public func all() async throws -> [CallerEntry] {
    try snapshot.current.get().map { CallerEntry(address: $0.key, state: $0.value) }.sorted { $0.address.rawValue < $1.address.rawValue }
  }
}

public final class FakePluginToggles: PluginToggles {
  public let snapshot = Broadcast<Result<[String: Bool], StoreReadError>>(.failure(StoreReadError("fake toggles: nothing published")))
  public let mutations = FakeMutation(failClosed: failing("toggle write"))
  public var writes: [String] { mutations.calls }
  public init() {}
  public func publish(_ toggles: [String: Bool]) { snapshot.send(.success(toggles)) }
  public func isEnabled(_ pluginID: String) throws -> Bool { try snapshot.current.get()[pluginID] ?? true }
  public func set(_ pluginID: String, enabled: Bool) async throws { try mutations.call("set(\(pluginID),\(enabled))").get() }
}

public final class FakeAuditSink: AuditSink {
  public let appended = Scripted<AuditRecord, Result<Void, any Error>> { _ in .failure(FakeError.unscripted("audit append")) }
  public let classification = Scripted<ClassifyInput, AuditOutcome> { _ in .handlerFailed }
  public init() {}
  public func append(_ record: AuditRecord) async throws { try appended.call(record).get() }
  public func classify(status: Int, gated: Bool) -> AuditOutcome { classification.call(ClassifyInput(status: status, gated: gated)) }
}

public final class FakeAuditReader: AuditReader {
  public let health = Broadcast<AuditHealth>(.unavailable("fake: unscripted"))
  public let headerResults = Scripted<AuditFilter, Result<[AuditHeader], any Error>> { _ in .failure(FakeError.unscripted("audit headers"))
  }
  public let recordResults = Scripted<String, Result<AuditRecord?, any Error>> { _ in .failure(FakeError.unscripted("audit record")) }
  public let mutations = FakeMutation(failClosed: failing("audit clear"))
  public init() {}
  public func headers(filter: AuditFilter) async throws -> [AuditHeader] { try headerResults.call(filter).get() }
  public func record(id: String) async throws -> AuditRecord? { try recordResults.call(id).get() }
  public func clear() async throws { try mutations.call("clear").get() }
}

public final class FakeGateService: GateService {
  public let secure = Scripted<GateRequest, GateResult> { _ in .denied(detail: "fake gate: unscripted") }
  public let notify = Scripted<GateRequest, GateResult> { _ in .denied(detail: "fake gate: unscripted") }
  public let caller = Scripted<NormalizedIP, CallerPromptResult> { _ in .denied }
  public let vaultUnlock = Scripted<String, GateResult> { _ in .denied(detail: "fake gate: unscripted") }
  public let vaultNeedRule = Scripted<VaultNeedInput, VaultNeedAction> { _ in
    .refuse(code: "secret_locked", detail: "fake gate: unscripted")
  }
  public let admitRule = Scripted<AdmitInput, AdmitVerdict> { _ in .deny(detail: "fake gate: unscripted") }
  public let controls = CallLog<String>()
  private let presentable = OSAllocatedUnfairLock(initialState: false)
  public init() {}
  public var canPresent: Bool {
    get { presentable.withLock { $0 } }
    set { presentable.withLock { $0 = newValue } }
  }
  public func awaitSecure(_ request: GateRequest) async -> GateResult { secure.call(request) }
  public func awaitNotify(_ request: GateRequest) async -> GateResult { notify.call(request) }
  public func awaitCaller(_ address: NormalizedIP) async -> CallerPromptResult { caller.call(address) }
  public func awaitVaultUnlock(reason: String) async -> GateResult { vaultUnlock.call(reason) }
  public func denyAll(reason: String, generation: UInt64) async { controls.record("denyAll(\(reason),\(generation))") }
  public func reopen(generation: UInt64) async { controls.record("reopen(\(generation))") }
  public func vaultNeed(_ input: VaultNeedInput) -> VaultNeedAction { vaultNeedRule.call(input) }
  public func admit(_ input: AdmitInput) -> AdmitVerdict { admitRule.call(input) }
}

public final class FakeGatePresenting: GatePresenting {
  public let presentations: AsyncStream<GatePresentation>
  private let continuation: AsyncStream<GatePresentation>.Continuation
  public let events = CallLog<String>()
  public let decisions = CallLog<GateDecision>()
  public init() { (presentations, continuation) = AsyncStream.makeStream() }
  public func present(_ p: GatePresentation) { continuation.yield(p) }
  public func didPresent(_ id: String, epoch: UInt64) async { events.record("didPresent(\(id),\(epoch))") }
  public func reportFullyVisible(_ id: String, _ visible: Bool) async { events.record("fullyVisible(\(id),\(visible))") }
  public func beginHold(_ id: String, epoch: UInt64) async { events.record("beginHold(\(id),\(epoch))") }
  public func cancelHold(_ id: String) async { events.record("cancelHold(\(id))") }
  public func resolve(_ id: String, decision: GateDecision, armedEpoch: UInt64) async {
    events.record("resolve(\(id),\(armedEpoch))")
    decisions.record(decision)
  }
}

public final class FakeAttendance: Attendance {
  public let current: Broadcast<AttendanceSnapshot>
  public let prompts = CallLog<SystemPromptKind>()
  public let crossChecks = CallLog<ScenePhaseValue>()
  private let handler = OSAllocatedUnfairLock<(@MainActor @Sendable (AttendanceTransition) -> Void)?>(initialState: nil)
  /// Fail closed: a fake nobody scripted reports a locked device.
  public init(state: AttendanceState = .locked) { current = Broadcast(AttendanceSnapshot(state: state, epoch: 0), buffering: .unbounded) }
  public func snapshot() -> AttendanceSnapshot { current.current }
  public func expectSystemPrompt<T: Sendable>(_ kind: SystemPromptKind, _ body: @Sendable () async throws -> T) async rethrows -> T {
    prompts.record(kind)
    return try await body()
  }
  public func setTransitionHandler(_ h: @escaping @MainActor @Sendable (AttendanceTransition) -> Void) { handler.withLock { $0 = h } }
  public func crossCheck(_ phase: ScenePhaseValue) { crossChecks.record(phase) }
  /// Bumps the epoch, publishes, and calls the registered handler on main, as the real monitor does.
  @MainActor public func transition(to state: AttendanceState) {
    let old = current.current
    let next = AttendanceSnapshot(state: state, epoch: old.epoch + 1)
    current.send(next)
    let h = handler.withLock { $0 }
    h?(AttendanceTransition(from: old.state, to: state, epoch: next.epoch))
  }
}

public final class FakeAuthenticator: Authenticator {
  public let outcomes = Scripted<String, AuthOutcome> { _ in .unavailable("fake: unscripted") }
  public let invalidations = CallLog<Void>()
  public init() {}
  public func evaluate(policy: AuthPolicy, reason: String) async -> AuthOutcome { outcomes.call("\(policy):\(reason)") }
  public func invalidate() { invalidations.record(()) }
}

public final class FakeKeychainStore: KeychainStore {
  public let reads = Scripted<KeychainAccess, Result<SecretBytes?, any Error>> { _ in .failure(FakeError.unscripted("keychain get")) }
  public let mutations = FakeMutation(failClosed: failing("keychain write"))
  public init() {}
  public func get(_ access: KeychainAccess) async throws -> SecretBytes? { try reads.call(access).get() }
  public func set(_ value: SecretBytes, for access: KeychainAccess) async throws { try mutations.call("set(\(access.account))").get() }
  public func delete(_ access: KeychainAccess) async throws { try mutations.call("delete(\(access.account))").get() }
}

public final class FakeVaultService: VaultService {
  public let state = Broadcast<VaultState>(.absent)
  public let unlockedSnapshot = Broadcast<Result<Bool, StoreReadError>>(.success(false))
  public let secrets = Scripted<String, Result<SecretBytes, any Error>> { _ in
    .failure(PluginError("secret_locked", "fake vault: unscripted"))
  }
  public let indexResults = Scripted<Void, Result<[VaultEntryMeta], any Error>> { _ in
    .failure(PluginError("vault_missing", "fake vault: unscripted"))
  }
  public let mutations = FakeMutation(failClosed: failing("vault unlock"))
  public init() {}
  public func isUnlocked() throws -> Bool { try unlockedSnapshot.current.get() }
  public func unlock(reason: String) async throws { try mutations.call("unlock(\(reason))").get() }
  public func lock() async {
    unlockedSnapshot.send(.success(false))
    state.send(.locked)
  }
  public func index() async throws -> [VaultEntryMeta] { try indexResults.call(()).get() }
  public func withSecret<T: Sendable>(name: String, _ body: @Sendable (SecretBytes) async throws -> T) async throws -> T {
    try await body(try secrets.call(name).get())
  }
}

public final class FakeSSHKeyStore: SSHKeyStore {
  public let creations = Scripted<String, Result<SSHKeyMaterial, any Error>> { _ in .failure(FakeError.unscripted("ssh createKey")) }
  public let publicKeys = Scripted<Void, Result<String, any Error>> { _ in .failure(FakeError.unscripted("ssh publicKey")) }
  public init() {}
  public func createKey(name: String) async throws -> SSHKeyMaterial { try creations.call(name).get() }
  public func publicKeyOpenSSH(_ material: SSHKeyMaterial) throws -> String { try publicKeys.call(()).get() }
}

public final class FakeGoogleAuth: GoogleAuth {
  public let tokens = Scripted<Void, Result<String, any Error>> { _ in .failure(FakeError.unscripted("google token")) }
  public init() {}
  public func accessToken() async throws -> String { try tokens.call(()).get() }
}

public final class FakeGoogleHTTP: GoogleHTTP {
  public let responses = Scripted<URLRequest, Result<(Data, HTTPURLResponse), any Error>> { _ in
    .failure(FakeError.unscripted("google http"))
  }
  public init() {}
  public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) { try responses.call(request).get() }
}

public final class FakeWebAuthSession: WebAuthSession {
  public let results = Scripted<URL, Result<URL, any Error>> { _ in .failure(FakeError.unscripted("web auth")) }
  public init() {}
  public func run(url: URL, callbackScheme: String) async throws -> URL { try results.call(url).get() }
}

public final class FakeSpeechOutput: SpeechOutput {
  public let results = Scripted<String, Result<Void, any Error>> { _ in .failure(PluginError("tts_unavailable", "fake: unscripted")) }
  public init() {}
  public func speak(_ text: String, rate: Double, pitch: Double) async throws { try results.call(text).get() }
}

public final class FakeCuePlayer: CuePlayer {
  public let results = Scripted<String, Result<Void, any Error>> { _ in .failure(PluginError("audio_unavailable", "fake: unscripted")) }
  public init() {}
  public func play(cue: String) async throws { try results.call(cue).get() }
}

public final class FakeContactsSource: ContactsSource {
  public let results = Scripted<String, Result<[ContactRow], any Error>> { _ in
    .failure(PluginError("contacts_unavailable", "fake: unscripted"))
  }
  public init() {}
  public func search(_ query: String, limit: Int) async throws -> [ContactRow] { try results.call(query).get() }
}

public final class FakeLogSource: LogSource {
  public let results = Scripted<Int, Result<[String], any Error>> { _ in .failure(PluginError("logcat_failed", "fake: unscripted")) }
  public init() {}
  public func read(lines: Int, filter: String?) async throws -> [String] { try results.call(lines).get() }
}

public final class FakeDiagnosticsSource: DiagnosticsSource {
  public let results = Scripted<Void, Result<JSONValue, any Error>> { _ in .failure(FakeError.unscripted("diagnostics")) }
  public init() {}
  public func report() async throws -> JSONValue { try results.call(()).get() }
}

public final class FakeCallObserver: CallObserver {
  private let inCall = OSAllocatedUnfairLock(initialState: true)
  public init() {}
  /// Fail closed: a call is assumed to be in progress until a test says otherwise.
  public var isInCall: Bool {
    get { inCall.withLock { $0 } }
    set { inCall.withLock { $0 = newValue } }
  }
}

public final class FakeNetworkInterfaces: NetworkInterfaces {
  public let results = Scripted<Void, Result<[InterfaceAddress], any Error>> { _ in .success([]) }
  public init() {}
  public func list() throws -> [InterfaceAddress] { try results.call(()).get() }
}

public final class FakePathMonitor: PathMonitor {
  public let path = Broadcast<NetworkPath>(.unsatisfied)
  public init() {}
}

public final class FakeBridgeControlling: BridgeControlling {
  public let state = Broadcast<BridgeState>(BridgeState())
  public let starts = CallLog<StartOrigin>()
  public let stops = CallLog<StopReason>()
  public init() {}
  /// Fail closed: recording a start does not make the bridge serve; a test publishes `state` itself.
  public func start(origin: StartOrigin) async { starts.record(origin) }
  public func stop(reason: StopReason) async { stops.record(reason) }
}

public final class FakeRequestHandling: RequestHandling {
  public let responses = Scripted<RequestHead, Resp> { _ in .error(status: 503, code: "internal_error", detail: "fake handler: unscripted")
  }
  public init() {}
  public func handle(_ head: RequestHead, remote: NormalizedIP, body: any RequestBodySource, context: RequestContext) async -> Resp {
    responses.call(head)
  }
}

public final class FakeInFlightControl: InFlightControl {
  public let interrupts = Scripted<UInt64, Int> { _ in 0 }
  public let counts = Scripted<UInt64, Int> { _ in 0 }
  public init() {}
  public func interruptAll(generation: UInt64, detail: String) async -> Int { interrupts.call(generation) }
  public func count(generation: UInt64) -> Int { counts.call(generation) }
}

public final class FakeAISummarizer: AISummarizer {
  public init() {}
  public func summarise(_ request: GateRequest) async -> String? { nil }
}

/// A body source over fixed bytes.
public struct FakeBodySource: RequestBodySource {
  public var data: Data
  public init(_ data: Data = Data()) { self.data = data }
  public func readAll(limit: Int) async throws -> Data {
    if data.count > limit { throw BodyReadError.tooLarge(bytes: data.count) }
    return data
  }
  public func stream() -> AsyncThrowingStream<Data, any Error> {
    AsyncThrowingStream {
      $0.yield(data)
      $0.finish()
    }
  }
}

/// A plugin with scripted route handlers, for registry and dispatch tests.
public struct FakePlugin: Plugin {
  public let id: String
  public let displayName: String
  public let enabledByDefault: Bool
  public let templates: [RouteTemplate]
  public let handler: @Sendable (Req) async throws -> Resp
  public init(
    id: String, templates: [RouteTemplate], enabledByDefault: Bool = true,
    handler: @escaping @Sendable (Req) async throws -> Resp = { _ in .ok(.object([:])) }
  ) {
    self.id = id
    self.displayName = id
    self.enabledByDefault = enabledByDefault
    self.templates = templates
    self.handler = handler
  }
  public func routes() -> [Route] { templates.map { Route($0, handler: handler) } }
  public func status() async -> PluginStatus { PluginStatus(summary: "fake", ready: true) }
}

/// Every fake at once, plus a `PluginDeps` over them.
public struct FakeServices: Sendable {
  public let policy = FakePolicyProviding()
  public let callers = FakeCallerRegistry()
  public let toggles = FakePluginToggles()
  public let auditSink = FakeAuditSink()
  public let auditReader = FakeAuditReader()
  public let gate = FakeGateService()
  public let attendance = FakeAttendance()
  public let vault = FakeVaultService()
  public let keychain = FakeKeychainStore()
  public let googleAuth = FakeGoogleAuth()
  public let googleHTTP = FakeGoogleHTTP()
  public let speech = FakeSpeechOutput()
  public let cues = FakeCuePlayer()
  public let contacts = FakeContactsSource()
  public let logs = FakeLogSource()
  public let diagnostics = FakeDiagnosticsSource()
  public let calls = FakeCallObserver()
  public let sshKeys = FakeSSHKeyStore()
  public let clock = TestClock()
  public init() {}
  public var deps: PluginDeps {
    PluginDeps(
      policy: policy, toggles: toggles, callers: callers, auditSink: auditSink, auditReader: auditReader, gate: gate,
      attendance: attendance, vault: vault, keychain: keychain, clock: clock, log: AppLog(),
      buildInfo: BuildInfo(version: "0.0.0", build: 0), googleAuth: googleAuth, googleHTTP: googleHTTP, speech: speech, cues: cues,
      contacts: contacts, logs: logs, diagnostics: diagnostics, calls: calls, sshKeys: sshKeys)
  }
}
