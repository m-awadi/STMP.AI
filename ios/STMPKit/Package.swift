// swift-tools-version: 6.2
// The one Swift package of the iOS client. Every target and every test edge is declared here by T0 (D111), so no
// later ticket edits this file. Rules:
//  - Swift 6 language mode, warnings are errors on OUR targets only.
//  - No upcoming features and no `defaultIsolation` setting: the package is nonisolated by default (D6, D54, D90). The app
//    target is MainActor by default and sets SWIFT_APPROACHABLE_CONCURRENCY = NO (D90).
//  - Runtime dependencies are SwiftNIO and SwiftNIO SSH only, pinned `exact:` (Q3, D91). Do not downgrade a pin to satisfy a list (D124).
import PackageDescription

let ours: [SwiftSetting] = [.treatAllWarnings(as: .error)]

let core: Target.Dependency = "STMPCore"
let support: Target.Dependency = "STMPTestSupport"
let nioCore: Target.Dependency = .product(name: "NIOCore", package: "swift-nio")
let nioPosix: Target.Dependency = .product(name: "NIOPosix", package: "swift-nio")
let nioHTTP1: Target.Dependency = .product(name: "NIOHTTP1", package: "swift-nio")
let nioEmbedded: Target.Dependency = .product(name: "NIOEmbedded", package: "swift-nio")
let nioSSH: Target.Dependency = .product(name: "NIOSSH", package: "swift-nio-ssh")

func module(_ name: String, _ deps: [Target.Dependency] = [core]) -> Target {
  .target(name: name, dependencies: deps, swiftSettings: ours)
}
func tests(_ name: String, _ deps: [Target.Dependency]) -> Target {
  .testTarget(name: name, dependencies: deps, swiftSettings: ours)
}

// Modules that depend only on STMPCore (plus the listed extras).
let plain = [
  "STMPDispatch", "STMPPluginsCore", "STMPPolicy", "STMPAudit", "STMPSecrets", "STMPGoogle", "STMPGmail", "STMPCalendar", "STMPDrive",
  "STMPDevice",
]
// Area modules and their unit-test targets: the module under test, STMPCore, STMPTestSupport.
let areaTests = plain.map { tests($0 + "Tests", [.init(stringLiteral: $0), core, support]) }
let everyModule: [Target.Dependency] =
  [
    "STMPHTTP", "STMPDispatch", "STMPPluginsCore", "STMPPolicy", "STMPAudit", "STMPGate", "STMPVault", "STMPSecrets", "STMPText",
    "STMPGoogle", "STMPGmail", "STMPCalendar", "STMPDrive", "STMPDevice", "STMPSSH",
  ].map { .init(stringLiteral: $0) }

let package = Package(
  name: "STMPKit",
  platforms: [.iOS(.v17), .macOS(.v14)],
  products: [
    "STMPCore", "STMPHTTP", "STMPDispatch", "STMPPluginsCore", "STMPPolicy", "STMPAudit", "STMPGate", "STMPVault", "STMPSecrets",
    "STMPText", "STMPGoogle", "STMPGmail", "STMPCalendar", "STMPDrive", "STMPDevice", "STMPSSH", "STMPBridge", "STMPTestSupport",
  ].map { .library(name: $0, targets: [$0]) },
  dependencies: [
    .package(url: "https://github.com/apple/swift-nio.git", exact: "2.105.0"),
    .package(url: "https://github.com/apple/swift-nio-ssh.git", exact: "0.15.1"),
  ],
  targets: [
    module("STMPCore", []),
    module("STMPTestSupport", [core]),  // never imports XCTest, never linked into the app (D111)
    module("STMPHTTP", [core, nioCore, nioPosix, nioHTTP1]),
    module("STMPText", []),
    module("STMPGate", [core, "STMPText"]),
    module("STMPVault", [core, "STMPText"]),
    module("STMPSSH", [core, nioCore, nioPosix, nioSSH]),
    module("STMPBridge", [core] + everyModule),
  ] + plain.map { module($0) } + [
    tests("STMPCoreTests", [core, support]),
    tests("ContractTests", ["STMPBridge", core, support]),
    tests("STMPHTTPTests", ["STMPHTTP", support, nioCore, nioPosix, nioHTTP1, nioEmbedded]),
    tests("STMPSSHTests", ["STMPSSH", support, nioCore, nioPosix, nioSSH, nioEmbedded]),
    tests("STMPGateTests", ["STMPGate", "STMPText", core, support]),
    tests("STMPVaultTests", ["STMPVault", "STMPText", core, support]),
    tests("STMPTextTests", ["STMPText", core, support]),
    tests("STMPBridgeTests", ["STMPBridge", "STMPHTTP", core, support]),
    tests("SliceTests", ["STMPBridge", "STMPHTTP", core, support]),
    tests("IntegrationTests", everyModule + ["STMPBridge", core, support]),
  ] + areaTests
)
