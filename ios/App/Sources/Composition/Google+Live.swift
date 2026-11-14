import STMPCore

/// Google platform services (T9). T0: fail-closed, so the google plugin reports NOT IN THIS BUILD.
struct GoogleServices {
  var auth: any GoogleAuth
  var http: any GoogleHTTP
  var webAuth: any WebAuthSession
}

enum GoogleLive {
  static func make(_ ctx: CompositionContext) -> GoogleServices {
    GoogleServices(auth: InertGoogleAuth(), http: InertGoogleHTTP(), webAuth: InertWebAuthSession())
  }
}
