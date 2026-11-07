import CryptoKit
import Foundation

/// End-to-end encryption for every app message, on every transport (LAN or relay).
///
/// Frame  = nonce(12) ‖ ciphertext ‖ tag(16)      (AES-256-GCM, = CryptoKit `combined`)
/// AAD    = "m2p" (Mac → phone) or "p2m" (phone → Mac), so frames cannot be reflected.
/// Plain  = UTF-8 JSON `{"type":…, "id":uuid, "ts":ms, …}`.
/// Frames older/newer than 10 minutes or with an already-seen id are dropped (replay guard).
final class SecureChannel {
    private let key: SymmetricKey
    private let sendAAD = Data("m2p".utf8)
    private let recvAAD = Data("p2m".utf8)
    private var seen = Set<String>()
    private var seenOrder: [String] = []
    private static let maxSkewMs: Int64 = 10 * 60 * 1000

    init(pairing: Pairing) {
        key = pairing.encKey
    }

    func seal(type: String, body: [String: Any]) -> Data? {
        var obj = body
        obj["type"] = type
        obj["id"] = UUID().uuidString
        obj["ts"] = Int64(Date().timeIntervalSince1970 * 1000)
        guard let json = try? JSONSerialization.data(withJSONObject: obj) else { return nil }
        return try? AES.GCM.seal(json, using: key, authenticating: sendAAD).combined
    }

    struct Header: Decodable {
        let type: String
        let id: String
        let ts: Int64
    }

    /// Returns the message type and its raw JSON, or nil if the frame is not authentic.
    func open(_ frame: Data) -> (type: String, json: Data)? {
        guard frame.count >= 28,
              let box = try? AES.GCM.SealedBox(combined: frame),
              let plain = try? AES.GCM.open(box, using: key, authenticating: recvAAD),
              let header = try? JSONDecoder().decode(Header.self, from: plain)
        else { return nil }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        guard abs(now - header.ts) <= SecureChannel.maxSkewMs, !seen.contains(header.id) else { return nil }
        seen.insert(header.id)
        seenOrder.append(header.id)
        if seenOrder.count > 4000 {
            for id in seenOrder.prefix(1000) { seen.remove(id) }
            seenOrder.removeFirst(1000)
        }
        return (header.type, plain)
    }
}
