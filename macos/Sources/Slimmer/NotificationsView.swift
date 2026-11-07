import SwiftUI

struct NotificationsView: View {
    @EnvironmentObject var m: AppModel

    var body: some View {
        let items = m.visibleNotifications
        VStack(spacing: 0) {
            if items.isEmpty {
                Spacer()
                VStack(spacing: 8) {
                    Image(systemName: "bell.slash").font(.largeTitle).foregroundStyle(.tertiary)
                    Text(emptyText).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }
                .padding()
                Spacer()
            } else {
                ScrollView {
                    LazyVStack(spacing: 8) {
                        ForEach(items) { n in
                            NotificationRow(n: n)
                        }
                    }
                    .padding(10)
                }
                Divider()
                HStack {
                    Spacer()
                    Button("모두 지우기") { m.dismissAllOnPhone() }
                        .buttonStyle(.borderless)
                        .font(.caption)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
            }
        }
    }

    private var emptyText: String {
        if !m.isConnected { return "폰과 연결되면 알림이 여기에 표시됩니다" }
        if m.phonePerms?.notif == false { return "폰에서 Slimmer의 알림 접근 권한을 허용하세요" }
        return "새 알림 없음"
    }
}

struct NotificationRow: View {
    @EnvironmentObject var m: AppModel
    let n: PhoneNotif
    @State private var replying = false
    @State private var replyText = ""
    @State private var hovering = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top, spacing: 8) {
                AppIconView(image: m.icons[n.pkg], size: 26)
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(n.app).font(.caption).foregroundStyle(.secondary)
                        Spacer()
                        Text(Fmt.relative(n.date)).font(.caption2).foregroundStyle(.tertiary)
                    }
                    if !n.title.isEmpty {
                        Text(n.title).font(.callout.weight(.semibold)).lineLimit(1)
                    }
                    if !n.text.isEmpty {
                        Text(n.text).font(.callout).lineLimit(4).textSelection(.enabled)
                    }
                }
            }

            if !n.actions.isEmpty || hovering {
                HStack(spacing: 6) {
                    ForEach(n.actions, id: \.self) { a in
                        Button(a.title) {
                            if a.reply { replying.toggle() } else { m.perform(n, action: a) }
                        }
                        .controlSize(.small)
                    }
                    Spacer()
                    Button {
                        m.dismissOnPhone(n.key)
                    } label: {
                        Image(systemName: "xmark")
                    }
                    .controlSize(.small)
                    .help("폰에서 지우기")
                }
            }

            if replying, let action = n.replyAction {
                HStack {
                    TextField("답장", text: $replyText)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit { send(action) }
                    Button("보내기") { send(action) }
                        .disabled(replyText.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color.primary.opacity(0.05)))
        .onHover { hovering = $0 }
        .contextMenu {
            Button("폰에서 열기") { m.open(n) }
            Button("폰에서 지우기") { m.dismissOnPhone(n.key) }
            Divider()
            Button("‘\(n.app)’ 알림 숨기기") { m.mute(n) }
        }
    }

    private func send(_ action: NotifAction) {
        let text = replyText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        m.perform(n, action: action, text: text)
        replyText = ""
        replying = false
    }
}
