import Foundation

// Wire models (phone → Mac). Field names match the Android side exactly.

struct NotifAction: Codable, Equatable, Hashable {
    let i: Int
    let title: String
    let reply: Bool
}

struct PhoneNotif: Codable, Equatable, Identifiable {
    let key: String
    let pkg: String
    let app: String
    let title: String
    let text: String
    let time: Int64
    let actions: [NotifAction]

    var id: String { key }
    var date: Date { Date(timeIntervalSince1970: Double(time) / 1000) }
    var replyAction: NotifAction? { actions.first { $0.reply } }
}

struct SmsThread: Codable, Equatable, Identifiable {
    let id: Int64
    let address: String
    let name: String?
    let snippet: String
    let date: Int64
    let unread: Int
    let group: Bool

    var displayName: String { (name?.isEmpty == false ? name : nil) ?? address }
    var dateValue: Date { Date(timeIntervalSince1970: Double(date) / 1000) }
}

struct SmsMessage: Codable, Equatable, Identifiable {
    let id: String
    let threadId: Int64
    let address: String
    let body: String
    let date: Int64
    /// 1 = received; anything else = sent / outbox / failed / queued (Telephony box types).
    let box: Int
    let mms: Bool

    var incoming: Bool { box == 1 }
    var failed: Bool { box == 5 }
    var dateValue: Date { Date(timeIntervalSince1970: Double(date) / 1000) }
}

struct PhonePerms: Codable, Equatable {
    let notif: Bool
    let sms: Bool
}

enum Inbound {
    struct Hello: Decodable { let device: String; let perms: PhonePerms; let battery: Int? }
    struct NotifPosted: Decodable { let n: PhoneNotif }
    struct NotifRemoved: Decodable { let key: String }
    struct NotifSnapshot: Decodable { let items: [PhoneNotif] }
    struct Icon: Decodable { let pkg: String; let png: String }
    struct Threads: Decodable { let threads: [SmsThread] }
    struct Messages: Decodable { let threadId: Int64; let messages: [SmsMessage] }
    struct Result: Decodable { let req: String; let ok: Bool; let error: String?; let threadId: Int64? }
    struct Status: Decodable { let battery: Int?; let charging: Bool? }
}

extension Data {
    func decode<T: Decodable>(_ type: T.Type) -> T? {
        try? JSONDecoder().decode(type, from: self)
    }
}
