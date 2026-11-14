import STMPCore
import STMPTestSupport
import XCTest

/// Every fake fails closed by default and takes scripted answers (D77, I1).
final class FakesFailClosedTests: XCTestCase {
  private let ip = NormalizedIP.parse("100.101.102.103")!
  private let request = GateRequest(
    id: "1", caller: GateCaller(address: NormalizedIP.parse("100.101.102.103")!, label: "x"), plugin: "p", verb: .read, method: "GET",
    path: "/x",
    summary: "")

  func testPolicyThrowsUntilPublished() throws {
    let f = FakePolicyProviding()
    XCTAssertThrowsError(try f.current())
    XCTAssertThrowsError(try f.resolve(plugin: "p", verb: .read))
    XCTAssertNil(f.exceptionOffer(global: .whitelist, plugin: "p", verb: .read, mode: .secure, needsPassword: false))
    f.publish(Policy(global: .whitelist))
    XCTAssertEqual(try f.current().global, .whitelist)
  }

  func testPolicyWritesThrowAndUpdatePublishes() async throws {
    let f = FakePolicyProviding()
    do {
      try await f.setGlobal(.paranoid)
      XCTFail("a write must throw by default")
    } catch {}
    f.publish(Policy(global: .whitelist))
    f.mutations.enqueue(.success(()))
    try await f.update(.global) {
      var p = $0
      p.global = .paranoid
      return p
    }
    XCTAssertEqual(try f.current().global, .paranoid)
    XCTAssertEqual(f.writes.count, 2)
  }

  func testCallersTogglesAndAudit() async throws {
    XCTAssertThrowsError(try FakeCallerRegistry().state(of: ip))
    XCTAssertThrowsError(try FakePluginToggles().isEnabled("x"))
    let sink = FakeAuditSink()
    do {
      try await sink.append(AuditRecord.sample)
      XCTFail("audit sink must throw by default")
    } catch {}
    XCTAssertEqual(sink.classify(status: 200, gated: true), .handlerFailed)
    sink.classification.enqueue(.approved)
    XCTAssertEqual(sink.classify(status: 200, gated: true), .approved)
    XCTAssertEqual(FakeAuditReader().health.current, .unavailable("fake: unscripted"))
  }

  func testGateDeniesAndPureRulesFailClosed() async {
    let g = FakeGateService()
    XCTAssertFalse(g.canPresent)
    let secure = await g.awaitSecure(request)
    let notify = await g.awaitNotify(request)
    let caller = await g.awaitCaller(ip)
    XCTAssertEqual(secure, .denied(detail: "fake gate: unscripted"))
    XCTAssertEqual(notify, .denied(detail: "fake gate: unscripted"))
    XCTAssertEqual(caller, .denied)
    XCTAssertEqual(
      g.vaultNeed(VaultNeedInput(needsVault: true, unlocked: true, state: .unlocked, mode: .secure, canPresent: true)),
      .refuse(code: "secret_locked", detail: "fake gate: unscripted"))
    XCTAssertEqual(
      g.admit(AdmitInput(attendance: AttendanceSnapshot(state: .attended, epoch: 1), gated: true, mode: .secure)),
      .deny(detail: "fake gate: unscripted"))
    g.secure.enqueue(.approved)
    let scripted = await g.awaitSecure(request)
    XCTAssertEqual(scripted, .approved)
    XCTAssertEqual(g.secure.callCount, 2)
  }

  func testVaultIsAbsentAndLocked() async throws {
    let v = FakeVaultService()
    XCTAssertEqual(v.state.current, .absent)
    XCTAssertFalse(try v.isUnlocked())
    do {
      _ = try await v.withSecret(name: "x") { _ in 1 }
      XCTFail("a secret must not be readable by default")
    } catch let e as PluginError { XCTAssertEqual(e.code, "secret_locked") }
  }

  func testBridgeNeverStartsAndRecordsCalls() async {
    let b = FakeBridgeControlling()
    await b.start(origin: .launch)
    XCTAssertFalse(b.state.current.serverUp)
    XCTAssertEqual(b.starts.all, [.launch])
  }

  func testAttendanceIsLockedAndTransitionCallsTheHandlerOnMain() async {
    let a = FakeAttendance()
    XCTAssertEqual(a.snapshot().state, .locked)
    let seen = CallLog<AttendanceTransition>()
    a.setTransitionHandler { seen.record($0) }
    await MainActor.run { a.transition(to: .attended) }
    XCTAssertEqual(seen.all, [AttendanceTransition(from: .locked, to: .attended, epoch: 1)])
    let value = await a.expectSystemPrompt(.localAuthentication) { 5 }
    XCTAssertEqual(value, 5)
    XCTAssertEqual(a.prompts.all, [.localAuthentication])
  }

  func testDeviceAndGoogleFakesThrowByDefault() async {
    do {
      try await FakeSpeechOutput().speak("hi", rate: 1, pitch: 1)
      XCTFail("tts must throw by default")
    } catch let e as PluginError { XCTAssertEqual(e.code, "tts_unavailable") } catch { XCTFail("\(error)") }
    do {
      _ = try await FakeGoogleAuth().accessToken()
      XCTFail("a token must not exist by default")
    } catch {}
    XCTAssertTrue(FakeCallObserver().isInCall)
    XCTAssertEqual(FakePathMonitor().path.current, .unsatisfied)
  }

