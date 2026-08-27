import Foundation
import SwiftUI
import UniformTypeIdentifiers

struct AnyPasteRootView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        switch model.authPhase {
        case let .configurationRequired(missingValues):
            ConfigurationMissingView(missingValues: missingValues) {
                model.retryConfiguration()
            }
        case .signedOut:
            AuthenticationView(model: model)
        case .authenticated:
            MainWorkspaceView(model: model)
        }
    }
}

struct MainWorkspaceView: View {
    @ObservedObject var model: AppModel
    @State private var columnVisibility: NavigationSplitViewVisibility = .all

    var body: some View {
        NavigationSplitView(columnVisibility: $columnVisibility) {
            sidebar
                .navigationSplitViewColumnWidth(min: 188, ideal: 220, max: 260)
        } detail: {
            VStack(spacing: 0) {
                if let errorMessage = model.errorMessage {
                    PasteErrorBanner(message: errorMessage) {
                        model.dismissError()
                    }
                    .padding(.horizontal, PasteSpacing.xxl)
                    .padding(.top, PasteSpacing.lg)
                }

                selectedPage
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(PasteColors.background)
            .toolbar {
                ToolbarItem {
                    Button {
                        Task { await model.refresh() }
                    } label: {
                        if model.isLoading {
                            ProgressView()
                                .controlSize(.small)
                        } else {
                            Image(systemName: "arrow.clockwise")
                        }
                    }
                    .disabled(model.isLoading)
                    .accessibilityLabel("새로고침")
                    .accessibilityHint("최신 클립보드 기록과 기기 상태를 불러옵니다")
                }
            }
        }
        .navigationSplitViewStyle(.balanced)
        .frame(minWidth: 920, minHeight: 640)
    }

    private var sidebar: some View {
        VStack(spacing: 0) {
            List {
                Section {
                    sidebarButton(route: .home, title: "홈", symbol: "house.fill")
                    sidebarButton(route: .send, title: "보내기", symbol: "paperplane.fill")
                    sidebarButton(
                        route: .devices,
                        title: "기기",
                        symbol: "laptopcomputer.and.iphone",
                        badge: model.onlineDevices.count
                    )
                }

                Section {
                    sidebarButton(route: .settings, title: "설정", symbol: "gearshape.fill")
                }
            }
            .listStyle(.sidebar)

            accountSummary
        }
        .background(PasteColors.surface)
    }

    private func sidebarButton(
        route: AppRoute,
        title: String,
        symbol: String,
        badge: Int? = nil
    ) -> some View {
        Button {
            model.selectedRoute = route
        } label: {
            HStack(spacing: PasteSpacing.sm) {
                Image(systemName: symbol)
                    .frame(width: 20)
                    .accessibilityHidden(true)
                Text(title)
                Spacer(minLength: PasteSpacing.sm)
                if let badge, badge > 0 {
                    Text("\(badge)")
                        .font(PasteTypography.captionStrong)
                        .foregroundStyle(isSelected(route) ? Color.white : PasteColors.textSecondary)
                        .padding(.horizontal, PasteSpacing.sm)
                        .frame(minHeight: 20)
                        .background(
                            isSelected(route)
                                ? Color.white.opacity(0.18)
                                : PasteColors.surfaceMuted
                        )
                        .clipShape(Capsule())
                        .accessibilityLabel("\(badge)개")
                }
            }
            .font(PasteTypography.bodyStrong)
            .foregroundStyle(isSelected(route) ? Color.white : PasteColors.text)
            .padding(.horizontal, PasteSpacing.md)
            .frame(maxWidth: .infinity, minHeight: 38, alignment: .leading)
            .background(isSelected(route) ? PasteColors.action : Color.clear)
            .clipShape(RoundedRectangle(cornerRadius: PasteRadius.small, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected(route) ? .isSelected : [])
    }

    private func isSelected(_ route: AppRoute) -> Bool {
        String(describing: model.selectedRoute) == String(describing: route)
    }

    private var accountSummary: some View {
        HStack(spacing: PasteSpacing.md) {
            Image(systemName: "person.crop.circle.fill")
                .font(.system(size: 26))
                .foregroundStyle(PasteColors.textSecondary)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: PasteSpacing.xxs) {
                Text(model.currentUser?.displayName.nonEmpty ?? "사용자")
                    .font(PasteTypography.bodyStrong)
                    .foregroundStyle(PasteColors.text)
                    .lineLimit(1)
                Text(model.currentUser?.email.nonEmpty ?? "로그인됨")
                    .font(PasteTypography.caption)
                    .foregroundStyle(PasteColors.textSecondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(PasteSpacing.lg)
        .overlay(alignment: .top) {
            Divider()
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var selectedPage: some View {
        switch model.selectedRoute {
        case .home:
            HomeDashboardView(model: model)
        case .currentItem:
            CurrentClipboardView(model: model)
        case .send:
            SendClipboardView(model: model)
        case .devices:
            DevicesView(model: model)
        case .settings:
            SettingsView(model: model)
        }
    }
}

struct HomeDashboardView: View {
    @ObservedObject var model: AppModel
    @State private var currentItemPreview: NSImage?

    private let metricColumns = [
        GridItem(.flexible(minimum: 160), spacing: PasteSpacing.lg),
        GridItem(.flexible(minimum: 160), spacing: PasteSpacing.lg),
        GridItem(.flexible(minimum: 160), spacing: PasteSpacing.lg)
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PasteSpacing.xxl) {
                HStack(alignment: .top, spacing: PasteSpacing.lg) {
                    PastePageHeader(
                        title: greeting,
                        subtitle: "최근 기록과 연결된 기기의 상태를 한눈에 확인하세요.",
                        symbol: "house.fill"
                    )
                    Spacer()
                    PasteStatusBadge(
                        label: model.syncStatus.label,
                        tone: WorkspacePresentation.syncTone(model.syncStatus),
                        symbol: WorkspacePresentation.syncSymbol(model.syncStatus)
                    )
                }

                connectedDeviceChips
                recentItems

                quickActions

                LazyVGrid(columns: metricColumns, spacing: PasteSpacing.lg) {
                    PasteMetricCard(
                        symbol: "doc.on.clipboard",
                        value: model.clipboardItems.isEmpty ? "없음" : "1개",
                        label: "현재 전송 항목",
                        tone: .informative
                    )
                    PasteMetricCard(
                        symbol: "circle.fill",
                        value: "\(model.onlineDevices.count)",
                        label: "온라인 기기",
                        tone: .success
                    )
                    PasteMetricCard(
                        symbol: "envelope.badge",
                        value: "\(model.unreadCount)",
                        label: "읽지 않은 항목",
                        tone: model.unreadCount > 0 ? .warning : .neutral
                    )
                }
            }
            .pastePagePadding()
        }
    }

    private var greeting: String {
        let name = model.currentUser?.displayName.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return name.isEmpty ? "반가워요" : "\(name)님, 반가워요"
    }

    private var quickActions: some View {
        VStack(alignment: .leading, spacing: PasteSpacing.md) {
            PasteSectionHeader(title: "빠른 작업", subtitle: "자주 쓰는 기능으로 바로 이동합니다.")
            HStack(spacing: PasteSpacing.md) {
                Button {
                    model.selectedRoute = .send
                } label: {
                    Label("새 항목 보내기", systemImage: "paperplane.fill")
                }
                .buttonStyle(PastePrimaryButtonStyle())

            }
        }
    }

    private var connectedDeviceChips: some View {
        VStack(alignment: .leading, spacing: PasteSpacing.md) {
            PasteSectionHeader(
                title: "연결된 기기",
                subtitle: "온라인 상태와 현재 항목의 수신·읽음 상태를 확인하세요."
            )

            if remoteDevices.isEmpty {
                Label("연결된 다른 기기가 없습니다.", systemImage: "laptopcomputer.and.iphone")
                    .font(PasteTypography.caption)
                    .foregroundStyle(PasteColors.textSecondary)
                    .padding(PasteSpacing.md)
                    .background(PasteColors.surfaceMuted)
                    .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: PasteSpacing.sm) {
                        ForEach(remoteDevices) { device in
                            let status = deviceChipStatus(for: device)
                            HStack(spacing: PasteSpacing.sm) {
                                Image(systemName: WorkspacePresentation.deviceSymbol(device.platform))
                                    .foregroundStyle(PasteColors.brandForeground)
                                    .accessibilityHidden(true)
                                VStack(alignment: .leading, spacing: PasteSpacing.xxs) {
                                    Text(device.deviceName)
                                        .font(PasteTypography.captionStrong)
                                        .foregroundStyle(PasteColors.text)
                                        .lineLimit(1)
                                    Label(status.label, systemImage: status.symbol)
                                        .font(PasteTypography.caption)
                                        .foregroundStyle(status.tone.foreground)
                                        .lineLimit(1)
                                }
                            }
                            .padding(.horizontal, PasteSpacing.md)
                            .padding(.vertical, PasteSpacing.sm)
                            .background(status.tone.background)
                            .clipShape(Capsule())
                            .overlay {
                                Capsule()
                                    .stroke(PasteColors.border, lineWidth: 1)
                            }
                            .accessibilityElement(children: .combine)
                            .accessibilityLabel("\(device.deviceName), \(status.label)")
                        }
                    }
                }
            }
        }
    }

    private var recentItems: some View {
        VStack(alignment: .leading, spacing: PasteSpacing.md) {
            PasteSectionHeader(title: "현재 전송 항목", subtitle: "새 항목을 보내면 기존 항목은 자동으로 교체됩니다.")

            if model.isLoading && model.clipboardItems.isEmpty {
                PasteLoadingState(message: "최근 기록을 불러오는 중입니다")
            } else if model.clipboardItems.isEmpty {
                PasteEmptyState(
                    symbol: "clipboard",
                    title: "아직 기록이 없습니다",
                    message: "보내기 화면에서 텍스트나 파일을 전송하면 여기에 표시됩니다.",
                    actionTitle: "첫 항목 보내기"
                ) {
                    model.selectedRoute = .send
                }
            } else {
                PasteCard(padding: 0) {
                    if let item = model.clipboardItems.first {
                        VStack(spacing: 0) {
                            Button {
                                model.selectedItem = item
                                model.selectedRoute = .currentItem
                            } label: {
                                ClipboardRecordRow(
                                    record: item,
                                    currentDeviceID: model.currentDeviceID,
                                    previewImage: currentItemPreview,
                                    prominent: true
                                )
                                .padding(.horizontal, PasteSpacing.lg)
                                .padding(.vertical, PasteSpacing.md)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityHint("현재 전송 항목의 상세 내용을 엽니다")

                            Divider()

                            HStack(spacing: PasteSpacing.sm) {
                                Button {
                                    if item.kind == .text {
                                        model.copy(item)
                                    } else {
                                        model.revealInDownloads(item)
                                    }
                                } label: {
                                    Label(
                                        item.kind == .text ? "복사" : "다운로드",
                                        systemImage: item.kind == .text ? "doc.on.doc" : "arrow.down.circle"
                                    )
                                }
                                .buttonStyle(PasteSecondaryButtonStyle())

                                Button {
                                    model.selectedItem = item
                                    model.selectedRoute = .currentItem
                                } label: {
                                    Label("상세 보기", systemImage: "arrow.right")
                                }
                                .buttonStyle(PasteSecondaryButtonStyle())

                                Spacer()
                            }
                            .padding(.horizontal, PasteSpacing.lg)
                            .padding(.vertical, PasteSpacing.sm)
                        }
                        .task(id: item.id) {
                            guard item.kind == .image else {
                                currentItemPreview = nil
                                return
                            }
                            currentItemPreview = await model.imagePreview(for: item)
                        }
                    }
                }
            }
        }
    }

    private var remoteDevices: [DeviceRecord] {
        model.devices.filter { $0.id != model.currentDeviceID }
    }

    private func deviceChipStatus(for device: DeviceRecord) -> DeviceChipStatus {
        guard device.isOnline else {
            return DeviceChipStatus(label: "오프라인", symbol: "circle", tone: .neutral)
        }
        guard let item = model.clipboardItems.first else {
            return DeviceChipStatus(label: "온라인", symbol: "circle.fill", tone: .success)
        }
        if item.isRead(by: device.id) {
            return DeviceChipStatus(label: "온라인 · 읽음", symbol: "checkmark.circle.fill", tone: .success)
        }
        if item.isReceived(by: device.id) {
            return DeviceChipStatus(label: "온라인 · 수신됨", symbol: "tray.and.arrow.down.fill", tone: .success)
        }
        return DeviceChipStatus(label: "온라인 · 전송됨", symbol: "paperplane.fill", tone: .informative)
    }
}

private struct DeviceChipStatus {
    let label: String
    let symbol: String
    let tone: PasteStatusTone
}

struct CurrentClipboardView: View {
    @ObservedObject var model: AppModel
    @State private var itemPendingDeletion: ClipboardRecord?

    var body: some View {
        Group {
            if model.isLoading && model.clipboardItems.isEmpty {
                PasteLoadingState(message: "현재 전송 항목을 불러오는 중입니다")
            } else if let record = model.clipboardItems.first {
                ClipboardDetailView(
                    model: model,
                    record: record,
                    sourceDeviceName: sourceDeviceName(for: record),
                    onCopy: { model.copy(record) },
                    onOpen: { model.open(record) },
                    onRevealInDownloads: { model.revealInDownloads(record) },
                    onDelete: { itemPendingDeletion = record }
                )
            } else {
                PasteEmptyState(
                    symbol: "clipboard",
                    title: "현재 전송 항목이 없습니다",
                    message: "텍스트나 파일을 보내면 가장 최근 항목 한 개만 여기에 표시됩니다.",
                    actionTitle: "새 항목 보내기"
                ) {
                    model.selectedRoute = .send
                }
            }
        }
        .pastePagePadding()
        .alert(
            "현재 항목을 삭제할까요?",
            isPresented: Binding(
                get: { itemPendingDeletion != nil },
                set: { if !$0 { itemPendingDeletion = nil } }
            ),
            presenting: itemPendingDeletion
        ) { record in
            Button("삭제", role: .destructive) {
                Task {
                    await model.delete(record)
                    itemPendingDeletion = nil
                }
            }
            Button("취소", role: .cancel) {
                itemPendingDeletion = nil
            }
        } message: { record in
            Text("‘\(record.summary)’ 항목은 삭제 후 복구할 수 없습니다.")
        }
    }

    private func sourceDeviceName(for record: ClipboardRecord) -> String {
        if record.sourceDeviceId == model.currentDeviceID {
            return "이 Mac"
        }
        return model.devices.first { $0.id == record.sourceDeviceId }?.deviceName.nonEmpty ?? "알 수 없는 기기"
    }
}

struct ClipboardRecordRow: View {
    let record: ClipboardRecord
    let currentDeviceID: String
    var previewImage: NSImage?
    var prominent = false

    var body: some View {
        HStack(spacing: PasteSpacing.md) {
            Group {
                if let previewImage, record.kind == .image {
                    Image(nsImage: previewImage)
                        .resizable()
                        .scaledToFill()
                } else {
                    Image(systemName: WorkspacePresentation.recordSymbol(record.kind))
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(WorkspacePresentation.recordTone(record.kind).foreground)
                        .frame(width: previewSize, height: previewSize)
                        .background(WorkspacePresentation.recordTone(record.kind).background)
                }
            }
            .frame(width: previewSize, height: previewSize)
            .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: PasteSpacing.xxs) {
                Text(record.summary)
                    .font(prominent ? PasteTypography.sectionTitle : PasteTypography.bodyStrong)
                    .foregroundStyle(PasteColors.text)
                    .lineLimit(prominent ? 2 : 1)
                if prominent {
                    Text(recordPreview)
                        .font(PasteTypography.caption)
                        .foregroundStyle(PasteColors.textSecondary)
                        .lineLimit(2)
                }
                HStack(spacing: PasteSpacing.sm) {
                    Text(WorkspacePresentation.relativeDate(record.createdAt))
                    if !record.isRead(by: currentDeviceID) {
                        Label("읽지 않음", systemImage: "circle.fill")
                            .labelStyle(.titleAndIcon)
                            .foregroundStyle(PasteColors.brandForeground)
                    }
                }
                .font(PasteTypography.caption)
                .foregroundStyle(PasteColors.textSecondary)
            }

            Spacer(minLength: PasteSpacing.sm)
            Image(systemName: "chevron.right")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(PasteColors.textTertiary)
                .accessibilityHidden(true)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            "\(WorkspacePresentation.recordKindLabel(record.kind)), \(record.summary), \(WorkspacePresentation.relativeDate(record.createdAt))"
        )
    }

    private var previewSize: CGFloat {
        prominent ? 76 : 36
    }

    private var recordPreview: String {
        switch record.kind {
        case .text:
            return record.content
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .replacingOccurrences(of: "\n", with: " ")
        case .image:
            return "이미지 · \(WorkspacePresentation.fileSize(record.fileSize))"
        case .file:
            return "파일 · \(record.mimeType.nonEmpty ?? "알 수 없는 형식") · \(WorkspacePresentation.fileSize(record.fileSize))"
        }
    }
}

struct ClipboardDetailView: View {
    @ObservedObject var model: AppModel
    let record: ClipboardRecord
    let sourceDeviceName: String
    let onCopy: () -> Void
    let onOpen: () -> Void
    let onRevealInDownloads: () -> Void
    let onDelete: () -> Void
    @State private var previewImage: NSImage?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PasteSpacing.xxl) {
                HStack(alignment: .top, spacing: PasteSpacing.md) {
                    Image(systemName: WorkspacePresentation.recordSymbol(record.kind))
                        .font(.system(size: 20, weight: .semibold))
                        .foregroundStyle(WorkspacePresentation.recordTone(record.kind).foreground)
                        .frame(width: 46, height: 46)
                        .background(WorkspacePresentation.recordTone(record.kind).background)
                        .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
                        .accessibilityHidden(true)

                    VStack(alignment: .leading, spacing: PasteSpacing.xxs) {
                        Text(WorkspacePresentation.recordKindLabel(record.kind))
                            .font(PasteTypography.sectionTitle)
                            .foregroundStyle(PasteColors.text)
                        Text(WorkspacePresentation.fullDate(record.createdAt))
                            .font(PasteTypography.caption)
                            .foregroundStyle(PasteColors.textSecondary)
                    }
                    Spacer()
                }

                contentPreview

                VStack(spacing: 0) {
                    metadataRow(label: "보낸 기기", value: sourceDeviceName)
                    Divider()
                    metadataRow(label: "생성 시각", value: WorkspacePresentation.fullDate(record.createdAt))
                    if let expiresAt = record.expiresAt {
                        Divider()
                        metadataRow(label: "만료 시각", value: WorkspacePresentation.fullDate(expiresAt))
                    }
                    if record.fileSize > 0 {
                        Divider()
                        metadataRow(label: "파일 크기", value: WorkspacePresentation.fileSize(record.fileSize))
                    }
                    if record.kind != .text {
                        Divider()
                        metadataRow(label: "형식", value: fileFormat)
                    }
                }
                .background(PasteColors.surfaceMuted)
                .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))

                if !deliveryTargets.isEmpty {
                    VStack(alignment: .leading, spacing: 0) {
                        Text("전달 상태")
                            .font(PasteTypography.captionStrong)
                            .foregroundStyle(PasteColors.textSecondary)
                            .padding(.horizontal, PasteSpacing.lg)
                            .padding(.vertical, PasteSpacing.md)
                        ForEach(deliveryTargets) { device in
                            Divider()
                            HStack(spacing: PasteSpacing.md) {
                                Circle()
                                    .fill(device.isOnline ? PasteColors.success : PasteColors.textTertiary)
                                    .frame(width: 8, height: 8)
                                Text(device.deviceName)
                                    .font(PasteTypography.body)
                                    .foregroundStyle(PasteColors.text)
                                Spacer()
                                Text(deliveryStatus(for: device))
                                    .font(PasteTypography.captionStrong)
                                    .foregroundStyle(deliveryTone(for: device))
                            }
                            .padding(.horizontal, PasteSpacing.lg)
                            .padding(.vertical, PasteSpacing.md)
                        }
                    }
                    .background(PasteColors.surfaceMuted)
                    .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
                }

                HStack(spacing: PasteSpacing.md) {
                    Button(action: onCopy) {
                        Label("복사", systemImage: "doc.on.doc")
                    }
                    .buttonStyle(PastePrimaryButtonStyle())
                    .accessibilityHint("이 항목을 Mac 클립보드에 복사합니다")

                    if record.kind != .text {
                        Button(action: onOpen) {
                            Label("다운로드 후 열기", systemImage: "arrow.down.circle")
                        }
                        .buttonStyle(PasteSecondaryButtonStyle())
                        .accessibilityHint("파일을 기본 앱으로 엽니다")

                        Button(action: onRevealInDownloads) {
                            Label("다운로드 폴더 열기", systemImage: "folder")
                        }
                        .buttonStyle(PasteSecondaryButtonStyle())
                        .accessibilityHint("파일을 다운로드 폴더에 저장하고 Finder에서 표시합니다")
                    }

                    Spacer()

                    Button(action: onDelete) {
                        Label("삭제", systemImage: "trash")
                    }
                    .buttonStyle(PasteDangerButtonStyle())
                    .accessibilityHint("삭제 확인 대화상자를 엽니다")
                }
            }
            .padding(PasteSpacing.xxl)
        }
        .onAppear {
            model.markReadFromUser(record)
            guard record.kind == .image, previewImage == nil else { return }
            Task { previewImage = await model.imagePreview(for: record) }
        }
    }

    @ViewBuilder
    private var contentPreview: some View {
        switch record.kind {
        case .text:
            Text(record.content.nonEmpty ?? "빈 텍스트")
                .font(.system(size: 14, design: .monospaced))
                .foregroundStyle(PasteColors.text)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, minHeight: 160, alignment: .topLeading)
                .padding(PasteSpacing.lg)
                .background(PasteColors.surfaceMuted)
                .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
        case .image:
            if let previewImage {
                Image(nsImage: previewImage)
                    .resizable()
                    .scaledToFit()
                    .frame(maxWidth: .infinity, minHeight: 180, maxHeight: 320)
                    .padding(PasteSpacing.sm)
                    .background(PasteColors.surfaceMuted)
                    .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
                    .accessibilityLabel("수신 이미지 미리보기")
            } else {
                filePreview(symbol: "photo.fill", title: record.fileName.nonEmpty ?? "이미지")
            }
        case .file:
            filePreview(symbol: "doc.fill", title: record.fileName.nonEmpty ?? "파일")
        }
    }

    private func filePreview(symbol: String, title: String) -> some View {
        VStack(spacing: PasteSpacing.md) {
            Image(systemName: symbol)
                .font(.system(size: 42, weight: .regular))
                .foregroundStyle(PasteColors.textTertiary)
                .accessibilityHidden(true)
            Text(title)
                .font(PasteTypography.bodyStrong)
                .foregroundStyle(PasteColors.text)
                .lineLimit(2)
                .multilineTextAlignment(.center)
            if record.fileSize > 0 {
                Text(WorkspacePresentation.fileSize(record.fileSize))
                    .font(PasteTypography.caption)
                    .foregroundStyle(PasteColors.textSecondary)
            }
            if record.kind != .text {
                Text(fileFormat)
                    .font(PasteTypography.caption)
                    .foregroundStyle(PasteColors.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 180)
        .padding(PasteSpacing.lg)
        .background(PasteColors.surfaceMuted)
        .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    private var fileFormat: String {
        let fileExtension = URL(fileURLWithPath: record.fileName).pathExtension.uppercased()
        let type = fileExtension.isEmpty ? "파일" : fileExtension
        return record.mimeType.isEmpty ? type : "\(type) · \(record.mimeType)"
    }

    private var deliveryTargets: [DeviceRecord] {
        if record.sourceDeviceId == model.currentDeviceID {
            return model.devices.filter { device in
                device.id != model.currentDeviceID
                    && (record.targetDeviceId.isEmpty || device.id == record.targetDeviceId)
            }
        }
        return model.devices.filter { $0.id == model.currentDeviceID }
    }

    private func deliveryStatus(for device: DeviceRecord) -> String {
        if record.isRead(by: device.id) { return "읽음" }
        if record.isReceived(by: device.id) { return "수신됨" }
        if !device.isOnline { return "오프라인 · 대기" }
        return "전송됨"
    }

    private func deliveryTone(for device: DeviceRecord) -> Color {
        if record.isRead(by: device.id) || record.isReceived(by: device.id) {
            return PasteColors.success
        }
        return device.isOnline ? PasteColors.brandForeground : PasteColors.textSecondary
    }

    private func metadataRow(label: String, value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: PasteSpacing.lg) {
            Text(label)
                .font(PasteTypography.caption)
                .foregroundStyle(PasteColors.textSecondary)
                .frame(width: 76, alignment: .leading)
            Text(value)
                .font(PasteTypography.body)
                .foregroundStyle(PasteColors.text)
                .textSelection(.enabled)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, PasteSpacing.lg)
        .padding(.vertical, PasteSpacing.md)
        .accessibilityElement(children: .combine)
    }
}

struct SendClipboardView: View {
    @ObservedObject var model: AppModel
    @State private var mode: SendMode = .text
    @State private var text = ""
    @State private var selectedFiles: [URL] = []
    @State private var targetDeviceID = ""
    @State private var targetMode: SendTargetMode = .allDevices
    @State private var lastSpecificTargetID = ""
    @State private var transferCompletion: TransferCompletion?
    @State private var showsFileImporter = false
    @State private var isDropTargeted = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PasteSpacing.xxl) {
                HStack(alignment: .top, spacing: PasteSpacing.lg) {
                    PastePageHeader(
                        title: "보내기",
                        subtitle: "텍스트나 파일을 선택한 기기로 안전하게 전송하세요.",
                        symbol: "paperplane.fill"
                    )
                    Spacer()
                    transferBadge
                }

                VStack(alignment: .leading, spacing: PasteSpacing.md) {
                    PasteSectionHeader(
                        title: "1 · 보낼 내용",
                        subtitle: "텍스트 또는 파일을 선택한 뒤 내용을 입력하세요."
                    )
                    Picker("보낼 내용", selection: $mode) {
                        ForEach(SendMode.allCases) { option in
                            Label(option.title, systemImage: option.symbol).tag(option)
                        }
                    }
                    .pickerStyle(.segmented)
                    .frame(maxWidth: 360)
                }

                PasteCard {
                    VStack(alignment: .leading, spacing: PasteSpacing.xl) {
                        if mode == .text {
                            textComposer
                        } else {
                            fileComposer
                        }

                        Divider()
                        targetPicker
                        Divider()
                        retentionPolicy
                        storagePolicy
                        sendAction
                    }
                }

                if let transferCompletion {
                    completionCard(transferCompletion)
                }

                if !model.failedTransfers.isEmpty {
                    failedTransferQueue
                }
            }
            .pastePagePadding()
        }
        .fileImporter(
            isPresented: $showsFileImporter,
            allowedContentTypes: [.item],
            allowsMultipleSelection: true
        ) { result in
            if case let .success(urls) = result {
                appendFiles(urls)
            }
        }
        .onChange(of: mode) {
            model.dismissError()
        }
        .onAppear {
            let savedTargetID = model.savedTransferTargetID(
                validDeviceIDs: Set(remoteDevices.map(\.id)),
            )
            targetDeviceID = savedTargetID
            lastSpecificTargetID = savedTargetID
            targetMode = savedTargetID.isEmpty ? .allDevices : .specificDevice
        }
        .onChange(of: targetDeviceID) { _, value in
            if !value.isEmpty {
                lastSpecificTargetID = value
            }
            model.rememberTransferTarget(value.isEmpty ? nil : value)
        }
        .onChange(of: targetMode) { _, value in
            guard value == .specificDevice else {
                targetDeviceID = ""
                return
            }

            let deviceIDs = Set(remoteDevices.map(\.id))
            if deviceIDs.contains(lastSpecificTargetID) {
                targetDeviceID = lastSpecificTargetID
            } else if let device = remoteDevices.first(where: \.isOnline) ?? remoteDevices.first {
                targetDeviceID = device.id
            }
        }
    }

    private var textComposer: some View {
        VStack(alignment: .leading, spacing: PasteSpacing.md) {
            PasteSectionHeader(
                title: "내용 입력",
                subtitle: "받는 기기의 클립보드에 복사할 내용을 입력하세요."
            )
            TextEditor(text: $text)
                .font(PasteTypography.body)
                .scrollContentBackground(.hidden)
                .padding(PasteSpacing.sm)
                .frame(minHeight: 220)
                .background(PasteColors.surfaceMuted)
                .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous)
                        .stroke(PasteColors.border, lineWidth: 1)
                }
                .accessibilityLabel("보낼 텍스트")
                .accessibilityHint("동기화할 텍스트를 입력합니다")
            HStack {
                Text("\(text.count)자")
                    .font(PasteTypography.caption)
                    .foregroundStyle(PasteColors.textSecondary)
                Spacer()
                if !text.isEmpty {
                    Button("내용 지우기") {
                        text = ""
                    }
                    .buttonStyle(.link)
                }
            }
        }
    }

    private var fileComposer: some View {
        VStack(alignment: .leading, spacing: PasteSpacing.md) {
            PasteSectionHeader(
                title: "파일 선택",
                subtitle: "파일을 선택하거나 아래 영역에 끌어다 놓으세요."
            )

            Button {
                showsFileImporter = true
            } label: {
                VStack(spacing: PasteSpacing.md) {
                    Image(systemName: isDropTargeted ? "tray.and.arrow.down.fill" : "arrow.down.doc")
                        .font(.system(size: 34, weight: .medium))
                        .foregroundStyle(isDropTargeted ? PasteColors.brandForeground : PasteColors.textTertiary)
                        .accessibilityHidden(true)
                    Text(isDropTargeted ? "여기에 놓으세요" : "파일 선택 또는 드래그 앤 드롭")
                        .font(PasteTypography.bodyStrong)
                        .foregroundStyle(PasteColors.text)
                    Text("여러 파일을 한 번에 선택할 수 있습니다.")
                        .font(PasteTypography.caption)
                        .foregroundStyle(PasteColors.textSecondary)
                }
                .frame(maxWidth: .infinity, minHeight: 170)
                .background(isDropTargeted ? PasteColors.brand.opacity(0.10) : PasteColors.surfaceMuted)
                .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous)
                        .stroke(
                            isDropTargeted ? PasteColors.brandForeground : PasteColors.borderStrong,
                            style: StrokeStyle(lineWidth: isDropTargeted ? 2 : 1, dash: [6, 5])
                        )
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("보낼 파일 선택")
            .accessibilityHint("파일 선택 창을 엽니다. 파일을 이 영역에 놓을 수도 있습니다")
            .dropDestination(for: URL.self) { urls, _ in
                appendFiles(urls)
                return !urls.isEmpty
            } isTargeted: { targeted in
                isDropTargeted = targeted
            }

            if !selectedFiles.isEmpty {
                VStack(spacing: 0) {
                    ForEach(selectedFiles, id: \.self) { url in
                        HStack(spacing: PasteSpacing.md) {
                            Image(systemName: "doc.fill")
                                .foregroundStyle(PasteColors.textSecondary)
                                .accessibilityHidden(true)
                            Text(url.lastPathComponent)
                                .font(PasteTypography.body)
                                .foregroundStyle(PasteColors.text)
                                .lineLimit(1)
                            Spacer()
                            Button {
                                selectedFiles.removeAll { $0 == url }
                            } label: {
                                Image(systemName: "xmark")
                            }
                            .buttonStyle(PasteIconButtonStyle())
                            .accessibilityLabel("\(url.lastPathComponent) 제거")
                        }
                        .padding(.horizontal, PasteSpacing.md)
                        .frame(minHeight: 44)
                        .accessibilityElement(children: .combine)

                        if url != selectedFiles.last {
                            Divider()
                                .padding(.leading, 40)
                        }
                    }
                }
                .background(PasteColors.surfaceMuted)
                .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
            }
        }
    }

    private var targetPicker: some View {
        VStack(alignment: .leading, spacing: PasteSpacing.md) {
            PasteSectionHeader(
                title: "2 · 대상 기기",
                subtitle: "전체 기기 전송과 특정 기기 전송 중 하나를 선택하세요."
            )

            Picker("전송 방식", selection: $targetMode) {
                Label("전체 기기", systemImage: "rectangle.stack.badge.person.crop")
                    .tag(SendTargetMode.allDevices)
                Label("특정 기기", systemImage: "scope")
                    .tag(SendTargetMode.specificDevice)
            }
            .pickerStyle(.segmented)
            .frame(maxWidth: 360)
            .accessibilityLabel("전송 대상 방식")

            if targetMode == .specificDevice, !remoteDevices.isEmpty {
                Picker("받는 기기", selection: $targetDeviceID) {
                    ForEach(remoteDevices) { device in
                        Text("\(device.deviceName)\(device.isOnline ? " · 온라인" : " · 오프라인")")
                            .tag(device.id)
                    }
                }
                .labelsHidden()
                .frame(maxWidth: 380)
                .accessibilityLabel("받는 기기")

                if let device = selectedTargetDevice {
                    HStack(spacing: PasteSpacing.sm) {
                        Image(systemName: WorkspacePresentation.deviceSymbol(device.platform))
                            .foregroundStyle(PasteColors.brandForeground)
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: PasteSpacing.xxs) {
                            Text(device.deviceName)
                                .font(PasteTypography.bodyStrong)
                                .foregroundStyle(PasteColors.text)
                            Label(
                                device.isOnline ? "온라인" : "오프라인",
                                systemImage: device.isOnline ? "circle.fill" : "circle"
                            )
                            .font(PasteTypography.caption)
                            .foregroundStyle(device.isOnline ? PasteColors.success : PasteColors.textSecondary)
                        }
                        Spacer()
                    }
                    .padding(PasteSpacing.md)
                    .background(PasteColors.surfaceMuted)
                    .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
                }
            }

            Label(
                targetMode == .allDevices
                    ? "연결된 모든 다른 기기에 전송합니다. 오프라인 기기는 연결되면 받습니다."
                    : "선택한 한 기기에만 전송합니다.",
                systemImage: targetMode == .allDevices ? "person.3.fill" : "scope"
            )
            .font(PasteTypography.caption)
            .foregroundStyle(PasteColors.textSecondary)
            .fixedSize(horizontal: false, vertical: true)

            if remoteDevices.isEmpty {
                Label("연결된 다른 기기가 없습니다. 전송한 항목은 기록에 저장됩니다.", systemImage: "info.circle")
                    .font(PasteTypography.caption)
                    .foregroundStyle(PasteColors.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var retentionPolicy: some View {
        VStack(alignment: .leading, spacing: PasteSpacing.md) {
            PasteSectionHeader(
                title: "3 · 5분 보관 안내",
                subtitle: "항목은 5분 동안만 보관되며, 새 항목이 들어오면 기존 항목은 즉시 사라집니다."
            )
        }
    }

    private var storagePolicy: some View {
        let used = WorkspacePresentation.fileSize(model.storageUsageBytes)
        let limit = WorkspacePresentation.fileSize(model.storageLimitBytes)
        return Label(
            "파일 저장 공간 \(used) / \(limit) 사용 중 · 최대 저장 용량을 넘는 파일은 전송할 수 없습니다.",
            systemImage: "externaldrive.fill"
        )
        .font(PasteTypography.caption)
        .foregroundStyle(PasteColors.textSecondary)
        .fixedSize(horizontal: false, vertical: true)
    }

    private var sendAction: some View {
        HStack(spacing: PasteSpacing.md) {
            Button(action: send) {
                HStack(spacing: PasteSpacing.sm) {
                    if model.transferState == .preparing || model.transferState == .uploading {
                        ProgressView()
                            .controlSize(.small)
                            .tint(.white)
                            .accessibilityHidden(true)
                    } else {
                        Image(systemName: "paperplane.fill")
                            .accessibilityHidden(true)
                    }
                    Text(isTransferring ? "보내는 중" : "보내기")
                }
            }
            .buttonStyle(PastePrimaryButtonStyle())
            .disabled(!canSend || isTransferring)
            .keyboardShortcut(.defaultAction)
            .accessibilityHint(targetMode == .allDevices ? "모든 다른 기기로 전송합니다" : "선택한 기기로 전송합니다")

            if isTransferring {
                Text("창을 닫지 말고 전송이 끝날 때까지 기다려 주세요.")
                    .font(PasteTypography.caption)
                    .foregroundStyle(PasteColors.textSecondary)
            }
        }
    }

    private var failedTransferQueue: some View {
        PasteCard {
            VStack(alignment: .leading, spacing: PasteSpacing.md) {
                PasteSectionHeader(
                    title: "재전송 대기",
                    subtitle: "실패 원인을 확인하고 각 항목을 다시 전송할 수 있습니다."
                )
                ForEach(model.failedTransfers) { transfer in
                    HStack(alignment: .top, spacing: PasteSpacing.md) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(PasteColors.warning)
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: PasteSpacing.xs) {
                            Text(transfer.title)
                                .font(PasteTypography.bodyStrong)
                                .foregroundStyle(PasteColors.text)
                                .lineLimit(1)
                            Text(transfer.meta)
                                .font(PasteTypography.caption)
                                .foregroundStyle(PasteColors.textSecondary)
                            Text(transfer.reason)
                                .font(PasteTypography.caption)
                                .foregroundStyle(PasteColors.warning)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: PasteSpacing.sm)
                        Button("다시 시도") {
                            Task { await model.retryFailedTransfer(transfer.id) }
                        }
                        .buttonStyle(PasteSecondaryButtonStyle())
                        .disabled(isTransferring)
                    }
                    .padding(PasteSpacing.md)
                    .background(PasteColors.warningSurface)
                    .clipShape(RoundedRectangle(cornerRadius: PasteRadius.small, style: .continuous))
                }
            }
        }
    }

    private var transferBadge: some View {
        PasteStatusBadge(
            label: WorkspacePresentation.transferLabel(model.transferState),
            tone: WorkspacePresentation.transferTone(model.transferState),
            symbol: WorkspacePresentation.transferSymbol(model.transferState)
        )
    }

    private var remoteDevices: [DeviceRecord] {
        model.devices.filter { $0.id != model.currentDeviceID }
    }

    private var selectedTargetDevice: DeviceRecord? {
        remoteDevices.first { $0.id == targetDeviceID }
    }

    private var canSend: Bool {
        guard targetMode == .allDevices || selectedTargetDevice != nil else { return false }
        switch mode {
        case .text:
            return !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .files:
            return !selectedFiles.isEmpty
        }
    }

    private var isTransferring: Bool {
        switch model.transferState {
        case .preparing, .uploading, .downloading:
            true
        case .idle, .succeeded, .failed:
            false
        }
    }

    private func appendFiles(_ urls: [URL]) {
        let additions = urls.filter { url in
            !selectedFiles.contains(url)
        }
        selectedFiles.append(contentsOf: additions)
    }

    private func send() {
        guard canSend, !isTransferring else { return }
        model.dismissError()
        let targetID: String? = targetMode == .allDevices ? nil : targetDeviceID
        let completion = TransferCompletion(
            itemDescription: mode == .text
                ? "텍스트 \(text.trimmingCharacters(in: .whitespacesAndNewlines).count)자"
                : "파일 \(selectedFiles.count)개",
            targetDescription: targetMode == .allDevices
                ? "전체 기기 · \(remoteDevices.count)대"
                : "\(selectedTargetDevice?.deviceName ?? "선택한 기기") · \(selectedTargetDevice?.isOnline == true ? "온라인" : "오프라인")",
            expiresAt: Date().addingTimeInterval(ClipboardRetention.fiveMinutes.duration)
        )

        Task {
            switch mode {
            case .text:
                let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
                await model.sendText(value, targetDeviceID: targetID, retention: .fiveMinutes)
                if model.errorMessage == nil {
                    text = ""
                    transferCompletion = completion
                }
            case .files:
                let files = selectedFiles
                await model.sendFiles(files, targetDeviceID: targetID, retention: .fiveMinutes)
                if model.errorMessage == nil {
                    selectedFiles = []
                    transferCompletion = completion
                }
            }
        }
    }

    private func completionCard(_ completion: TransferCompletion) -> some View {
        PasteCard {
            HStack(alignment: .top, spacing: PasteSpacing.md) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(PasteColors.success)
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: PasteSpacing.xs) {
                    Text("전송을 완료했어요")
                        .font(PasteTypography.sectionTitle)
                        .foregroundStyle(PasteColors.text)
                    Text("\(completion.targetDescription)에 \(completion.itemDescription)을 보냈습니다.")
                        .font(PasteTypography.body)
                        .foregroundStyle(PasteColors.textSecondary)
                    Label(
                        "\(WorkspacePresentation.fullDate(completion.expiresAt))에 만료 · 5분 보관",
                        systemImage: "clock"
                    )
                    .font(PasteTypography.caption)
                    .foregroundStyle(PasteColors.textSecondary)
                }

                Spacer(minLength: PasteSpacing.sm)

                Button("닫기") {
                    transferCompletion = nil
                }
                .buttonStyle(PasteSecondaryButtonStyle())
            }
        }
    }
}

