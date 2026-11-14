import Foundation

/// Every pushed screen (PLAN 1.9). Complete as listed and never extended outside an amendment.
enum AppRoute: Hashable {
  case log
  case logDetail(id: String)
  case exceptions
  case secrets
  case ssh
  case sshConnection(name: String)
  case plugin(id: String)
  case googleSignIn
  case onboarding
  case callers
  case vault
  case settingsDebug
}
