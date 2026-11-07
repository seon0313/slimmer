import Foundation

/// WebSocket client for the relay (works from anywhere: phone on LTE, Mac at home, ...).
/// All callbacks are delivered on the main queue.
final class RelayClient: NSObject, URLSessionWebSocketDelegate {
    enum State: Equatable {
        case disabled
        case connecting
        case connected
        case waiting(String)
    }

    var onFrame: ((Data) -> Void)?
    var onPeer: ((Bool) -> Void)?
    var onState: ((State) -> Void)?

    private(set) var state: State = .disabled {
        didSet { if state != oldValue { onState?(state) } }
    }

    private var session: URLSession!
    private var task: URLSessionWebSocketTask?
    private var url: URL?
    private var attempt = 0
    private var generation = 0
    private var pingTimer: Timer?
    private var lastHeard = Date()

    override init() {
        super.init()
        let cfg = URLSessionConfiguration.default
        cfg.waitsForConnectivity = false
        cfg.timeoutIntervalForRequest = 15
        session = URLSession(configuration: cfg, delegate: self, delegateQueue: .main)
    }

    func connect(to url: URL?) {
        self.url = url
        attempt = 0
        restart()
    }

    /// Drop the current socket and reconnect immediately (network changed, wake from sleep).
    func reconnectNow() {
        guard url != nil else { return }
        attempt = 0
        restart()
    }

    func send(_ data: Data) {
        guard state == .connected, let task else { return }
        task.send(.data(data)) { _ in }
    }

    private func restart() {
        generation += 1
        pingTimer?.invalidate()
        pingTimer = nil
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        onPeer?(false)
        guard let url else {
            state = .disabled
            return
        }
        let t = session.webSocketTask(with: url)
        t.maximumMessageSize = 8 << 20
        task = t
        state = .connecting
        t.resume()
        receive(t, generation: generation)
    }

    private func receive(_ t: URLSessionWebSocketTask, generation gen: Int) {
        t.receive { [weak self] result in
            DispatchQueue.main.async {
                guard let self, gen == self.generation else { return }
                switch result {
                case .failure(let error):
                    self.fail(error.localizedDescription)
                    return
                case .success(let message):
                    self.lastHeard = Date()
                    switch message {
                    case .data(let d):
                        self.onFrame?(d)
                    case .string(let s):
                        self.handleControl(s)
                    @unknown default:
                        break
                    }
                }
                self.receive(t, generation: gen)
            }
        }
    }

    private func handleControl(_ s: String) {
        guard let d = s.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: d) as? [String: Any],
              obj["t"] as? String == "peer" else { return }
        onPeer?(obj["online"] as? Bool ?? false)
    }

    private func fail(_ reason: String) {
        let gen = generation
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        pingTimer?.invalidate()
        pingTimer = nil
        onPeer?(false)
        let delay = min(30.0, pow(2.0, Double(attempt)))
        attempt += 1
        state = .waiting(reason)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self, gen == self.generation, self.task == nil else { return }
            self.restart()
        }
    }

    private func startPing() {
        pingTimer?.invalidate()
        let gen = generation
        pingTimer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
            guard let self, gen == self.generation, let task = self.task else { return }
            if Date().timeIntervalSince(self.lastHeard) > 65 {
                self.fail("응답 없음")
                return
            }
            task.sendPing { error in
                DispatchQueue.main.async {
                    if error == nil { self.lastHeard = Date() }
                }
            }
        }
    }

    // MARK: URLSessionWebSocketDelegate (main queue)

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didOpenWithProtocol protocol: String?) {
        guard webSocketTask === task else { return }
        attempt = 0
        lastHeard = Date()
        state = .connected
        startPing()
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        guard webSocketTask === task else { return }
        fail("연결 종료 (\(closeCode.rawValue))")
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard task === self.task else { return }
        fail(error?.localizedDescription ?? "연결 종료")
    }
}
