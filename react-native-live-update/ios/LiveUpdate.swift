import Foundation
import React

#if canImport(ActivityKit)
  import ActivityKit
#endif

/// Live Activities, driven from JS.
///
/// Everything ActivityKit is behind `@available(iOS 16.1, *)`: the pod builds
/// against 16.0 so it can go into an app with a lower floor, and on anything
/// older every call resolves "unsupported" rather than failing to link.
@objc(LiveUpdate)
final class LiveUpdate: RCTEventEmitter {

  private var hasListeners = false

  /// Started activities, by the caller's own id. ActivityKit hands back its own
  /// opaque id; callers think in order ids, so the mapping lives here.
  private var tokenTasks: [String: Task<Void, Never>] = [:]

  override static func requiresMainQueueSetup() -> Bool { false }

  override func supportedEvents() -> [String] { ["LiveUpdatePushToken"] }

  override func startObserving() { hasListeners = true }
  override func stopObserving() { hasListeners = false }

  // MARK: - Support

  @objc(isSupported:withRejecter:)
  func isSupported(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
    #if canImport(ActivityKit)
      if #available(iOS 16.1, *) {
        let info = ActivityAuthorizationInfo()
        resolve([
          "supported": true,
          // The user can switch Live Activities off per app in Settings, and
          // Activity.request() throws if they have. Report it rather than
          // discovering it at start().
          "enabled": info.areActivitiesEnabled,
        ])
        return
      }
      resolve(["supported": false, "enabled": false, "reason": "Live Activities require iOS 16.1"])
    #else
      resolve(["supported": false, "enabled": false, "reason": "ActivityKit is unavailable in this build"])
    #endif
  }

  /// The detail behind the two booleans in `isSupported()`.
  ///
  /// `promotedSurface` reports the Dynamic Island as available whenever
  /// ActivityKit is, which is as precise as iOS allows: there is no public API
  /// that says whether this particular iPhone has the hardware. On one that
  /// does not, the very same activity is drawn on the Lock Screen instead, so
  /// there is no branch for an app to take — which is why Apple never exposed
  /// the question.
  @objc(getCapabilities:withRejecter:)
  func getCapabilities(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
    let version = ProcessInfo.processInfo.operatingSystemVersion
    let osVersion = "\(version.majorVersion).\(version.minorVersion)"

    #if canImport(ActivityKit)
      if #available(iOS 16.1, *) {
        let enabled = ActivityAuthorizationInfo().areActivitiesEnabled
        resolve([
          "platform": "ios",
          "osVersion": osVersion,
          "supported": true,
          "enabled": enabled,
          "stagesSupported": true,
          "lockScreenSupported": true,
          "pushUpdatesSupported": true,
          "promotedSurface": ["available": enabled, "type": "dynamicIsland"],
          // Live Activities are governed by their own per-app switch, not by
          // the notification permission — an app with notifications denied can
          // still show one.
          "notificationPermission": "granted",
          "reason": enabled ? nil : "Live Activities are turned off for this app in Settings",
        ].compactMapValues { $0 })
        return
      }
    #endif

    resolve([
      "platform": "ios",
      "osVersion": osVersion,
      "supported": false,
      "enabled": false,
      "stagesSupported": false,
      "lockScreenSupported": false,
      "pushUpdatesSupported": false,
      "promotedSurface": ["available": false],
      "notificationPermission": "unknown",
      "reason": "Live Activities require iOS 16.1",
    ])
  }

  /// Android's notification channel has no counterpart here — iOS gives the
  /// user one switch per app and no knobs for the developer. Present so
  /// calling it unconditionally at startup is safe.
  @objc(configureNotifications:resolver:rejecter:)
  func configureNotifications(
    config: NSDictionary,
    resolve: RCTPromiseResolveBlock,
    reject: RCTPromiseRejectBlock
  ) {
    resolve(nil)
  }

  // MARK: - Lifecycle

  /// `options` carries `persistent`, which is Android-only and ignored here.
  /// iOS gives an app no say in whether a Live Activity can be swiped away —
  /// the user can always clear one from the Lock Screen, and there is no
  /// callback for it and no way to re-request it. Accepted so the JS API has
  /// one shape on both platforms.
  @objc(start:name:content:options:resolver:rejecter:)
  func start(
    id: String,
    name: String,
    content: NSDictionary,
    options: NSDictionary,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) {
    #if canImport(ActivityKit)
      guard #available(iOS 16.1, *) else {
        reject("NOT_SUPPORTED", "Live Activities require iOS 16.1", nil)
        return
      }

      guard ActivityAuthorizationInfo().areActivitiesEnabled else {
        reject("PERMISSION_DENIED", "Live Activities are turned off for this app in Settings", nil)
        return
      }

      // Starting one that already exists is an update, not a second activity —
      // otherwise a screen that re-mounts stacks duplicates on the Lock Screen.
      if let existing = Self.activity(for: id) {
        Task {
          await existing.update(using: Self.state(from: content))
          resolve(["id": id, "pushToken": Self.tokenString(existing.pushToken)])
        }
        return
      }

      do {
        let activity = try Activity<LiveUpdateAttributes>.request(
          attributes: LiveUpdateAttributes(id: id, name: name),
          contentState: Self.state(from: content),
          // Ask for a token unconditionally: the app cannot know yet whether
          // the server will want to push, and requesting later is not possible.
          pushType: .token
        )
        observeToken(of: activity, id: id)
        resolve(["id": id, "pushToken": Self.tokenString(activity.pushToken)])
      } catch {
        reject("START_FAILED", error.localizedDescription, error)
      }
    #else
      reject("NOT_SUPPORTED", "ActivityKit is unavailable in this build", nil)
    #endif
  }

  @objc(update:content:resolver:rejecter:)
  func update(
    id: String,
    content: NSDictionary,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) {
    #if canImport(ActivityKit)
      guard #available(iOS 16.1, *), let activity = Self.activity(for: id) else {
        reject("NOT_FOUND", "No live update with id \(id)", nil)
        return
      }
      Task {
        await activity.update(using: Self.state(from: content))
        resolve(nil)
      }
    #else
      reject("NOT_SUPPORTED", "ActivityKit is unavailable in this build", nil)
    #endif
  }

  @objc(end:dismissAfterMs:resolver:rejecter:)
  func end(
    id: String,
    dismissAfterMs: NSNumber,
    resolve: @escaping RCTPromiseResolveBlock,
    reject: @escaping RCTPromiseRejectBlock
  ) {
    #if canImport(ActivityKit)
      guard #available(iOS 16.1, *), let activity = Self.activity(for: id) else {
        // Already gone is the outcome the caller wanted.
        resolve(nil)
        return
      }
      tokenTasks[id]?.cancel()
      tokenTasks[id] = nil

      let ms = dismissAfterMs.doubleValue
      let policy: ActivityUIDismissalPolicy =
        ms <= 0 ? .immediate : .after(Date().addingTimeInterval(ms / 1000))

      Task {
        await activity.end(dismissalPolicy: policy)
        resolve(nil)
      }
    #else
      resolve(nil)
    #endif
  }

  @objc(getRunning:withRejecter:)
  func getRunning(resolve: RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
    #if canImport(ActivityKit)
      if #available(iOS 16.1, *) {
        resolve(Activity<LiveUpdateAttributes>.activities.map { $0.attributes.id })
        return
      }
    #endif
    resolve([])
  }

  @objc(endAll:withRejecter:)
  func endAll(resolve: @escaping RCTPromiseResolveBlock, reject: RCTPromiseRejectBlock) {
    #if canImport(ActivityKit)
      if #available(iOS 16.1, *) {
        tokenTasks.values.forEach { $0.cancel() }
        tokenTasks.removeAll()
        Task {
          for activity in Activity<LiveUpdateAttributes>.activities {
            await activity.end(dismissalPolicy: .immediate)
          }
          resolve(nil)
        }
        return
      }
    #endif
    resolve(nil)
  }

  // MARK: - Helpers

  #if canImport(ActivityKit)
    @available(iOS 16.1, *)
    private static func activity(for id: String) -> Activity<LiveUpdateAttributes>? {
      // Read the system's list rather than keeping our own: activities survive
      // the app being killed, so a dictionary built at start() is empty after a
      // relaunch while the activity is still on the Lock Screen.
      Activity<LiveUpdateAttributes>.activities.first { $0.attributes.id == id }
    }

    @available(iOS 16.1, *)
    private static func state(from content: NSDictionary) -> LiveUpdateAttributes.ContentState {
      LiveUpdateAttributes.ContentState(
        title: content["title"] as? String ?? "",
        message: content["message"] as? String,
        status: content["status"] as? String,
        progress: (content["progress"] as? NSNumber)?.doubleValue,
        stages: stages(from: content["stages"]),
        // JS speaks milliseconds, Date speaks seconds.
        endsAt: (content["endsAt"] as? NSNumber).map { $0.doubleValue / 1000 },
        icon: content["icon"] as? String,
        color: content["color"] as? String,
        deepLink: content["deepLink"] as? String
      )
    }

    /// `trackerIcon` is read and discarded here: it is the marker that rides
    /// Android's progress track, and iOS's bar has no equivalent. Carrying it
    /// into ContentState would spend part of the 4KB budget on something
    /// nothing draws.
    @available(iOS 16.1, *)
    private static func stages(from value: Any?) -> [LiveUpdateAttributes.Stage]? {
      guard let raw = value as? [NSDictionary], !raw.isEmpty else { return nil }
      let stages = raw.compactMap { entry -> LiveUpdateAttributes.Stage? in
        guard let id = entry["id"] as? String else { return nil }
        return LiveUpdateAttributes.Stage(
          id: id,
          title: entry["title"] as? String ?? "",
          weight: (entry["weight"] as? NSNumber)?.doubleValue ?? 1,
          color: entry["color"] as? String
        )
      }
      return stages.isEmpty ? nil : stages
    }

    private static func tokenString(_ data: Data?) -> String? {
      guard let data else { return nil }
      return data.map { String(format: "%02x", $0) }.joined()
    }

    /// ActivityKit issues the push token asynchronously and rotates it, so the
    /// value available at start() is usually nil. This streams every token the
    /// system hands out for the life of the activity.
    @available(iOS 16.1, *)
    private func observeToken(of activity: Activity<LiveUpdateAttributes>, id: String) {
      tokenTasks[id]?.cancel()
      tokenTasks[id] = Task { [weak self] in
        for await data in activity.pushTokenUpdates {
          guard let self, self.hasListeners else { continue }
          let token = data.map { String(format: "%02x", $0) }.joined()
          self.sendEvent(withName: "LiveUpdatePushToken", body: ["id": id, "token": token])
        }
      }
    }
  #endif
}
