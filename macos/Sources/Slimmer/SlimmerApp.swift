import SwiftUI

@main
struct SlimmerApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        MenuBarExtra {
            MenuView()
                .environmentObject(model)
        } label: {
            MenuBarLabel(model: model)
        }
        .menuBarExtraStyle(.window)

        Window("메시지", id: "messages") {
            MessagesWindow()
                .environmentObject(model)
                .frame(minWidth: 640, minHeight: 440)
        }
        .defaultSize(width: 760, height: 560)
    }
}

struct MenuBarLabel: View {
    @ObservedObject var model: AppModel

    var body: some View {
        let count = model.visibleNotifications.count
        HStack(spacing: 2) {
            Image(systemName: model.isConnected ? "iphone.radiowaves.left.and.right" : "iphone.slash")
            if count > 0 { Text("\(count)") }
        }
    }
}
