import Foundation
import STMPCore
import STMPTestSupport

@testable import STMP

extension AppEnvironment {
  /// A snapshot-test environment over the STMPTestSupport fakes, `now` fixed at 2026-01-01T12:00:00Z and a UTC `en_US_POSIX`
  /// calendar (D70). Test code only: the app binary never links the fakes (D111). `#Preview` blocks use `inert` instead.
  @MainActor
  static func preview(
    services: FakeServices = FakeServices(), storageRoot: StorageRoot = StorageRoot(url: FileManager.default.temporaryDirectory)
  ) -> AppEnvironment {
    let bridge = FakeBridgeControlling()
    return AppEnvironment(
      now: { StmpSnapshot.fixedNow }, calendar: AppEnvironment.utcCalendar, storageRoot: storageRoot, preferences: .ephemeral(),
      launchOptions: .none, log: AppLog(), buildInfo: BuildInfo(version: "0.1.0", build: 1),
      bridgeServices: BridgeServices(
        policy: services.policy, toggles: services.toggles, callers: services.callers, auditSink: services.auditSink,
        auditReader: services.auditReader, gate: services.gate, gatePresenting: FakeGatePresenting()),
      attendance: AttendanceServices(attendance: services.attendance),
      vault: VaultServices(authenticator: FakeAuthenticator(), keychain: services.keychain, vault: services.vault),
      google: GoogleServices(auth: services.googleAuth, http: services.googleHTTP, webAuth: FakeWebAuthSession()),
      device: DeviceServices(
        speech: services.speech, cues: services.cues, contacts: services.contacts, logs: services.logs,
        diagnostics: services.diagnostics, calls: services.calls),
      ssh: SSHServices(keys: services.sshKeys),
      network: NetworkServices(interfaces: FakeNetworkInterfaces(), path: FakePathMonitor()), bridge: bridge)
  }
}
