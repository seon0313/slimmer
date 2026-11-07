import AppKit
import Combine
import Foundation
import Network
import ServiceManagement

/// App state. Everything runs on the main queue.
final class AppModel: ObservableObject {
    // Pairing / settings
    @Published private(set) var pairing: Pairing
    @Published var relayURL: String { didSet { defaults.set(relayURL, forKey: "relayURL") } }
    @Published var directHost: String { didSet { defaults.set(directHost, forKey: "directHost") } }
    @Published var directEnabled: Bool {
        didSet { defaults.set(directEnabled, forKey: "directEnabled"); applyConnection() }
    }
    @Published var mirrorToMac: Bool { didSet { defaults.set(mirrorToMac, forKey: "mirrorToMac") } }
    @Published var silentBanners: Bool { didSet { defaults.set(silentBanners, forKey: "silentBanners") } }
    @Published private(set) var mutedApps: [String: String] {   // pkg → app name
        didSet { defaults.set(mutedApps, forKey: "mutedApps") }
    }
    @Published var launchAtLogin: Bool = SMAppService.mainApp.status == .enabled {
        didSet { setLaunchAtLogin(launchAtLogin) }
    }

    // Connection
    @Published private(set) var route: LinkManager.Route = .none
    @Published private(set) var relayState: RelayClient.State = .disabled
    @Published private(set) var relayPeerOnline = false
    @Published private(set) var lanStatus = ""
    @Published private(set) var phoneName: String?
    @Published private(set) var phonePerms: PhonePerms?
    @Published private(set) var battery: Int?
    @Published private(set) var charging = false

    // Data
    @Published private(set) var notifications: [PhoneNotif] = []
    @Published private(set) var icons: [String: NSImage] = [:]
    @Published private(set) var threads: [SmsThread] = []
    @Published private(set) var messages: [Int64: [SmsMessage]] = [:]
    @Published private(set) var loadingThreads = false
    @Published var selectedThread: Int64? { didSet { if let t = selectedThread { loadMessages(t) } } }
    @Published private(set) var pendingSends: [String: PendingSend] = [:]
    @Published var toast: String?

    struct PendingSend: Equatable {
        let threadId: Int64?
        let address: String
        let body: String
        var failed: String?
    }

    let link = LinkManager()
    let notifier = MacNotifier()
    private let defaults = UserDefaults.standard
    private let pathMonitor = NWPathMonitor()
    private var pendingReplies: [String: String] = [:]   // req → notif key
    private var toastWork: DispatchWorkItem?
    private var sawFirstPath = false

    var macName: String { Host.current().localizedName ?? "Mac" }
    var pairingURI: String { pairing.uri(relay: relayURL, macName: macName, direct: directHost) }
    var isConnected: Bool { route != .none }
    var visibleNotifications: [PhoneNotif] { notifications.filter { mutedApps[$0.pkg] == nil } }