struct DevicesView: View {
    @ObservedObject var model: AppModel
    @State private var selectedDeviceID: String?
    @State private var devicePendingRemoval: DeviceRecord?

    var body: some View {
        VStack(alignment: .leading, spacing: PasteSpacing.xl) {
            HStack(alignment: .top, spacing: PasteSpacing.xl) {
                PastePageHeader(
                    title: "기기",
                    subtitle: "계정에 연결된 기기와 최근 접속 상태를 확인하세요.",
                    symbol: "laptopcomputer.and.iphone"
                )
                Spacer()
                PasteStatusBadge(
                    label: "온라인 \(model.onlineDevices.count)대",
                    tone: model.onlineDevices.isEmpty ? .neutral : .success,
                    symbol: "circle.fill"
                )
            }

            if model.isLoading && model.devices.isEmpty {
                PasteLoadingState(message: "연결된 기기를 불러오는 중입니다")
            } else if model.devices.isEmpty {
                PasteEmptyState(
                    symbol: "laptopcomputer.and.iphone",
                    title: "연결된 기기가 없습니다",
                    message: "같은 계정으로 다른 기기에서 로그인하면 이곳에 표시됩니다.",
                    actionTitle: "다시 확인"
                ) {
                    Task { await model.refresh() }
                }
            } else {
                HSplitView {
                    deviceList
                        .frame(minWidth: 300, idealWidth: 380, maxWidth: 460)

                    Group {
                        if let selectedDevice {
                            DeviceDetailView(
                                device: selectedDevice,
                                isCurrentDevice: selectedDevice.id == model.currentDeviceID,
                                onRename: { name in
                                    Task { await model.renameDevice(selectedDevice, to: name) }
                                },
                                onRemove: { devicePendingRemoval = selectedDevice }
                            )
                        } else {
                            PasteEmptyState(
                                symbol: "cursorarrow.click",
                                title: "기기를 선택하세요",
                                message: "왼쪽 목록에서 기기를 선택하면 연결 정보를 볼 수 있습니다."
                            )
                        }
                    }
                    .frame(minWidth: 340, maxWidth: .infinity, maxHeight: .infinity)
                    .background(PasteColors.surfaceRaised)
                }
                .clipShape(RoundedRectangle(cornerRadius: PasteRadius.large, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: PasteRadius.large, style: .continuous)
                        .stroke(PasteColors.border, lineWidth: 1)
                }
            }
        }
        .pastePagePadding()
        .onAppear {
            if selectedDeviceID == nil {
                selectedDeviceID = model.devices.first?.id
            }
        }
        .alert(
            "기기 연결을 해제할까요?",
            isPresented: Binding(
                get: { devicePendingRemoval != nil },
                set: { if !$0 { devicePendingRemoval = nil } }
            ),
            presenting: devicePendingRemoval
        ) { device in
            Button("연결 해제", role: .destructive) {
                Task {
                    await model.removeDevice(device)
                    if selectedDeviceID == device.id {
                        selectedDeviceID = model.devices.first { $0.id != device.id }?.id
                    }
                    devicePendingRemoval = nil
                }
            }
            Button("취소", role: .cancel) {
                devicePendingRemoval = nil
            }
        } message: { device in
            Text("‘\(device.deviceName)’에서 로그아웃되고 이후 동기화가 차단됩니다. 다시 사용하려면 새 기기로 연결해야 합니다.")
        }
    }

