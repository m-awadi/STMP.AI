import STMPCore

/// Device platform services (T10). T0: fail-closed (a call is assumed to be in progress, so nothing speaks).
struct DeviceServices {
  var speech: any SpeechOutput
  var cues: any CuePlayer
  var contacts: any ContactsSource
  var logs: any LogSource
  var diagnostics: any DiagnosticsSource
  var calls: any CallObserver

  static var inert: DeviceServices {
    DeviceServices(
      speech: InertSpeechOutput(), cues: InertCuePlayer(), contacts: InertContactsSource(), logs: InertLogSource(),
      diagnostics: InertDiagnosticsSource(), calls: InertCallObserver())
  }
}

enum DeviceLive {
  static func make(_ ctx: CompositionContext) -> DeviceServices { .inert }
}
