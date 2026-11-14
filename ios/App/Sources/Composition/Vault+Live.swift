import STMPCore

/// Vault platform services (T6). T0: the vault is ABSENT, authentication is unavailable, the Keychain fails closed.
struct VaultServices {
  var authenticator: any Authenticator
  var keychain: any KeychainStore
  var vault: any VaultService
}

enum VaultLive {
  static func make(_ ctx: CompositionContext) -> VaultServices {
    VaultServices(authenticator: InertAuthenticator(), keychain: InertKeychainStore(), vault: InertVaultService())
  }
}