    private var deviceList: some View {
        List(model.devices) { device in
            Button {
                selectedDeviceID = device.id
            } label: {
                DeviceRecordRow(
                    device: device,
                    isCurrentDevice: device.id == model.currentDeviceID
                )
                .padding(.vertical, PasteSpacing.xs)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .listRowBackground(
                selectedDeviceID == device.id
                    ? PasteColors.surfacePressed
                    : Color.clear
            )
            .accessibilityAddTraits(selectedDeviceID == device.id ? .isSelected : [])
        }
        .listStyle(.inset)
    }

    private var selectedDevice: DeviceRecord? {
        guard let selectedDeviceID else { return nil }
        return model.devices.first { $0.id == selectedDeviceID }
    }
}

struct DeviceRecordRow: View {
    let device: DeviceRecord
    let isCurrentDevice: Bool

    var body: some View {
        HStack(spacing: PasteSpacing.md) {
            Image(systemName: WorkspacePresentation.deviceSymbol(device.platform))
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(device.isOnline ? PasteColors.success : PasteColors.textSecondary)
                .frame(width: 38, height: 38)
                .background(device.isOnline ? PasteColors.successSurface : PasteColors.surfaceMuted)
                .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: PasteSpacing.xxs) {
                HStack(spacing: PasteSpacing.sm) {
                    Text(device.deviceName)
                        .font(PasteTypography.bodyStrong)
                        .foregroundStyle(PasteColors.text)
                        .lineLimit(1)
                    if isCurrentDevice {
                        Text("현재 기기")
                            .font(PasteTypography.captionStrong)
                            .foregroundStyle(PasteColors.informative)
                    }
                }
                Text(device.isOnline ? "온라인" : WorkspacePresentation.lastSeen(device.lastSeenAt))
                    .font(PasteTypography.caption)
                    .foregroundStyle(device.isOnline ? PasteColors.success : PasteColors.textSecondary)
            }
            Spacer(minLength: PasteSpacing.sm)
            Circle()
                .fill(device.isOnline ? PasteColors.success : PasteColors.borderStrong)
                .frame(width: 8, height: 8)
                .accessibilityHidden(true)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            "\(device.deviceName), \(isCurrentDevice ? "현재 기기, " : "")\(device.isOnline ? "온라인" : WorkspacePresentation.lastSeen(device.lastSeenAt))"
        )
    }
}

