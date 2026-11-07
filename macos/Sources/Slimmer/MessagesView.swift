import SwiftUI

/// Messages inside the menu bar popover: thread list → conversation.
struct CompactMessagesView: View {
    @EnvironmentObject var m: AppModel
    @State private var composing = false

    var body: some View {
        if composing {
            NewMessageView(onClose: { composing = false })
        } else if let id = m.selectedThread {
            VStack(spacing: 0) {
                HStack {
                    Button {
                        m.selectedThread = nil
                    } label: {
                        Image(systemName: "chevron.left")
                    }
                    .buttonStyle(.borderless)
                    Text(m.thread(id)?.displayName ?? "대화").font(.headline).lineLimit(1)
                    Spacer()
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                Divider()
                ConversationView(threadId: id)
            }
        } else {
            VStack(spacing: 0) {
                HStack {
                    Text("문자").font(.headline)
                    Spacer()
                    Button { m.requestThreads() } label: { Image(systemName: "arrow.clockwise") }
                        .buttonStyle(.borderless)
                        .help("새로고침")
                    Button { composing = true } label: { Image(systemName: "square.and.pencil") }
                        .buttonStyle(.borderless)
                        .help("새 메시지")
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                Divider()
                ThreadListView(selection: Binding(get: { m.selectedThread }, set: { m.selectedThread = $0 }))
            }
        }
    }
}

/// Stand-alone messages window (split view).
struct MessagesWindow: View {
    @EnvironmentObject var m: AppModel
    @State private var composing = false

    var body: some View {
        NavigationSplitView {
            ThreadListView(selection: Binding(get: { m.selectedThread }, set: { m.selectedThread = $0 }))
                .navigationSplitViewColumnWidth(min: 220, ideal: 260)
                .toolbar {
                    ToolbarItem {
                        Button { m.requestThreads() } label: { Image(systemName: "arrow.clockwise") }
                    }
                    ToolbarItem {
                        Button { composing = true } label: { Image(systemName: "square.and.pencil") }
                    }
                }
        } detail: {
            if let id = m.selectedThread {
                ConversationView(threadId: id)
                    .navigationTitle(m.thread(id)?.displayName ?? "대화")
            } else {
                Text(m.isConnected ? "대화를 선택하세요" : "폰과 연결되어 있지 않습니다")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .sheet(isPresented: $composing) {
            NewMessageView(onClose: { composing = false })
                .frame(width: 380, height: 300)
        }
        .onAppear { if m.threads.isEmpty { m.requestThreads() } }
    }
}

struct ThreadListView: View {
    @EnvironmentObject var m: AppModel
    @Binding var selection: Int64?

    var body: some View {
        if m.threads.isEmpty {
            VStack(spacing: 8) {
                Spacer()
                if m.loadingThreads {
                    ProgressView()
                } else {
                    Image(systemName: "message").font(.largeTitle).foregroundStyle(.tertiary)
                    Text(emptyText).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }
                Spacer()
            }
            .frame(maxWidth: .infinity)
            .padding()
        } else {
            List(m.threads, selection: $selection) { t in
                ThreadRow(t: t).tag(t.id)
            }
            .listStyle(.sidebar)
        }
    }

    private var emptyText: String {
        if !m.isConnected { return "폰과 연결되면 문자 목록이 표시됩니다" }
        if m.phonePerms?.sms == false { return "폰에서 Slimmer의 SMS 권한을 허용하세요" }
        return "문자 없음"
    }
}

struct ThreadRow: View {
    let t: SmsThread

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            ZStack {
                Circle().fill(Color.accentColor.opacity(0.2))
                Image(systemName: t.group ? "person.2.fill" : "person.fill")
                    .font(.caption)
                    .foregroundStyle(Color.accentColor)
            }
            .frame(width: 30, height: 30)
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(t.displayName).font(.callout.weight(t.unread > 0 ? .bold : .medium)).lineLimit(1)
                    Spacer()
                    Text(Fmt.relative(t.dateValue)).font(.caption2).foregroundStyle(.secondary)
                }
                HStack {
                    Text(t.snippet).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                    Spacer()
                    if t.unread > 0 {
                        Text("\(t.unread)")
                            .font(.caption2.bold())
                            .padding(.horizontal, 5).padding(.vertical, 1)
                            .background(Capsule().fill(Color.accentColor))
                            .foregroundStyle(.white)
                    }
                }
            }
        }
        .padding(.vertical, 3)
    }
}

