import STMPCore

/// Attendance (T5 replaces the adapter behind `AttendanceLive.make`). T0: the placeholder adapter in `Platform/Attendance`.
struct AttendanceServices {
  var attendance: any Attendance
}

enum AttendanceLive {
  static func make(_ ctx: CompositionContext) -> AttendanceServices {
    AttendanceServices(attendance: AttendancePlaceholder(log: ctx.log))
  }
}