struct DeviceDetailView: View {
    let device: DeviceRecord
    let isCurrentDevice: Bool
    let onRename: (String) -> Void
    let onRemove: () -> Void
    @State private var showsRenameDialog = false
    @State private var proposedName = ""

    var body: some View {
        VStack(alignment: .leading, spacing: PasteSpacing.xxl) {
            HStack(alignment: .top, spacing: PasteSpacing.lg) {
                Image(systemName: WorkspacePresentation.deviceSymbol(device.platform))
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(device.isOnline ? PasteColors.success : PasteColors.textSecondary)
                    .frame(width: 60, height: 60)
                    .background(device.isOnline ? PasteColors.successSurface : PasteColors.surfaceMuted)
                    .clipShape(RoundedRectangle(cornerRadius: PasteRadius.large, style: .continuous))
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: PasteSpacing.xs) {
                    Text(device.deviceName)
                        .font(PasteTypography.pageTitle)
                        .foregroundStyle(PasteColors.text)
                        .accessibilityAddTraits(.isHeader)
                    PasteStatusBadge(
                        label: device.isOnline ? "온라인" : "오프라인",
                        tone: device.isOnline ? .success : .neutral,
                        symbol: "circle.fill"
                    )
                }
                Spacer()
            }

            VStack(spacing: 0) {
                detailRow(label: "플랫폼", value: WorkspacePresentation.platformLabel(device.platform))
                Divider()
                detailRow(label: "최근 접속", value: WorkspacePresentation.fullDate(device.lastSeenAt))
                Divider()
                detailRow(label: "기기 ID", value: device.id)
            }
            .background(PasteColors.surfaceMuted)
            .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))

            Button {
                proposedName = device.deviceName
                showsRenameDialog = true
            } label: {
                Label("기기 이름 변경", systemImage: "pencil")
            }
            .buttonStyle(PasteSecondaryButtonStyle())

            if isCurrentDevice {
                Label("현재 사용 중인 기기는 여기서 제거할 수 없습니다.", systemImage: "info.circle")
                    .font(PasteTypography.body)
                    .foregroundStyle(PasteColors.textSecondary)
            } else {
                Button(action: onRemove) {
                    Label("기기 목록에서 제거", systemImage: "trash")
                }
                .buttonStyle(PasteDangerButtonStyle())
                .accessibilityHint("이 기기의 등록 정보를 목록에서 삭제합니다")
            }

            Spacer()
        }
        .padding(PasteSpacing.xxl)
        .alert("기기 이름 변경", isPresented: $showsRenameDialog) {
            TextField("예: 내 맥북, 업무용 폰", text: $proposedName)
            Button("변경") {
                onRename(proposedName)
            }
            Button("취소", role: .cancel) {}
        } message: {
            Text("이 이름은 같은 계정으로 연결된 모든 기기에 표시됩니다.")
        }
    }

    private func detailRow(label: String, value: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: PasteSpacing.lg) {
            Text(label)
                .font(PasteTypography.caption)
                .foregroundStyle(PasteColors.textSecondary)
                .frame(width: 76, alignment: .leading)
            Text(value)
                .font(PasteTypography.body)
                .foregroundStyle(PasteColors.text)
                .textSelection(.enabled)
            Spacer()
        }
        .padding(.horizontal, PasteSpacing.lg)
        .padding(.vertical, PasteSpacing.md)
        .accessibilityElement(children: .combine)
    }
}

