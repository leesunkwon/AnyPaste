import AppKit
import SwiftUI

struct MenuBarPanelView: View {
    @ObservedObject var model: AppModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(spacing: 0) {
            panelHeader
            Divider()

            if let errorMessage = model.errorMessage {
                PasteErrorBanner(message: errorMessage) {
                    model.dismissError()
                }
                .padding(PasteSpacing.md)
            }

            if model.currentUser == nil {
                signedOutContent
            } else {
                recentContent
            }

            Divider()
            panelFooter
        }
        .frame(width: 360, height: 480)
        .background(PasteColors.background)
    }

    private var panelHeader: some View {
        HStack(spacing: PasteSpacing.md) {
            Image(systemName: "doc.on.clipboard.fill")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Color.white)
                .frame(width: 34, height: 34)
                .background(PasteColors.brand)
                .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: PasteSpacing.xxs) {
                Text("AnyPaste")
                    .font(PasteTypography.bodyStrong)
                    .foregroundStyle(PasteColors.text)
                Text(model.syncStatus.label)
                    .font(PasteTypography.caption)
                    .foregroundStyle(WorkspacePresentation.syncTone(model.syncStatus).foreground)
            }

            Spacer()

            if model.isLoading {
                ProgressView()
                    .controlSize(.small)
                    .accessibilityLabel("동기화 중")
            } else {
                Button {
                    Task { await model.refresh() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .buttonStyle(PasteIconButtonStyle())
                .accessibilityLabel("새로고침")
            }
        }
        .padding(.horizontal, PasteSpacing.lg)
        .frame(height: 58)
        .accessibilityElement(children: .contain)
    }

    private var signedOutContent: some View {
        PasteEmptyState(
            symbol: "person.crop.circle.badge.exclamationmark",
            title: "로그인이 필요합니다",
            message: "앱을 열어 로그인하면 최근 기록을 메뉴 막대에서 바로 사용할 수 있습니다.",
            actionTitle: "앱 열기"
        ) {
            showMainWindow(route: .home)
        }
        .frame(maxHeight: .infinity)
    }

    private var recentContent: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("현재 전송 항목")
                    .font(PasteTypography.captionStrong)
                    .foregroundStyle(PasteColors.textSecondary)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                if model.unreadCount > 0 {
                    Text("읽지 않음 \(model.unreadCount)")
                        .font(PasteTypography.captionStrong)
                        .foregroundStyle(PasteColors.brandForeground)
                }
            }
            .padding(.horizontal, PasteSpacing.lg)
            .padding(.vertical, PasteSpacing.md)

            if model.isLoading && model.clipboardItems.isEmpty {
                PasteLoadingState(message: "최근 항목을 불러오는 중입니다")
            } else if model.clipboardItems.isEmpty {
                PasteEmptyState(
                    symbol: "clipboard",
                    title: "현재 전송 항목이 없습니다",
                    message: "다른 기기에서 내용을 보내면 여기에 표시됩니다.",
                    actionTitle: "보내기 열기"
                ) {
                    showMainWindow(route: .send)
                }
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(Array(model.clipboardItems.prefix(1)).indices, id: \.self) { index in
                            let record = Array(model.clipboardItems.prefix(1))[index]
                            recentRow(record)

                            if index < min(model.clipboardItems.count, 1) - 1 {
                                Divider()
                                    .padding(.leading, 54)
                            }
                        }
                    }
                }
            }
        }
        .frame(maxHeight: .infinity)
    }

    private func recentRow(_ record: ClipboardRecord) -> some View {
        HStack(spacing: PasteSpacing.md) {
            Image(systemName: WorkspacePresentation.recordSymbol(record.kind))
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(WorkspacePresentation.recordTone(record.kind).foreground)
                .frame(width: 34, height: 34)
                .background(WorkspacePresentation.recordTone(record.kind).background)
                .clipShape(RoundedRectangle(cornerRadius: PasteRadius.small, style: .continuous))
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: PasteSpacing.xxs) {
                Text(record.summary)
                    .font(PasteTypography.body)
                    .foregroundStyle(PasteColors.text)
                    .lineLimit(1)
                Text(WorkspacePresentation.relativeDate(record.createdAt))
                    .font(PasteTypography.caption)
                    .foregroundStyle(PasteColors.textSecondary)
            }

            Spacer(minLength: PasteSpacing.sm)

            if record.kind != .text {
                Button {
                    model.open(record)
                } label: {
                    Image(systemName: "arrow.up.forward.app")
                }
                .buttonStyle(PasteIconButtonStyle())
                .accessibilityLabel("\(record.summary) 열기")
            }

            Button {
                model.copy(record)
            } label: {
                Image(systemName: "doc.on.doc")
            }
            .buttonStyle(PasteIconButtonStyle())
            .accessibilityLabel("\(record.summary) 복사")
        }
        .padding(.horizontal, PasteSpacing.lg)
        .padding(.vertical, PasteSpacing.sm)
        .accessibilityElement(children: .contain)
    }

    private var panelFooter: some View {
        VStack(spacing: PasteSpacing.sm) {
            Toggle(isOn: $model.autoSync) {
                Label("자동 동기화", systemImage: "arrow.triangle.2.circlepath")
                    .font(PasteTypography.body)
            }
            .toggleStyle(.switch)
            .accessibilityHint("새 클립보드 내용을 자동으로 동기화합니다")

            HStack(spacing: PasteSpacing.sm) {
                Button {
                    showMainWindow(route: .currentItem)
                } label: {
                    Label("현재 항목", systemImage: "doc.on.clipboard")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(PasteSecondaryButtonStyle())

                Button {
                    showMainWindow(route: .settings)
                } label: {
                    Label("설정", systemImage: "gearshape")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(PasteSecondaryButtonStyle())
            }
        }
        .padding(PasteSpacing.lg)
    }

    private func showMainWindow(route: AppRoute) {
        model.selectedRoute = route
        openWindow(id: "main")
        NSApp.activate(ignoringOtherApps: true)
    }
}
