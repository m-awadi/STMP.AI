import Foundation

/// A JSON document. Equality is order-insensitive for objects (dictionaries) and treats `.int(1)` and `.double(1)` as equal.
public enum JSONValue: Sendable, Hashable, Codable {
  case null
  case bool(Bool)
  case int(Int)
  case double(Double)
  case string(String)
  case array([JSONValue])
  case object([String: JSONValue])

  public static func == (lhs: JSONValue, rhs: JSONValue) -> Bool {
    switch (lhs, rhs) {
    case (.null, .null): return true
    case (.bool(let a), .bool(let b)): return a == b
    case (.int(let a), .int(let b)): return a == b
    case (.double(let a), .double(let b)): return a == b
    case (.int(let a), .double(let b)), (.double(let b), .int(let a)): return Double(a) == b
    case (.string(let a), .string(let b)): return a == b
    case (.array(let a), .array(let b)): return a == b
    case (.object(let a), .object(let b)): return a == b
    default: return false
    }
  }

  public func hash(into hasher: inout Hasher) {
    switch self {
    case .null: hasher.combine(0)
    case .bool(let v): hasher.combine(v)
    case .int(let v): hasher.combine(Double(v))
    case .double(let v): hasher.combine(v)
    case .string(let v): hasher.combine(v)
    case .array(let v): hasher.combine(v)
    case .object(let v): hasher.combine(v)
    }
  }

  public init(from decoder: any Decoder) throws {
    let c = try decoder.singleValueContainer()
    if c.decodeNil() {
      self = .null
    } else if let v = try? c.decode(Bool.self) {
      self = .bool(v)
    } else if let v = try? c.decode(Int.self) {
      self = .int(v)
    } else if let v = try? c.decode(Double.self) {
      self = .double(v)
    } else if let v = try? c.decode(String.self) {
      self = .string(v)
    } else if let v = try? c.decode([JSONValue].self) {
      self = .array(v)
    } else {
      self = .object(try c.decode([String: JSONValue].self))
    }
  }

  public func encode(to encoder: any Encoder) throws {
    var c = encoder.singleValueContainer()
    switch self {
    case .null: try c.encodeNil()
    case .bool(let v): try c.encode(v)
    case .int(let v): try c.encode(v)
    case .double(let v): try c.encode(v)
    case .string(let v): try c.encode(v)
    case .array(let v): try c.encode(v)
    case .object(let v): try c.encode(v)
    }
  }

  /// Parses UTF-8 JSON text.
  public static func parse(_ data: Data) throws -> JSONValue { try JSONDecoder().decode(JSONValue.self, from: data) }

  /// Compact JSON with sorted keys, so output is stable.
  public func serialized() throws -> Data {
    let e = JSONEncoder()
    e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    return try e.encode(self)
  }

  public subscript(key: String) -> JSONValue? {
    if case .object(let o) = self { return o[key] }
    return nil
  }

  public var stringValue: String? { if case .string(let s) = self { return s } else { return nil } }
  public var intValue: Int? {
    switch self {
    case .int(let v): return v
    case .double(let v) where v == v.rounded(): return Int(v)
    default: return nil
    }
  }
  public var boolValue: Bool? { if case .bool(let b) = self { return b } else { return nil } }
  public var arrayValue: [JSONValue]? { if case .array(let a) = self { return a } else { return nil } }
  public var objectValue: [String: JSONValue]? { if case .object(let o) = self { return o } else { return nil } }
}