struct ConversationView: View {
    @EnvironmentObject var m: AppModel
    let threadId: Int64
    @State private var draft = ""

    var body: some View {
        let msgs = m.messages[threadId] ?? []
        let pending = m.pending(for: threadId)
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 6) {
                        ForEach(Array(msgs.enumerated()), id: \.element.id) { idx, msg in
                            if idx == 0 || !Calendar.current.isDate(msgs[idx - 1].dateValue, inSameDayAs: msg.dateValue) {
                                Text(msg.dateValue.formatted(date: .abbreviated, time: .omitted))
                                    .font(.caption2).foregroundStyle(.secondary).padding(.top, 6)
                            }
                            Bubble(text: msg.body, incoming: msg.incoming, time: msg.dateValue,
                                   note: msg.failed ? "전송 실패" : (msg.mms ? "MMS" : nil))
                        }
                        ForEach(pending, id: \.0) { req, p in
                            VStack(alignment: .trailing, spacing: 2) {
                                Bubble(text: p.body, incoming: false, time: nil, note: p.failed ?? "보내는 중…")
                                    .opacity(0.6)
                                if p.failed != nil {
                                    HStack {
                                        Button("다시 보내기") { m.retryPending(req) }
                                        Button("삭제") { m.discardPending(req) }
                                    }
                                    .controlSize(.small)
                                }
                            }
                        }
                        Color.clear.frame(height: 1).id("bottom")
                    }
                    .padding(10)
                }
                .onAppear { proxy.scrollTo("bottom", anchor: .bottom) }
                .onChange(of: msgs.count) { _ in proxy.scrollTo("bottom", anchor: .bottom) }
                .onChange(of: pending.count) { _ in proxy.scrollTo("bottom", anchor: .bottom) }
            }
            Divider()
            composer
        }
    }

    @ViewBuilder
    private var composer: some View {
        if let t = m.thread(threadId), t.group {
            Text("그룹 대화는 폰에서 답장하세요")
                .font(.caption).foregroundStyle(.secondary)
                .padding(10)
        } else {
            HStack(alignment: .bottom) {
                TextField("문자 메시지", text: $draft, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .lineLimit(1...5)
                    .onSubmit(send)
                Button(action: send) { Image(systemName: "paperplane.fill") }
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || address == nil)
                    .keyboardShortcut(.return, modifiers: .command)
            }
            .padding(10)
        }
    }

    private var address: String? {
        if let t = m.thread(threadId) { return t.address }
        return m.messages[threadId]?.last?.address
    }

    private func send() {
        guard let address else { return }
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        m.sendSms(to: address, body: text, threadId: threadId)
        draft = ""
    }
}

struct Bubble: View {
    let text: String
    let incoming: Bool
    let time: Date?
    var note: String? = nil

    var body: some View {
        HStack {
            if !incoming { Spacer(minLength: 40) }
            VStack(alignment: incoming ? .leading : .trailing, spacing: 2) {
                Text(text)
                    .textSelection(.enabled)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .background(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(incoming ? Color.primary.opacity(0.08) : Color.accentColor)
                    )
                    .foregroundStyle(incoming ? Color.primary : Color.white)
                HStack(spacing: 4) {
                    if let note { Text(note) }
                    if let time { Text(time.formatted(date: .omitted, time: .shortened)) }
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
            }
            if incoming { Spacer(minLength: 40) }
        }
    }
}

struct NewMessageView: View {
    @EnvironmentObject var m: AppModel
    var onClose: () -> Void
    @State private var to = ""
    @State private var text = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("새 메시지").font(.headline)
                Spacer()
                Button("취소", action: onClose).buttonStyle(.borderless)
            }
            TextField("받는 사람 (전화번호)", text: $to)
                .textFieldStyle(.roundedBorder)
            TextField("메시지", text: $text, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .lineLimit(3...8)
            HStack {
                Spacer()
                Button("보내기") {
                    m.sendSms(to: to, body: text, threadId: nil)
                    onClose()
                }
                .keyboardShortcut(.return, modifiers: .command)
                .disabled(to.trimmingCharacters(in: .whitespaces).isEmpty
                          || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                          || !m.isConnected)
            }
            Spacer()
        }
        .padding(12)
    }
}
