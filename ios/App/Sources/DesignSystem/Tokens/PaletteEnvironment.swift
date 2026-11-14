import SwiftUI

extension Palette {
  /// The palette for a colour scheme. Light and dark follow the system; nothing in the app sets `.preferredColorScheme`.
  nonisolated static func current(for scheme: ColorScheme, increasedContrast: Bool = false) -> Palette {
    let base = scheme == .dark ? Palette.dark : Palette.light
    return increasedContrast ? base.increasedContrast() : base
  }

  /// ui.md 1.3: with Increase Contrast on, secondary text rises to `ink` and control boundaries use `ink` too.
  nonisolated func increasedContrast() -> Palette {
    Palette(
      isDark: isDark, body: body, panel: panel, sunken: sunken, readout: readout, hairline: hairline, outline: ink, ink: ink, etched: ink,
      onInk: onInk, signal: signal, onSignal: onSignal, readoutText: readoutText, readoutCommand: readoutCommand,
      readoutMuted: readoutMuted,
      readoutDanger: readoutDanger, readoutString: readoutString, okText: okText, cautionText: cautionText, signalText: signalText,
      okGlyph: okGlyph, cautionGlyph: cautionGlyph, signalGlyph: signalGlyph, okSurface: okSurface, cautionSurface: cautionSurface,
      signalSurface: signalSurface, ledOk: ledOk, ledCaution: ledCaution, ledSignal: ledSignal, ledOff: ledOff,
      bevelHighlight: bevelHighlight, bevelShade: bevelShade, inkHighlight: inkHighlight, keyLip: keyLip, keyLipInk: keyLipInk,
      keyLipSignal: keyLipSignal, readoutShadow: readoutShadow, rockerTrack: rockerTrack, rockerCapOff: rockerCapOff, rockerOffText: ink,
      holdTrack: holdTrack, tapePaper: tapePaper, scrim: scrim, focus: focus, ripple: ripple)
  }

  /// Blends `fg` at `alpha` (0...255) over an opaque `bg`, as Android `ContrastTest.over` does (rounding per channel).
  nonisolated static func over(_ fg: ColorToken, _ bg: ColorToken, alpha: Int) -> ColorToken {
    let a = Double(alpha) / 255
    func mix(_ f: Double, _ b: Double) -> UInt32 { UInt32((((f * 255) * a + (b * 255) * (1 - a))).rounded()) }
    return ColorToken(0xFF00_0000 | mix(fg.red, bg.red) << 16 | mix(fg.green, bg.green) << 8 | mix(fg.blue, bg.blue))
  }
}

private struct PaletteKey: EnvironmentKey {
  static let defaultValue = Palette.light
}

extension EnvironmentValues {
  /// Read by every Instrument component. `PaletteReader` (or a snapshot helper) sets it from the colour scheme.
  var palette: Palette {
    get { self[PaletteKey.self] }
    set { self[PaletteKey.self] = newValue }
  }

  /// Fed from `Policy.soundEffects` by `AppRoot`; UI sounds check it before playing.
  var soundEffectsEnabled: Bool {
    get { self[SoundEffectsKey.self] }
    set { self[SoundEffectsKey.self] = newValue }
  }
}

private struct SoundEffectsKey: EnvironmentKey {
  static let defaultValue = true
}

/// Resolves `\.palette` from the environment colour scheme and contrast, so views never pick a palette themselves.
struct PaletteReader<Content: View>: View {
  @Environment(\.colorScheme) private var scheme
  @Environment(\.colorSchemeContrast) private var contrast
  private let content: Content
  init(@ViewBuilder content: () -> Content) { self.content = content() }
  var body: some View {
    content.environment(\.palette, Palette.current(for: scheme, increasedContrast: contrast == .increased))
  }
}
