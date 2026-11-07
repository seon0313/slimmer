import Foundation
import Network

/// WebSocket server for direct connections: same Wi-Fi (found via Bonjour), or from
/// anywhere through Tailscale / a forwarded port. Frames are end-to-end encrypted, so
/// peers that cannot produce a valid frame within a few seconds are dropped.
/// All callbacks run on the main queue.
final class LanServer {
    static let defaultPort: UInt16 = 47900
    static let serviceType = "_slimmer._tcp"

    var onFrame: ((Data, LanPeer) -> Void)?
    var onPeerClosed: ((LanPeer) -> Void)?
    var onState: ((String) -> Void)?

    private var listener: NWListener?
    private var peers: [ObjectIdentifier: LanPeer] = [:]
    private(set) var port: UInt16?

    func start(serviceName: String) {
        stop()
        do {
            listener = try makeListener(port: NWEndpoint.Port(rawValue: LanServer.defaultPort)!, name: serviceName)
        } catch {
            listener = try? makeListener(port: .any, name: serviceName)
        }
        guard let listener else {
            onState?("직접 연결 서버를 시작할 수 없습니다")
            return
        }
        listener.start(queue: .main)
    }

    func stop() {
        listener?.cancel()
        listener = nil
        port = nil
        for p in peers.values { p.conn.cancel() }
        peers.removeAll()
    }

    private func makeListener(port: NWEndpoint.Port, name: String) throws -> NWListener {
        let ws = NWProtocolWebSocket.Options()
        ws.autoReplyPing = true
        ws.maximumMessageSize = 8 << 20
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 15
        tcp.keepaliveInterval = 5
        tcp.keepaliveCount = 4
        let params = NWParameters(tls: nil, tcp: tcp)
        params.allowLocalEndpointReuse = true
        params.defaultProtocolStack.applicationProtocols.insert(ws, at: 0)

        let l = try NWListener(using: params, on: port)
        l.service = NWListener.Service(name: name, type: LanServer.serviceType)
        l.newConnectionHandler = { [weak self] conn in self?.accept(conn) }
        l.stateUpdateHandler = { [weak self, weak l] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.port = l?.port?.rawValue
                self.onState?("직접 연결 대기 중 (포트 \(self.port ?? 0))")
            case .failed(let err):
                self.onState?("직접 연결 서버 오류: \(err.localizedDescription)")
            default:
                break
            }
        }
        return l
    }

    private func accept(_ conn: NWConnection) {
        let peer = LanPeer(conn: conn)
        peers[ObjectIdentifier(peer)] = peer
        conn.stateUpdateHandler = { [weak self, weak peer] state in
            guard let self, let peer else { return }
            switch state {
            case .failed, .cancelled:
                self.drop(peer)
            default:
                break
            }
        }
        conn.start(queue: .main)
        receive(peer)
        // Unauthenticated sockets get 10 s to prove they hold the key.
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak peer] in
            if let peer, !peer.authenticated { peer.close() }
        }
    }

    private func receive(_ peer: LanPeer) {
        peer.conn.receiveMessage { [weak self, weak peer] data, context, _, error in
            guard let self, let peer else { return }
            if error != nil {
                peer.close()
                return
            }
            if let meta = context?.protocolMetadata(definition: NWProtocolWebSocket.definition)
                as? NWProtocolWebSocket.Metadata {
                switch meta.opcode {
                case .binary:
                    if let data { self.onFrame?(data, peer) }
                case .close:
                    peer.close()
                    return
                default:
                    break
                }
            } else if context?.isFinal == true {
                peer.close()
                return
            }
            self.receive(peer)
        }
    }

    private func drop(_ peer: LanPeer) {
        guard peers.removeValue(forKey: ObjectIdentifier(peer)) != nil else { return }
        onPeerClosed?(peer)
    }
}

final class LanPeer {
    let conn: NWConnection
    var authenticated = false

    init(conn: NWConnection) {
        self.conn = conn
    }

    var remoteDescription: String {
        if case .hostPort(let host, _) = conn.endpoint { return "\(host)" }
        return "\(conn.endpoint)"
    }

    func send(_ data: Data) {
        let meta = NWProtocolWebSocket.Metadata(opcode: .binary)
        let ctx = NWConnection.ContentContext(identifier: "frame", metadata: [meta])
        conn.send(content: data, contentContext: ctx, isComplete: true, completion: .contentProcessed { _ in })
    }

    func close() {
        conn.cancel()
    }
}
