import Foundation

/// Owns both transports and picks the route for outgoing messages:
/// a direct (LAN / Tailscale) connection when the phone has one, otherwise the relay.
/// Main-queue only.
final class LinkManager {
    enum Route: Equatable {
        case none, direct, relay
    }

    var onMessage: ((String, Data) -> Void)?
    var onChange: (() -> Void)?

    let relay = RelayClient()
    let lan = LanServer()

    private(set) var relayState: RelayClient.State = .disabled
    private(set) var relayPeerOnline = false
    private(set) var lanStatus = "꺼짐"
    private(set) var directPeer: LanPeer?
    private var channel: SecureChannel?

    var route: Route {
        if directPeer != nil { return .direct }
        if relayState == .connected && relayPeerOnline { return .relay }
        return .none
    }

    init() {
        relay.onState = { [weak self] s in
            self?.relayState = s
            self?.onChange?()
        }
        relay.onPeer = { [weak self] online in
            guard let self, online != self.relayPeerOnline else { return }
            self.relayPeerOnline = online
            self.onChange?()
        }
        relay.onFrame = { [weak self] data in
            _ = self?.receive(data)
        }
        lan.onState = { [weak self] s in
            self?.lanStatus = s
            self?.onChange?()
        }
        lan.onFrame = { [weak self] data, peer in
            guard let self else { return }
            if self.receive(data) {
                peer.authenticated = true
                if self.directPeer !== peer {
                    self.directPeer?.close()
                    self.directPeer = peer
                    self.onChange?()
                }
            } else if !peer.authenticated {
                peer.close()
            }
        }
        lan.onPeerClosed = { [weak self] peer in
            guard let self, self.directPeer === peer else { return }
            self.directPeer = nil
            self.onChange?()
        }
    }

    func configure(pairing: Pairing, relayURL: String, directEnabled: Bool) {
        channel = SecureChannel(pairing: pairing)
        directPeer?.close()
        directPeer = nil
        relayPeerOnline = false
        relay.connect(to: pairing.relayRoomURL(relayURL))
        if directEnabled {
            lan.start(serviceName: pairing.lanServiceName)
        } else {
            lan.stop()
            lanStatus = "꺼짐"
        }
        onChange?()
    }

    func reconnect() {
        relay.reconnectNow()
    }

    @discardableResult
    func send(_ type: String, _ body: [String: Any] = [:]) -> Bool {
        guard let channel, let frame = channel.seal(type: type, body: body) else { return false }
        switch route {
        case .direct:
            directPeer?.send(frame)
            return true
        case .relay:
            relay.send(frame)
            return true
        case .none:
            return false
        }
    }

    @discardableResult
    private func receive(_ frame: Data) -> Bool {
        guard let msg = channel?.open(frame) else { return false }
        onMessage?(msg.type, msg.json)
        return true
    }
}