  func testRequestHandlingAnswers503ByDefault() async {
    let r = await FakeRequestHandling().handle(
      RequestHead(method: "GET", path: "/x"), remote: ip, body: FakeBodySource(), context: RequestContext(generation: 1, acceptedAt: Date())
    )
    XCTAssertEqual(r.status, 503)
  }

  func testTestClockAdvancesSleepers() async throws {
    let clock = TestClock()
    let done = CallLog<Int>()
    let t = Task {
      try await clock.sleep(for: .seconds(10))
      done.record(1)
    }
    while clock.sleeperCount == 0 { await Task.yield() }
    clock.advance(by: .seconds(9))
    XCTAssertEqual(done.all, [])
    clock.advance(by: .seconds(1))
    try await t.value
    XCTAssertEqual(done.all, [1])
  }
}

extension AuditRecord {
  fileprivate static var sample: AuditRecord {
    AuditRecord(
      id: "1", ts: 1, outcome: .approved, label: "", plugin: "p", verb: "READ", origin: "", remote: "", requester: "", route: "",
      method: "GET",
      path: "/", summary: "", responseStatus: 200, decision: AuditDecision(mode: "PARANOID", source: .default), durationMs: 0, noise: false,
      appVersionName: "0", appVersionCode: 0)
  }
}

/// D122: the synchronous accessors read a published snapshot from a nonisolated synchronous function.
final class SyncSeamReadTests: XCTestCase {
  private nonisolated static func policy(_ s: any PolicyProviding) throws -> GlobalMode { try s.current().global }
  private nonisolated static func caller(_ s: any CallerRegistry, _ ip: NormalizedIP) throws -> CallerState? { try s.state(of: ip) }
  private nonisolated static func toggle(_ s: any PluginToggles) throws -> Bool { try s.isEnabled("x") }
  private nonisolated static func unlocked(_ s: any VaultService) throws -> Bool { try s.isUnlocked() }

  func testPolicySnapshot() throws {
    let f = FakePolicyProviding()
    XCTAssertThrowsError(try Self.policy(f))
    f.publish(Policy(global: .dontDisturb))
    XCTAssertEqual(try Self.policy(f), .dontDisturb)
  }

  func testCallerSnapshotAndLoopback() throws {
    let f = FakeCallerRegistry()
    let ip = NormalizedIP.parse("100.101.102.103")!
    XCTAssertThrowsError(try Self.caller(f, ip))
    f.publish([ip: .verified(name: "laptop")])
    XCTAssertEqual(try Self.caller(f, ip), .verified(name: "laptop"))
    XCTAssertEqual(try Self.caller(f, NormalizedIP.parse("127.0.0.1")!), .verified(name: "this device"))
    XCTAssertNil(try Self.caller(f, NormalizedIP.parse("100.101.102.104")!))
  }

  func testToggleSnapshot() throws {
    let f = FakePluginToggles()
    XCTAssertThrowsError(try Self.toggle(f))
    f.publish(["x": false])
    XCTAssertFalse(try Self.toggle(f))
  }

  func testVaultSnapshot() throws {
    let f = FakeVaultService()
    XCTAssertFalse(try Self.unlocked(f))
    f.unlockedSnapshot.send(.success(true))
    XCTAssertTrue(try Self.unlocked(f))
    f.unlockedSnapshot.send(.failure(StoreReadError("unreadable")))
    XCTAssertThrowsError(try Self.unlocked(f))
  }
}

/// The shipped Inert implementations fail closed too (D111).
final class InertSeamsTests: XCTestCase {
  func testInertDepsFailClosed() async throws {
    let d = PluginDeps.inert()
    XCTAssertThrowsError(try d.policy.current())
    XCTAssertThrowsError(try d.callers.state(of: NormalizedIP.parse("100.64.0.1")!))
    XCTAssertFalse(d.gate.canPresent)
    let r = await d.gate.awaitSecure(
      GateRequest(
        id: "1", caller: GateCaller(address: NormalizedIP.parse("100.64.0.1")!, label: ""), plugin: "p", verb: .read, method: "GET",
        path: "/", summary: ""))
    if case .approved = r { XCTFail("inert gate must deny") }
    XCTAssertEqual(d.vault.state.current, .absent)
    XCTAssertEqual(d.auditSink.classify(status: 200, gated: false), .handlerFailed)
    XCTAssertEqual(d.attendance.snapshot().state, .locked)
    XCTAssertTrue(d.calls.isInCall)
    do {
      try await d.auditSink.append(AuditRecord.sample)
      XCTFail("inert audit sink must throw")
    } catch {}
    let bridge = InertBridgeControlling()
    await bridge.start(origin: .user)
    XCTAssertFalse(bridge.state.current.serverUp)
  }

  func testNotBuiltPluginsThrowInternalError() async {
    let p = NotBuiltPlugin(id: "x", displayName: "X", templates: [RouteTemplate(plugin: "x", "GET", "/x", "d", .read)])
    let route = p.routes()[0]
    do {
      _ = try await route.handler(Req(method: "GET", path: "/x"))
      XCTFail("a placeholder handler must throw")
    } catch let e as PluginError { XCTAssertEqual(e, PluginError("internal_error", "not built yet")) } catch { XCTFail("\(error)") }
  }
}