struct SettingsView: View {
    @ObservedObject var model: AppModel
    @State private var confirmsSignOut = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: PasteSpacing.xxl) {
                PastePageHeader(
                    title: "설정",
                    subtitle: "동기화 방식과 알림, 계정 정보를 관리하세요.",
                    symbol: "gearshape.fill"
                )

                settingSection(title: "동기화", subtitle: "클립보드 기록이 전송되는 방식을 설정합니다.") {
                    settingToggle(
                        title: "자동 동기화",
                        description: "새 클립보드 내용을 다른 기기와 자동으로 공유합니다.",
                        symbol: "arrow.triangle.2.circlepath",
                        isOn: $model.autoSync
                    )
                    Divider()
                    settingToggle(
                        title: "Wi-Fi에서만 파일 전송",
                        description: "모바일 네트워크 사용량을 줄이도록 파일 전송을 제한합니다.",
                        symbol: "wifi",
                        isOn: $model.wifiOnlyTransfers
                    )
                }

                settingSection(title: "알림", subtitle: "새 항목이 도착했을 때 알림을 받을지 선택합니다.") {
                    settingToggle(
                        title: "수신 알림",
                        description: "다른 기기에서 새 항목이 도착하면 알림을 표시합니다.",
                        symbol: "bell.badge",
                        isOn: $model.notificationsEnabled
                    )
                }

                accountSection
            }
            .pastePagePadding()
            .frame(maxWidth: 820, alignment: .leading)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .alert("로그아웃할까요?", isPresented: $confirmsSignOut) {
            Button("로그아웃", role: .destructive) {
                model.signOut()
            }
            Button("취소", role: .cancel) {}
        } message: {
            Text("이 Mac의 세션이 종료되며 다시 로그인해야 동기화할 수 있습니다.")
        }
    }

    private func settingSection<Content: View>(
        title: String,
        subtitle: String,
        @ViewBuilder content: @escaping () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: PasteSpacing.md) {
            PasteSectionHeader(title: title, subtitle: subtitle)
            PasteCard(padding: 0) {
                VStack(spacing: 0) {
                    content()
                }
            }
        }
    }

    private func settingToggle(
        title: String,
        description: String,
        symbol: String,
        isOn: Binding<Bool>
    ) -> some View {
        HStack(spacing: PasteSpacing.lg) {
            Image(systemName: symbol)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(PasteColors.brandForeground)
                .frame(width: 38, height: 38)
                .background(PasteColors.brand.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: PasteRadius.medium, style: .continuous))
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: PasteSpacing.xxs) {
                Text(title)
                    .font(PasteTypography.bodyStrong)
                    .foregroundStyle(PasteColors.text)
                Text(description)
                    .font(PasteTypography.caption)
                    .foregroundStyle(PasteColors.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: PasteSpacing.lg)
            Toggle(title, isOn: isOn)
                .labelsHidden()
                .toggleStyle(.switch)
                .accessibilityLabel(title)
                .accessibilityHint(description)
        }
        .padding(PasteSpacing.lg)
    }

    private var accountSection: some View {
        VStack(alignment: .leading, spacing: PasteSpacing.md) {
            PasteSectionHeader(title: "계정", subtitle: "현재 로그인한 계정과 이 기기의 정보를 확인합니다.")
            PasteCard {
                HStack(alignment: .top, spacing: PasteSpacing.lg) {
                    Image(systemName: "person.crop.circle.fill")
                        .font(.system(size: 42))
                        .foregroundStyle(PasteColors.textSecondary)
                        .accessibilityHidden(true)

                    VStack(alignment: .leading, spacing: PasteSpacing.xs) {
                        Text(model.currentUser?.displayName.nonEmpty ?? "사용자")
                            .font(PasteTypography.sectionTitle)
                            .foregroundStyle(PasteColors.text)
                        Text(model.currentUser?.email.nonEmpty ?? "이메일 정보 없음")
                            .font(PasteTypography.body)
                            .foregroundStyle(PasteColors.textSecondary)
                            .textSelection(.enabled)
                        Text("기기 ID: \(model.currentDeviceID)")
                            .font(PasteTypography.caption)
                            .foregroundStyle(PasteColors.textTertiary)
                            .textSelection(.enabled)
                    }

                    Spacer()

                    Button("로그아웃") {
                        confirmsSignOut = true
                    }
                    .buttonStyle(PasteDangerButtonStyle())
                }
                .accessibilityElement(children: .contain)
            }
        }
    }
}