    init() {
        pairing = Pairing.loadOrCreate()
        relayURL = defaults.string(forKey: "relayURL") ?? ""
        directHost = defaults.string(forKey: "directHost") ?? ""
        directEnabled = defaults.object(forKey: "directEnabled") as? Bool ?? true
        mirrorToMac = defaults.object(forKey: "mirrorToMac") as? Bool ?? true
        silentBanners = defaults.bool(forKey: "silentBanners")
        mutedApps = defaults.dictionary(forKey: "mutedApps") as? [String: String] ?? [:]

        link.onChange = { [weak self] in self?.syncLinkState() }
        link.onMessage = { [weak self] type, json in self?.handle(type, json) }
        notifier.onReply = { [weak self] key, text in self?.reply(to: key, text: text) }
        notifier.onDismissOnPhone = { [weak self] key in self?.dismissOnPhone(key) }
        notifier.onOpen = { [weak self] key in self?.openFromBanner(key) }

        pathMonitor.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == .satisfied
            DispatchQueue.main.async {
                guard let self else { return }
                defer { self.sawFirstPath = true }
                if self.sawFirstPath && satisfied { self.link.reconnect() }
            }
        }
        pathMonitor.start(queue: .global(qos: .utility))
        NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in self?.link.reconnect() }

        applyConnection()
    }

    // MARK: Connection

    func applyConnection() {
        link.configure(pairing: pairing, relayURL: relayURL, directEnabled: directEnabled)
    }

    func regeneratePairing() {
        pairing = Pairing.generate()
        pairing.save()
        notifications = []
        threads = []
        messages = [:]
        phoneName = nil
        phonePerms = nil
        notifier.removeAll()
        applyConnection()
    }

    private func syncLinkState() {
        let wasConnected = isConnected
        route = link.route
        relayState = link.relayState
        relayPeerOnline = link.relayPeerOnline
        lanStatus = link.lanStatus
        if wasConnected && !isConnected {
            pendingSends = pendingSends.mapValues { var p = $0; p.failed = p.failed ?? "연결 끊김"; return p }
        }
    }

    var statusText: String {
        switch route {
        case .direct: return "직접 연결됨"
        case .relay: return "릴레이로 연결됨"
        case .none:
            switch relayState {
            case .disabled: return directEnabled ? "같은 네트워크에서 폰을 기다리는 중" : "릴레이 주소를 설정하세요"
            case .connecting: return "릴레이 연결 중…"
            case .connected: return "폰이 오프라인입니다"
            case .waiting(let why): return "재연결 대기 중 (\(why))"
            }
        }
    }

    var relayStatusText: String {
        switch relayState {
        case .disabled: return "사용 안 함"
        case .connecting: return "연결 중…"
        case .connected: return relayPeerOnline ? "연결됨 · 폰 온라인" : "연결됨 · 폰 오프라인"
        case .waiting(let why): return "재연결 대기 (\(why))"
        }
    }

    // MARK: Inbound

    private func handle(_ type: String, _ json: Data) {
        switch type {
        case "hello":
            guard let m = json.decode(Inbound.Hello.self) else { return }
            phoneName = m.device
            phonePerms = m.perms
            if let b = m.battery { battery = b }
            link.send("notif.sync")
            requestThreads()
            if let t = selectedThread { loadMessages(t) }

        case "status":
            guard let m = json.decode(Inbound.Status.self) else { return }
            battery = m.battery ?? battery
            charging = m.charging ?? charging

        case "icon":
            guard let m = json.decode(Inbound.Icon.self), let d = Data(base64Encoded: m.png),
                  let img = NSImage(data: d) else { return }
            icons[m.pkg] = img

        case "notif.snapshot":
            guard let m = json.decode(Inbound.NotifSnapshot.self) else { return }
            notifications = m.items.sorted { $0.time > $1.time }

        case "notif.posted":
            guard let m = json.decode(Inbound.NotifPosted.self) else { return }
            let n = m.n
            let isUpdate = notifications.contains { $0.key == n.key }
            notifications.removeAll { $0.key == n.key }
            notifications.insert(n, at: 0)
            if notifications.count > 200 { notifications.removeLast(notifications.count - 200) }
            if mirrorToMac && mutedApps[n.pkg] == nil {
                notifier.post(n, silent: silentBanners || isUpdate)
            }

        case "notif.removed":
            guard let m = json.decode(Inbound.NotifRemoved.self) else { return }
            notifications.removeAll { $0.key == m.key }
            notifier.remove(key: m.key)

        case "notif.result":
            guard let m = json.decode(Inbound.Result.self) else { return }
            pendingReplies.removeValue(forKey: m.req)
            if !m.ok { showToast("폰에서 실행 실패: \(m.error ?? "알 수 없는 오류")") }

        case "sms.threads":
            guard let m = json.decode(Inbound.Threads.self) else { return }
            threads = m.threads
            loadingThreads = false

        case "sms.messages":
            guard let m = json.decode(Inbound.Messages.self) else { return }
            messages[m.threadId] = m.messages

        case "sms.changed":
            requestThreads()
            if let t = selectedThread { loadMessages(t) }

        case "sms.result":
            guard let m = json.decode(Inbound.Result.self) else { return }
            let pending = pendingSends[m.req]
            if m.ok {
                pendingSends.removeValue(forKey: m.req)
                if let tid = m.threadId ?? pending?.threadId {
                    // A new conversation: jump into the thread the phone created.
                    if pending != nil && pending?.threadId == nil { selectedThread = tid } else { loadMessages(tid) }
                }
                requestThreads()
            } else {
                pendingSends[m.req]?.failed = m.error ?? "전송 실패"
            }

        default:
            break
        }
    }

    // MARK: Notifications

    func reply(to key: String, text: String) {
        guard let n = notifications.first(where: { $0.key == key }), let action = n.replyAction else {
            showToast("이 알림은 답장을 지원하지 않습니다")
            return
        }
        perform(n, action: action, text: text)
    }

    func perform(_ n: PhoneNotif, action: NotifAction, text: String? = nil) {
        let req = UUID().uuidString
        var body: [String: Any] = ["req": req, "key": n.key, "action": action.i]
        if let text { body["text"] = text }
        if link.send("notif.action", body) {
            pendingReplies[req] = n.key
        } else {
            showToast("폰과 연결되어 있지 않습니다")
        }
    }

    func dismissOnPhone(_ key: String) {
        link.send("notif.dismiss", ["key": key])
        notifications.removeAll { $0.key == key }
        notifier.remove(key: key)
    }

    func dismissAllOnPhone() {
        for n in visibleNotifications { link.send("notif.dismiss", ["key": n.key]) }
        notifications.removeAll { mutedApps[$0.pkg] == nil }
        notifier.removeAll()
    }

    func open(_ n: PhoneNotif) {
        link.send("notif.open", ["key": n.key])
    }

    private func openFromBanner(_ key: String) {
        NSApp.activate(ignoringOtherApps: true)
    }

    func mute(_ n: PhoneNotif) {
        mutedApps[n.pkg] = n.app
    }

    func unmute(_ pkg: String) {
        mutedApps.removeValue(forKey: pkg)
    }

    // MARK: SMS

    func requestThreads() {
        if link.send("sms.threads.get", ["limit": 80]) { loadingThreads = threads.isEmpty }
    }

    func loadMessages(_ threadId: Int64) {
        link.send("sms.messages.get", ["threadId": threadId, "limit": 150])
    }

    func thread(_ id: Int64?) -> SmsThread? {
        guard let id else { return nil }
        return threads.first { $0.id == id }
    }

    /// Sends an SMS. `threadId` is nil when starting a new conversation.
    func sendSms(to address: String, body: String, threadId: Int64?) {
        let text = body.trimmingCharacters(in: .whitespacesAndNewlines)
        let addr = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !addr.isEmpty else { return }
        let req = UUID().uuidString
        var payload: [String: Any] = ["req": req, "address": addr, "body": text]
        if let threadId { payload["threadId"] = threadId }
        var pending = PendingSend(threadId: threadId, address: addr, body: text, failed: nil)
        if !link.send("sms.send", payload) { pending.failed = "폰과 연결되어 있지 않습니다" }
        pendingSends[req] = pending
    }

    func pending(for threadId: Int64) -> [(String, PendingSend)] {
        pendingSends.filter { $0.value.threadId == threadId }.sorted { $0.key < $1.key }
    }

    func discardPending(_ req: String) {
        pendingSends.removeValue(forKey: req)
    }

    func retryPending(_ req: String) {
        guard let p = pendingSends.removeValue(forKey: req) else { return }
        sendSms(to: p.address, body: p.body, threadId: p.threadId)
    }

    // MARK: Misc

    func showToast(_ text: String) {
        toast = text
        toastWork?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.toast = nil }
        toastWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 4, execute: work)
    }

    private func setLaunchAtLogin(_ on: Bool) {
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
        } catch {
            showToast("로그인 항목 설정 실패: \(error.localizedDescription)")
        }
    }

    func copyPairingCode() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(pairingURI, forType: .string)
        showToast("페어링 코드를 복사했습니다")
    }
}
