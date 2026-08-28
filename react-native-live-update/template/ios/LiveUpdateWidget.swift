import ActivityKit
import SwiftUI
import WidgetKit

/// The layout for every live update the app shows.
///
/// This file belongs to the WIDGET EXTENSION target, not the app. Apple
/// requires the SwiftUI for a Live Activity to be compiled into an extension
/// inside the app bundle, and an extension cannot link a CocoaPod — which is
/// why the package ships this as a template you copy rather than as code you
/// import. `LiveUpdateAttributes.swift` from the package must be added to this
/// same target: ActivityKit matches an activity to its layout by that type, so
/// the app and the widget have to agree on it exactly.
///
/// Restyle freely. The only fixed part is the ContentState fields it reads.
@available(iOS 16.1, *)
struct LiveUpdateWidget: Widget {
  var body: some WidgetConfiguration {
    ActivityConfiguration(for: LiveUpdateAttributes.self) { context in
      // ── Lock Screen / banner, and every iPhone without an island ──
      LockScreenView(context: context)
        .activityBackgroundTint(Color.black.opacity(0.55))
        .activitySystemActionForegroundColor(.white)
        .widgetURL(context.state.deepLink.flatMap(URL.init(string:)))

    } dynamicIsland: { context in
      DynamicIsland {
        DynamicIslandExpandedRegion(.leading) {
          Image(systemName: context.state.icon ?? "shippingbox.fill")
            .font(.title2)
            .foregroundStyle(context.accent)
        }
        DynamicIslandExpandedRegion(.trailing) {
          if let endsAt = context.state.endsAt {
            // Counts down on its own — no updates needed to keep it honest,
            // which matters because each update costs a system budget slot.
            Text(Date(timeIntervalSince1970: endsAt), style: .timer)
              .monospacedDigit()
              .multilineTextAlignment(.trailing)
              .frame(maxWidth: 64)
          } else if let status = context.state.status {
            Text(status).font(.caption).foregroundStyle(.secondary)
          }
        }
        DynamicIslandExpandedRegion(.center) {
          Text(context.state.title)
            .font(.headline)
            .lineLimit(1)
        }
        DynamicIslandExpandedRegion(.bottom) {
          VStack(alignment: .leading, spacing: 6) {
            if let message = context.state.message {
              Text(message)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)
            }
            ProgressTrack(
              stages: context.state.stages,
              progress: context.state.progress,
              accent: context.accent
            )
            if let stage = context.currentStage {
              Text(stage.title)
                .font(.caption2)
                .foregroundStyle(.secondary)
            }
          }
        }
      } compactLeading: {
        Image(systemName: context.state.icon ?? "shippingbox.fill")
          .foregroundStyle(context.accent)
      } compactTrailing: {
        if let endsAt = context.state.endsAt {
          Text(Date(timeIntervalSince1970: endsAt), style: .timer)
            .monospacedDigit()
            // The compact slot is genuinely tiny; an unbounded timer pushes
            // the leading glyph off the island entirely.
            .frame(maxWidth: 44)
        } else if let status = context.state.status {
          Text(status).font(.caption2).lineLimit(1)
        }
      } minimal: {
        Image(systemName: context.state.icon ?? "shippingbox.fill")
          .foregroundStyle(context.accent)
      }
      .keylineTint(context.accent)
      .widgetURL(context.state.deepLink.flatMap(URL.init(string:)))
    }
  }
}

@available(iOS 16.1, *)
private struct LockScreenView: View {
  let context: ActivityViewContext<LiveUpdateAttributes>

  var body: some View {
    VStack(alignment: .leading, spacing: 10) {
      HStack(alignment: .center, spacing: 14) {
        Image(systemName: context.state.icon ?? "shippingbox.fill")
          .font(.title)
          .foregroundStyle(.white)

        VStack(alignment: .leading, spacing: 4) {
          Text(context.state.title)
            .font(.headline)
            .foregroundStyle(.white)
            .lineLimit(1)

          if let message = context.state.message {
            Text(message)
              .font(.caption)
              .foregroundStyle(.white.opacity(0.8))
              .lineLimit(2)
          }
        }

        Spacer(minLength: 0)

        if let endsAt = context.state.endsAt {
          VStack(spacing: 2) {
            Text(Date(timeIntervalSince1970: endsAt), style: .timer)
              .font(.title3.monospacedDigit())
              .foregroundStyle(.white)
              .multilineTextAlignment(.center)
              .frame(maxWidth: 70)
            Text("left")
              .font(.caption2)
              .foregroundStyle(.white.opacity(0.7))
          }
        } else if let status = context.state.status {
          Text(status)
            .font(.caption.weight(.semibold))
            .foregroundStyle(.white)
        }
      }

      ProgressTrack(
        stages: context.state.stages,
        progress: context.state.progress,
        accent: context.accent
      )

      if let stage = context.currentStage {
        Text(stage.title)
          .font(.caption2)
          .foregroundStyle(.white.opacity(0.75))
      }
    }
    .padding(16)
  }
}