enum WorkspacePresentation {
    static func syncTone(_ status: SyncStatus) -> PasteStatusTone {
        let value = String(describing: status).lowercased()
        if value.contains("fail") { return .danger }
        if value.contains("offline") || value.contains("stop") { return .warning }
        if value.contains("sync") { return .informative }
        return .success
    }

    static func syncSymbol(_ status: SyncStatus) -> String {
        let value = String(describing: status).lowercased()
        if value.contains("fail") { return "exclamationmark.triangle.fill" }
        if value.contains("offline") { return "wifi.slash" }
        if value.contains("stop") { return "pause.circle.fill" }
        if value.contains("sync") { return "arrow.triangle.2.circlepath" }
        return "checkmark.circle.fill"
    }

    static func transferLabel(_ state: TransferState) -> String {
        switch state {
        case .idle: "전송 준비"
        case .preparing: "준비 중"
        case .uploading: "업로드 중"
        case .downloading: "다운로드 중"
        case .succeeded: "전송 완료"
        case .failed: "전송 실패"
        }
    }

    static func transferTone(_ state: TransferState) -> PasteStatusTone {
        switch state {
        case .idle: .neutral
        case .preparing, .uploading, .downloading: .informative
        case .succeeded: .success
        case .failed: .danger
        }
    }

