import STMPCore

/// SSH key store (T11 puts the Secure Enclave adapter here). T0: fail-closed.
struct SSHServices {
  var keys: any SSHKeyStore
}

enum SSHLive {
  static func make(_ ctx: CompositionContext) -> SSHServices { SSHServices(keys: InertSSHKeyStore()) }
}
