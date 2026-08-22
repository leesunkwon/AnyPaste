import SwiftUI

@main
struct AnyPasteApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        Window("AnyPaste", id: "main") {
            ContentView(model: model)
                .task {
                    await model.start()
                }
        }
        .defaultSize(width: 1080, height: 720)
        .commands {
            CommandGroup(replacing: .newItem) { }
            CommandMenu("AnyPaste") {
                Button("홈") {
                    model.selectedRoute = .home
                }
                .keyboardShortcut("1", modifiers: .command)

                Button("전체 기록") {
                    model.selectedRoute = .history
                }
                .keyboardShortcut("2", modifiers: .command)

                Button("보내기") {
                    model.selectedRoute = .send
                }
                .keyboardShortcut("3", modifiers: .command)
            }
        }

        MenuBarExtra("AnyPaste", systemImage: "doc.on.clipboard.fill") {
            MenuBarPanelView(model: model)
        }
        .menuBarExtraStyle(.window)
    }
}