    static func transferSymbol(_ state: TransferState) -> String {
        switch state {
        case .idle: "paperplane"
        case .preparing: "clock"
        case .uploading: "arrow.up.circle.fill"
        case .downloading: "arrow.down.circle.fill"
        case .succeeded: "checkmark.circle.fill"
        case .failed: "exclamationmark.circle.fill"
        }
    }

    static func recordKindLabel(_ kind: ClipboardKind) -> String {
        switch kind {
        case .text: "텍스트"
        case .image: "이미지"
        case .file: "파일"
        }
    }

    static func recordSymbol(_ kind: ClipboardKind) -> String {
        switch kind {
        case .text: "text.alignleft"
        case .image: "photo.fill"
        case .file: "doc.fill"
        }
    }

    static func recordTone(_ kind: ClipboardKind) -> PasteStatusTone {
        switch kind {
        case .text: .informative
        case .image: .success
        case .file: .warning
        }
    }

    static func deviceSymbol(_ platform: String) -> String {
        platform.lowercased().contains("android") ? "smartphone" : "laptopcomputer"
    }

    static func platformLabel(_ platform: String) -> String {
        platform.lowercased().contains("android") ? "Android" : "macOS"
    }

    static func relativeDate(_ date: Date?) -> String {
        guard let date else { return "시간 정보 없음" }
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter.localizedString(for: date, relativeTo: Date())
    }

    static func lastSeen(_ date: Date?) -> String {
        guard let date else { return "최근 접속 정보 없음" }
        return "\(relativeDate(date)) 접속"
    }

    static func fullDate(_ date: Date?) -> String {
        guard let date else { return "시간 정보 없음" }
        return date.formatted(date: .abbreviated, time: .shortened)
    }

    static func fullDate(_ date: Date) -> String {
        date.formatted(date: .abbreviated, time: .shortened)
    }

    static func fileSize(_ value: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: value, countStyle: .file)
    }
}

private enum SendMode: String, CaseIterable, Identifiable {
    case text
    case files

    var id: String { rawValue }

    var title: String {
        switch self {
        case .text: "텍스트"
        case .files: "파일"
        }
    }

    var symbol: String {
        switch self {
        case .text: "text.alignleft"
        case .files: "doc.on.doc"
        }
    }
}

private enum SendTargetMode: String, CaseIterable, Identifiable {
    case allDevices
    case specificDevice

    var id: String { rawValue }
}

private struct TransferCompletion {
    let itemDescription: String
    let targetDescription: String
    let expiresAt: Date
}

private extension String {
    var nonEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