/// The progress bar, segmented when the activity has stages.
///
/// The iOS counterpart to Android's `ProgressStyle` track: same input, same
/// reading, drawn by hand because SwiftUI's `ProgressView` has no notion of
/// segments. Each stage becomes a capsule sized by its weight, filled by how
/// far `progress` has travelled *through that stage* — so a bar at 0.5 across
/// three equal stages fills the first, half-fills the second, and leaves the
/// third empty, exactly as the Android track does.
@available(iOS 16.1, *)
private struct ProgressTrack: View {
  let stages: [LiveUpdateAttributes.Stage]?
  let progress: Double?
  let accent: Color

  private let height: CGFloat = 6
  private let spacing: CGFloat = 3

  var body: some View {
    if let stages, !stages.isEmpty {
      GeometryReader { geometry in
        // Spacing is taken out before the weights are applied, so the capsules
        // plus the gaps between them add up to exactly the available width.
        let usable = max(geometry.size.width - spacing * CGFloat(stages.count - 1), 0)
        let total = stages.reduce(0) { $0 + max($1.weight, 0) }

        HStack(spacing: spacing) {
          ForEach(Array(stages.enumerated()), id: \.element.id) { index, stage in
            let width = total > 0 ? usable * (max(stage.weight, 0) / total) : 0
            Capsule()
              .fill(.white.opacity(0.25))
              .frame(width: width)
              .overlay(alignment: .leading) {
                Capsule()
                  .fill(Color(hex: stage.color) ?? accent)
                  .frame(width: width * fill(ofStageAt: index, in: stages, total: total))
              }
          }
        }
      }
      .frame(height: height)
    } else if let progress {
      ProgressView(value: min(max(progress, 0), 1))
        .tint(accent)
    }
  }

  /// How much of one stage is behind the marker, as 0...1.
  private func fill(
    ofStageAt index: Int,
    in stages: [LiveUpdateAttributes.Stage],
    total: Double
  ) -> Double {
    // No progress at all means indeterminate, and a half-drawn segmented bar
    // reads as a real value. Show the track empty instead.
    guard total > 0, let progress else { return 0 }

    let start = stages.prefix(index).reduce(0) { $0 + max($1.weight, 0) } / total
    let end = start + max(stages[index].weight, 0) / total
    guard end > start else { return 0 }

    return min(max((min(max(progress, 0), 1) - start) / (end - start), 0), 1)
  }
}

@available(iOS 16.1, *)
extension ActivityViewContext where Attributes == LiveUpdateAttributes {
  var accent: Color { Color(hex: state.color) ?? .accentColor }

  /// The stage the marker is currently inside, for the caption under the bar.
  ///
  /// Derived from `progress` rather than sent as its own field: one source of
  /// truth means the label can never disagree with the bar above it.
  var currentStage: LiveUpdateAttributes.Stage? {
    guard let stages = state.stages, !stages.isEmpty, let progress = state.progress
    else { return nil }

    let total = stages.reduce(0) { $0 + max($1.weight, 0) }
    guard total > 0 else { return nil }

    var travelled = 0.0
    for stage in stages {
      travelled += max(stage.weight, 0) / total
      if progress < travelled { return stage }
    }
    return stages.last
  }
}

fileprivate extension Color {
  /// `#RGB`, `#RRGGBB` and `#AARRGGBB`, matching what the JS layer validates.
  init?(hex: String?) {
    guard var value = hex?.trimmingCharacters(in: .whitespaces) else { return nil }
    if value.hasPrefix("#") { value.removeFirst() }

    // Widen the shorthand by doubling each digit: f80 -> ff8800.
    if value.count == 3 {
      value = value.map { "\($0)\($0)" }.joined()
    }
    guard value.count == 6 || value.count == 8,
      let raw = UInt64(value, radix: 16)
    else { return nil }

    let hasAlpha = value.count == 8
    let alpha = hasAlpha ? Double((raw >> 24) & 0xFF) / 255 : 1
    self.init(
      .sRGB,
      red: Double((raw >> 16) & 0xFF) / 255,
      green: Double((raw >> 8) & 0xFF) / 255,
      blue: Double(raw & 0xFF) / 255,
      opacity: alpha
    )
  }
}

/// The extension's entry point. One bundle can hold several widgets; this one
/// holds the single live-update configuration.
@main
struct LiveUpdateWidgetBundle: WidgetBundle {
  var body: some Widget {
    if #available(iOS 16.1, *) {
      LiveUpdateWidget()
    }
  }
}
