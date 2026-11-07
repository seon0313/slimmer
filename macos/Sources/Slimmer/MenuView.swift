import SwiftUI

enum MenuTab: Hashable {
    case notifications, messages, settings
}

struct MenuView: View {
    @EnvironmentObject var m: AppModel
    @Environment(\.openWindow) private var openWindow
    @State private var tab: MenuTab = .notifications

    var body: some View {
        VStack(spacing: 0) {
            header
            Picker("", selection: $tab) {
                Text(m.visibleNotifications.isEmpty ? "알림" : "알림 \(m.visibleNotifications.count)")
                    .tag(MenuTab.notifications)
                Text("메시지").tag(MenuTab.messages)
                Text("설정").tag(MenuTab.settings)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .padding(.horizontal, 12)
            .padding(.bottom, 8)

            Divider()

            Group {
                switch tab {
                case .notifications: NotificationsView()
                case .messages: CompactMessagesView()
                case .settings: SettingsView()
                }
            }
            .frame(height: 460)

            if let toast = m.toast {
                Text(toast)
                    .font(.caption)
                    .padding(.horizontal, 10).padding(.vertical, 6)
                    .frame(maxWidth: .infinity)
                    .background(Color.orange.opacity(0.15))
            }

            Divider()
            HStack {
                Button {
                    openWindow(id: "messages")
                    NSApp.activate(ignoringOtherApps: true)
                } label: {
                    Label("메시지 창", systemImage: "macwindow")
                }
                Spacer()
                Button("종료") { NSApp.terminate(nil) }
            }
            .buttonStyle(.borderless)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .frame(width: 380)
        .onAppear {
            if m.phoneName == nil && !m.isConnected { tab = .settings }
        }
    }

    private var header: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(m.isConnected ? Color.green : Color.secondary.opacity(0.5))
                .frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 1) {
                Text(m.phoneName ?? "Slimmer").font(.headline)
                Text(m.statusText).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            if let b = m.battery, m.isConnected {
                Label("\(b)%", systemImage: m.charging ? "battery.100.bolt" : batteryIcon(b))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(12)
    }

    private func batteryIcon(_ level: Int) -> String {
        switch level {
        case ..<13: return "battery.0"
        case ..<38: return "battery.25"
        case ..<63: return "battery.50"
        case ..<88: return "battery.75"
        default: return "battery.100"
        }
    }
}

/// Small rounded app icon (falls back to a generic bell).
struct AppIconView: View {
    let image: NSImage?
    var size: CGFloat = 28

    var body: some View {
        Group {
            if let image {
                Image(nsImage: image).resizable().interpolation(.high)
            } else {
                Image(systemName: "app.badge").resizable().scaledToFit().padding(4)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: size * 0.22, style: .continuous))
    }
}

enum Fmt {
    static func relative(_ date: Date) -> String {
        let cal = Calendar.current
        if cal.isDateInToday(date) {
            return date.formatted(date: .omitted, time: .shortened)
        }
        if cal.isDateInYesterday(date) { return "어제" }
        if cal.isDate(date, equalTo: Date(), toGranularity: .year) {
            return date.formatted(.dateTime.month().day())
        }
        return date.formatted(date: .numeric, time: .omitted)
    }
}
