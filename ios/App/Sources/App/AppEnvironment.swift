import Foundation
import Observation
import STMPBridge
import STMPCore

/// The DI container (PLAN 1.9). `live` composes the `Composition/*+Live.swift` factories and `BridgeFactory`; `inert` builds
/// every service from STMPCore's fail-closed `Inert…` types and is what the unit-test host and `#Preview` use. The app
/// binary never links STMPTestSupport (D111), so `preview(...)` over the fakes lives in `App/Tests/Support/`.
@MainActor @Observable
final class AppEnvironment {
  let now: @Sendable () -> Date
  let calendar: Calendar
  let storageRoot: StorageRoot
  let preferences: AppPreferences
  let launchOptions: LaunchOptions
  let log: AppLog
  let buildInfo: BuildInfo

  // Stores and gate (built by BridgeLive until T3, T4 and T5 land their live objects).
  let policy: any PolicyProviding
  let toggles: any PluginToggles
  let callers: any CallerRegistry
  let auditSink: any AuditSink
  let auditReader: any AuditReader
  let gate: any GateService
  let gatePresenting: any GatePresenting
  // Platform services.
  let attendance: any Attendance
  let authenticator: any Authenticator
  let keychain: any KeychainStore
  let vault: any VaultService
  let googleAuth: any GoogleAuth
  let googleHTTP: any GoogleHTTP
  let webAuth: any WebAuthSession
  let speech: any SpeechOutput
  let cues: any CuePlayer
  let contacts: any ContactsSource
  let logs: any LogSource
  let diagnostics: any DiagnosticsSource
  let calls: any CallObserver
  let sshKeys: any SSHKeyStore
  let interfaces: any NetworkInterfaces
  let pathMonitor: any PathMonitor
  let bridge: any BridgeControlling
  let lifecycle: AppLifecycle

  /// Fed from `Policy.soundEffects` (default on while the policy is unreadable). Views read it as `\.soundEffectsEnabled`.
  var soundEffectsEnabled: Bool { (try? policy.current().soundEffects) ?? true }

  init(
    now: @escaping @Sendable () -> Date, calendar: Calendar, storageRoot: StorageRoot, preferences: AppPreferences,
    launchOptions: LaunchOptions, log: AppLog, buildInfo: BuildInfo, bridgeServices b: BridgeServices, attendance: AttendanceServices,
    vault v: VaultServices, google: GoogleServices, device: DeviceServices, ssh: SSHServices, network: NetworkServices,
    bridge: any BridgeControlling
  ) {
    self.now = now
    self.calendar = calendar
    self.storageRoot = storageRoot
    self.preferences = preferences
    self.launchOptions = launchOptions
    self.log = log
    self.buildInfo = buildInfo
    policy = b.policy
    toggles = b.toggles
    callers = b.callers
    auditSink = b.auditSink
    auditReader = b.auditReader
    gate = b.gate
    gatePresenting = b.gatePresenting
    self.attendance = attendance.attendance
    authenticator = v.authenticator
    keychain = v.keychain
    vault = v.vault
    googleAuth = google.auth
    googleHTTP = google.http
    webAuth = google.webAuth
    speech = device.speech
    cues = device.cues
    contacts = device.contacts
    logs = device.logs
    diagnostics = device.diagnostics
    calls = device.calls
    sshKeys = ssh.keys
    interfaces = network.interfaces
    pathMonitor = network.path
    self.bridge = bridge
    lifecycle = AppLifecycle(bridge: bridge)
  }

  /// Wall-clock time in views comes only from `now` and `calendar` (D70).
  static let utcCalendar: Calendar = {
    var c = Calendar(identifier: .gregorian)
    c.timeZone = TimeZone(identifier: "UTC") ?? .gmt
    c.locale = Locale(identifier: "en_US_POSIX")
    return c
  }()

  /// The live composition. T0's factories are fail-closed stubs, so the bridge never starts until T12 fills `BridgeFactory`.
  static func live(storageRoot: StorageRoot, launchOptions: LaunchOptions, preferences: AppPreferences) -> AppEnvironment {
    let log = AppLog()
    let buildInfo = BuildInfo.from(.main)
    let ctx = CompositionContext(
      storageRoot: storageRoot, launchOptions: launchOptions, preferences: preferences, log: log, buildInfo: buildInfo,
      clock: SystemStmpClock())
    let network = NetworkLive.make(ctx)
    let attendance = AttendanceLive.make(ctx)
    let vault = VaultLive.make(ctx)
    let google = GoogleLive.make(ctx)
    let device = DeviceLive.make(ctx)
    let ssh = SSHLive.make(ctx)
    let stores = BridgeLive.make(ctx)
    let deps = PluginDeps(
      policy: stores.policy, toggles: stores.toggles, callers: stores.callers, auditSink: stores.auditSink, auditReader: stores.auditReader,
      gate: stores.gate, attendance: attendance.attendance, vault: vault.vault, keychain: vault.keychain, clock: ctx.clock, log: log,
      buildInfo: buildInfo, googleAuth: google.auth, googleHTTP: google.http, speech: device.speech, cues: device.cues,
      contacts: device.contacts, logs: device.logs, diagnostics: device.diagnostics, calls: device.calls, sshKeys: ssh.keys)
    let config = BridgeConfig(bind: launchOptions.testBindLoopback ? .loopback : .tailnet, port: launchOptions.testPort ?? 8787)
    let bridge = BridgeFactory.make(
      config: config, platform: BridgePlatform(deps: deps, interfaces: network.interfaces, path: network.path))
    return AppEnvironment(
      now: { Date() }, calendar: .current, storageRoot: storageRoot, preferences: preferences, launchOptions: launchOptions, log: log,
      buildInfo: buildInfo, bridgeServices: stores, attendance: attendance, vault: vault, google: google, device: device, ssh: ssh,
      network: network, bridge: bridge)
  }

  /// Every service fail-closed, a given storage root, no bridge start, no Keychain access and a private defaults domain (D69, D111).
  static func inert(storageRoot: StorageRoot) -> AppEnvironment {
    let inertDeps = PluginDeps.inert()
    return AppEnvironment(
      now: { Date(timeIntervalSince1970: 1_767_268_800) }, calendar: utcCalendar, storageRoot: storageRoot,
      preferences: .ephemeral(), launchOptions: .none, log: AppLog(), buildInfo: inertDeps.buildInfo,
      bridgeServices: BridgeServices.inert,
      attendance: AttendanceServices(attendance: InertAttendance()),
      vault: VaultServices(authenticator: InertAuthenticator(), keychain: InertKeychainStore(), vault: InertVaultService()),
      google: GoogleServices(auth: InertGoogleAuth(), http: InertGoogleHTTP(), webAuth: InertWebAuthSession()),
      device: DeviceServices.inert, ssh: SSHServices(keys: InertSSHKeyStore()),
      network: NetworkServices(interfaces: InertNetworkInterfaces(), path: InertPathMonitor()), bridge: InertBridgeControlling())
  }
}
