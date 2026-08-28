import Foundation

#if canImport(ActivityKit)
  import ActivityKit

  /// The shape of every live update this package can show.
  ///
  /// This file is compiled into BOTH the app (through the pod) and the widget
  /// extension that draws the activity — ActivityKit matches an activity to its
  /// layout by this type, so the two targets must agree on it exactly. Adding
  /// the file to the widget target is part of the iOS setup; there is no way
  /// around it, because Apple requires the SwiftUI layout to live in an
  /// extension inside the host app and an extension cannot link a pod.
  ///
  /// Fixed fields rather than a dictionary: SwiftUI lays this out at compile
  /// time, so it can only render keys it already knows.
  @available(iOS 16.1, *)
  public struct LiveUpdateAttributes: ActivityAttributes {

    /// One leg of the progress track, mirroring `stages` in the JS API.
    ///
    /// `weight` is relative; the layout scales the set to fill the bar. It
    /// carries no "completed" flag on purpose — which stages are done is
    /// derivable from `progress`, and two sources of truth for the same fact
    /// is how a bar ends up disagreeing with its own labels.
    public struct Stage: Codable, Hashable {
      public var id: String
      public var title: String
      public var weight: Double
      /// `#RRGGBB`, or nil for the activity's accent.
      public var color: String?

      public init(id: String, title: String, weight: Double = 1, color: String? = nil) {
        self.id = id
        self.title = title
        self.weight = weight
        self.color = color
      }
    }

    /// The part that changes.
    ///
    /// Keep it small. ActivityKit caps the encoded state at 4KB and rejects
    /// the update — not the activity — when it overflows, so a payload that
    /// grows with the data is an activity that silently stops moving. Nothing
    /// sensitive belongs here either: it renders on a locked screen.
    public struct ContentState: Codable, Hashable {
      public var title: String
      public var message: String?
      public var status: String?
      public var progress: Double?
      public var stages: [Stage]?
      /// Epoch SECONDS (JS hands over milliseconds; the module divides).
      /// SwiftUI counts down to this on its own, with no further updates.
      public var endsAt: Double?
      public var icon: String?
      /// `#RRGGBB` accent for the bar and the island keyline.
      public var color: String?
      /// Where a tap should land. Applied by the widget as `.widgetURL`.
      public var deepLink: String?

      public init(
        title: String,
        message: String? = nil,
        status: String? = nil,
        progress: Double? = nil,
        stages: [Stage]? = nil,
        endsAt: Double? = nil,
        icon: String? = nil,
        color: String? = nil,
        deepLink: String? = nil
      ) {
        self.title = title
        self.message = message
        self.status = status
        self.progress = progress
        self.stages = stages
        self.endsAt = endsAt
        self.icon = icon
        self.color = color
        self.deepLink = deepLink
      }
    }

    /// Caller's own id — an order id. Stable for the life of the activity.
    public var id: String
    /// Static label that never changes once started.
    public var name: String

    public init(id: String, name: String) {
      self.id = id
      self.name = name
    }
  }
#endif
