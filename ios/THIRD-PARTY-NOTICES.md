# Third-party notices (iOS)

STMP is licensed under the Apache License 2.0 (see the repository `LICENSE`). The components below are used under
their own licences. `scripts/resolved-check.sh` fails when a resolved package is missing here or lacks a licence and a
`runtime` or `test-only` mark (D124). The runtime set is the transitive closure of `swift-nio-ssh` and `swift-nio`.

## Swift packages

| Package | Licence | Use | Copyright |
|---|---|---|---|
| `swift-nio` | Apache-2.0, runtime | HTTP server and channels | Copyright Apple Inc. and the SwiftNIO project authors |
| `swift-nio-ssh` | Apache-2.0, runtime | SSH client (the `ssh` plugin) | Copyright Apple Inc. and the SwiftNIO project authors |
| `swift-crypto` | Apache-2.0, runtime | Crypto for swift-nio-ssh | Copyright Apple Inc. and the SwiftCrypto project authors |
| `swift-asn1` | Apache-2.0, runtime | ASN.1 for swift-crypto | Copyright Apple Inc. and the SwiftASN1 project authors |
| `swift-atomics` | Apache-2.0, runtime | Atomics for swift-nio | Copyright Apple Inc. and the Swift project authors |
| `swift-collections` | Apache-2.0, runtime | Collections for swift-nio | Copyright Apple Inc. and the Swift project authors |
| `swift-system` | Apache-2.0, runtime | System calls for swift-nio | Copyright Apple Inc. and the Swift project authors |
| `swift-snapshot-testing` | MIT, test-only | Snapshot tests | Copyright (c) Point-Free, Inc. |
| `swift-custom-dump` | MIT, test-only | Dependency of swift-snapshot-testing | Copyright (c) Point-Free, Inc. |
| `swift-issue-reporting` | MIT, test-only | Dependency of swift-custom-dump | Copyright (c) Point-Free, Inc. |
| `swift-syntax` | Apache-2.0, test-only | Dependency of swift-snapshot-testing macros | Copyright Apple Inc. and the Swift project authors |

## Fonts

| Font | Licence | Notes |
|---|---|---|
| Geist and Geist Mono | SIL OFL 1.1 | `App/Resources/Fonts/`, notice in `App/Resources/Fonts/OFL-Geist.txt` |
