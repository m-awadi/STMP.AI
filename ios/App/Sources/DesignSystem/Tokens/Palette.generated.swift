import SwiftUI

// GENERATED from Android `res/values{,-night}/colors.xml` at the baseline SHA (the 44 colour roles of Palette.kt).
// Do not edit by hand. `argb` is 0xAARRGGBB; alpha roles keep their alpha.

/// One colour role. `argb` is the source of truth; `color` is the sRGB `Color`.
nonisolated struct ColorToken: Sendable, Hashable {
  nonisolated let argb: UInt32
  nonisolated init(_ argb: UInt32) { self.argb = argb }
  nonisolated var alpha: Double { Double((argb >> 24) & 0xFF) / 255 }
  nonisolated var red: Double { Double((argb >> 16) & 0xFF) / 255 }
  nonisolated var green: Double { Double((argb >> 8) & 0xFF) / 255 }
  nonisolated var blue: Double { Double(argb & 0xFF) / 255 }
  var color: Color { Color(.sRGB, red: red, green: green, blue: blue, opacity: alpha) }
}

/// The Instrument palette (ui.md 1.2). Light and dark follow the system; there is no in-app switch.
nonisolated struct Palette: Sendable, Hashable {
  nonisolated let isDark: Bool
  nonisolated let body: ColorToken
  nonisolated let panel: ColorToken
  nonisolated let sunken: ColorToken
  nonisolated let readout: ColorToken
  nonisolated let hairline: ColorToken
  nonisolated let outline: ColorToken
  nonisolated let ink: ColorToken
  nonisolated let etched: ColorToken
  nonisolated let onInk: ColorToken
  nonisolated let signal: ColorToken
  nonisolated let onSignal: ColorToken
  nonisolated let readoutText: ColorToken
  nonisolated let readoutCommand: ColorToken
  nonisolated let readoutMuted: ColorToken
  nonisolated let readoutDanger: ColorToken
  nonisolated let readoutString: ColorToken
  nonisolated let okText: ColorToken
  nonisolated let cautionText: ColorToken
  nonisolated let signalText: ColorToken
  nonisolated let okGlyph: ColorToken
  nonisolated let cautionGlyph: ColorToken
  nonisolated let signalGlyph: ColorToken
  nonisolated let okSurface: ColorToken
  nonisolated let cautionSurface: ColorToken
  nonisolated let signalSurface: ColorToken
  nonisolated let ledOk: ColorToken
  nonisolated let ledCaution: ColorToken
  nonisolated let ledSignal: ColorToken
  nonisolated let ledOff: ColorToken
  nonisolated let bevelHighlight: ColorToken
  nonisolated let bevelShade: ColorToken
  nonisolated let inkHighlight: ColorToken
  nonisolated let keyLip: ColorToken
  nonisolated let keyLipInk: ColorToken
  nonisolated let keyLipSignal: ColorToken
  nonisolated let readoutShadow: ColorToken
  nonisolated let rockerTrack: ColorToken
  nonisolated let rockerCapOff: ColorToken
  nonisolated let rockerOffText: ColorToken
  nonisolated let holdTrack: ColorToken
  nonisolated let tapePaper: ColorToken
  nonisolated let scrim: ColorToken
  nonisolated let focus: ColorToken
  nonisolated let ripple: ColorToken

  nonisolated static let roleNames: [String] = [
    "body",
    "panel",
    "sunken",
    "readout",
    "hairline",
    "outline",
    "ink",
    "etched",
    "onInk",
    "signal",
    "onSignal",
    "readoutText",
    "readoutCommand",
    "readoutMuted",
    "readoutDanger",
    "readoutString",
    "okText",
    "cautionText",
    "signalText",
    "okGlyph",
    "cautionGlyph",
    "signalGlyph",
    "okSurface",
    "cautionSurface",
    "signalSurface",
    "ledOk",
    "ledCaution",
    "ledSignal",
    "ledOff",
    "bevelHighlight",
    "bevelShade",
    "inkHighlight",
    "keyLip",
    "keyLipInk",
    "keyLipSignal",
    "readoutShadow",
    "rockerTrack",
    "rockerCapOff",
    "rockerOffText",
    "holdTrack",
    "tapePaper",
    "scrim",
    "focus",
    "ripple",
  ]

  /// Role lookup by name, for the contrast fixture.
  nonisolated func token(_ role: String) -> ColorToken? {
    switch role {
    case "body": return body
    case "panel": return panel
    case "sunken": return sunken
    case "readout": return readout
    case "hairline": return hairline
    case "outline": return outline
    case "ink": return ink
    case "etched": return etched
    case "onInk": return onInk
    case "signal": return signal
    case "onSignal": return onSignal
    case "readoutText": return readoutText
    case "readoutCommand": return readoutCommand
    case "readoutMuted": return readoutMuted
    case "readoutDanger": return readoutDanger
    case "readoutString": return readoutString
    case "okText": return okText
    case "cautionText": return cautionText
    case "signalText": return signalText
    case "okGlyph": return okGlyph
    case "cautionGlyph": return cautionGlyph
    case "signalGlyph": return signalGlyph
    case "okSurface": return okSurface
    case "cautionSurface": return cautionSurface
    case "signalSurface": return signalSurface
    case "ledOk": return ledOk
    case "ledCaution": return ledCaution
    case "ledSignal": return ledSignal
    case "ledOff": return ledOff
    case "bevelHighlight": return bevelHighlight
    case "bevelShade": return bevelShade
    case "inkHighlight": return inkHighlight
    case "keyLip": return keyLip
    case "keyLipInk": return keyLipInk
    case "keyLipSignal": return keyLipSignal
    case "readoutShadow": return readoutShadow
    case "rockerTrack": return rockerTrack
    case "rockerCapOff": return rockerCapOff
    case "rockerOffText": return rockerOffText
    case "holdTrack": return holdTrack
    case "tapePaper": return tapePaper
    case "scrim": return scrim
    case "focus": return focus
    case "ripple": return ripple
    default: return nil
    }
  }

  nonisolated static let light = Palette(
    isDark: false,
    body: ColorToken(0xFFE4E2DD),
    panel: ColorToken(0xFFF3F2EE),
    sunken: ColorToken(0xFFDCD9D2),
    readout: ColorToken(0xFF17171A),
    hairline: ColorToken(0xFFD2CFC7),
    outline: ColorToken(0xFF77746D),
    ink: ColorToken(0xFF151518),
    etched: ColorToken(0xFF625F59),
    onInk: ColorToken(0xFFFFFFFF),
    signal: ColorToken(0xFFFF4F1A),
    onSignal: ColorToken(0xFF151518),
    readoutText: ColorToken(0xFFE7ECE4),
    readoutCommand: ColorToken(0xFFF1F4EF),
    readoutMuted: ColorToken(0xFF79827B),
    readoutDanger: ColorToken(0xFFFFB79F),
    readoutString: ColorToken(0xFFE8D9A8),
    okText: ColorToken(0xFF146D41),
    cautionText: ColorToken(0xFF805500),
    signalText: ColorToken(0xFFB32900),
    okGlyph: ColorToken(0xFF1A9257),
    cautionGlyph: ColorToken(0xFFAE7300),
    signalGlyph: ColorToken(0xFFF33800),
    okSurface: ColorToken(0xFFD5E9DC),
    cautionSurface: ColorToken(0xFFF2E6CD),
    signalSurface: ColorToken(0xFFF5DBD0),
    ledOk: ColorToken(0xFF20B26A),
    ledCaution: ColorToken(0xFFE99A00),
    ledSignal: ColorToken(0xFFFF4F1A),
    ledOff: ColorToken(0xFFBDBAB2),
    bevelHighlight: ColorToken(0xFFFFFFFF),
    bevelShade: ColorToken(0x12000000),
    inkHighlight: ColorToken(0x33FFFFFF),
    keyLip: ColorToken(0xFFC9C6BE),
    keyLipInk: ColorToken(0xFF000000),
    keyLipSignal: ColorToken(0xFFC23A10),
    readoutShadow: ColorToken(0x8C000000),
    rockerTrack: ColorToken(0xFFD3D0C8),
    rockerCapOff: ColorToken(0xFFF7F6F2),
    rockerOffText: ColorToken(0xFF625F59),
    holdTrack: ColorToken(0xFFCFCCC4),
    tapePaper: ColorToken(0xFFFBFAF6),
    scrim: ColorToken(0x73000000),
    focus: ColorToken(0xFF151518),
    ripple: ColorToken(0x1F151518)
  )

  nonisolated static let dark = Palette(
    isDark: true,
    body: ColorToken(0xFF141416),
    panel: ColorToken(0xFF1E1E21),
    sunken: ColorToken(0xFF2A2A2E),
    readout: ColorToken(0xFF0A0A0B),
    hairline: ColorToken(0xFF34343A),
    outline: ColorToken(0xFF8F8C85),
    ink: ColorToken(0xFFECEBE7),
    etched: ColorToken(0xFF94918A),
    onInk: ColorToken(0xFF151518),
    signal: ColorToken(0xFFFF6A3D),
    onSignal: ColorToken(0xFF151518),
    readoutText: ColorToken(0xFFE7ECE4),
    readoutCommand: ColorToken(0xFFF1F4EF),
    readoutMuted: ColorToken(0xFF79827B),
    readoutDanger: ColorToken(0xFFFFB79F),
    readoutString: ColorToken(0xFFE8D9A8),
    okText: ColorToken(0xFF2FCB7E),
    cautionText: ColorToken(0xFFF2A920),
    signalText: ColorToken(0xFFFF6A3D),
    okGlyph: ColorToken(0xFF2FCB7E),
    cautionGlyph: ColorToken(0xFFF2A920),
    signalGlyph: ColorToken(0xFFFF6A3D),
    okSurface: ColorToken(0xFF213A30),
    cautionSurface: ColorToken(0xFF403421),
    signalSurface: ColorToken(0xFF422A25),
    ledOk: ColorToken(0xFF2FCB7E),
    ledCaution: ColorToken(0xFFF2A920),
    ledSignal: ColorToken(0xFFFF6A3D),
    ledOff: ColorToken(0xFF4A4A50),
    bevelHighlight: ColorToken(0x0AFFFFFF),
    bevelShade: ColorToken(0x66000000),
    inkHighlight: ColorToken(0x33FFFFFF),
    keyLip: ColorToken(0xFF0A0A0B),
    keyLipInk: ColorToken(0xFF9A988F),
    keyLipSignal: ColorToken(0xFFC2502A),
    readoutShadow: ColorToken(0xB3000000),
    rockerTrack: ColorToken(0xFF0F0F11),
    rockerCapOff: ColorToken(0xFF60606A),
    rockerOffText: ColorToken(0xFFECEBE7),
    holdTrack: ColorToken(0xFF3A3A3F),
    tapePaper: ColorToken(0xFF2A2A2E),
    scrim: ColorToken(0x99000000),
    focus: ColorToken(0xFFECEBE7),
    ripple: ColorToken(0x29ECEBE7)
  )
}
