import Foundation

/// The frozen text surface (D117). The T7 sanitiser replaces the bodies; the signatures stay.
/// Placeholder rules fail closed: they escape every non-ASCII scalar (and, in `systemSurface`, every control too).
public enum UntrustedText {
  /// Sanitises request text for display inside the app.
  public static func sanitise(_ text: String, escapeWrapGlyphs: Bool) -> String {
    escape(text, controlsToo: false)
  }

  /// Sanitises request text for a system surface (Face ID reason, notification): `\n` and `\t` are escaped as well (D117).
  public static func systemSurface(_ text: String) -> String {
    escape(text, controlsToo: true)
  }

  private static func escape(_ text: String, controlsToo: Bool) -> String {
    var out = ""
    for scalar in text.unicodeScalars {
      let v = scalar.value
      let isAsciiControl = v < 0x20 || v == 0x7F
      if v >= 0x80 || (controlsToo && isAsciiControl) {
        out += "\\u{" + String(v, radix: 16, uppercase: true) + "}"
      } else if isAsciiControl && v != 0x0A && v != 0x09 {
        out += "\\u{" + String(v, radix: 16, uppercase: true) + "}"
      } else {
        out.unicodeScalars.append(scalar)
      }
    }
    return out
  }
}
