import Foundation

/// The one place an error code becomes an HTTP status: Android `ErrorCodes.kt` MAP plus the iOS superset (D106).
/// Total over every code the app can throw; `ErrorTableTests` scans the package sources for `PluginError("..."` literals.
/// An unknown code is a defect and answers 500 (the safe direction: never recorded as noise).
public enum ErrorTable {
  public struct Entry: Sendable, Hashable {
    public var status: Int
    /// False for codes Android emits but `ErrorCodes.kt` omits (D106).
    public var listedOnAndroid: Bool
    public var platform: String?
  }

  public static let entries: [String: Entry] = [
    "bad_request": Entry(status: 400, listedOnAndroid: true, platform: nil),
    "bad_count": Entry(status: 400, listedOnAndroid: true, platform: nil),
    "bad_package": Entry(status: 400, listedOnAndroid: true, platform: nil),
    "bad_time_min": Entry(status: 400, listedOnAndroid: true, platform: nil),
    "bad_time_max": Entry(status: 400, listedOnAndroid: true, platform: nil),
    "bad_time_range": Entry(status: 400, listedOnAndroid: true, platform: nil),
    "bad_timezone": Entry(status: 400, listedOnAndroid: true, platform: nil),
    "bad_max": Entry(status: 400, listedOnAndroid: true, platform: nil),
    "bad_lines": Entry(status: 400, listedOnAndroid: true, platform: nil),
    "sudo_needs_stdin": Entry(status: 400, listedOnAndroid: true, platform: nil),
    "unsupported_action": Entry(status: 400, listedOnAndroid: true, platform: nil),
    "missing_reply_text": Entry(status: 400, listedOnAndroid: true, platform: nil),
    "decode_error": Entry(status: 400, listedOnAndroid: true, platform: nil),
    "unknown_secret": Entry(status: 400, listedOnAndroid: true, platform: nil),
    "host_not_allowed": Entry(status: 400, listedOnAndroid: true, platform: nil),
    "redirect_refused": Entry(status: 400, listedOnAndroid: true, platform: nil),
    "unsupported_content_type": Entry(status: 400, listedOnAndroid: true, platform: nil),
    "unsupported_storage": Entry(status: 400, listedOnAndroid: true, platform: nil),
    "vault_exists": Entry(status: 409, listedOnAndroid: true, platform: nil),
    "not_found": Entry(status: 404, listedOnAndroid: true, platform: nil),
    "plugin_disabled": Entry(status: 409, listedOnAndroid: true, platform: nil),
    "notification_gone": Entry(status: 410, listedOnAndroid: true, platform: nil),
    "not_clearable": Entry(status: 409, listedOnAndroid: true, platform: nil),
    "auth_required": Entry(status: 401, listedOnAndroid: true, platform: nil),
    "auth_expired": Entry(status: 401, listedOnAndroid: true, platform: nil),
    "secret_locked": Entry(status: 403, listedOnAndroid: true, platform: nil),
    "denied": Entry(status: 403, listedOnAndroid: true, platform: nil),
    "caller_unverified": Entry(status: 403, listedOnAndroid: true, platform: nil),
    "caller_denied": Entry(status: 403, listedOnAndroid: true, platform: nil),
    "prompt_unreachable": Entry(status: 503, listedOnAndroid: true, platform: nil),
    "auth_not_configured": Entry(status: 503, listedOnAndroid: true, platform: nil),
    "listener_unavailable": Entry(status: 503, listedOnAndroid: true, platform: nil),
    "model_not_ready": Entry(status: 503, listedOnAndroid: true, platform: nil),
    "ai_unavailable": Entry(status: 500, listedOnAndroid: true, platform: nil),
    "audio_unavailable": Entry(status: 500, listedOnAndroid: true, platform: nil),
    "text_too_long": Entry(status: 400, listedOnAndroid: true, platform: nil),
    "tts_busy": Entry(status: 503, listedOnAndroid: true, platform: nil),
    "tts_unavailable": Entry(status: 503, listedOnAndroid: true, platform: nil),
    "tts_failed": Entry(status: 500, listedOnAndroid: true, platform: nil),
    "tts_in_call": Entry(status: 409, listedOnAndroid: true, platform: nil),
    "contacts_unavailable": Entry(status: 503, listedOnAndroid: true, platform: nil),
    "contacts_query_failed": Entry(status: 500, listedOnAndroid: true, platform: nil),
    "vault_missing": Entry(status: 503, listedOnAndroid: true, platform: nil),
    "vault_corrupt": Entry(status: 500, listedOnAndroid: true, platform: nil),
    "vault_unrecoverable": Entry(status: 500, listedOnAndroid: true, platform: nil),
    "vault_unwrap_failed": Entry(status: 500, listedOnAndroid: true, platform: nil),
    "allowlist_corrupt": Entry(status: 500, listedOnAndroid: true, platform: nil),
    "upstream_timeout": Entry(status: 503, listedOnAndroid: true, platform: nil),
    "upstream_failed": Entry(status: 409, listedOnAndroid: true, platform: nil),
    "policy_unavailable": Entry(status: 503, listedOnAndroid: true, platform: nil),
    "calendar_zone_unavailable": Entry(status: 503, listedOnAndroid: true, platform: nil),
    "google_error": Entry(status: 409, listedOnAndroid: true, platform: nil),
    "ssh_bad_key": Entry(status: 409, listedOnAndroid: true, platform: nil),
    "ssh_keygen_failed": Entry(status: 500, listedOnAndroid: true, platform: nil),
    "ssh_host_key_unknown": Entry(status: 409, listedOnAndroid: true, platform: nil),
    "ssh_host_key_changed": Entry(status: 409, listedOnAndroid: true, platform: nil),
    "ssh_host_key_refused": Entry(status: 409, listedOnAndroid: true, platform: nil),
    "ssh_timeout": Entry(status: 503, listedOnAndroid: true, platform: nil),
    "ssh_job_not_found": Entry(status: 404, listedOnAndroid: true, platform: nil),
    "ssh_job_limit": Entry(status: 503, listedOnAndroid: true, platform: nil),
    "ssh_failed": Entry(status: 409, listedOnAndroid: true, platform: nil),
    "auth_error": Entry(status: 500, listedOnAndroid: true, platform: nil),
    "secure_storage_error": Entry(status: 500, listedOnAndroid: true, platform: nil),
    "email_lookup_failed": Entry(status: 500, listedOnAndroid: true, platform: nil),
    "no_scopes": Entry(status: 500, listedOnAndroid: true, platform: nil),
    "internal_error": Entry(status: 500, listedOnAndroid: true, platform: nil),
    "approval_timeout": Entry(status: 408, listedOnAndroid: false, platform: nil),
    "caller_registry_unavailable": Entry(status: 500, listedOnAndroid: false, platform: nil),
    "audit_unavailable": Entry(status: 500, listedOnAndroid: false, platform: nil),
    "logcat_failed": Entry(status: 500, listedOnAndroid: false, platform: nil),
    "not_supported_on_ios": Entry(status: 501, listedOnAndroid: true, platform: "ios"),
  ]

  public static func status(for code: String) -> Int { entries[code]?.status ?? 500 }
  public static func isClassified(_ code: String) -> Bool { entries[code] != nil }
  public static var codes: Set<String> { Set(entries.keys) }
}
