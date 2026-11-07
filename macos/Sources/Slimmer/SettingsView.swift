import SwiftUI

struct SettingsView: View {
    @EnvironmentObject var m: AppModel
    @State private var relayDraft = ""
    @State private var directDraft = ""
    @State private var confirmReset = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                pairingSection
                Divider()
                connectionSection
                Divider()
                optionsSection
                if !m.mutedApps.isEmpty {
                    Divider()
                    mutedSection
                }
            }
            .padding(12)
        }
        .onAppear {
            relayDraft = m.relayURL
            directDraft = m.directHost
        }
    }

    private var pairingSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("폰 연결").font(.headline)
            Text("폰의 Slimmer 앱에서 ‘QR 스캔’으로 아래 코드를 찍으세요. 릴레이 주소를 바꾸면 다시 스캔해야 합니다.")
                .font(.caption).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                if let img = QRCode.image(for: m.pairingURI, size: 200) {
                    Image(nsImage: img)
                        .interpolation(.none)
                        .resizable()
                        .frame(width: 200, height: 200)
                        .padding(8)
                        .background(Color.white)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                }
                Spacer()
            }
            HStack {
                Text("확인 코드").foregroundStyle(.secondary)
                Text(m.pairing.fingerprint).font(.system(.body, design: .monospaced)).bold()
                Spacer()
                Button("코드 복사") { m.copyPairingCode() }
                Button("새로 페어링…") { confirmReset = true }
            }
            .font(.callout)
            .confirmationDialog("새 키로 다시 페어링할까요?", isPresented: $confirmReset) {
                Button("새로 페어링", role: .destructive) { m.regeneratePairing() }
            } message: {
                Text("기존에 연결된 폰은 더 이상 연결되지 않으며 QR을 다시 스캔해야 합니다.")
            }
        }
    }

    private var connectionSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("외부 네트워크 (릴레이)").font(.headline)
            Text("폰이 LTE/5G 등 다른 네트워크에 있어도 연결되도록 중계 서버를 거칩니다. 내용은 종단간 암호화되어 서버는 볼 수 없습니다.")
                .font(.caption).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                TextField("wss://slimmer-relay.example.workers.dev", text: $relayDraft)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(applyRelay)
                Button("적용", action: applyRelay)
                    .disabled(relayDraft == m.relayURL)
            }
            LabeledContent("릴레이", value: m.relayStatusText).font(.caption)

            Toggle("같은 네트워크에서는 직접 연결", isOn: $m.directEnabled)
            if m.directEnabled {
                LabeledContent("직접 연결", value: m.lanStatus.isEmpty ? "시작 중…" : m.lanStatus).font(.caption)
                HStack {
                    TextField("직접 접속 주소 (선택, 예: mac.tailnet.ts.net:47900)", text: $directDraft)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit { m.directHost = directDraft }
                    Button("저장") { m.directHost = directDraft }
                        .disabled(directDraft == m.directHost)
                }
                Text("Tailscale이나 포트포워딩을 쓰면 릴레이 없이도 외부에서 직접 연결할 수 있습니다. 저장 후 QR을 다시 스캔하세요.")
                    .font(.caption2).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var optionsSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("옵션").font(.headline)
            Toggle("macOS 알림으로 표시 (답장 가능)", isOn: $m.mirrorToMac)
            Toggle("알림 소리 끄기", isOn: $m.silentBanners)
            Toggle("로그인 시 실행", isOn: $m.launchAtLogin)
            Button("macOS 알림 설정 열기…") { m.notifier.openSettings() }
                .buttonStyle(.link)
                .font(.caption)
        }
    }

    private var mutedSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("숨긴 앱").font(.headline)
            ForEach(m.mutedApps.sorted { $0.value < $1.value }, id: \.key) { pkg, name in
                HStack {
                    AppIconView(image: m.icons[pkg], size: 18)
                    Text(name)
                    Spacer()
                    Button("다시 표시") { m.unmute(pkg) }.controlSize(.small)
                }
            }
        }
    }

    private func applyRelay() {
        m.relayURL = relayDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        relayDraft = m.relayURL
        m.applyConnection()
    }
}
