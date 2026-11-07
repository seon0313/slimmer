import AppKit
import CoreImage
import CryptoKit
import Foundation

/// Shared secret between this Mac and the phone. Everything else is derived from it:
///
///   room id  = hex(SHA256("slimmer/room" ‖ K))[0..<32]   — relay room (public)
///   lan id   = hex(SHA256("slimmer/lan"  ‖ K))[0..<16]   — Bonjour service name suffix
///   enc key  = SHA256("slimmer/enc" ‖ K)                  — AES-256-GCM key
///
/// The pairing QR / code is `slimmer://pair?v=1&k=<base64url K>&r=<relay>&n=<name>&d=<host:port>`.
struct Pairing: Equatable {
    let secret: Data

    static func generate() -> Pairing {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed")
        return Pairing(secret: Data(bytes))
    }

    private func derive(_ label: String) -> Data {
        Data(SHA256.hash(data: Data(label.utf8) + secret))
    }

    var roomId: String { String(derive("slimmer/room").hexString.prefix(32)) }
    var lanId: String { String(derive("slimmer/lan").hexString.prefix(16)) }
    var encKey: SymmetricKey { SymmetricKey(data: derive("slimmer/enc")) }
    var lanServiceName: String { "slimmer-\(lanId)" }

    /// Short human-readable fingerprint shown on both devices to confirm they match.
    var fingerprint: String {
        let h = derive("slimmer/fp").hexString.uppercased()
        return "\(h.prefix(4))-\(h.dropFirst(4).prefix(4))"
    }

    func uri(relay: String, macName: String, direct: String) -> String {
        var items: [(String, String)] = [("v", "1"), ("k", secret.base64URLEncoded), ("n", macName)]
        if let r = Pairing.normalizeRelay(relay) { items.append(("r", r)) }
        let d = direct.trimmingCharacters(in: .whitespacesAndNewlines)
        if !d.isEmpty { items.append(("d", d)) }
        // Strict encoding (only unreserved characters pass) so every parser agrees.
        let allowed = CharacterSet(charactersIn:
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        let query = items.map { k, v in
            "\(k)=\(v.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")"
        }.joined(separator: "&")
        return "slimmer://pair?\(query)"
    }

    /// Accepts `https://x`, `wss://x`, or a bare host and returns a `ws(s)://` base URL.
    static func normalizeRelay(_ raw: String) -> String? {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return nil }
        if s.hasPrefix("https://") { s = "wss://" + s.dropFirst(8) }
        else if s.hasPrefix("http://") { s = "ws://" + s.dropFirst(7) }
        else if !s.hasPrefix("wss://") && !s.hasPrefix("ws://") { s = "wss://" + s }
        while s.hasSuffix("/") { s.removeLast() }
        return s
    }

    func relayRoomURL(_ raw: String) -> URL? {
        guard let base = Pairing.normalizeRelay(raw) else { return nil }
        return URL(string: "\(base)/v1/room/\(roomId)?role=mac")
    }

    // MARK: Storage

    private static let defaultsKey = "pairingSecret"

    static func loadOrCreate() -> Pairing {
        if let s = UserDefaults.standard.string(forKey: defaultsKey),
           let d = Data(base64Encoded: s), d.count == 32 {
            return Pairing(secret: d)
        }
        let p = generate()
        p.save()
        return p
    }

    func save() {
        UserDefaults.standard.set(secret.base64EncodedString(), forKey: Pairing.defaultsKey)
    }
}

enum QRCode {
    static func image(for string: String, size: CGFloat = 220) -> NSImage? {
        guard let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
        filter.setValue(Data(string.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }
        let scale = max(1, floor(size / output.extent.width))
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let rep = NSCIImageRep(ciImage: scaled)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }
}

extension Data {
    var hexString: String { map { String(format: "%02x", $0) }.joined() }

    var base64URLEncoded: String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
