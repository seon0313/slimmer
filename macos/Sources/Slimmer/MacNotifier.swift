import AppKit
import Foundation
import UserNotifications

/// Mirrors phone notifications into macOS Notification Center, with inline reply for
/// messaging notifications (KakaoTalk, Messages, ...).
final class MacNotifier: NSObject, UNUserNotificationCenterDelegate {
    static let replyCategory = "phone.reply"
    static let plainCategory = "phone.plain"
    static let replyAction = "reply"
    static let dismissAction = "dismissOnPhone"

    var onReply: ((_ key: String, _ text: String) -> Void)?
    var onDismissOnPhone: ((_ key: String) -> Void)?
    var onOpen: ((_ key: String) -> Void)?

    private let center: UNUserNotificationCenter?
    private(set) var authorized = false

    override init() {
        // UNUserNotificationCenter crashes outside an .app bundle (e.g. `swift run`).
        center = Bundle.main.bundleIdentifier != nil ? UNUserNotificationCenter.current() : nil
        super.init()
        guard let center else { return }
        center.delegate = self
        let reply = UNTextInputNotificationAction(
            identifier: MacNotifier.replyAction, title: "답장",
            options: [], textInputButtonTitle: "보내기", textInputPlaceholder: "메시지")
        let dismiss = UNNotificationAction(identifier: MacNotifier.dismissAction, title: "폰에서 지우기", options: [])
        center.setNotificationCategories([
            UNNotificationCategory(identifier: MacNotifier.replyCategory, actions: [reply, dismiss], intentIdentifiers: []),
            UNNotificationCategory(identifier: MacNotifier.plainCategory, actions: [dismiss], intentIdentifiers: []),
        ])
        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            DispatchQueue.main.async { self.authorized = granted }
        }
    }

    func post(_ n: PhoneNotif, silent: Bool) {
        guard let center else { return }
        let content = UNMutableNotificationContent()
        content.title = n.title.isEmpty ? n.app : n.title
        content.subtitle = n.title.isEmpty ? "" : n.app
        content.body = n.text
        content.userInfo = ["key": n.key]
        content.categoryIdentifier = n.replyAction != nil ? MacNotifier.replyCategory : MacNotifier.plainCategory
        content.threadIdentifier = n.pkg
        if !silent { content.sound = .default }
        center.add(UNNotificationRequest(identifier: n.key, content: content, trigger: nil))
    }

    func remove(key: String) {
        center?.removeDeliveredNotifications(withIdentifiers: [key])
    }

    func removeAll() {
        center?.removeAllDeliveredNotifications()
    }

    func openSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.notifications") {
            NSWorkspace.shared.open(url)
        }
    }

    // MARK: UNUserNotificationCenterDelegate

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let key = response.notification.request.content.userInfo["key"] as? String ?? ""
        let text = (response as? UNTextInputNotificationResponse)?.userText
        let action = response.actionIdentifier
        DispatchQueue.main.async {
            switch action {
            case MacNotifier.replyAction:
                if let text, !text.isEmpty { self.onReply?(key, text) }
            case MacNotifier.dismissAction:
                self.onDismissOnPhone?(key)
            case UNNotificationDefaultActionIdentifier:
                self.onOpen?(key)
            default:
                break
            }
            completionHandler()
        }
    }
}
