import SwiftUI

struct ContentView: View {
    @ObservedObject var model: AppModel
    @State private var isWindowDropTargeted = false

    var body: some View {
        AnyPasteRootView(model: model)
            .overlay {
                if isWindowDropTargeted, model.authPhase == .authenticated {
                    VStack(spacing: PasteSpacing.md) {
                        Image(systemName: "tray.and.arrow.down.fill")
                            .font(.system(size: 32, weight: .semibold))
                            .foregroundStyle(PasteColors.brandForeground)
                        Text("파일을 놓아 보내기 준비")
                            .font(PasteTypography.sectionTitle)
                            .foregroundStyle(PasteColors.text)
                        Text("파일을 놓으면 보내기 화면에서 대상 기기를 선택할 수 있어요.")
                            .font(PasteTypography.body)
                            .foregroundStyle(PasteColors.textSecondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(PasteColors.background.opacity(0.94))
                    .overlay {
                        RoundedRectangle(cornerRadius: PasteRadius.large, style: .continuous)
                            .stroke(PasteColors.brandForeground, style: StrokeStyle(lineWidth: 2, dash: [8, 6]))
                            .padding(PasteSpacing.lg)
                    }
                    .allowsHitTesting(false)
                }
            }
            .dropDestination(for: URL.self) { urls, _ in
                guard model.authPhase == .authenticated else { return false }
                model.prepareDroppedFilesForSend(urls)
                return !urls.isEmpty
            } isTargeted: { targeted in
                isWindowDropTargeted = targeted && model.authPhase == .authenticated
            }
    }
}
